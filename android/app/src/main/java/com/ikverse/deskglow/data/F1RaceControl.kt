package com.ikverse.deskglow.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** What a piece of news is about, so the widget can colour it. */
enum class EventKind { Flag, Red, Penalty, Incident, Info, Pit, Position, Fastest }

/**
 * One piece of news in plain words. A driver is written as [CODE] in [text], so the widget can name
 * them as its own setting says (code, number or surname).
 */
data class LiveEvent(val kind: EventKind, val text: String, val at: Instant)

/** While a session has not started: how long before its start race control messages are still taken as its own. */
internal val MESSAGES_FROM: java.time.Duration = java.time.Duration.ofMinutes(45)

/** Everything the widgets use from race control's messages, read once whenever they change. */
internal class RaceControl(
    val delayed: Boolean = false,
    /** A new start or restart time race control announced, and what it is the time of: "Formation lap", "Resumes", "Starts". */
    val restart: Instant? = null,
    val restartLabel: String = "",
    val rainRisk: Int? = null,
    /** Time penalties not yet served, in seconds by car number. */
    val penalties: Map<String, Int> = emptyMap(),
    /** The news, oldest first; only the last few are kept. */
    val events: List<LiveEvent> = emptyList(),
    /** The marshal sectors under a yellow flag now, by number: "YELLOW" or "DOUBLE YELLOW". */
    val sectorFlags: Map<Int, String> = emptyMap(),
)

private val CAR = Regex("""CAR (\d+) \(([A-Z]{3})\)""")
private val SECONDS = Regex("""(\d+) SECOND""")
private val RESTART = Regex("""(?:START|RESUME)S? AT (\d{1,2}):(\d{2})""")
private val RAIN = Regex("""RISK OF RAIN.*?(\d{1,3})\s?%""")

private const val KEPT_EVENTS = 6

/** How far apart in time a flag and a message about a car may be for the car to be named as the flag's cause. */
private const val CAUSE_WITHIN_S = 60L

/** Messages that name a car without saying it caused anything. */
private val NOT_A_CAUSE = listOf("DELETED", "BLUE FLAG", "PENALTY", "TRACK LIMITS", "PIT LANE", "PIT EXIT", "NOTED", "NO FURTHER", "SERVED", "REPRIMAND")

/** The car race control wrote about nearest in time to [at], within a minute either side; null when none was. */
private fun causeNear(causes: List<Pair<Instant, String>>, at: Instant): String? =
    causes.filter { java.time.Duration.between(it.first, at).abs().seconds <= CAUSE_WITHIN_S }
        .minByOrNull { java.time.Duration.between(it.first, at).abs() }?.second

/**
 * Reads the message list: the snapshot is an array, and a change before any snapshot is an object
 * keyed by index. [offset] is the track's own offset from UTC, for the times race control writes in track time.
 */
internal fun readRaceControl(topic: JSONObject?, offset: ZoneOffset, since: Instant? = null): RaceControl {
    val list = topic?.opt("Messages")
    val messages: List<JSONObject> = when (list) {
        is JSONArray -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        is JSONObject -> list.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to list.optJSONObject(k) } }
            .sortedBy { it.first }.mapNotNull { it.second }.toList()
        else -> emptyList()
    }
    var delayed = false
    var restart: Instant? = null
    var label = ""
    var rain: Int? = null
    val penalties = LinkedHashMap<String, Int>()
    val events = ArrayList<LiveEvent>()
    val sectorFlags = LinkedHashMap<Int, String>()
    // Each car race control wrote about (stopped, spun, in an incident), with when, to name what brought a flag out.
    val causes = messages.mapNotNull { m ->
        val text = m.optString("Message").uppercase()
        val code = CAR.find(text)?.groupValues?.get(2) ?: return@mapNotNull null
        if (NOT_A_CAUSE.any { it in text }) return@mapNotNull null
        val at = runCatching { LocalDateTime.parse(m.optString("Utc")).toInstant(ZoneOffset.UTC) }.getOrNull() ?: return@mapNotNull null
        at to code
    }

    for (m in messages) {
        val text = m.optString("Message").uppercase()
        if (text.isEmpty()) continue
        val at = runCatching { LocalDateTime.parse(m.optString("Utc")).toInstant(ZoneOffset.UTC) }.getOrNull() ?: continue
        // The feed holds the last session's messages until the next one starts.
        if (since != null && at.isBefore(since)) continue
        fun news(kind: EventKind, what: String) { events += LiveEvent(kind, what, at) }
        val car = CAR.find(text)
        val code = car?.groupValues?.get(2)
        val number = car?.groupValues?.get(1)

        val scope = m.optString("Scope")
        val flag = m.optString("Flag").uppercase()
        val sector = m.optInt("Sector", 0).takeIf { it > 0 }
        // Which sectors are under yellow: set and cleared sector by sector, and all cleared by a green or clear for the whole track.
        // A yellow already out in that sector, given again, is not news.
        val already = sector != null && sectorFlags[sector] == flag
        if (scope == "Sector" && sector != null) {
            when (flag) {
                "YELLOW", "DOUBLE YELLOW" -> sectorFlags[sector] = flag
                "CLEAR", "GREEN" -> sectorFlags.remove(sector)
            }
        } else if (scope == "Track" && (flag == "CLEAR" || flag == "GREEN" || flag == "RED")) sectorFlags.clear()
        fun caused(what: String) = causeNear(causes, at)?.let { "$what · [$it]" } ?: what

        when {
            scope == "Sector" && sector != null && (flag == "YELLOW" || flag == "DOUBLE YELLOW") ->
                if (!already) news(EventKind.Flag, caused("${if (flag == "DOUBLE YELLOW") "Double yellow" else "Yellow"} · sector $sector"))
            "DELAYED START" in text || "START DELAYED" in text -> { delayed = true; news(EventKind.Info, "Start delayed") }
            RESTART.containsMatchIn(text) -> {
                val (h, min) = RESTART.find(text)!!.destructured
                restart = trackTime(at, offset, h.toInt(), min.toInt())
                label = when {
                    "FORMATION" in text -> "Formation lap"
                    "RESUME" in text -> "Resumes"
                    else -> "Starts"
                }
            }
            "FORMATION" in text -> news(EventKind.Info, "Formation lap")
            RAIN.containsMatchIn(text) -> {
                rain = RAIN.find(text)!!.groupValues[1].toInt().coerceIn(0, 100)
                news(EventKind.Info, "Rain risk $rain%")
            }
            "PENALTY" in text && code != null && number != null && "NO PENALTY" !in text -> when {
                "SERVED" in text -> penalties.remove(number)
                else -> {
                    val seconds = SECONDS.find(text)?.groupValues?.get(1)?.toInt()
                    if (seconds != null) {
                        penalties[number] = (penalties[number] ?: 0) + seconds
                        news(EventKind.Penalty, "[$code] +${seconds}s penalty")
                    } else if ("DRIVE THROUGH" in text || "DRIVE-THROUGH" in text) {
                        news(EventKind.Penalty, "[$code] drive-through penalty")
                    }
                }
            }
            "INCIDENT INVOLVING" in text && code != null -> when {
                "NO FURTHER" in text -> news(EventKind.Info, "[$code] no further action")
                "WILL BE INVESTIGATED" in text || "UNDER INVESTIGATION" in text -> news(EventKind.Incident, "[$code] under investigation")
            }
            "DELETED" in text && code != null -> news(EventKind.Info, "[$code] lap time deleted")
            code != null && "STOPPED" in text -> news(EventKind.Incident, "[$code] stopped on track")
            "VIRTUAL SAFETY CAR" in text -> news(EventKind.Flag, if ("ENDING" in text) "VSC ending" else "Virtual safety car")
            "SAFETY CAR" in text -> news(EventKind.Flag, if ("IN THIS LAP" in text) "Safety car in this lap" else "Safety car")
            // Before the red flag: "CHEQUERED FLAG" has "RED FLAG" in it.
            "CHEQUERED" in text -> news(EventKind.Flag, "Chequered flag")
            "RED FLAG" in text || (m.optString("Flag") == "RED" && m.optString("Scope") == "Track") -> news(EventKind.Red, caused("Red flag"))
            m.optString("Flag") == "CLEAR" && m.optString("Scope") == "Track" -> news(EventKind.Flag, "Track clear")
            "LOW GRIP" in text -> news(EventKind.Info, "Low grip")
            "CLIMATIC" in text -> news(EventKind.Info, "Conditions changing")
        }
    }
    // A repeated line of news (the same car noted twice) reads as one.
    val distinct = events.filterIndexed { i, e -> i == 0 || e.text != events[i - 1].text }
    return RaceControl(delayed, restart, label, rain, penalties, distinct.takeLast(KEPT_EVENTS), sectorFlags)
}

/** [hour]:[minute] at the track, on the day [sent] was in there: the next day if that is already well past. */
private fun trackTime(sent: Instant, offset: ZoneOffset, hour: Int, minute: Int): Instant {
    val day: LocalDate = sent.atOffset(offset).toLocalDate()
    val then = LocalDateTime.of(day, LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))).toInstant(offset)
    return if (then.isBefore(sent.minusSeconds(6 * 3600))) then.plusSeconds(24 * 3600) else then
}
