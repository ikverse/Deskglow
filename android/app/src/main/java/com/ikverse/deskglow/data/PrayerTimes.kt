package com.ikverse.deskglow.data

import com.ikverse.deskglow.store.AppPrefs
import com.ikverse.deskglow.store.City
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class Prayer(val english: String, val arabic: String) {
    Fajr("Fajr", "الفجر"), Dhuhr("Dhuhr", "الظهر"), Asr("Asr", "العصر"), Maghrib("Maghrib", "المغرب"), Isha("Isha", "العشاء"),
}

/** The five times for one day, in the place's own time. */
data class PrayerDay(val date: LocalDate, val times: Map<Prayer, LocalTime>, /** Sunrise (Shuruq), when the answer had it. */ val sunrise: LocalTime? = null)

sealed interface PrayerState {
    data object NoLocation : PrayerState
    data class Loading(val city: City) : PrayerState
    data class Ready(val city: City, val days: List<PrayerDay>) : PrayerState
    /** The last fetch failed; [last] is what was shown before, if it is still for today. */
    data class Failed(val city: City, val last: List<PrayerDay>?) : PrayerState
}

/**
 * Prayer times from Aladhan (aladhan.com): free, with no account and no key. Asked for where the
 * phone is now, so the times follow it when it travels; with location off or not allowed, for the
 * city picked on the Home screen. Today and tomorrow are fetched together, so after Isha the next
 * Fajr is already known. Fetched again on a new day, when the phone has moved more than [MOVED_KM],
 * or when the method changes; the last answer is kept so the widget shows it at once.
 */
class PrayerRepository(
    private val prefs: AppPrefs,
    private val http: Http,
    private val today: () -> LocalDate = LocalDate::now,
    /** Where the phone is, or null when that is unknown or not allowed. Blocks; called off the main thread. */
    private val locate: () -> City? = { null },
) {

    /** Times by Aladhan's [method] number and Asr [school] (0 Shafi, 1 Hanafi). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(method: Int, school: Int): Flow<PrayerState> =
        combine(prefs.city, prefs.autoLocation) { manual, auto -> manual to auto }
            .flatMapLatest { (manual, auto) -> forPlace(manual, auto, method, school) }

    private fun forPlace(manual: City?, auto: Boolean, method: Int, school: Int): Flow<PrayerState> = flow {
        val key = "$method|$school"
        var last = cached()?.takeIf { it.key == key }
        last?.let { emit(PrayerState.Ready(it.city, it.days)) }
        while (true) {
            val found = if (auto) runCatching(locate).getOrNull()?.also(prefs::setDetectedCity) else null
            val here = found ?: (if (auto) prefs.detectedCity.value else null) ?: manual
            if (here == null) {
                emit(PrayerState.NoLocation)
                delay(RETRY_MS)
                continue
            }
            val date = today()
            val have = last?.takeIf { it.date == date && distanceKm(it.city, here) < MOVED_KM }
            if (have == null) {
                if (last == null) emit(PrayerState.Loading(here))
                val fresh = runCatching { listOf(fetch(here, date, method, school), fetch(here, date.plusDays(1), method, school)) }.getOrNull()
                if (fresh == null) {
                    emit(PrayerState.Failed(here, last?.takeIf { it.date == date }?.days))
                    delay(RETRY_MS)
                    continue
                }
                last = Cached(key, date, here, fresh)
                prefs.prayerCache = cacheJson(last)
            }
            emit(PrayerState.Ready(last!!.city, last.days))
            // Look again in a while (the phone may move), and just after midnight for the new day.
            val untilTomorrow = Duration.between(LocalDateTime.now(), date.plusDays(1).atStartOfDay()).toMillis() + 60_000
            delay(min(REFRESH_MS, untilTomorrow.coerceAtLeast(60_000)))
        }
    }.flowOn(Dispatchers.IO)

    private fun fetch(city: City, date: LocalDate, method: Int, school: Int): PrayerDay {
        val url = "https://api.aladhan.com/v1/timings/${date.format(DAY)}?latitude=${city.latitude}&longitude=${city.longitude}" +
            "&method=$method&school=$school"
        return parsePrayerDay(String(http.get(url)), date)
    }

    private data class Cached(val key: String, val date: LocalDate, val city: City, val days: List<PrayerDay>)

    private fun cached(): Cached? = prefs.prayerCache?.let { text ->
        runCatching {
            val json = JSONObject(text)
            val days = json.getJSONArray("days")
            Cached(
                json.getString("key"), LocalDate.parse(json.getString("date")), City.fromJson(json.getString("city"))!!,
                (0 until days.length()).map { i ->
                    val day = days.getJSONObject(i)
                    PrayerDay(
                        LocalDate.parse(day.getString("date")), Prayer.entries.associateWith { LocalTime.parse(day.getString(it.name)) },
                        day.optString("Sunrise").takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) },
                    )
                },
            )
        }.getOrNull()
    }

    private fun cacheJson(c: Cached): String = JSONObject()
        .put("key", c.key).put("date", c.date.toString()).put("city", c.city.toJson())
        .put("days", JSONArray(c.days.map { day -> JSONObject().put("date", day.date.toString()).also { o -> day.times.forEach { (p, t) -> o.put(p.name, t.toString()) }; day.sunrise?.let { o.put("Sunrise", it.toString()) } } }))
        .toString()

    companion object {
        const val REFRESH_MS = 30 * 60 * 1000L
        const val RETRY_MS = 5 * 60 * 1000L
        const val MOVED_KM = 5.0
        private val DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy")

        /** Aladhan's methods offered in the widget, as (number, name). */
        val METHODS = listOf(
            5 to "Egyptian General Authority", 3 to "Muslim World League", 4 to "Umm al-Qura, Makkah", 2 to "ISNA (North America)",
            1 to "Karachi", 8 to "Gulf Region", 9 to "Kuwait", 10 to "Qatar", 16 to "Dubai (experimental)",
            13 to "Diyanet, Turkey (experimental)", 17 to "JAKIM, Malaysia", 20 to "Kemenag, Indonesia", 11 to "Singapore",
            19 to "Algeria", 18 to "Tunisia", 21 to "Morocco", 23 to "Jordan", 15 to "Moonsighting Committee",
        )
    }
}

internal fun parsePrayerDay(body: String, date: LocalDate): PrayerDay {
    val timings = JSONObject(body).getJSONObject("data").getJSONObject("timings")
    // Times come as "04:31", or "04:31 (EET)" when a time zone is asked for.
    val sunrise = timings.optString("Sunrise").takeIf { it.length >= 5 }?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }
    return PrayerDay(date, Prayer.entries.associateWith { LocalTime.parse(timings.getString(it.name).take(5)) }, sunrise)
}

/** The next prayer after [now], and when. Null only when the days given are all in the past. */
fun nextPrayer(days: List<PrayerDay>, now: LocalDateTime): Pair<Prayer, LocalDateTime>? =
    days.sortedBy { it.date }
        .flatMap { day -> Prayer.entries.map { it to day.date.atTime(day.times.getValue(it)) } }
        .firstOrNull { it.second.isAfter(now) }

/** "1 h 20 m", "20 m", or "1 m" for anything under a minute. */
fun untilText(now: LocalDateTime, then: LocalDateTime): String {
    val minutes = Duration.between(now, then).toMinutes().coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} m" else "$minutes m"
}

/** Great-circle distance, good enough to tell whether the phone has moved to another town. */
fun distanceKm(a: City, b: City): Double {
    val rad = Math.PI / 180
    val dLat = (b.latitude - a.latitude) * rad
    val dLon = (b.longitude - a.longitude) * rad
    val h = sin(dLat / 2).pow(2) + cos(a.latitude * rad) * cos(b.latitude * rad) * sin(dLon / 2).pow(2)
    return 2 * 6371 * asin(sqrt(h))
}
