package com.ikverse.deskglow.data

import com.ikverse.deskglow.store.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One session of a race weekend: "FP1", "Sprint Quali", "Sprint", "Qualifying" or "Race". */
data class F1Session(val kind: String, val start: Instant) {
    val end: Instant get() = start.plus(Duration.ofMinutes(if (kind == "Race") 120 else 60))
    fun liveAt(now: Instant) = !now.isBefore(start) && now.isBefore(end)
}

/** A race weekend: "Japanese GP" at "Suzuka, Japan", its sessions in order. */
data class F1Race(val round: Int, val name: String, val place: String, val sessions: List<F1Session>) {
    val first: F1Session get() = sessions.first()
    val race: F1Session get() = sessions.last()
}

/** A driver or a team in a result or a table. [id] is a driver's three-letter code or a team's id. */
data class F1Entry(val id: String, val name: String, val teamId: String, val points: Double, val position: Int)

/** The top of a race's result. */
data class F1Result(val round: Int, val raceName: String, val podium: List<F1Entry>)

data class F1Data(
    val races: List<F1Race>,
    val lastResult: F1Result?,
    val drivers: List<F1Entry>,
    val constructors: List<F1Entry>,
    /** Where each driver or team stood one round earlier, by id, for the ▲▼ arrows. */
    val previousDrivers: Map<String, Int>,
    val previousConstructors: Map<String, Int>,
    /** The round the standings are after. */
    val round: Int,
    /** The outline of the circuit for the weekend now or next, once fetched. */
    val track: F1Track? = null,
)

/**
 * A circuit's outline for one race: points in a box whose longer side is 1, turned the way the
 * track is usually drawn, with y running down the screen. [aspect] is the box's width over its height.
 */
data class F1Track(val season: Int, val round: Int, val points: List<Pair<Float, Float>>, val aspect: Float) {
    fun isFor(race: F1Race) = season == seasonOf(race) && round == race.round

    fun toJson(): String = JSONObject()
        .put("season", season).put("round", round).put("aspect", aspect.toDouble())
        .put("points", JSONArray().apply { points.forEach { (x, y) -> put(x.toDouble()); put(y.toDouble()) } })
        .toString()

    companion object {
        fun fromJson(text: String): F1Track {
            val j = JSONObject(text)
            val flat = j.getJSONArray("points")
            val points = (0 until flat.length() / 2).map { flat.getDouble(it * 2).toFloat() to flat.getDouble(it * 2 + 1).toFloat() }
            return F1Track(j.getInt("season"), j.getInt("round"), points, j.getDouble("aspect").toFloat())
        }
    }
}

private fun seasonOf(race: F1Race) = race.first.start.atZone(ZoneOffset.UTC).year

sealed interface F1State {
    data object Loading : F1State
    data class Ready(val data: F1Data) : F1State
    /** The last fetch failed; [last] is what was shown before, if anything. */
    data class Failed(val last: F1Data?) : F1State
}

/**
 * Formula 1 from Jolpica (api.jolpi.ca), the free, keyless successor to the Ergast API, and the
 * outline of the next circuit from OpenF1 (which circuit) and MultiViewer (its shape). Fetched
 * only while an F1 widget is on screen. The calendar is fetched again weekly; results and standings
 * daily, every few hours over a race weekend, and soon after each race ends. The answers are kept
 * as they came, so the widgets show the last of them at once and survive a dropped connection.
 */
class F1Repository(
    private val prefs: AppPrefs,
    private val http: Http,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun updates(): Flow<F1State> = flow {
        var raw = cached()
        var data = raw?.let { runCatching { parseF1(it) }.getOrNull() }
        var track = prefs.f1Track?.let { runCatching { F1Track.fromJson(it) }.getOrNull() }
        fun withTrack(d: F1Data): F1Data {
            val race = trackRace(d, Instant.ofEpochMilli(clock()))
            return d.copy(track = track?.takeIf { race != null && it.isFor(race) })
        }
        emit(data?.let { F1State.Ready(withTrack(it)) } ?: F1State.Loading)
        while (true) {
            val now = clock()
            val needCalendar = raw == null || data == null || now - raw.calendarAt > CALENDAR_MS
            val needStandings = raw == null || data == null || standingsStale(raw.standingsAt, data, now)
            if (needCalendar || needStandings) {
                val fresh = runCatching { fetch(raw.takeIf { !needCalendar }, now) }.getOrNull()
                val parsed = fresh?.let { runCatching { parseF1(it) }.getOrNull() }
                if (fresh == null || parsed == null) {
                    emit(F1State.Failed(data))
                    delay(RETRY_MS)
                    continue
                }
                raw = fresh
                data = parsed
                prefs.f1Cache = fresh.toJson()
            }
            F1Roster.update(data)
            // The outline is a nicety: when it cannot be had, the widget goes without and it is tried again next time round.
            val race = trackRace(data, Instant.ofEpochMilli(clock()))
            if (race != null && track?.isFor(race) != true) {
                runCatching { fetchTrack(race) }.getOrNull()?.let {
                    track = it
                    prefs.f1Track = it.toJson()
                }
            }
            emit(F1State.Ready(withTrack(data)))
            delay(CHECK_MS)
        }
    }.flowOn(Dispatchers.IO)

    /** Old after a day, or after six hours over a race weekend, or once a race has ended since they were fetched. */
    private fun standingsStale(fetchedAt: Long, data: F1Data, nowMs: Long): Boolean {
        val now = Instant.ofEpochMilli(nowMs)
        val weekend = data.races.any { now.isAfter(it.first.start.minus(Duration.ofDays(1))) && now.isBefore(it.race.end.plus(Duration.ofDays(1))) }
        if (nowMs - fetchedAt > if (weekend) WEEKEND_MS else DAY_MS) return true
        // Results reach the API a little after the flag, so look two hours on.
        return data.races.any { val settled = it.race.end.plus(Duration.ofHours(2)).toEpochMilli(); settled in (fetchedAt + 1)..nowMs }
    }

    /** Everything, or everything but the calendar when [keep] still has a fresh one. */
    private fun fetch(keep: F1Raw?, now: Long): F1Raw {
        val calendar = keep?.calendar ?: get("current.json")
        val drivers = get("current/driverStandings.json")
        val constructors = get("current/constructorStandings.json")
        val results = get("current/last/results.json")
        val round = standingsRound(drivers)
        val previousDrivers = if (round > 1) get("current/${round - 1}/driverStandings.json") else null
        val previousConstructors = if (round > 1) get("current/${round - 1}/constructorStandings.json") else null
        return F1Raw(calendar, results, drivers, constructors, previousDrivers, previousConstructors, keep?.calendarAt ?: now, now)
    }

    private fun get(path: String) = String(http.get("$BASE/$path"))

    /** OpenF1 knows which circuit each weekend is at; MultiViewer has that circuit's outline. */
    private fun fetchTrack(race: F1Race): F1Track? {
        val season = seasonOf(race)
        val key = circuitKeyFor(String(http.get("$OPENF1/meetings?year=$season")), race) ?: return null
        return parseTrack(String(http.get("$MULTIVIEWER/circuits/$key/$season")), season, race.round)
    }

    private fun cached(): F1Raw? = prefs.f1Cache?.let { runCatching { F1Raw.fromJson(it) }.getOrNull() }

    companion object {
        const val BASE = "https://api.jolpi.ca/ergast/f1"
        const val OPENF1 = "https://api.openf1.org/v1"
        const val MULTIVIEWER = "https://api.multiviewer.app/api/v1"
        const val CHECK_MS = 30 * 60 * 1000L
        const val RETRY_MS = 10 * 60 * 1000L
        const val WEEKEND_MS = 6 * 60 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val CALENDAR_MS = 7 * DAY_MS
    }
}

/** The API's answers as they came, and when they were fetched. */
internal data class F1Raw(
    val calendar: String,
    val results: String?,
    val drivers: String,
    val constructors: String,
    val previousDrivers: String?,
    val previousConstructors: String?,
    val calendarAt: Long,
    val standingsAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("calendar", calendar).putOpt("results", results).put("drivers", drivers).put("constructors", constructors)
        .putOpt("previousDrivers", previousDrivers).putOpt("previousConstructors", previousConstructors)
        .put("calendarAt", calendarAt).put("standingsAt", standingsAt)
        .toString()

    companion object {
        fun fromJson(text: String): F1Raw {
            val j = JSONObject(text)
            fun opt(name: String) = if (j.has(name) && !j.isNull(name)) j.getString(name) else null
            return F1Raw(
                j.getString("calendar"), opt("results"), j.getString("drivers"), j.getString("constructors"),
                opt("previousDrivers"), opt("previousConstructors"), j.getLong("calendarAt"), j.getLong("standingsAt"),
            )
        }
    }
}

internal fun parseF1(raw: F1Raw): F1Data {
    val drivers = parseStandings(raw.drivers, constructors = false)
    return F1Data(
        races = parseCalendar(raw.calendar),
        lastResult = raw.results?.let(::parseResult),
        drivers = drivers,
        constructors = parseStandings(raw.constructors, constructors = true),
        previousDrivers = raw.previousDrivers?.let { parseStandings(it, false) }.orEmpty().associate { it.id to it.position },
        previousConstructors = raw.previousConstructors?.let { parseStandings(it, true) }.orEmpty().associate { it.id to it.position },
        round = standingsRound(raw.drivers),
    )
}

private fun mr(body: String) = JSONObject(body).getJSONObject("MRData")

private val SESSIONS = listOf(
    "FirstPractice" to "FP1", "SecondPractice" to "FP2", "ThirdPractice" to "FP3",
    "SprintQualifying" to "Sprint Quali", "SprintShootout" to "Sprint Quali", "Sprint" to "Sprint", "Qualifying" to "Qualifying",
)

private fun instantOf(o: JSONObject): Instant {
    val date = LocalDate.parse(o.getString("date"))
    // A time is missing only for sessions not yet fixed; noon UTC keeps them on the right day.
    val time = o.optString("time").takeIf { it.isNotBlank() }?.removeSuffix("Z")?.let(LocalTime::parse) ?: LocalTime.NOON
    return date.atTime(time).toInstant(ZoneOffset.UTC)
}

internal fun parseCalendar(body: String): List<F1Race> {
    val races = mr(body).getJSONObject("RaceTable").getJSONArray("Races")
    return races.objects().map { r ->
        val sessions = SESSIONS.mapNotNull { (key, kind) -> r.optJSONObject(key)?.let { F1Session(kind, instantOf(it)) } } +
            F1Session("Race", instantOf(r))
        val location = r.getJSONObject("Circuit").getJSONObject("Location")
        F1Race(
            r.getInt("round"), shortName(r.getString("raceName")),
            listOf(location.optString("locality"), location.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
            sessions.sortedBy { it.start },
        )
    }.sortedBy { it.round }
}

internal fun parseResult(body: String): F1Result? {
    val race = mr(body).getJSONObject("RaceTable").getJSONArray("Races").objects().firstOrNull() ?: return null
    val podium = race.getJSONArray("Results").objects().take(3).mapIndexed { i, r -> driverEntry(r, i) }
    return F1Result(race.getInt("round"), shortName(race.getString("raceName")), podium)
}

internal fun standingsRound(body: String): Int =
    runCatching { mr(body).getJSONObject("StandingsTable").getJSONArray("StandingsLists").objects().firstOrNull()?.getInt("round") ?: 0 }.getOrDefault(0)

internal fun parseStandings(body: String, constructors: Boolean): List<F1Entry> {
    val list = mr(body).getJSONObject("StandingsTable").getJSONArray("StandingsLists").objects().firstOrNull() ?: return emptyList()
    val rows = list.getJSONArray(if (constructors) "ConstructorStandings" else "DriverStandings").objects()
    return rows.mapIndexed { i, row ->
        if (constructors) {
            val team = row.getJSONObject("Constructor")
            F1Entry(team.getString("constructorId"), team.getString("name"), team.getString("constructorId"), row.optDouble("points", 0.0), positionOf(row, i))
        } else {
            driverEntry(row, i, row.optJSONArray("Constructors")?.objects()?.lastOrNull())
        }
    }
}

/** A driver from a result or a standings row, keyed by the three-letter code ("VER"). */
private fun driverEntry(row: JSONObject, index: Int, team: JSONObject? = row.optJSONObject("Constructor")): F1Entry {
    val driver = row.getJSONObject("Driver")
    val family = driver.optString("familyName")
    val code = driver.optString("code").takeIf { it.isNotBlank() } ?: family.take(3).uppercase()
    return F1Entry(code, "${driver.optString("givenName")} $family".trim(), team?.optString("constructorId").orEmpty(), row.optDouble("points", 0.0), positionOf(row, index))
}

private fun positionOf(row: JSONObject, index: Int) = row.optString("position").toIntOrNull() ?: (index + 1)

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** The weekend whose circuit the race-weekend widget would draw: the one running, or the next. */
fun trackRace(data: F1Data, now: Instant): F1Race? = data.races.firstOrNull { it.race.end.isAfter(now) }

/** OpenF1's circuit key for [race]: the meeting (not a test) that starts within two days of the weekend's first session. */
internal fun circuitKeyFor(meetings: String, race: F1Race): Int? =
    JSONArray(meetings).objects().firstOrNull { m ->
        !m.optString("meeting_name").contains("Testing", ignoreCase = true) &&
            runCatching { Duration.between(OffsetDateTime.parse(m.getString("date_start")).toInstant(), race.first.start).abs() < Duration.ofDays(2) }.getOrDefault(false)
    }?.optInt("circuit_key")?.takeIf { it > 0 }

/**
 * A MultiViewer circuit: its x and y turned by its rotation, y flipped to run down the screen,
 * thinned to at most [TRACK_POINTS] points, and scaled into a box whose longer side is 1.
 */
internal fun parseTrack(body: String, season: Int, round: Int): F1Track? {
    val j = JSONObject(body)
    val xs = j.getJSONArray("x")
    val ys = j.getJSONArray("y")
    val count = min(xs.length(), ys.length())
    if (count < 3) return null
    val angle = Math.toRadians(j.optDouble("rotation", 0.0))
    val stride = max(1, count / TRACK_POINTS)
    val turned = (0 until count step stride).map { i ->
        val x = xs.getDouble(i)
        val y = ys.getDouble(i)
        (x * cos(angle) - y * sin(angle)) to -(x * sin(angle) + y * cos(angle))
    }
    val left = turned.minOf { it.first }
    val top = turned.minOf { it.second }
    val width = turned.maxOf { it.first } - left
    val height = turned.maxOf { it.second } - top
    val side = max(width, height).takeIf { it > 0 } ?: return null
    val points = turned.map { (x, y) -> ((x - left) / side).toFloat() to ((y - top) / side).toFloat() }
    return F1Track(season, round, points, (width / max(height, side * 0.01)).toFloat())
}

private const val TRACK_POINTS = 240

/** "Japanese Grand Prix" to "Japanese GP". */
fun shortName(raceName: String) = raceName.replace("Grand Prix", "GP").trim()

/** What the race-weekend widget shows. */
sealed interface WeekendView {
    /** The weekend now or next: the session running (if any) and the next to start (if any). */
    data class Upcoming(val race: F1Race, val live: F1Session?, val next: F1Session?) : WeekendView
    /** From a race until a day before the next weekend: the podium, and which race is next. */
    data class AfterRace(val result: F1Result, val next: F1Race?) : WeekendView
    data object Empty : WeekendView
}

/** How long before a weekend's first session the widget turns from the last podium to that weekend. */
val UPCOMING_LEAD: Duration = Duration.ofDays(1)

fun weekendView(data: F1Data, now: Instant): WeekendView {
    val current = data.races.firstOrNull { it.race.end.isAfter(now) }
    val result = data.lastResult
    val previous = data.races.lastOrNull { !it.race.end.isAfter(now) }
    if (result != null && previous != null && result.round == previous.round && (current == null || now.isBefore(current.first.start.minus(UPCOMING_LEAD)))) {
        return WeekendView.AfterRace(result, current)
    }
    current ?: return WeekendView.Empty
    return WeekendView.Upcoming(current, current.sessions.firstOrNull { it.liveAt(now) }, current.sessions.firstOrNull { it.start.isAfter(now) })
}

/** "2 d 4 h", "4 h 12 m", "12:34" (minutes and seconds in the last hour). */
fun countdownText(now: Instant, then: Instant): String {
    val seconds = Duration.between(now, then).seconds.coerceAtLeast(0)
    return when {
        seconds >= 86_400 -> "${seconds / 86_400} d ${seconds % 86_400 / 3600} h"
        seconds >= 3600 -> "${seconds / 3600} h ${seconds % 3600 / 60} m"
        else -> String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
}

/** A standings row and its move since the round before: positive is up, null when it was not there. */
data class StandingRow(val entry: F1Entry, val move: Int?)

/**
 * The top [count] rows, and the favourite as an extra row when it is further down. The favourite
 * is matched by id: a driver's code ("HAM") or a team's id ("ferrari").
 */
fun standingRows(entries: List<F1Entry>, previous: Map<String, Int>, count: Int, favourite: String): Pair<List<StandingRow>, StandingRow?> {
    fun row(e: F1Entry) = StandingRow(e, previous[e.id]?.let { it - e.position })
    val top = entries.take(count).map(::row)
    val extra = entries.drop(count).firstOrNull { favourite.isNotEmpty() && it.id == favourite }?.let(::row)
    return top to extra
}

/** Rows split into two columns for a wide box: the first half (the larger, if odd) on the left. */
fun <T> standingColumns(rows: List<T>): Pair<List<T>, List<T>> = rows.take((rows.size + 1) / 2) to rows.drop((rows.size + 1) / 2)

/** Team colours, close to each team's own. A team not listed (a new one) shows grey until it is added. */
fun teamColour(teamId: String): Long = when (teamId) {
    "red_bull" -> 0xFF3671C6
    "mclaren" -> 0xFFFF8000
    "ferrari" -> 0xFFE8002D
    "mercedes" -> 0xFF27F4D2
    "aston_martin" -> 0xFF229971
    "alpine" -> 0xFFFF87BC
    "williams" -> 0xFF64C4FF
    "rb" -> 0xFF6692FF
    "haas" -> 0xFFB6BABD
    "sauber" -> 0xFF52E252
    "audi" -> 0xFFF50537
    "cadillac" -> 0xFFC9A75D
    else -> 0xFF8C8C8C
}

/**
 * The drivers and teams the favourite picker offers: the current grid from the last answer, or this
 * season's grid as known when the app was built, before the first answer arrives.
 */
object F1Roster {
    @Volatile
    var drivers: List<Pair<String, String>> = listOf(
        "VER" to "Max Verstappen", "HAD" to "Isack Hadjar", "NOR" to "Lando Norris", "PIA" to "Oscar Piastri",
        "LEC" to "Charles Leclerc", "HAM" to "Lewis Hamilton", "RUS" to "George Russell", "ANT" to "Kimi Antonelli",
        "ALO" to "Fernando Alonso", "STR" to "Lance Stroll", "GAS" to "Pierre Gasly", "COL" to "Franco Colapinto",
        "ALB" to "Alexander Albon", "SAI" to "Carlos Sainz", "LAW" to "Liam Lawson", "LIN" to "Arvid Lindblad",
        "OCO" to "Esteban Ocon", "BEA" to "Oliver Bearman", "HUL" to "Nico Hülkenberg", "BOR" to "Gabriel Bortoleto",
        "PER" to "Sergio Pérez", "BOT" to "Valtteri Bottas",
    )
        private set

    @Volatile
    var teams: List<Pair<String, String>> = listOf(
        "mclaren" to "McLaren", "mercedes" to "Mercedes", "red_bull" to "Red Bull", "ferrari" to "Ferrari",
        "williams" to "Williams", "rb" to "Racing Bulls", "aston_martin" to "Aston Martin", "haas" to "Haas",
        "audi" to "Audi", "alpine" to "Alpine", "cadillac" to "Cadillac",
    )
        private set

    fun update(data: F1Data) {
        if (data.drivers.isNotEmpty()) drivers = data.drivers.map { it.id to it.name }
        if (data.constructors.isNotEmpty()) teams = data.constructors.map { it.id to it.name }
    }
}

/** A widget's own favourite wins; "" follows the one set on the Home screen; "none" follows nobody. */
fun effectiveFavourite(own: String, app: String): String = when (own) {
    "none" -> ""
    "" -> app
    else -> own
}

/** The colour of the followed team: [team]'s own, else the team [driver] drives for. Null when neither is known. */
fun favouriteColour(data: F1Data, driver: String, team: String): Int? {
    val teamId = team.ifEmpty { data.drivers.firstOrNull { it.id == driver }?.teamId.orEmpty() }
    return teamId.takeIf { it.isNotEmpty() }?.let { teamColour(it).toInt() }
}
