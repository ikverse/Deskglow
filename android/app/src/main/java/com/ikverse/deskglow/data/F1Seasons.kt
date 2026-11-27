package com.ikverse.deskglow.data

import com.ikverse.deskglow.store.AppPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** One race of a finished season, for looking back on it: who won, who started on pole, and the podium. */
data class SeasonRound(val round: Int, val name: String, val winner: F1Entry?, val pole: F1Entry?, val podium: List<F1Entry>)

/** A year's champions: the driver and the team. */
data class Champion(val year: Int, val driver: F1Entry?, val team: F1Entry?)

/**
 * What PitWall shows between seasons: the finished [season]'s races and final tables, the champions
 * of the years before it (newest first), and next season's calendar once it is published.
 */
data class F1Seasons(
    val season: Int,
    val rounds: List<SeasonRound> = emptyList(),
    val drivers: List<F1Entry> = emptyList(),
    val constructors: List<F1Entry> = emptyList(),
    val next: List<F1Race> = emptyList(),
    val honour: List<Champion> = emptyList(),
)

/** How long after a race PitWall keeps its weekend on screen before moving on. */
val RESULT_HOLD: Duration = Duration.ofHours(12)

/** How long before a new season's first session PitWall leaves the look back for that weekend. */
val SEASON_LEAD: Duration = Duration.ofDays(7)

private fun yearOf(race: F1Race) = race.race.start.atZone(ZoneOffset.UTC).year

/**
 * The season to look back on at [now], going by the calendar [races]: the calendar's own once its last
 * race is well over, the one before while its first weekend is still more than [SEASON_LEAD] away, and
 * last year's when the calendar is empty. Null while a season is under way.
 */
fun seasonToReview(races: List<F1Race>, now: Instant): Int? {
    if (races.isEmpty()) return now.atZone(ZoneOffset.UTC).year - 1
    val last = races.last()
    if (!now.isBefore(last.race.end.plus(RESULT_HOLD))) return yearOf(last)
    val first = races.first()
    if (races.none { !it.race.end.isAfter(now) } && now.isBefore(first.first.start.minus(SEASON_LEAD))) return yearOf(first) - 1
    return null
}

/**
 * A finished season from Jolpica, for PitWall's off-season screen. Asks for nothing while a season is
 * under way; between seasons it fetches the season once (again every half hour until every round is
 * in, and once more three days after the last race, when the results are settled), the champions of
 * the ten years before once ever, and next season's calendar weekly. Requests are spaced out, well
 * inside Jolpica's limits, and everything is kept in a file so the screen shows at once.
 */
class F1SeasonsRepository(
    private val prefs: AppPrefs,
    private val http: Http,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun updates(calendar: StateFlow<F1State>): Flow<F1Seasons?> = channelFlow {
        var kept = prefs.f1Seasons?.let { runCatching { Kept.fromJson(it) }.getOrNull() }
        send(kept?.seasons)
        calendar.map(::racesOf).distinctUntilChanged().collectLatest { races ->
            races ?: return@collectLatest
            while (true) {
                val season = seasonToReview(races, Instant.ofEpochMilli(clock()))
                if (season != null) {
                    refresh(kept, season, races) {
                        kept = it
                        prefs.f1Seasons = it.toJson()
                        send(it.seasons)
                    }
                }
                delay(CHECK_MS)
            }
        }
    }.flowOn(io)

    private fun racesOf(state: F1State): List<F1Race>? = when (state) {
        is F1State.Ready -> state.data.races
        is F1State.Failed -> state.last?.races
        F1State.Loading -> null
    }

    /** Fetches whatever of [season] is missing or old, handing each part to [save] as it arrives. */
    private suspend fun refresh(before: Kept?, season: Int, races: List<F1Race>, save: suspend (Kept) -> Unit) {
        var k = before?.takeIf { it.seasons.season == season } ?: Kept(F1Seasons(season, honour = before?.seasons?.honour.orEmpty()))
        val now = clock()
        // The calendar is the reviewed season's own until Jolpica moves on to the next; then how many rounds it had is not known.
        val lastEnd = races.lastOrNull()?.takeIf { yearOf(it) == season }?.race?.end?.toEpochMilli()
        val expected = if (lastEnd != null) races.size else 1
        val whole = k.seasons.rounds.size >= expected && k.seasons.drivers.isNotEmpty()
        val settled = lastEnd?.plus(SETTLE_MS)
        val due = if (!whole) now - k.seasonAt >= STALE_MS else settled != null && k.seasonAt < settled && now >= settled
        if (due) {
            runCatching { fetchSeason(season) }.getOrNull()?.let { fresh ->
                k = k.copy(seasons = k.seasons.copy(rounds = fresh.rounds, drivers = fresh.drivers, constructors = fresh.constructors), seasonAt = now)
                save(k)
            }
        }
        // Not published until late in the year: looked for daily until it is there, weekly after.
        if (now - k.nextAt >= if (k.seasons.next.isEmpty()) DAY_MS else WEEK_MS) {
            runCatching { get("${season + 1}.json?limit=100") }.getOrNull()?.let { body ->
                runCatching { parseCalendar(body) }.getOrNull()?.let { next ->
                    k = k.copy(seasons = k.seasons.copy(next = next), nextBody = body, nextAt = now)
                    save(k)
                }
            }
        }
        val years = (season - 1 downTo season - HONOUR_YEARS).toList()
        val missing = years.filter { y -> k.seasons.honour.none { it.year == y } }
        if (missing.isNotEmpty()) {
            val found = missing.mapNotNull { y ->
                runCatching {
                    Champion(
                        y,
                        parseStandings(get("$y/driverstandings/1.json"), constructors = false).firstOrNull(),
                        parseStandings(get("$y/constructorstandings/1.json"), constructors = true).firstOrNull(),
                    )
                }.getOrNull()
            }
            val honour = (k.seasons.honour + found).filter { it.year in years }.sortedByDescending { it.year }
            if (honour != k.seasons.honour) {
                k = k.copy(seasons = k.seasons.copy(honour = honour))
                save(k)
            }
        }
    }

    private suspend fun fetchSeason(season: Int): F1Seasons {
        val rows = (1..3).flatMap { parseResultRows(get("$season/results/$it.json?limit=100")) }
        // The poles are a nicety: without them the grid of the top three still gives most.
        val poles = runCatching { polesOf(season) }.getOrDefault(emptyMap())
        return F1Seasons(
            season,
            rounds = seasonRounds(rows, poles),
            drivers = parseStandings(get("$season/driverstandings.json?limit=100"), constructors = false),
            constructors = parseStandings(get("$season/constructorstandings.json?limit=100"), constructors = true),
        )
    }

    private suspend fun polesOf(season: Int): Map<Int, F1Entry> {
        val out = HashMap<Int, F1Entry>()
        var offset = 0
        while (true) {
            val body = get("$season/qualifying/1.json?limit=100&offset=$offset")
            out += parsePoles(body)
            offset += 100
            if (offset >= totalOf(body)) break
        }
        return out
    }

    private suspend fun get(path: String): String {
        delay(GAP_MS)
        return String(http.get("${F1Repository.BASE}/$path"))
    }

    companion object {
        const val CHECK_MS = 30 * 60 * 1000L
        /** A little under [CHECK_MS], so a season still short of rounds is asked for again on the next look. */
        const val STALE_MS = 25 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val WEEK_MS = 7 * DAY_MS
        /** How long after the last race the results are taken as settled (penalties, appeals). */
        const val SETTLE_MS = 3 * DAY_MS
        /** Between requests: Jolpica allows four a second. */
        const val GAP_MS = 400L
        const val HONOUR_YEARS = 10
    }
}

/** What is kept in the file: the season as parsed, when it and the next calendar were fetched, and that calendar as it came. */
internal data class Kept(val seasons: F1Seasons, val seasonAt: Long = 0, val nextAt: Long = 0, val nextBody: String? = null) {
    fun toJson(): String = JSONObject()
        .put("v", FORMAT).put("season", seasons.season).put("seasonAt", seasonAt).put("nextAt", nextAt).putOpt("next", nextBody)
        .put("rounds", JSONArray().apply {
            seasons.rounds.forEach { r ->
                put(
                    JSONObject().put("round", r.round).put("name", r.name).putOpt("winner", r.winner?.json()).putOpt("pole", r.pole?.json())
                        .put("podium", JSONArray().apply { r.podium.forEach { put(it.json()) } }),
                )
            }
        })
        .put("drivers", JSONArray().apply { seasons.drivers.forEach { put(it.json()) } })
        .put("constructors", JSONArray().apply { seasons.constructors.forEach { put(it.json()) } })
        .put("honour", JSONArray().apply {
            seasons.honour.forEach { put(JSONObject().put("year", it.year).putOpt("driver", it.driver?.json()).putOpt("team", it.team?.json())) }
        })
        .toString()

    companion object {
        const val FORMAT = 1

        fun fromJson(text: String): Kept {
            val j = JSONObject(text)
            require(j.optInt("v") == FORMAT)
            val nextBody = j.optString("next").takeIf { it.isNotEmpty() }
            return Kept(
                F1Seasons(
                    season = j.getInt("season"),
                    rounds = j.getJSONArray("rounds").items().map { r ->
                        SeasonRound(r.getInt("round"), r.getString("name"), r.optJSONObject("winner")?.entry(), r.optJSONObject("pole")?.entry(), r.getJSONArray("podium").items().map { it.entry() })
                    },
                    drivers = j.getJSONArray("drivers").items().map { it.entry() },
                    constructors = j.getJSONArray("constructors").items().map { it.entry() },
                    next = nextBody?.let { runCatching { parseCalendar(it) }.getOrNull() }.orEmpty(),
                    honour = j.getJSONArray("honour").items().map { Champion(it.getInt("year"), it.optJSONObject("driver")?.entry(), it.optJSONObject("team")?.entry()) },
                ),
                j.optLong("seasonAt"), j.optLong("nextAt"), nextBody,
            )
        }
    }
}

private fun F1Entry.json() = JSONObject().put("id", id).put("name", name).put("team", teamId).put("points", points).put("position", position)
private fun JSONObject.entry() = F1Entry(getString("id"), optString("name"), optString("team"), optDouble("points", 0.0), optInt("position"))
private fun JSONArray.items(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun racesIn(body: String): List<JSONObject> =
    JSONObject(body).getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races").items()

private fun totalOf(body: String): Int = JSONObject(body).getJSONObject("MRData").optString("total").toIntOrNull() ?: 0

/** One finisher of one race, with where it started. */
internal class ResultRow(val round: Int, val race: String, val entry: F1Entry, val grid: Int)

private fun driverOf(row: JSONObject): F1Entry {
    val driver = row.getJSONObject("Driver")
    val family = driver.optString("familyName")
    val code = driver.optString("code").takeIf { it.isNotBlank() } ?: family.take(3).uppercase()
    return F1Entry(
        code, "${driver.optString("givenName")} $family".trim(), row.optJSONObject("Constructor")?.optString("constructorId").orEmpty(),
        row.optDouble("points", 0.0), row.optString("position").toIntOrNull() ?: 0,
    )
}

/** Every finisher in a "results" answer, whichever places it was asked for. */
internal fun parseResultRows(body: String): List<ResultRow> = racesIn(body).flatMap { race ->
    race.optJSONArray("Results")?.items().orEmpty().map { r ->
        ResultRow(race.getInt("round"), shortName(race.getString("raceName")), driverOf(r), r.optString("grid").toIntOrNull() ?: 0)
    }
}

/** Who was quickest in qualifying, by round. Jolpica's answer to "position 1" holds stray rows of other places, which are passed over. */
internal fun parsePoles(body: String): Map<Int, F1Entry> = racesIn(body).mapNotNull { race ->
    race.optJSONArray("QualifyingResults")?.items()?.firstOrNull { it.optString("position") == "1" }?.let { race.getInt("round") to driverOf(it) }
}.toMap()

/** The season's races in order from [rows] (the top three of each); the pole from [poles], else whoever of the three started first. */
internal fun seasonRounds(rows: List<ResultRow>, poles: Map<Int, F1Entry>): List<SeasonRound> =
    rows.groupBy { it.round }.toSortedMap().map { (round, of) ->
        val podium = of.sortedBy { it.entry.position }.map { it.entry }
        SeasonRound(round, of.first().race, podium.firstOrNull { it.position == 1 }, poles[round] ?: of.firstOrNull { it.grid == 1 }?.entry, podium)
    }
