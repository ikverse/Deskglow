package com.ikverse.deskglow.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** One message from the feed: a topic's whole state ([full], from the snapshot on connecting) or a change to merge in. */
class LiveMessage(val topic: String, val data: JSONObject, val full: Boolean)

/**
 * Where live timing comes from. Kept behind this so another source (a licensed one, say) can take
 * the feed's place without the widget or [F1LiveRepository] changing.
 */
fun interface LiveTimingSource {
    /** Connects and subscribes to [topics]; throws an [IOException] when it cannot. */
    fun open(topics: List<String>): LiveConnection
}

interface LiveConnection : Closeable {
    /**
     * Waits for the next messages; empty when only a keep-alive came, or nothing for a while. Throws an
     * [IOException] once the connection is lost, which includes a stream gone silent for too long.
     */
    fun poll(): List<LiveMessage>
}

/**
 * Formula 1's own live-timing feed: the one behind its timing pages. It is a SignalR hub; one open
 * stream (server-sent events) carries what it says, and plain HTTP the few things said to it. Where
 * the server does not offer a stream it falls back to long polling, one request per batch. Unofficial
 * and undocumented, so it may change or close.
 */
object F1LiveTimingFeed : LiveTimingSource {
    const val BASE = "https://livetiming.formula1.com/signalrcore"
    override fun open(topics: List<String>): LiveConnection =
        try {
            SseConnection(BASE, topics)
        } catch (e: TransportUnavailable) {
            LongPollConnection(BASE, topics)
        }
}

/** The server does not stream to this client; the connection can be made another way. A lost network is an [IOException] instead. */
private class TransportUnavailable : Exception()

private const val RECORD_END = '\u001e'

/** The cookies and plain requests every SignalR transport needs. The load balancer pins a connection to one server by a cookie, so each answer's cookies go back with the next request. */
private class Wire {
    private val cookies = LinkedHashMap<String, String>()
    @Volatile var inFlight: HttpURLConnection? = null

    /** Opens [url] and reads its headers; the caller reads the body (or streams it) and disconnects. */
    fun connect(url: String, method: String, body: String?, readTimeoutMs: Int, accept: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        inFlight = connection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", UrlConnectionHttp.USER_AGENT)
            // Compressed streams are held back until a block fills, which is the opposite of live.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (accept != null) connection.setRequestProperty("Accept", accept)
            if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            connection.responseCode
            connection.headerFields.filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }.values.flatten().forEach { header ->
                val pair = header.substringBefore(';').split('=', limit = 2)
                if (pair.size == 2) cookies[pair[0].trim()] = pair[1].trim()
            }
            return connection
        } catch (e: Exception) {
            connection.disconnect()
            if (inFlight === connection) inFlight = null
            throw e
        }
    }

    fun request(url: String, method: String, body: String?, readTimeoutMs: Int = 15_000): ByteArray {
        val connection = connect(url, method, body, readTimeoutMs)
        try {
            // 204 is how long polling says the server has ended the connection.
            if (connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT) throw IOException("connection ended")
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $method")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
            if (inFlight === connection) inFlight = null
        }
    }
}

private fun handshake() = """{"protocol":"json","version":1}$RECORD_END"""

private fun subscription(topics: List<String>) =
    JSONObject().put("type", 1).put("target", "Subscribe").put("arguments", JSONArray().put(JSONArray(topics))).put("invocationId", "1").toString() + RECORD_END

/**
 * One connection over a server-sent event stream. The server pings every 15 seconds, so a stream
 * quiet for [STALL_MS] has lost its connection, whatever the phone's network layer believes.
 */
private class SseConnection(base: String, topics: List<String>) : LiveConnection {
    private val wire = Wire()
    private val url: String
    private val stream: HttpURLConnection
    private val reader: Reader
    private val events = SseEvents()
    private val chars = CharArray(8192)
    @Volatile private var closed = false

    init {
        val negotiated = JSONObject(String(wire.request("$base/negotiate?negotiateVersion=1", "POST", "")))
        val offered = negotiated.optJSONArray("availableTransports")
        val streams = offered != null && (0 until offered.length()).any { offered.optJSONObject(it)?.optString("transport") == "ServerSentEvents" }
        if (!streams) throw TransportUnavailable()
        url = "$base?id=" + URLEncoder.encode(negotiated.getString("connectionToken"), "UTF-8")
        stream = wire.connect(url, "GET", null, STALL_MS, accept = "text/event-stream")
        if (stream.responseCode !in 200..299 || stream.contentType?.contains("text/event-stream") != true) {
            stream.disconnect()
            throw TransportUnavailable()
        }
        reader = InputStreamReader(stream.inputStream, Charsets.UTF_8)
        try {
            wire.request(url, "POST", handshake())
            wire.request(url, "POST", subscription(topics))
        } catch (e: IOException) {
            stream.disconnect()
            throw e
        }
    }

    override fun poll(): List<LiveMessage> {
        if (closed) throw IOException("closed")
        while (true) {
            val ready = events.take()
            if (ready.isNotEmpty()) return ready.flatMap(::parseSignalR)
            val read = try {
                reader.read(chars)
            } catch (e: SocketTimeoutException) {
                throw IOException("the stream went quiet", e)
            }
            if (read < 0) throw IOException("the stream ended")
            events.add(chars, read)
            // Whatever else has already arrived goes out in the same batch.
            val batch = events.take()
            if (batch.isNotEmpty()) return batch.flatMap(::parseSignalR)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stream.disconnect()
        Thread { runCatching { wire.request(url, "DELETE", null) } }.start()
    }

    private companion object {
        const val STALL_MS = 35_000
    }
}

/**
 * Cuts an event stream into its events: each ends at a blank line, and its data lines (an event may
 * have several) make its payload. Comment lines and other fields are ignored.
 */
internal class SseEvents {
    private val buffer = StringBuilder()

    fun add(text: CharArray, length: Int) {
        buffer.append(text, 0, length)
    }

    /** The payloads of the events completed so far; the rest stays until the stream ends them. */
    fun take(): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val end = endOfEvent() ?: break
            val (at, separator) = end
            val block = buffer.substring(0, at)
            buffer.delete(0, at + separator)
            val data = block.lineSequence().map { it.trimEnd('\r') }.filter { it.startsWith("data:") }.map { it.removePrefix("data:").removePrefix(" ") }.toList()
            if (data.isNotEmpty()) out += data.joinToString("\n")
        }
        return out
    }

    /** Where the next blank line starts, and how long it is. */
    private fun endOfEvent(): Pair<Int, Int>? {
        var i = 0
        while (i < buffer.length - 1) {
            if (buffer[i] == '\n' && buffer[i + 1] == '\n') return i to 2
            if (buffer[i] == '\r' && i + 3 < buffer.length && buffer[i + 1] == '\n' && buffer[i + 2] == '\r' && buffer[i + 3] == '\n') return i to 4
            i++
        }
        return null
    }
}

/** One connection by long polling: one request per batch of messages. */
private class LongPollConnection(base: String, topics: List<String>) : LiveConnection {
    private val wire = Wire()
    private val url: String
    @Volatile private var closed = false

    init {
        val negotiated = JSONObject(String(wire.request("$base/negotiate?negotiateVersion=1", "POST", "")))
        url = "$base?id=" + URLEncoder.encode(negotiated.getString("connectionToken"), "UTF-8")
        wire.request(url, "POST", handshake())
        wire.request(url, "POST", subscription(topics))
    }

    override fun poll(): List<LiveMessage> {
        if (closed) throw IOException("closed")
        val body = try {
            String(wire.request(url, "GET", null, readTimeoutMs = 100_000))
        } catch (e: SocketTimeoutException) {
            return emptyList()
        }
        return parseSignalR(body)
    }

    override fun close() {
        if (closed) return
        closed = true
        wire.inFlight?.disconnect()
        Thread { runCatching { wire.request(url, "DELETE", null) } }.start()
    }
}

/**
 * SignalR's JSON frames, each ended by 0x1E: a "feed" call is one topic's change, the answer to the
 * subscription is every topic in full, and a close frame ends the connection.
 */
internal fun parseSignalR(body: String): List<LiveMessage> = body.split(RECORD_END).filter { it.isNotBlank() }.flatMap { frame ->
    val j = JSONObject(frame)
    when (j.optInt("type", 0)) {
        1 -> {
            val args = j.optJSONArray("arguments")
            val topic = args?.optString(0).orEmpty()
            val data = args?.opt(1)
            if (topic.isNotEmpty() && data is JSONObject) listOf(LiveMessage(topic, data, full = false)) else emptyList()
        }
        3 -> {
            if (j.has("error")) throw IOException("subscribe refused: " + j.optString("error"))
            val result = j.optJSONObject("result") ?: JSONObject()
            result.keys().asSequence().mapNotNull { topic -> (result.opt(topic) as? JSONObject)?.let { LiveMessage(topic, it, full = true) } }.toList()
        }
        7 -> throw IOException("closed by the server: " + j.optString("error"))
        else -> emptyList()
    }
}
