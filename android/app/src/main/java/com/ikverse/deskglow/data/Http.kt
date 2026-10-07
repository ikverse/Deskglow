package com.ikverse.deskglow.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The app's only way onto the internet: plain GETs (weather, the font library). Swapped for a fake in tests. */
fun interface Http {
    /** The body of [url], or an [IOException] for any failure, including a non-200 answer. */
    @Throws(IOException::class)
    fun get(url: String): ByteArray
}

/**
 * Android's built-in client. It asks for compressed answers and unpacks them by itself. The user
 * agent is not a browser's on purpose: Google Fonts then serves TrueType files, which Android can
 * load, instead of the WOFF2 it gives browsers.
 */
object UrlConnectionHttp : Http {
    const val USER_AGENT = "Deskglow/1.0 (Android)"

    override fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $url")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
