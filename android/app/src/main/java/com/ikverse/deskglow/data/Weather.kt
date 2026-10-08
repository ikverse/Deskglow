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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Weather from Open-Meteo: free, with no account and no key. Fetched only while a weather widget is
 * on screen, and then at most every [REFRESH_MS]; the last answer is kept so the widget shows
 * something at once and survives a dropped connection.
 */
class WeatherRepository(
    private val prefs: AppPrefs,
    private val http: Http,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where the phone is, or null when that is unknown or not allowed. Blocks; called off the main thread. */
    private val locate: () -> City? = { null },
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(): Flow<WeatherState> = combine(prefs.city, prefs.autoLocation) { manual, auto -> manual to auto }
        .flatMapLatest { (manual, auto) ->
            if (!auto && manual == null) flowOf(WeatherState.NoCity) else forCity(manual, auto)
        }

    /**
     * With [auto] on, the phone's position is read again before each refresh and wins over the city
     * picked by name, which is the fallback while there is no position.
     */
    private fun forCity(manual: City?, auto: Boolean): Flow<WeatherState> = flow {
        var city = (if (auto) prefs.detectedCity.value else null) ?: manual
        var last = city?.let(::cached)
        city?.let { emit(if (last != null) WeatherState.Ready(it, last) else WeatherState.Loading(it)) }
        while (true) {
            if (auto) {
                val found = runCatching(locate).getOrNull()
                if (found != null) {
                    prefs.setDetectedCity(found)
                    if (found != city) {
                        city = found
                        last = cached(found)
                    }
                }
            }
            val here = city
            if (here == null) {
                emit(WeatherState.NoCity)
                delay(RETRY_MS)
                continue
            }
            val age = last?.let { clock() - it.fetchedAtMs } ?: Long.MAX_VALUE
            if (age < REFRESH_MS) {
                emit(WeatherState.Ready(here, last!!))
                delay(REFRESH_MS - age)
                continue
            }
            val fresh = runCatching { fetch(here) }.getOrNull()
            if (fresh != null) {
                last = fresh
                prefs.weatherCache = cacheJson(here, fresh)
                emit(WeatherState.Ready(here, fresh))
            } else {
                emit(if (last != null) WeatherState.Ready(here, last) else WeatherState.Failed(here, null))
                delay(RETRY_MS)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun fetch(city: City): Weather {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${city.latitude}&longitude=${city.longitude}" +
            "&current=temperature_2m,weather_code,is_day,apparent_temperature,relative_humidity_2m,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=1"
        return parseForecast(String(http.get(url)), clock())
    }

    /** Places matching [name], for the owner to pick from. */
    fun search(name: String): List<City> {
        val query = URLEncoder.encode(name.trim(), "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$query&count=8&language=en&format=json"
        return parseCities(String(http.get(url)))
    }

    private fun cached(city: City): Weather? = prefs.weatherCache?.let { text ->
        runCatching {
            val json = JSONObject(text)
            if (json.getString("city") != city.toJson()) return@runCatching null
            val w = json.getJSONObject("weather")
            Weather(
                w.getDouble("t"), w.getInt("code"), w.getBoolean("day"), w.getDouble("hi"), w.getDouble("lo"), w.getLong("at"),
                feelsLikeC = w.numberOrNull("feels")?.toDouble(),
                humidityPercent = w.numberOrNull("hum")?.toInt(),
                windKmh = w.numberOrNull("wind")?.toDouble(),
                rainChancePercent = w.numberOrNull("rain")?.toInt(),
            )
        }.getOrNull()
    }

    private fun cacheJson(city: City, w: Weather): String = JSONObject()
        .put("city", city.toJson())
        .put("weather", JSONObject().put("t", w.temperatureC).put("code", w.code).put("day", w.isDay)
            .put("hi", w.highC).put("lo", w.lowC).put("at", w.fetchedAtMs)
            .putOpt("feels", w.feelsLikeC).putOpt("hum", w.humidityPercent)
            .putOpt("wind", w.windKmh).putOpt("rain", w.rainChancePercent))
        .toString()

    companion object {
        const val REFRESH_MS = 30 * 60 * 1000L
        const val RETRY_MS = 5 * 60 * 1000L
    }
}

internal fun parseForecast(body: String, nowMs: Long): Weather {
    val json = JSONObject(body)
    val current = json.getJSONObject("current")
    val daily = json.getJSONObject("daily")
    return Weather(
        temperatureC = current.getDouble("temperature_2m"),
        code = current.getInt("weather_code"),
        isDay = current.optInt("is_day", 1) == 1,
        highC = daily.getJSONArray("temperature_2m_max").getDouble(0),
        lowC = daily.getJSONArray("temperature_2m_min").getDouble(0),
        fetchedAtMs = nowMs,
        feelsLikeC = current.numberOrNull("apparent_temperature")?.toDouble(),
        humidityPercent = current.numberOrNull("relative_humidity_2m")?.toInt(),
        windKmh = current.numberOrNull("wind_speed_10m")?.toDouble(),
        rainChancePercent = daily.optJSONArray("precipitation_probability_max")?.takeIf { it.length() > 0 && !it.isNull(0) }?.getInt(0),
    )
}

private fun JSONObject.numberOrNull(name: String): Number? = if (has(name) && !isNull(name)) get(name) as? Number else null

internal fun parseCities(body: String): List<City> {
    val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).map { index ->
        val place = results.getJSONObject(index)
        val region = listOf(place.optString("admin1"), place.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
        City(place.getString("name"), region, place.getDouble("latitude"), place.getDouble("longitude"))
    }
}

/** Open-Meteo's weather codes (the WMO ones) grouped into what the icon can draw. */
fun skyOf(code: Int): Sky = when (code) {
    0, 1 -> Sky.Clear
    2 -> Sky.PartlyCloudy
    3 -> Sky.Cloudy
    45, 48 -> Sky.Fog
    51, 53, 55, 56, 57 -> Sky.Drizzle
    61, 63, 65, 66, 67, 80, 81, 82 -> Sky.Rain
    71, 73, 75, 77, 85, 86 -> Sky.Snow
    95, 96, 99 -> Sky.Storm
    else -> Sky.Cloudy
}

fun conditionOf(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57 -> "Drizzle"
    61, 63, 65, 66, 67 -> "Rain"
    80, 81, 82 -> "Showers"
    71, 73, 75, 77 -> "Snow"
    85, 86 -> "Snow showers"
    95, 96, 99 -> "Thunderstorm"
    else -> "Cloudy"
}

fun formatTemperature(celsius: Double, fahrenheit: Boolean): String {
    val value = if (fahrenheit) celsius * 9 / 5 + 32 else celsius
    return String.format(Locale.US, "%d°", Math.round(value))
}
