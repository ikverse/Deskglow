package com.ikverse.deskglow.data

import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.store.AppPrefs
import com.ikverse.deskglow.store.City
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The weather feed with a fake network: what it asks for, what it keeps, and when it asks again. */
@RunWith(RobolectricTestRunner::class)
class WeatherRepositoryTest {
    private val cairo = City("Cairo", "Egypt", 30.06, 31.25)
    private val forecast = """{"utc_offset_seconds":10800,
            "current":{"temperature_2m":25.0,"weather_code":2,"is_day":1},
            "hourly":{"time":["2026-10-10T14:00","2026-10-10T15:00","2026-10-10T16:00"],"temperature_2m":[25.1,24.0,22.5],"weather_code":[2,61,3],"is_day":[1,1,0]},
            "daily":{"temperature_2m_max":[30.1],"temperature_2m_min":[18.0],"sunrise":["2026-10-10T05:42"],"sunset":["2026-10-10T17:30"],"uv_index_max":[6.4]}}"""

    private class CountingHttp(private val body: String) : Http {
        val urls = mutableListOf<String>()
        override fun get(url: String): ByteArray {
            urls += url
            return body.toByteArray()
        }
    }

    private fun prefs() = AppPrefs(ApplicationProvider.getApplicationContext()).also {
        it.setAutoLocation(false)
        it.setCity(cairo)
    }

    private suspend fun ready(repo: WeatherRepository, wanted: (Weather) -> Boolean = { true }) =
        withTimeout(10_000) { (repo.updates().first { it is WeatherState.Ready && wanted(it.weather) } as WeatherState.Ready).weather }

    @Test
    fun `the hours and sun times are asked for, read, and kept for the next run`() = runBlocking {
        val prefs = prefs()
        val http = CountingHttp(forecast)
        val first = ready(WeatherRepository(prefs, http, { 1_000L }))
        assertEquals(3, first.hours.size)
        assertEquals(java.time.LocalDateTime.of(2026, 10, 10, 5, 42), first.sunrise)
        assertEquals(1, http.urls.size)
        assertEquals(true, "hourly=" in http.urls[0] && "sunrise,sunset" in http.urls[0] && "forecast_days=2" in http.urls[0])

        // Soon after, a fresh run shows the kept answer, hours and all, without asking again.
        val again = ready(WeatherRepository(prefs, http, { 2_000L }))
        assertEquals(first.hours, again.hours)
        assertEquals(first.sunrise, again.sunrise)
        assertEquals(first.utcOffsetSeconds, again.utcOffsetSeconds)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `with location no longer allowed, a city found before gives way to the one picked by name`() = runBlocking {
        val prefs = prefs()
        prefs.setAutoLocation(true)
        prefs.setDetectedCity(City("London", "UK", 51.5, -0.12))
        val http = CountingHttp(forecast)
        val state = withTimeout(10_000) {
            WeatherRepository(prefs, http, { 1_000L }, locate = { null }, canLocate = { false }).updates().first { it is WeatherState.Ready } as WeatherState.Ready
        }
        assertEquals(cairo, state.city)
    }

    @Test
    fun `the sun's times say whether it is day, and without them the reading's own flag does`() {
        val w = Weather(20.0, 0, isDay = true, 25.0, 15.0, 0, sunrise = java.time.LocalDateTime.of(2026, 10, 10, 5, 42), sunset = java.time.LocalDateTime.of(2026, 10, 10, 17, 30))
        assertEquals(false, w.isDayAt(java.time.LocalDateTime.of(2026, 10, 10, 17, 45)))
        assertEquals(true, w.isDayAt(java.time.LocalDateTime.of(2026, 10, 10, 12, 0)))
        assertEquals(false, w.copy(isDay = false).isDayAt(java.time.LocalDateTime.of(2026, 10, 11, 12, 0))) // another day's times say nothing
        assertEquals(true, w.copy(sunrise = null).isDayAt(java.time.LocalDateTime.of(2026, 10, 10, 23, 0)))
    }

    @Test
    fun `past midnight with no connection, yesterday's answer still gives today's prayer times`() = runBlocking {
        val prefs = prefs()
        val day = """{"code":200,"data":{"timings":{"Fajr":"04:27","Sunrise":"05:49","Dhuhr":"11:40","Asr":"15:00","Maghrib":"17:31","Isha":"18:48"},"meta":{"timezone":"Africa/Cairo"}}}"""
        var online = true
        val http = object : Http {
            override fun get(url: String): ByteArray = if (online) day.toByteArray() else throw java.io.IOException("offline")
        }
        var today = java.time.LocalDate.of(2026, 10, 7)
        val first = withTimeout(10_000) { PrayerRepository(prefs, http, { today }).updates(5, 0).first { it is PrayerState.Ready } as PrayerState.Ready }
        assertEquals(java.time.ZoneId.of("Africa/Cairo"), first.days[0].zone)
        online = false
        today = today.plusDays(1)
        val later = withTimeout(10_000) { PrayerRepository(prefs, http, { today }).updates(5, 0).first { it is PrayerState.Failed } as PrayerState.Failed }
        assertEquals(true, later.last?.any { it.date == today })
        assertEquals(java.time.ZoneId.of("Africa/Cairo"), later.last?.first()?.zone) // kept with the times
    }

    @Test
    fun `an answer kept before the hours were asked for is fetched again at once`() = runBlocking {
        val prefs = prefs()
        prefs.weatherCache = """{"city":${org.json.JSONObject.quote(cairo.toJson())},"weather":{"t":20.0,"code":1,"day":true,"hi":25.0,"lo":15.0,"at":1000}}"""
        val http = CountingHttp(forecast)
        val fresh = ready(WeatherRepository(prefs, http, { 2_000L })) { it.hours.isNotEmpty() }
        assertEquals(3, fresh.hours.size)
        assertEquals(1, http.urls.size)
    }
}
