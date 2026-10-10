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
    fun `an answer kept before the hours were asked for is fetched again at once`() = runBlocking {
        val prefs = prefs()
        prefs.weatherCache = """{"city":${org.json.JSONObject.quote(cairo.toJson())},"weather":{"t":20.0,"code":1,"day":true,"hi":25.0,"lo":15.0,"at":1000}}"""
        val http = CountingHttp(forecast)
        val fresh = ready(WeatherRepository(prefs, http, { 2_000L })) { it.hours.isNotEmpty() }
        assertEquals(3, fresh.hours.size)
        assertEquals(1, http.urls.size)
    }
}
