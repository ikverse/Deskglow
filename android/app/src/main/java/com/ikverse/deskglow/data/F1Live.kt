package com.ikverse.deskglow.data

import com.ikverse.deskglow.store.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** The flag out on track, from the feed's track status. */
enum class TrackFlag { Clear, Yellow, SafetyCar, Red, VirtualSafetyCar, VscEnding }

/**
 * One car in a classification. [gap] is to the leader and [interval] to the car ahead, as the feed
 * writes them ("+0.512", "1L"); the leader has its best lap there instead, or "Leader" in a race.
 */
data class LiveRow(
    val position: Int,
    val number: String,
    val code: String,
    /** The team's colour as the feed gives it, 0xFFRRGGBB; null when it gave none. */
    val teamColour: Long?,
    val gap: String,
    val interval: String,
    val inPit: Boolean = false,
    /** Retired, or stopped on track. */
    val out: Boolean = false,
    /** Out of qualifying in an earlier part. */
    val knockedOut: Boolean = false,
)

/** A session as the live-timing feed last described it: running, or over with its classification. */
data class LiveSession(
    val key: Int,
    /** "Singapore GP". */
    val meeting: String,
    /** "Practice 1", "Sprint Qualifying", "Race". */
    val name: String,
    /** "Practice", "Qualifying" or "Race" (a sprint is a "Race"). */
    val type: String,
    val start: Instant?,
    /** The feed's own word: "Inactive", "Started", "Aborted" (red flag), "Finished", "Finalised", "Ends". */
    val status: String,
    /** Over for good: the last part of qualifying included. */
    val finished: Boolean,
    val rows: List<LiveRow>,
    val flag: TrackFlag = TrackFlag.Clear,
    val lap: Int? = null,
    val totalLaps: Int? = null,
    /** Which part of qualifying (1 to 3) is on; null outside qualifying. */
    val part: Int? = null,
    /** The session clock: [remaining] as of [clockAt], counting down while [clockRunning]. */
    val remaining: Duration? = null,
    val clockAt: Instant? = null,
    val clockRunning: Boolean = false,
) {
    val isRace: Boolean get() = type == "Race"

    /** Under way, or stopped by a red flag. */
    val running: Boolean get() = status == "Started" || status == "Aborted"

    fun timeLeft(now: Instant): Duration? {
        val left = remaining ?: return null
        if (!clockRunning || clockAt == null) return left
        return left.minus(Duration.between(clockAt, now)).let { if (it.isNegative) Duration.ZERO else it }
    }

    fun toJson(): String = JSONObject()
        .put("key", key).put("meeting", meeting).put("name", name).put("type", type)
        .putOpt("start", start?.toEpochMilli()).put("status", status).put("finished", finished)
        .put("rows", JSONArray().apply {
            rows.forEach { r ->
                put(
                    JSONObject().put("position", r.position).put("number", r.number).put("code", r.code).putOpt("colour", r.teamColour)
                        .put("gap", r.gap).put("interval", r.interval).put("pit", r.inPit).put("out", r.out).put("knockedOut", r.knockedOut),
                )
            }
        })
        .toString()

    companion object {
        /** A saved classification; the clock and flag are not kept, as only finished sessions are saved. */
        fun fromJson(text: String): LiveSession {
            val j = JSONObject(text)
            val rows = j.getJSONArray("rows").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map { r ->
                LiveRow(
                    r.getInt("position"), r.getString("number"), r.getString("code"), if (r.has("colour")) r.getLong("colour") else null,
                    r.getString("gap"), r.getString("interval"), r.optBoolean("pit"), r.optBoolean("out"), r.optBoolean("knockedOut"),
                )
            }
            return LiveSession(
                j.getInt("key"), j.getString("meeting"), j.getString("name"), j.getString("type"),
                if (j.has("start")) Instant.ofEpochMilli(j.getLong("start")) else null, j.getString("status"), j.getBoolean("finished"), rows,
            )
        }
    }
}

sealed interface F1LiveState {
    /** No session seen yet, and nothing saved from before. */
    data object Waiting : F1LiveState
    data class Live(val session: LiveSession) : F1LiveState
    /** The last session to finish, shown until the next one starts. */
    data class Result(val session: LiveSession) : F1LiveState
}

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
    /** Waits for the next messages; empty when none came for a while. Throws an [IOException] once the connection is lost. */
    fun poll(): List<LiveMessage>
}

/**
 * The feed's topics as they stand: the snapshot replaces a topic whole, and each change is merged
 * into it. Changes to a list arrive as an object keyed by index ({"1": {...}}).
 */
class LiveTiming {
    private val topics = HashMap<String, JSONObject>()

    fun apply(message: LiveMessage) {
        val now = topics[message.topic]
        topics[message.topic] = if (message.full || now == null) message.data else mergeInto(now, message.data)
    }

    fun session(): LiveSession? = parseLiveSession(topics)
}

internal fun mergeInto(target: JSONObject, change: JSONObject): JSONObject {
    for (key in change.keys()) {
        val new = change.get(key)
        val old = target.opt(key)
        when {
            new is JSONObject && old is JSONObject -> mergeInto(old, new)
            new is JSONObject && old is JSONArray -> mergeInto(old, new)
            else -> target.put(key, new)
        }
    }
    return target
}

private fun mergeInto(target: JSONArray, change: JSONObject) {
    for (key in change.keys()) {
        val index = key.toIntOrNull() ?: continue
        val new = change.get(key)
        val old = target.opt(index)
        when {
            new is JSONObject && old is JSONObject -> mergeInto(old, new)
            new is JSONObject && old is JSONArray -> mergeInto(old, new)
            else -> target.put(index, new)
        }
    }
}

/** The topics the widget reads. */
val LIVE_TOPICS = listOf("SessionInfo", "SessionStatus", "TrackStatus", "LapCount", "ExtrapolatedClock", "TimingData", "DriverList")

internal fun parseLiveSession(topics: Map<String, JSONObject>): LiveSession? {
    val info = topics["SessionInfo"] ?: return null
    val type = info.optString("Type")
    val status = topics["SessionStatus"]?.optString("Status").orEmpty()
    val timing = topics["TimingData"] ?: JSONObject()
    val part = timing.optInt("SessionPart").takeIf { it > 0 && type == "Qualifying" }
    val parts = timing.optJSONArray("NoEntries")?.length()
    val over = setOf("Finalised", "Ends")
    val finished = status in over || info.optString("SessionStatus") in over ||
        (status == "Finished" && (part == null || parts == null || part >= parts))
    val laps = topics["LapCount"]
    val clock = topics["ExtrapolatedClock"]
    val drivers = topics["DriverList"]
    val lines = timing.optJSONObject("Lines") ?: JSONObject()

    val rows = lines.keys().asSequence().mapNotNull { number ->
        val line = lines.optJSONObject(number) ?: return@mapNotNull null
        val driver = drivers?.optJSONObject(number)
        val (gap, interval) = when {
            type == "Race" -> line.optString("GapToLeader") to line.optJSONObject("IntervalToPositionAhead")?.optString("Value").orEmpty()
            part != null -> qualifyingGaps(line, part, line.optBoolean("KnockedOut"))
            else -> line.optString("TimeDiffToFastest") to line.optString("TimeDiffToPositionAhead")
        }
        LiveRow(
            position = line.optString("Position").toIntOrNull() ?: line.optInt("Line", 99),
            number = number,
            code = driver?.optString("Tla")?.takeIf { it.isNotBlank() } ?: number,
            teamColour = driver?.optString("TeamColour")?.let(::hexColour),
            gap = gap, interval = interval,
            inPit = line.optBoolean("InPit"),
            out = line.optBoolean("Retired") || line.optBoolean("Stopped"),
            knockedOut = line.optBoolean("KnockedOut"),
        ) to line
    }.sortedBy { it.first.position }.toList()

    // The leader has no gap to anyone: show its best lap, or in a race simply that it leads.
    val withLeader = rows.mapIndexed { i, (row, line) ->
        if (i > 0) row
        else {
            val best = if (type == "Race") "Leader" else bestLap(line, part)
            row.copy(gap = best, interval = best)
        }
    }
    return LiveSession(
        key = info.optInt("Key"),
        meeting = shortName(info.optJSONObject("Meeting")?.optString("Name").orEmpty()),
        name = info.optString("Name"),
        type = type,
        start = runCatching { LocalDateTime.parse(info.getString("StartDate")).toInstant(offsetOf(info.optString("GmtOffset"))) }.getOrNull(),
        status = status,
        finished = finished,
        rows = withLeader,
        flag = when (topics["TrackStatus"]?.optString("Status")) {
            "2" -> TrackFlag.Yellow
            "4" -> TrackFlag.SafetyCar
            "5" -> TrackFlag.Red
            "6" -> TrackFlag.VirtualSafetyCar
            "7" -> TrackFlag.VscEnding
            else -> TrackFlag.Clear
        },
        lap = laps?.optInt("CurrentLap")?.takeIf { it > 0 },
        totalLaps = laps?.optInt("TotalLaps")?.takeIf { it > 0 },
        part = part,
        remaining = clock?.optString("Remaining")?.let(::parseClock),
        clockAt = clock?.optString("Utc")?.let { runCatching { Instant.parse(it) }.getOrNull() },
        clockRunning = clock?.optBoolean("Extrapolating") ?: false,
    )
}

/** In qualifying: the gaps in the part now on (none yet without a lap in it), or for a car [knockedOut] earlier, in the last part it ran in. */
private fun qualifyingGaps(line: JSONObject, part: Int, knockedOut: Boolean): Pair<String, String> {
    val stats = line.optJSONArray("Stats") ?: return "" to ""
    fun at(i: Int): Pair<String, String>? = stats.optJSONObject(i)?.let { s ->
        val gap = s.optString("TimeDiffToFastest")
        val ahead = s.optString("TimeDifftoPositionAhead").ifEmpty { s.optString("TimeDiffToPositionAhead") }
        (gap to ahead).takeIf { gap.isNotEmpty() }
    }
    if (!knockedOut) return at(part - 1) ?: ("" to "")
    return (part - 1 downTo 0).firstNotNullOfOrNull(::at) ?: ("" to "")
}

private fun bestLap(line: JSONObject, part: Int?): String {
    if (part == null) return line.optJSONObject("BestLapTime")?.optString("Value").orEmpty()
    val laps = line.optJSONArray("BestLapTimes") ?: return ""
    return (part - 1 downTo 0).firstNotNullOfOrNull { i -> laps.optJSONObject(i)?.optString("Value")?.takeIf { it.isNotEmpty() } }.orEmpty()
}

/** "F47600" to 0xFFF47600. */
internal fun hexColour(hex: String): Long? = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { it or 0xFF000000 }

/** "00:06:59" to 6 minutes 59 seconds. */
internal fun parseClock(text: String): Duration? {
    val parts = text.split(':').map { it.toLongOrNull() ?: return null }
    if (parts.size != 3) return null
    return Duration.ofHours(parts[0]).plusMinutes(parts[1]).plusSeconds(parts[2])
}

/** "08:00:00" or "-05:00:00" to that offset from UTC. */
private fun offsetOf(text: String): ZoneOffset {
    val negative = text.startsWith("-")
    val parts = text.removePrefix("-").split(':').map { it.toIntOrNull() ?: 0 }
    val seconds = (parts.getOrElse(0) { 0 } * 3600 + parts.getOrElse(1) { 0 } * 60 + parts.getOrElse(2) { 0 }) * if (negative) -1 else 1
    return ZoneOffset.ofTotalSeconds(seconds)
}

/** How long before a session's planned start the widget connects. */
val LIVE_LEAD: Duration = Duration.ofMinutes(5)

/** How long after a session's planned end the widget stays connected at most; red flags run long, and the feed says when it is really over. */
val LIVE_OVERRUN: Duration = Duration.ofHours(3)

/** The session whose live window [now] is in, the latest if two overlap (a long first practice and the second). */
fun liveWindowSession(races: List<F1Race>, now: Instant): F1Session? =
    races.asSequence().flatMap { it.sessions }.lastOrNull { !now.isBefore(it.start.minus(LIVE_LEAD)) && now.isBefore(it.end.plus(LIVE_OVERRUN)) }

/** Whether [saved] is older than the last session to have ended, so its result is still to be fetched (the widget was not on screen during it). */
fun needsCatchUp(saved: LiveSession?, races: List<F1Race>, now: Instant): Boolean {
    if (saved?.start == null) return true
    val last = races.asSequence().flatMap { it.sessions }.lastOrNull { !it.end.isAfter(now) } ?: return false
    return saved.start.isBefore(last.start.minus(Duration.ofHours(1)))
}

/** How long before the next session the race-weekend widget turns from a session's top 3 back to its countdown. */
val TOP_THREE_UNTIL: Duration = Duration.ofHours(1)

/** How far a result's start may be from the calendar's time for that session and still be taken as its result. */
private val SAME_SESSION: Duration = Duration.ofMinutes(90)

/** The last session's top 3 for the race-weekend widget: [session] of [race], and the session after it, if any, of [nextRace]. */
data class SessionTop(val race: F1Race, val session: F1Session, val rows: List<LiveRow>, val nextRace: F1Race?, val next: F1Session?)

/**
 * The top 3 of the session that started last, from [result], for as long as the race-weekend widget
 * shows it: from the session's end until an hour before the next one, or after a race until a day
 * before the next weekend, as the podium does. Null while a session is on, and when [result] is not
 * that session's (an older one, while the newer result is still to come).
 */
fun sessionTop(races: List<F1Race>, result: LiveSession?, now: Instant): SessionTop? {
    val start = result?.start ?: return null
    if (!result.finished || result.rows.isEmpty()) return null
    val sessions = races.flatMap { race -> race.sessions.map { race to it } }
    val (race, session) = sessions.lastOrNull { !it.second.start.isAfter(now) } ?: return null
    if (session.liveAt(now) || Duration.between(start, session.start).abs() > SAME_SESSION) return null
    val after = sessions.firstOrNull { it.second.start.isAfter(now) }
    if (after != null) {
        val lead = if (after.first == race) TOP_THREE_UNTIL else UPCOMING_LEAD
        if (!now.isBefore(after.second.start.minus(lead))) return null
    }
    return SessionTop(race, session, result.rows.take(3), after?.first, after?.second)
}

/**
 * Live timing for the session on now, and the result of the last one. Connects only from just before
 * a session starts until the feed says it is over, then saves the classification, disconnects
 * completely and shows that result until the next session starts. When the widget missed a session,
 * its result is fetched once afterwards: from the feed, which keeps it until the next session, or
 * failing that from OpenF1. Runs only while a live widget is on screen.
 */
class F1LiveRepository(
    private val prefs: AppPrefs,
    private val source: LiveTimingSource,
    private val http: Http,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun updates(calendar: StateFlow<F1State>): Flow<F1LiveState> = channelFlow {
        // The calendar says when sessions are; keep it fetched while this runs.
        launch { calendar.collect {} }
        var result = prefs.f1LiveResult?.let { runCatching { LiveSession.fromJson(it) }.getOrNull() }
        send(result?.let { F1LiveState.Result(it) } ?: F1LiveState.Waiting)

        suspend fun keep(session: LiveSession) {
            if (session.key == result?.key && result?.finished == true) return
            result = session
            prefs.f1LiveResult = session.toJson()
            send(F1LiveState.Result(session))
        }

        var done: Instant? = null
        var lastCatchUp = Long.MIN_VALUE / 2
        while (true) {
            val now = Instant.ofEpochMilli(clock())
            val races = when (val s = calendar.value) {
                is F1State.Ready -> s.data.races
                is F1State.Failed -> s.last?.races
                F1State.Loading -> null
            }
            val window = races?.let { liveWindowSession(it, now) }
            if (races != null && window != null && window.start != done) {
                val outcome = runCatching { showAtMostEverySecond { show -> follow(races, window, show) } }
                outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                if (outcome.isFailure) {
                    // Dropped: try again, the screen keeps what it shows meanwhile.
                    delay(RETRY_MS)
                    continue
                }
                val ended = outcome.getOrNull()
                if (ended == null) {
                    // The window closed with no end from the feed: back to the last result.
                    result?.let { send(F1LiveState.Result(it)) }
                    done = window.start
                } else {
                    keep(ended)
                    // The feed may still be showing the session before this one; look again shortly.
                    if (ended.start != null && Duration.between(ended.start, window.start).abs() < Duration.ofHours(3)) done = window.start
                    else delay(STALE_MS)
                }
                continue
            }
            if ((races == null && result == null || races != null && needsCatchUp(result, races, now)) && clock() - lastCatchUp > CATCH_UP_MS) {
                lastCatchUp = clock()
                latestResult()?.let { keep(it) }
            }
            delay(CHECK_MS)
        }
    }.flowOn(io)

    /**
     * Runs [follow] with a `show` that puts a session on screen at most once a second: the feed sends
     * several messages a second, and a redraw per message is work the eye cannot use. The newest session
     * always arrives within a second, and the last one is not lost when [follow] ends.
     */
    private suspend fun <T> ProducerScope<F1LiveState>.showAtMostEverySecond(follow: suspend (suspend (LiveSession) -> Unit) -> T): T {
        val pending = Channel<LiveSession>(Channel.CONFLATED)
        val sender = launch {
            for (session in pending) {
                send(F1LiveState.Live(session))
                delay(SHOW_EVERY_MS)
            }
        }
        try {
            return follow { pending.trySend(it) }
        } finally {
            sender.cancelAndJoin()
            if (isActive) pending.tryReceive().getOrNull()?.let { send(F1LiveState.Live(it)) }
        }
    }

    /**
     * Follows the feed through the session of [window], passing it to [show] from when it starts, until
     * the feed says it is over; returns it then. Null when the window closes first. Throws when the
     * connection fails. Leaving (cancelled, or done) closes the connection.
     */
    private suspend fun follow(races: List<F1Race>, window: F1Session, show: suspend (LiveSession) -> Unit): LiveSession? {
        val timing = LiveTiming()
        val connection = source.open(LIVE_TOPICS)
        val poller = Executors.newSingleThreadExecutor()
        try {
            var started = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (liveWindowSession(races, Instant.ofEpochMilli(clock()))?.start != window.start) return null
                val messages = connection.pollCancellable(poller)
                if (messages.isEmpty()) continue
                messages.forEach(timing::apply)
                val session = timing.session() ?: continue
                if (session.finished) return session.takeIf { it.rows.isNotEmpty() }
                // Before the start the feed has little to show: keep the last result up until then.
                if (session.running) started = true
                if (started) show(session)
            }
        } finally {
            connection.close()
            poller.shutdown()
        }
    }

    /** A poll can wait a long time for data; on its own thread, so leaving closes the connection and cuts it short. */
    private suspend fun LiveConnection.pollCancellable(poller: ExecutorService): List<LiveMessage> =
        suspendCancellableCoroutine { continuation ->
            poller.execute {
                try {
                    continuation.resume(poll())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
            continuation.invokeOnCancellation { close() }
        }

    /** The last session's result: from the feed's snapshot between sessions, or else from OpenF1. */
    private fun latestResult(): LiveSession? =
        runCatching { snapshot() }.getOrNull()?.takeIf { it.finished && it.rows.isNotEmpty() }
            ?: runCatching { openF1Latest() }.getOrNull()

    private fun snapshot(): LiveSession? = source.open(LIVE_TOPICS).use { connection ->
        val timing = LiveTiming()
        repeat(3) {
            val messages = connection.poll()
            messages.forEach(timing::apply)
            if (messages.any { it.full }) return timing.session()
        }
        null
    }

    private fun openF1Latest(): LiveSession? {
        fun get(path: String) = String(http.get("$OPENF1/$path"))
        val session = JSONArray(get("sessions?session_key=latest")).optJSONObject(0) ?: return null
        val key = session.getInt("session_key")
        val meeting = JSONArray(get("meetings?meeting_key=${session.getInt("meeting_key")}")).optJSONObject(0)?.optString("meeting_name").orEmpty()
        return parseOpenF1Result(session, meeting, get("session_result?session_key=$key"), get("drivers?session_key=$key"))
    }

    companion object {
        const val OPENF1 = "https://api.openf1.org/v1"
        const val CHECK_MS = 60_000L
        const val SHOW_EVERY_MS = 1_000L
        const val RETRY_MS = 15_000L
        const val STALE_MS = 30_000L
        const val CATCH_UP_MS = 10 * 60_000L
    }
}

/** A finished session from OpenF1's session, results and drivers answers. Null when it has no results yet. */
internal fun parseOpenF1Result(session: JSONObject, meeting: String, results: String, drivers: String): LiveSession? {
    val people = JSONArray(drivers).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.optInt("driver_number").toString() }
    val rows = JSONArray(results).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .filter { it.optInt("position") > 0 }.sortedBy { it.optInt("position") }
    if (rows.isEmpty()) return null
    val race = session.optString("session_type") == "Race"

    /** A number, or the furthest part of qualifying reached ([q1, q2, q3]). */
    fun numberOf(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is JSONArray -> (value.length() - 1 downTo 0).firstNotNullOfOrNull { i -> (value.opt(i) as? Number)?.toDouble() }
        else -> null
    }
    val gaps = rows.map { numberOf(it.opt("gap_to_leader")) }
    val out = rows.mapIndexed { i, r ->
        val number = r.optInt("driver_number").toString()
        val person = people[number]
        val gapValue = gaps[i]
        val gap = when {
            i == 0 -> if (race) "Leader" else numberOf(r.opt("duration"))?.let(::lapTime).orEmpty()
            gapValue != null -> String.format(Locale.US, "+%.3f", gapValue)
            else -> (r.opt("gap_to_leader") as? String)?.replace("+", "")?.replace(Regex(" LAPS?"), "L").orEmpty()
        }
        val ahead = gaps.getOrNull(i - 1)
        val interval = when {
            i == 0 -> gap
            gapValue != null && ahead != null -> String.format(Locale.US, "+%.3f", gapValue - ahead)
            else -> gap
        }
        LiveRow(
            r.optInt("position"), number, person?.optString("name_acronym")?.takeIf { it.isNotBlank() } ?: number,
            person?.optString("team_colour")?.let(::hexColour), gap, interval,
            out = r.optBoolean("dnf") || r.optBoolean("dns") || r.optBoolean("dsq"),
        )
    }
    return LiveSession(
        key = session.getInt("session_key"),
        meeting = shortName(meeting),
        name = session.optString("session_name"),
        type = session.optString("session_type"),
        start = runCatching { OffsetDateTime.parse(session.getString("date_start")).toInstant() }.getOrNull(),
        status = "Finalised",
        finished = true,
        rows = out,
    )
}

/** 92.274 seconds to "1:32.274". */
internal fun lapTime(seconds: Double): String {
    val millis = Math.round(seconds * 1000)
    return String.format(Locale.US, "%d:%02d.%03d", millis / 60_000, millis / 1000 % 60, abs(millis % 1000))
}

/**
 * Formula 1's own live-timing feed: the one behind its timing pages, over SignalR's long polling,
 * so no more than plain HTTP is needed. Unofficial and undocumented, so it may change or close.
 */
object F1LiveTimingFeed : LiveTimingSource {
    const val BASE = "https://livetiming.formula1.com/signalrcore"
    override fun open(topics: List<String>): LiveConnection = SignalRConnection(BASE, topics)
}

private const val RECORD_END = '\u001e'

/**
 * One SignalR connection by long polling. The load balancer pins a connection to one server by a
 * cookie, so the cookies from each answer go back with the next request.
 */
private class SignalRConnection(base: String, topics: List<String>) : LiveConnection {
    private val cookies = LinkedHashMap<String, String>()
    private val url: String
    @Volatile private var inFlight: HttpURLConnection? = null
    @Volatile private var closed = false

    init {
        val negotiated = JSONObject(String(request("$base/negotiate?negotiateVersion=1", "POST", "")))
        url = "$base?id=" + URLEncoder.encode(negotiated.getString("connectionToken"), "UTF-8")
        request(url, "POST", """{"protocol":"json","version":1}$RECORD_END""")
        val subscribe = JSONObject().put("type", 1).put("target", "Subscribe").put("arguments", JSONArray().put(JSONArray(topics))).put("invocationId", "1")
        request(url, "POST", subscribe.toString() + RECORD_END)
    }

    override fun poll(): List<LiveMessage> {
        if (closed) throw IOException("closed")
        val body = try {
            String(request(url, "GET", null, readTimeoutMs = 100_000))
        } catch (e: SocketTimeoutException) {
            return emptyList()
        }
        return parseSignalR(body)
    }

    override fun close() {
        if (closed) return
        closed = true
        inFlight?.disconnect()
        Thread { runCatching { request(url, "DELETE", null) } }.start()
    }

    private fun request(url: String, method: String, body: String?, readTimeoutMs: Int = 15_000): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        inFlight = connection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", UrlConnectionHttp.USER_AGENT)
            if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            connection.headerFields.filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }.values.flatten().forEach { header ->
                val pair = header.substringBefore(';').split('=', limit = 2)
                if (pair.size == 2) cookies[pair[0].trim()] = pair[1].trim()
            }
            // 204 is how long polling says the server has ended the connection.
            if (code == HttpURLConnection.HTTP_NO_CONTENT) throw IOException("connection ended")
            if (code !in 200..299) throw IOException("HTTP $code for $method")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
            if (inFlight === connection) inFlight = null
        }
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
