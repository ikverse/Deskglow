package com.ikverse.deskglow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DataParsingTest {
    @Test
    fun `current readings in milliamps or microamps both come out as milliamps`() {
        assertEquals(296, normaliseCurrent(296)) // the Note 9 reports mA
        assertEquals(296, normaliseCurrent(-296))
        assertEquals(1850, normaliseCurrent(1_850_000)) // others report µA, as Android documents
        assertEquals(1850, normaliseCurrent(-1_850_000))
        assertEquals(0, normaliseCurrent(0))
        assertNull(normaliseCurrent(Int.MIN_VALUE)) // not reported at all
    }

    @Test
    fun `voltage in volts is turned into millivolts`() {
        assertEquals(4224, normaliseVoltage(4224))
        assertEquals(4000, normaliseVoltage(4))
    }

    // Answers as Open-Meteo gave them on 2026-10-07, trimmed.
    private val forecast = """{"latitude":30.08307,"longitude":31.238375,"timezone":"Africa/Cairo",
        "current":{"time":"2026-10-07T21:00","interval":900,"temperature_2m":25.0,"weather_code":2,"is_day":0},
        "daily":{"time":["2026-10-07"],"temperature_2m_max":[30.1],"temperature_2m_min":[18.0]}}"""
    private val places = """{"results":[{"id":360630,"name":"Cairo","latitude":30.06263,"longitude":31.24967,"country":"Egypt","admin1":"Cairo Governorate"},
        {"id":4234985,"name":"Cairo","latitude":37.00533,"longitude":-89.17646,"country":"United States","admin1":"Illinois"}]}"""

    @Test
    fun `an Open-Meteo forecast is read`() {
        val weather = parseForecast(forecast, nowMs = 123L)
        assertEquals(25.0, weather.temperatureC, 0.0)
        assertEquals(2, weather.code)
        assertEquals(false, weather.isDay)
        assertEquals(30.1, weather.highC, 0.0)
        assertEquals(18.0, weather.lowC, 0.0)
        assertEquals(Sky.PartlyCloudy, skyOf(weather.code))
        assertEquals("Partly cloudy", conditionOf(weather.code))
    }

    @Test
    fun `a city search is read, with region and country to tell places apart`() {
        val cities = parseCities(places)
        assertEquals(2, cities.size)
        assertEquals("Cairo, Cairo Governorate, Egypt", cities[0].label)
        assertEquals("Cairo, Illinois, United States", cities[1].label)
        assertEquals(30.06263, cities[0].latitude, 0.0)
        assertEquals(emptyList<Any>(), parseCities("""{"generationtime_ms":0.1}"""))
    }

    @Test
    fun `temperatures round and convert`() {
        assertEquals("25°", formatTemperature(25.0, fahrenheit = false))
        assertEquals("30°", formatTemperature(30.1, fahrenheit = false))
        assertEquals("86°", formatTemperature(30.1, fahrenheit = true))
    }

    @Test
    fun `every weather code lands on an icon`() {
        for (code in listOf(0, 1, 2, 3, 45, 48, 51, 61, 65, 71, 80, 85, 95, 99, 12345)) skyOf(code)
        assertEquals(Sky.Clear, skyOf(0))
        assertEquals(Sky.Storm, skyOf(95))
        assertEquals(Sky.Snow, skyOf(85))
    }

    private val zone = ZoneId.of("Africa/Cairo")
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    private fun utcMs(t: LocalDateTime) = t.toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `the next event is the earliest one that has not ended, timed before all-day`() {
        val now = LocalDateTime.of(2026, 10, 7, 20, 0)
        val entries = listOf(
            CalendarEntry("Lunch", ms(now.withHour(12)), ms(now.withHour(13)), false), // over
            CalendarEntry("Holiday", utcMs(now.toLocalDate().atStartOfDay()), utcMs(now.toLocalDate().plusDays(1).atStartOfDay()), true),
            CalendarEntry("Team call", ms(now.withHour(20).withMinute(30)), ms(now.withHour(21)), false),
            CalendarEntry("Gym", ms(now.plusDays(1).withHour(7)), ms(now.plusDays(1).withHour(8)), false),
        )
        val next = nextEvent(entries, ms(now), zone) as EventState.Next
        assertEquals("Team call", next.title)
        assertEquals(now.withHour(20).withMinute(30), next.start)
    }

    @Test
    fun `an event in progress is still shown, and all-day ones are read as dates`() {
        val now = LocalDateTime.of(2026, 10, 7, 20, 45)
        val ongoing = listOf(CalendarEntry("Team call", ms(now.withHour(20).withMinute(30)), ms(now.withHour(21)), false))
        assertEquals("Team call", (nextEvent(ongoing, ms(now), zone) as EventState.Next).title)
        val allDay = listOf(CalendarEntry("Holiday", utcMs(now.toLocalDate().atStartOfDay()), utcMs(now.toLocalDate().plusDays(1).atStartOfDay()), true))
        val next = nextEvent(allDay, ms(now), zone) as EventState.Next
        assertEquals(now.toLocalDate().atStartOfDay(), next.start)
        assertEquals(EventState.None, nextEvent(emptyList(), ms(now), zone))
    }
}
