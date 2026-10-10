package com.ikverse.deskglow.data

import com.ikverse.deskglow.store.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
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

/** Where a session stands, in one word: from what the feed says and, before the start, the clock. */
enum class LivePhase { PreStart, Delayed, Break, Running, SafetyCar, VirtualSafetyCar, Red, Finished, Final }

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
    /** Out for good; such a car is listed last. */
    val retired: Boolean = false,
    /** The team's name as the feed writes it ("Red Bull Racing"). */
    val team: String = "",
    val grid: Int? = null,
    /** The tyre's compound as its letter: S, M, H, I or W. */
    val tyre: Char? = null,
    /** Laps on that set. */
    val tyreLaps: Int? = null,
    val stops: Int = 0,
    /** Laps run, in practice. */
    val laps: Int? = null,
    val lastLap: String = "",
    /** The car's best lap: in qualifying, in the part now on. */
    val bestLap: String = "",
    /** Time penalties not yet served, in seconds. */
    val penaltySeconds: Int = 0,
    /** In a race: closing on the car ahead. */
    val catching: Boolean = false,
    /** In a race: holds the fastest lap. */
    val fastest: Boolean = false,
) {
    val lastLapMs: Long? get() = lapMillis(lastLap)
    val bestMs: Long? get() = lapMillis(bestLap)

    /** Places gained since the grid: positive is up. Null when the grid is not known. */
    val gained: Int? get() = grid?.takeIf { it > 0 }?.let { it - position }
}

/** A session as the live-timing feed last described it: running, or over with its classification. */
data class LiveSession(
    val key: Int,
    /** "Singapore GP". */
    val meeting: String,
    /** "Practice 1", "Sprint Qualifying", "Race". */
    val name: String,
    /** "Practice", "Qualifying" or "Race" (a sprint is a "Race"). */
    val type: String,
    /** When the session was scheduled to start; a delay does not move it. */
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
    /** Race control has said the start is delayed. */
    val delayed: Boolean = false,
    /** A new start or restart time race control announced, and what it is the time of ("Formation lap"). */
    val restart: Instant? = null,
    val restartLabel: String = "",
    val rainRisk: Int? = null,
    val raining: Boolean = false,
    val trackTemp: Double? = null,
    /** Race control's news in plain words, oldest first. */
    val events: List<LiveEvent> = emptyList(),
    /** In qualifying: how many cars go through from the part now on; null in the last part and outside qualifying. */
    val cutoff: Int? = null,
    /** When the feed last said anything, as this was shown; null in a saved result. */
    val signalAt: Instant? = null,
    /** The connection is down and this is the last that was seen. */
    val signalLost: Boolean = false,
) {
    val isRace: Boolean get() = type == "Race"

    /** Under way, or stopped by a red flag. */
    val running: Boolean get() = status == "Started" || status == "Aborted"

    fun timeLeft(now: Instant): Duration? {
        val left = remaining ?: return null
        if (!clockRunning || clockAt == null) return left
        return left.minus(Duration.between(clockAt, now)).let { if (it.isNegative) Duration.ZERO else it }
    }

    /** Where the session stands at [now]. Only before the start does the clock matter: a start time gone by with nothing started is a delay. */
    fun phase(now: Instant): LivePhase = when {
        finished -> if (status in FINAL) LivePhase.Final else LivePhase.Finished
        status == "Aborted" -> LivePhase.Red
        status == "Started" -> when (flag) {
            TrackFlag.SafetyCar -> LivePhase.SafetyCar
            TrackFlag.VirtualSafetyCar, TrackFlag.VscEnding -> LivePhase.VirtualSafetyCar
            TrackFlag.Red -> LivePhase.Red
            else -> LivePhase.Running
        }
        betweenParts -> LivePhase.Break
        delayed || (start != null && now.isAfter(start.plusSeconds(DELAY_GRACE_S))) -> LivePhase.Delayed
        else -> LivePhase.PreStart
    }

    /** Qualifying waiting for its next part: one has been run, so the start time long gone is not a delay. */
    private val betweenParts: Boolean
        get() = type == "Qualifying" && part != null && (part >= 2 || status == "Finished" || rows.any { it.bestLap.isNotEmpty() })

    /** Laps left after the one the leader is on; null where there is no lap count. */
    val lapsToGo: Int? get() = if (lap != null && totalLaps != null) (totalLaps - lap).coerceAtLeast(0) else null

    /** The cars that go out at the end of this part of qualifying: those below the cut-off. */
    fun dropZone(): List<LiveRow> = cutoff?.let { c -> rows.filter { it.position > c && !it.knockedOut } }.orEmpty()

    /**
     * In qualifying, how far [row] is inside the cut-off (positive: it is that much quicker than the
     * first car out) or outside it (negative: that much slower than the last car in), in seconds.
     * Null without a time of its own, or without the car it is measured against.
     */
    fun cutoffMargin(row: LiveRow): Double? {
        val c = cutoff ?: return null
        val own = row.bestMs ?: return null
        return if (row.position <= c) {
            val firstOut = rows.firstOrNull { it.position == c + 1 }?.bestMs ?: return null
            (firstOut - own) / 1000.0
        } else {
            val lastIn = rows.firstOrNull { it.position == c }?.bestMs ?: return null
            -(own - lastIn) / 1000.0
        }
    }

    fun toJson(): String = JSONObject()
        .put("key", key).put("meeting", meeting).put("name", name).put("type", type)
        .putOpt("start", start?.toEpochMilli()).put("status", status).put("finished", finished)
        .put("rows", JSONArray().apply {
            rows.forEach { r ->
                put(
                    JSONObject().put("position", r.position).put("number", r.number).put("code", r.code).putOpt("colour", r.teamColour)
                        .put("gap", r.gap).put("interval", r.interval).put("pit", r.inPit).put("out", r.out).put("knockedOut", r.knockedOut)
                        .put("retired", r.retired).put("team", r.team).putOpt("grid", r.grid).putOpt("tyre", r.tyre?.toString())
                        .putOpt("tyreLaps", r.tyreLaps).put("stops", r.stops).putOpt("laps", r.laps).put("lastLap", r.lastLap)
                        .put("bestLap", r.bestLap).put("penalty", r.penaltySeconds).put("fastest", r.fastest),
                )
            }
        })
        .toString()

    companion object {
        /** A saved classification; the clock, flag and news are not kept, as only finished sessions are saved. */
        fun fromJson(text: String): LiveSession {
            val j = JSONObject(text)
            val rows = j.getJSONArray("rows").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map { r ->
                LiveRow(
                    r.getInt("position"), r.getString("number"), r.getString("code"), if (r.has("colour")) r.getLong("colour") else null,
                    r.getString("gap"), r.getString("interval"), r.optBoolean("pit"), r.optBoolean("out"), r.optBoolean("knockedOut"),
                    retired = r.optBoolean("retired"), team = r.optString("team"),
                    grid = if (r.has("grid")) r.getInt("grid") else null,
                    tyre = r.optString("tyre").firstOrNull(),
                    tyreLaps = if (r.has("tyreLaps")) r.getInt("tyreLaps") else null,
                    stops = r.optInt("stops"), laps = if (r.has("laps")) r.getInt("laps") else null,
                    lastLap = r.optString("lastLap"), bestLap = r.optString("bestLap"),
                    penaltySeconds = r.optInt("penalty"), fastest = r.optBoolean("fastest"),
                )
            }
            return LiveSession(
                j.getInt("key"), j.getString("meeting"), j.getString("name"), j.getString("type"),
                if (j.has("start")) Instant.ofEpochMilli(j.getLong("start")) else null, j.getString("status"), j.getBoolean("finished"), rows,
            )
        }
    }
}

/** The feed's last word on a session: over for good. */
private val FINAL = setOf("Finalised", "Ends")

/** How long past its scheduled start a session may sit unstarted before it counts as delayed. */
private const val DELAY_GRACE_S = 120L

sealed interface F1LiveState {
    /** No session seen yet, and nothing saved from before. */
    data object Waiting : F1LiveState
    data class Live(val session: LiveSession) : F1LiveState
    /** The last session to finish, shown until the next one starts. */
    data class Result(val session: LiveSession) : F1LiveState
}

/**
 * The feed's topics as they stand: the snapshot replaces a topic whole, and each change is merged
 * into it. Changes to a list arrive as an object keyed by index ({"1": {...}}). What no widget reads
 * (mini-sectors, speed traps, headshots) is dropped as it arrives, so it is never merged, kept or parsed again.
 */
class LiveTiming {
    private val topics = HashMap<String, JSONObject>()
    private var control: RaceControl? = null

    /**
     * The part of qualifying that was on when the status last changed: the next part starts a moment
     * before the status leaves "Finished", so the part now on is not always the one that finished.
     */
    private var statusPart: Int? = null

    /** Takes in [message]; false when nothing in it is shown anywhere, so there is nothing to redraw. */
    @Synchronized
    fun apply(message: LiveMessage): Boolean {
        if (message.topic == "Heartbeat") return false
        val spec = PRUNE[message.topic]
        val used = spec == null || prune(message.data, spec)
        if (!used && !message.full) return false
        val now = topics[message.topic]
        topics[message.topic] = if (message.full || now == null) message.data else mergeInto(now, message.data)
        if (message.topic == "RaceControlMessages" || message.topic == "SessionInfo") control = null
        // A snapshot is all of a moment, so its part goes with its status; a later change of part does not.
        if (message.topic == "SessionStatus" || message.full && message.topic == "TimingData") {
            statusPart = topics["TimingData"]?.optInt("SessionPart")?.takeIf { it > 0 }
        }
        return true
    }

    @Synchronized
    fun session(): LiveSession? {
        val info = topics["SessionInfo"] ?: return null
        val news = control ?: readRaceControl(topics["RaceControlMessages"], offsetOf(info.optString("GmtOffset")), startOf(info)?.minus(MESSAGES_FROM)?.takeIf { topics["SessionStatus"]?.optString("Status") == "Inactive" }).also { control = it }
        return parseLiveSession(topics, news, statusPart)
    }

    /** Whether the session is over for good: checked without building the classification. */
    @Synchronized
    fun finished(): Boolean = headerOf(topics, statusPart)?.finished == true
}

/** What each busy topic keeps: a key to nothing keeps all of it, a key to a map goes on inside it, and "*" is any key. */
private val LINE_KEEP: Map<String, Any?> = mapOf(
    "Position" to null, "Line" to null, "GapToLeader" to null, "IntervalToPositionAhead" to null,
    "TimeDiffToFastest" to null, "TimeDiffToPositionAhead" to null, "InPit" to null, "Retired" to null, "Stopped" to null,
    "KnockedOut" to null, "Stats" to null, "BestLapTimes" to null, "NumberOfLaps" to null, "NumberOfPitStops" to null,
    "BestLapTime" to mapOf("Value" to null), "LastLapTime" to mapOf("Value" to null),
)
private val PRUNE: Map<String, Map<String, Any?>> = mapOf(
    "TimingData" to mapOf("Lines" to mapOf("*" to LINE_KEEP), "SessionPart" to null, "NoEntries" to null),
    "DriverList" to mapOf("*" to mapOf("Tla" to null, "TeamColour" to null, "TeamName" to null)),
    "TimingAppData" to mapOf("Lines" to mapOf("*" to mapOf("GridPos" to null, "Stints" to mapOf("*" to mapOf("Compound" to null, "TotalLaps" to null))))),
)

/** Drops from [node] what [spec] does not keep; true while anything is left. */
@Suppress("UNCHECKED_CAST")
private fun prune(node: Any?, spec: Any?): Boolean {
    val keep = spec as? Map<String, Any?> ?: return true
    when (node) {
        is JSONObject -> {
            for (key in node.keys().asSequence().toList()) {
                val known = keep.containsKey(key)
                if (!known && !keep.containsKey("*")) {
                    node.remove(key)
                    continue
                }
                val child = node.opt(key)
                if ((child is JSONObject || child is JSONArray) && !prune(child, if (known) keep[key] else keep["*"])) node.remove(key)
            }
            return node.length() > 0
        }
        is JSONArray -> {
            for (i in 0 until node.length()) (node.opt(i) as? JSONObject)?.let { prune(it, keep["*"]) }
            return node.length() > 0
        }
        else -> return true
    }
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

/** Everything the widgets read while a session runs. */
val LIVE_TOPICS = listOf(
    "SessionInfo", "SessionStatus", "TrackStatus", "LapCount", "ExtrapolatedClock", "TimingData", "DriverList",
    "RaceControlMessages", "WeatherData", "TimingAppData", "Heartbeat",
)

/** All the race weekend and schedule widgets need: whether the session is on, delayed or stopped. About a kilobyte a minute. */
val LITE_TOPICS = listOf("SessionInfo", "SessionStatus", "TrackStatus", "LapCount", "RaceControlMessages", "Heartbeat")

private class Header(val info: JSONObject, val type: String, val status: String, val part: Int?, val finished: Boolean)

/** [statusPart] is the part of qualifying that was on when the status last changed, where known. */
private fun headerOf(topics: Map<String, JSONObject>, statusPart: Int? = null): Header? {
    val info = topics["SessionInfo"] ?: return null
    val type = info.optString("Type")
    val status = topics["SessionStatus"]?.optString("Status").orEmpty()
    val timing = topics["TimingData"]
    val part = timing?.optInt("SessionPart")?.takeIf { it > 0 && type == "Qualifying" }
    val parts = timing?.optJSONArray("NoEntries")?.length()
    // "Finished" ends every part of qualifying, so it counts only when the last part is known to be the one that ended;
    // without that, only the feed's last word does.
    val ended = statusPart ?: part
    val finished = status in FINAL || info.optString("SessionStatus") in FINAL ||
        (status == "Finished" && if (type == "Qualifying") ended != null && parts != null && ended >= parts else true)
    return Header(info, type, status, part, finished)
}

internal fun parseLiveSession(topics: Map<String, JSONObject>, news: RaceControl? = null, statusPart: Int? = null): LiveSession? {
    val header = headerOf(topics, statusPart) ?: return null
    val info = header.info
    val type = header.type
    val part = header.part
    val timing = topics["TimingData"] ?: JSONObject()
    val scheduled = startOf(info)
    val control = news ?: readRaceControl(topics["RaceControlMessages"], offsetOf(info.optString("GmtOffset")), scheduled?.minus(MESSAGES_FROM)?.takeIf { header.status == "Inactive" })
    val laps = topics["LapCount"]
    val clock = topics["ExtrapolatedClock"]
    val drivers = topics["DriverList"]
    val weather = topics["WeatherData"]
    val appLines = topics["TimingAppData"]?.optJSONObject("Lines")
    val lines = timing.optJSONObject("Lines") ?: JSONObject()
    val noEntries = timing.optJSONArray("NoEntries")
    // The feed holds the session before until this one starts: its times are not this session's.
    val leftOver = header.status == "Inactive" && part == null && (type == "Qualifying" || type == "Practice")

    val rows = lines.keys().asSequence().mapNotNull { number ->
        val line = lines.optJSONObject(number) ?: return@mapNotNull null
        val driver = drivers?.optJSONObject(number)
        val app = appLines?.optJSONObject(number)
        val (gap, interval) = when {
            type == "Race" -> line.optString("GapToLeader") to line.optJSONObject("IntervalToPositionAhead")?.optString("Value").orEmpty()
            part != null -> qualifyingGaps(line, part, line.optBoolean("KnockedOut"))
            else -> line.optString("TimeDiffToFastest") to line.optString("TimeDiffToPositionAhead")
        }
        val stints = stintsOf(app?.opt("Stints"))
        val current = stints.lastOrNull()
        LiveRow(
            position = line.optString("Position").toIntOrNull() ?: line.optInt("Line", 99),
            number = number,
            code = driver?.optString("Tla")?.takeIf { it.isNotBlank() } ?: number,
            teamColour = driver?.optString("TeamColour")?.let(::hexColour),
            gap = gap, interval = interval,
            inPit = line.optBoolean("InPit"),
            out = line.optBoolean("Retired") || line.optBoolean("Stopped"),
            knockedOut = line.optBoolean("KnockedOut"),
            retired = line.optBoolean("Retired"),
            team = driver?.optString("TeamName").orEmpty(),
            grid = app?.optString("GridPos")?.toIntOrNull()?.takeIf { it > 0 },
            tyre = current?.optString("Compound")?.let(::compoundLetter),
            tyreLaps = current?.optInt("TotalLaps", -1)?.takeIf { it >= 0 },
            stops = maxOf((stints.size - 1).coerceAtLeast(0), line.optInt("NumberOfPitStops")),
            laps = line.optInt("NumberOfLaps", -1).takeIf { it >= 0 },
            lastLap = line.optJSONObject("LastLapTime")?.optString("Value").orEmpty(),
            bestLap = if (part != null) elementAt(line.opt("BestLapTimes"), part - 1)?.optString("Value").orEmpty() else line.optJSONObject("BestLapTime")?.optString("Value").orEmpty(),
            penaltySeconds = control.penalties[number] ?: 0,
            catching = type == "Race" && line.optJSONObject("IntervalToPositionAhead")?.optBoolean("Catching") == true,
        ) to line
    }.sortedWith(compareBy({ it.first.retired }, { it.first.position })).toList()

    // The leader has no gap to anyone: show its best lap, or in a race simply that it leads.
    val fastestMs = if (type == "Race") rows.mapNotNull { it.first.bestMs }.minOrNull() else null
    val withLeader = rows.mapIndexed { i, (row, line) ->
        val marked = if (fastestMs != null && row.bestMs == fastestMs) row.copy(fastest = true) else row
        if (i > 0) marked
        else {
            val best = if (type == "Race") "Leader" else bestLap(line, part)
            marked.copy(gap = best, interval = best)
        }
    }
    return LiveSession(
        key = info.optInt("Key"),
        meeting = shortName(info.optJSONObject("Meeting")?.optString("Name").orEmpty()),
        name = info.optString("Name"),
        type = type,
        start = scheduled,
        status = header.status,
        finished = header.finished,
        rows = if (leftOver) emptyList() else withLeader,
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
        delayed = control.delayed,
        restart = control.restart,
        restartLabel = control.restartLabel,
        rainRisk = control.rainRisk,
        raining = weather?.optString("Rainfall").let { it == "1" || (it?.toDoubleOrNull() ?: 0.0) > 0.0 },
        trackTemp = weather?.optString("TrackTemp")?.toDoubleOrNull(),
        events = control.events,
        cutoff = if (part != null && noEntries != null && part < noEntries.length()) noEntries.optInt(part).takeIf { it > 0 } else null,
    )
}

/** When the session in [info] is scheduled to start. */
private fun startOf(info: JSONObject): Instant? =
    runCatching { LocalDateTime.parse(info.getString("StartDate")).toInstant(offsetOf(info.optString("GmtOffset"))) }.getOrNull()

/** A list in a snapshot, or an object keyed by index when the first news of it came as a change: its [index]th element. */
private fun elementAt(value: Any?, index: Int): JSONObject? = when (value) {
    is JSONArray -> value.optJSONObject(index)
    is JSONObject -> value.optJSONObject(index.toString())
    else -> null
}

/** A car's tyre stints, oldest first. */
private fun stintsOf(value: Any?): List<JSONObject> = when (value) {
    is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
    is JSONObject -> value.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to value.optJSONObject(k) } }
        .sortedBy { it.first }.mapNotNull { it.second }.toList()
    else -> emptyList()
}

private fun compoundLetter(name: String): Char? = when (name.uppercase()) {
    "SOFT" -> 'S'
    "MEDIUM" -> 'M'
    "HARD" -> 'H'
    "INTERMEDIATE" -> 'I'
    "WET" -> 'W'
    else -> null
}

/** In qualifying: the gaps in the part now on (none yet without a lap in it), or for a car [knockedOut] earlier, in the last part it ran in. */
private fun qualifyingGaps(line: JSONObject, part: Int, knockedOut: Boolean): Pair<String, String> {
    val stats = line.opt("Stats")
    fun at(i: Int): Pair<String, String>? = elementAt(stats, i)?.let { s ->
        val gap = s.optString("TimeDiffToFastest")
        val ahead = s.optString("TimeDifftoPositionAhead").ifEmpty { s.optString("TimeDiffToPositionAhead") }
        (gap to ahead).takeIf { gap.isNotEmpty() }
    }
    if (stats == null) return "" to ""
    if (!knockedOut) return at(part - 1) ?: ("" to "")
    return (part - 1 downTo 0).firstNotNullOfOrNull(::at) ?: ("" to "")
}

private fun bestLap(line: JSONObject, part: Int?): String {
    if (part == null) return line.optJSONObject("BestLapTime")?.optString("Value").orEmpty()
    // Only the part now on, as the other cars' gaps are to the fastest of that part.
    return elementAt(line.opt("BestLapTimes"), part - 1)?.optString("Value").orEmpty()
}

/** "F47600" to 0xFFF47600. */
internal fun hexColour(hex: String): Long? = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { it or 0xFF000000 }

/** "00:06:59" to 6 minutes 59 seconds. */
internal fun parseClock(text: String): Duration? {
    val parts = text.split(':').map { it.toLongOrNull() ?: return null }
    if (parts.size != 3) return null
    return Duration.ofHours(parts[0]).plusMinutes(parts[1]).plusSeconds(parts[2])
}

/** "1:32.274" or "59.987" to milliseconds; null when it is not a lap time (blank, or a gap). */
internal fun lapMillis(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty() || t.startsWith("+")) return null
    val minutes = if (':' in t) t.substringBefore(':').toLongOrNull() ?: return null else 0L
    val seconds = (if (':' in t) t.substringAfter(':') else t).toDoubleOrNull() ?: return null
    return minutes * 60_000 + Math.round(seconds * 1000)
}

private val LAPPED = Regex("""^\+?(\d+)\s?L(?:APS?)?$""", RegexOption.IGNORE_CASE)

/** A gap as it reads best: "1L" is "+1 lap", "2L" is "+2 laps"; a time stays as it is. */
fun gapText(gap: String): String = LAPPED.matchEntire(gap.trim())?.let { m ->
    val laps = m.groupValues[1].toInt()
    "+$laps lap" + if (laps == 1) "" else "s"
} ?: gap

/** "08:00:00" or "-05:00:00" to that offset from UTC. */
internal fun offsetOf(text: String): ZoneOffset {
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

/** What the feed says about the calendar's session [session]: [live] is the feed's own view of it. */
data class FeedStatus(val session: F1Session, val phase: LivePhase, val live: LiveSession)

/**
 * The calendar session the feed is following now and where it stands, so a widget that knows only
 * the calendar can say "delayed" or "red flag", and stay live past the slot the calendar gave the
 * session. Null when the feed is following nothing, or something the calendar does not list.
 */
fun feedStatus(races: List<F1Race>, state: F1LiveState, now: Instant): FeedStatus? {
    val live = (state as? F1LiveState.Live)?.session ?: return null
    val start = live.start ?: return null
    val session = races.asSequence().flatMap { it.sessions }.minByOrNull { Duration.between(it.start, start).abs() } ?: return null
    if (Duration.between(session.start, start).abs() > SAME_SESSION) return null
    return FeedStatus(session, live.phase(now), live)
}

/**
 * [view] as the feed sees the weekend: while it says a session is on, delayed or stopped, that
 * session is the live one whatever slot the calendar gave it (a delayed sprint outlives its hour).
 */
fun weekendViewWithFeed(view: WeekendView, races: List<F1Race>, fed: FeedStatus?): WeekendView {
    if (fed == null || fed.phase == LivePhase.PreStart) return view
    val race = races.firstOrNull { r -> r.sessions.any { it == fed.session } } ?: return view
    return WeekendView.Upcoming(race, fed.session, race.sessions.firstOrNull { it.start.isAfter(fed.session.start) }, fed)
}

/** The race control news worth showing at [now]: the latest, if it is fresh. */
fun LiveSession.freshEvent(now: Instant, within: Duration = EVENT_FRESH): LiveEvent? =
    events.lastOrNull()?.takeIf { !it.at.isAfter(now.plusSeconds(60)) && Duration.between(it.at, now) <= within }

/** How long a piece of news stays on a widget. */
val EVENT_FRESH: Duration = Duration.ofSeconds(90)

/**
 * Live timing for the session on now, and the result of the last one. Connects only from just before
 * a session starts until the feed says it is over, then saves the classification, disconnects
 * completely and shows that result until the next session starts. While only the calendar-based
 * widgets are on screen it subscribes to the few topics they need; while the live widget is on screen
 * [updates]'s `detail` is true and it takes the rest. When the widget missed a session, its result is
 * fetched once afterwards: from the feed, which keeps it until the next session, or failing that from
 * OpenF1. Runs only while an F1 widget is on screen.
 */
class F1LiveRepository(
    private val prefs: AppPrefs,
    private val source: LiveTimingSource,
    private val http: Http,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** What one run of [updates] has seen of the feed, kept across reconnects so a dropped connection can show the last thing it said. */
    private class Seen {
        @Volatile var at: Instant? = null
        @Volatile var shown: LiveSession? = null
    }

    private val seen = Seen()

    private sealed interface Followed {
        /** The session is over (the classification as it stands, with no rows when only the few topics were followed), or null when the window closed first. */
        data class Over(val session: LiveSession?) : Followed
        /** The widgets on screen now want other topics than the ones subscribed. */
        data object Reconnect : Followed
    }

    fun updates(calendar: StateFlow<F1State>, detail: StateFlow<Boolean> = MutableStateFlow(true)): Flow<F1LiveState> = channelFlow {
        // The calendar says when sessions are; keep it fetched while this runs.
        launch { calendar.collect {} }
        var result = prefs.f1LiveResult?.let { runCatching { LiveSession.fromJson(it) }.getOrNull() }
        // The feed stops a few seconds after the last widget leaves and starts again when one returns:
        // a session on until then is still on, so what was last seen is what shows until the feed says more.
        val resumed = seen.shown?.takeIf { shown ->
            val races = when (val s = calendar.value) {
                is F1State.Ready -> s.data.races
                is F1State.Failed -> s.last?.races
                F1State.Loading -> null
            }
            val window = races?.let { liveWindowSession(it, Instant.ofEpochMilli(clock())) }
            window != null && sameSession(shown, window)
        }
        send(resumed?.let { F1LiveState.Live(it) } ?: result?.let { F1LiveState.Result(it) } ?: F1LiveState.Waiting)

        suspend fun keep(session: LiveSession) {
            // A finished result gives way only to a later finished one with its classification ("Finalised" after "Finished").
            if (!(session.key == result?.key && result?.finished == true) || session.finished && session.rows.isNotEmpty()) {
                result = session
                prefs.f1LiveResult = session.toJson()
            }
            send(F1LiveState.Result(result ?: session))
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
                val outcome = runCatching { follow(races, window, detail, seen) }
                outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                if (outcome.isFailure) {
                    // Dropped: say so over what was last seen, and try again.
                    seen.shown?.let { send(F1LiveState.Live(it.copy(signalAt = seen.at, signalLost = true))) }
                    delay(RETRY_MS)
                    continue
                }
                when (val followed = outcome.getOrThrow()) {
                    Followed.Reconnect -> Unit
                    is Followed.Over -> {
                        seen.shown = null
                        val ended = followed.session
                        if (ended == null) {
                            // The window closed with no end from the feed: back to the last result.
                            send(result?.let { F1LiveState.Result(it) } ?: F1LiveState.Waiting)
                            done = window.start
                        } else {
                            if (ended.rows.isNotEmpty()) keep(ended)
                            else {
                                // Followed without the timing: the classification is fetched whole, at once.
                                send(result?.let { F1LiveState.Result(it) } ?: F1LiveState.Waiting)
                                lastCatchUp = Long.MIN_VALUE / 2
                            }
                            // The feed may still be showing the session before this one; look again shortly.
                            if (ended.start != null && Duration.between(ended.start, window.start).abs() < Duration.ofHours(3)) done = window.start
                            else delay(STALE_MS)
                        }
                    }
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
     * Follows the feed through the session of [window], putting the session on screen from before it
     * starts until the feed says it is over. The classification is built at most once a second, and
     * only after something shown has changed: the feed sends several messages a second, and a redraw
     * per message is work the eye cannot use. Throws when the connection fails. Leaving (cancelled,
     * or done) closes the connection.
     */
    private suspend fun ProducerScope<F1LiveState>.follow(races: List<F1Race>, window: F1Session, detail: StateFlow<Boolean>, seen: Seen): Followed {
        val full = detail.value
        val timing = LiveTiming()
        val connection = source.open(if (full) LIVE_TOPICS else LITE_TOPICS)
        val poller = Executors.newSingleThreadExecutor()
        val changed = Channel<Unit>(Channel.CONFLATED)
        val builder = launch {
            for (ignored in changed) {
                timing.session()?.takeIf { !it.finished && sameSession(it, window) }?.let { session ->
                    val shown = session.copy(signalAt = seen.at)
                    seen.shown = shown
                    send(F1LiveState.Live(shown))
                }
                delay(SHOW_EVERY_MS)
            }
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (liveWindowSession(races, Instant.ofEpochMilli(clock()))?.start != window.start) return Followed.Over(null)
                if (detail.value != full) return Followed.Reconnect
                val messages = connection.pollCancellable(poller)
                seen.at = Instant.ofEpochMilli(clock())
                var news = false
                for (message in messages) if (timing.apply(message)) news = true
                if (!news) continue
                if (timing.finished()) return Followed.Over(timing.session())
                changed.trySend(Unit)
            }
        } finally {
            builder.cancel()
            connection.close()
            poller.shutdown()
        }
    }

    /** Whether [session] is the one [window] is about, rather than the one before it that the feed may still hold. */
    private fun sameSession(session: LiveSession, window: F1Session): Boolean =
        session.start != null && Duration.between(session.start, window.start).abs() <= SAME_SESSION

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
            messages.forEach { timing.apply(it) }
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
