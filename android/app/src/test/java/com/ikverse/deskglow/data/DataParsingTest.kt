package com.ikverse.deskglow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
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
    fun `the extra readings are read when present and left empty when not`() {
        val plain = parseForecast(forecast, nowMs = 1L)
        assertNull(plain.feelsLikeC)
        assertNull(plain.humidityPercent)
        assertNull(plain.windKmh)
        assertNull(plain.rainChancePercent)

        val full = parseForecast(
            """{"current":{"temperature_2m":25.0,"weather_code":2,"is_day":1,"apparent_temperature":26.4,"relative_humidity_2m":61,"wind_speed_10m":12.5},
            "daily":{"temperature_2m_max":[30.1],"temperature_2m_min":[18.0],"precipitation_probability_max":[35]}}""",
            nowMs = 1L,
        )
        assertEquals(26.4, full.feelsLikeC!!, 0.0)
        assertEquals(61, full.humidityPercent)
        assertEquals(12.5, full.windKmh!!, 0.0)
        assertEquals(35, full.rainChancePercent)

        val gap = parseForecast(
            """{"current":{"temperature_2m":25.0,"weather_code":2,"relative_humidity_2m":null},
            "daily":{"temperature_2m_max":[30.1],"temperature_2m_min":[18.0],"precipitation_probability_max":[null]}}""",
            nowMs = 1L,
        )
        assertNull(gap.humidityPercent)
        assertNull(gap.rainChancePercent)
    }

    @Test
    fun `wind is shown in km per hour, or miles per hour for Fahrenheit`() {
        assertEquals("14 km/h", com.ikverse.deskglow.widgets.formatWind(14.2, miles = false))
        assertEquals("9 mph", com.ikverse.deskglow.widgets.formatWind(14.2, miles = true))
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
    fun `a found position is rounded and named, or called the current location`() {
        val named = cityAt(30.06263, 31.24967, "Cairo" to "Egypt")
        assertEquals("Cairo, Egypt", named.label)
        assertEquals(30.06, named.latitude, 0.0)
        assertEquals(31.25, named.longitude, 0.0)
        assertEquals("Current location", cityAt(-33.8688, 151.2093, null).label)
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

    // An Aladhan answer for Cairo, Egyptian method, trimmed.
    private fun aladhan(fajr: String, dhuhr: String, asr: String, maghrib: String, isha: String) =
        """{"code":200,"status":"OK","data":{"timings":{"Fajr":"$fajr","Sunrise":"05:49","Dhuhr":"$dhuhr","Asr":"$asr","Sunset":"17:31",
        "Maghrib":"$maghrib","Isha":"$isha","Imsak":"04:17","Midnight":"23:40"},"meta":{"timezone":"Africa/Cairo","method":{"id":5}}}}"""

    private val today = java.time.LocalDate.of(2026, 10, 9)
    private val prayerDays = listOf(
        parsePrayerDay(aladhan("04:27", "11:40", "15:00", "17:31", "18:48"), today),
        // A time zone after the time, as Aladhan writes it when asked for one, is ignored.
        parsePrayerDay(aladhan("04:28 (EET)", "11:40 (EET)", "14:59 (EET)", "17:30 (EET)", "18:47 (EET)"), today.plusDays(1)),
    )

    @Test
    fun `an Aladhan answer is read into the five prayers`() {
        val day = prayerDays[0]
        assertEquals(java.time.LocalTime.of(4, 27), day.times[Prayer.Fajr])
        assertEquals(java.time.LocalTime.of(15, 0), day.times[Prayer.Asr])
        assertEquals(java.time.LocalTime.of(18, 48), day.times[Prayer.Isha])
        assertEquals(java.time.LocalTime.of(17, 30), prayerDays[1].times[Prayer.Maghrib])
    }

    @Test
    fun `the next prayer is the first still to come, and after Isha it is tomorrow's Fajr`() {
        val afternoon = today.atTime(13, 40)
        assertEquals(Prayer.Asr to today.atTime(15, 0), nextPrayer(prayerDays, afternoon))
        assertEquals("1 h 20 m", untilText(afternoon, today.atTime(15, 0)))
        val night = today.atTime(21, 0)
        assertEquals(Prayer.Fajr to today.plusDays(1).atTime(4, 28), nextPrayer(prayerDays, night))
        assertEquals("1 m", untilText(today.atTime(14, 59, 30), today.atTime(15, 0)))
        assertNull(nextPrayer(prayerDays, today.plusDays(2).atStartOfDay()))
    }

    @Test
    fun `moving across town is not moving, but going to Alexandria is`() {
        val cairo = com.ikverse.deskglow.store.City("Cairo", "", 30.0444, 31.2357)
        val giza = com.ikverse.deskglow.store.City("Giza", "", 30.0131, 31.2089)
        val alexandria = com.ikverse.deskglow.store.City("Alexandria", "", 31.2001, 29.9187)
        assertEquals(0.0, distanceKm(cairo, cairo), 0.001)
        assert(distanceKm(cairo, giza) < 5.0)
        assertEquals(180.0, distanceKm(cairo, alexandria), 10.0)
    }

    private val f1 = com.ikverse.deskglow.F1Samples.data
    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = java.time.LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC)

    @Test
    fun `the F1 calendar is read with every session in order, sprint weekends included`() {
        assertEquals(listOf(17, 18, 19), f1.races.map { it.round })
        val japan = f1.races[1]
        assertEquals("Japanese GP", japan.name)
        assertEquals("Suzuka, Japan", japan.place)
        assertEquals(listOf("FP1", "FP2", "FP3", "Qualifying", "Race"), japan.sessions.map { it.kind })
        assertEquals(utc(2026, 10, 11, 5), japan.race.start)
        assertEquals(listOf("FP1", "Sprint Quali", "Sprint", "Qualifying", "Race"), f1.races[2].sessions.map { it.kind })
    }

    @Test
    fun `F1 results and standings are read`() {
        val result = f1.lastResult!!
        assertEquals(17, result.round)
        assertEquals(listOf("NOR", "VER", "LEC"), result.podium.map { it.id })
        assertEquals("red_bull", result.podium[1].teamId)
        assertEquals(17, f1.round)
        assertEquals("NOR", f1.drivers[0].id)
        assertEquals(200.5, f1.drivers[4].points, 0.0)
        assertEquals("mclaren", f1.drivers[1].teamId)
        assertEquals("McLaren", f1.constructors[0].name)
        assertEquals(2, f1.previousDrivers["NOR"])
        assertEquals(emptyMap<String, Int>(), f1.previousConstructors)
        assertEquals(emptyList<F1Entry>(), parseStandings("""{"MRData":{"StandingsTable":{"season":"2027","StandingsLists":[]}}}""", false))
    }

    @Test
    fun `the race weekend shows the podium until a day before the next weekend, then counts down, then goes live`() {
        val afterSingapore = weekendView(f1, utc(2026, 10, 6, 12)) as WeekendView.AfterRace
        assertEquals(listOf("NOR", "VER", "LEC"), afterSingapore.result.podium.map { it.id })
        assertEquals("Japanese GP", afterSingapore.next!!.name)

        // Japan's FP1 is at 02:30 UTC on the 9th: still the podium 25 hours before, the practice countdown 7 hours before.
        assertTrue(weekendView(f1, utc(2026, 10, 8, 1, 30)) is WeekendView.AfterRace)
        val sevenHoursOut = weekendView(f1, utc(2026, 10, 8, 19, 30)) as WeekendView.Upcoming
        assertEquals("Japanese GP", sevenHoursOut.race.name)
        assertNull(sevenHoursOut.live)
        assertEquals("FP1", sevenHoursOut.next!!.kind)
        assertEquals("7 h 0 m", countdownText(utc(2026, 10, 8, 19, 30), sevenHoursOut.next.start))

        val friday = weekendView(f1, utc(2026, 10, 9, 4)) as WeekendView.Upcoming
        assertEquals("Japanese GP", friday.race.name)
        assertNull(friday.live)
        assertEquals("FP2", friday.next!!.kind)

        val duringQuali = weekendView(f1, utc(2026, 10, 10, 6, 30)) as WeekendView.Upcoming
        assertEquals("Qualifying", duringQuali.live!!.kind)
        assertEquals("Race", duringQuali.next!!.kind)

        // The result in hand is still Singapore's, so after Japan it counts down to Austin.
        val afterJapan = weekendView(f1, utc(2026, 10, 12, 12)) as WeekendView.Upcoming
        assertEquals("United States GP", afterJapan.race.name)
        assertEquals(WeekendView.Empty, weekendView(f1.copy(lastResult = null), utc(2026, 11, 1, 0)))
    }

    @Test
    fun `the circuit is found by the meeting that starts with the weekend, never a test`() {
        val japan = f1.races[1]
        val meetings = """[
            {"meeting_name":"Pre-Season Testing","date_start":"2026-10-09T02:30:00+00:00","circuit_key":63},
            {"meeting_name":"Singapore Grand Prix","date_start":"2026-10-02T09:30:00+00:00","circuit_key":61},
            {"meeting_name":"Japanese Grand Prix","date_start":"2026-10-09T02:30:00+00:00","circuit_key":46}
        ]"""
        assertEquals(46, circuitKeyFor(meetings, japan))
        assertNull(circuitKeyFor("""[{"meeting_name":"Australian Grand Prix","date_start":"2026-03-06T01:30:00+00:00","circuit_key":10}]""", japan))
        assertEquals(japan, trackRace(f1, utc(2026, 10, 6, 12)))
    }

    @Test
    fun `a track outline is turned, flipped to run down the screen, and scaled to a box of side 1`() {
        // A 10 by 5 rectangle, drawn as it comes: twice as wide as tall, top edge first once flipped.
        val flat = parseTrack("""{"x":[0,10,10,0],"y":[0,0,5,5],"rotation":0}""", 2026, 18)!!
        assertEquals(2f, flat.aspect, 0.001f)
        assertEquals(listOf(0f to 0.5f, 1f to 0.5f, 1f to 0f, 0f to 0f), flat.points)
        // Turned a quarter: now half as wide as tall.
        val turned = parseTrack("""{"x":[0,10,10,0],"y":[0,0,5,5],"rotation":90}""", 2026, 18)!!
        assertEquals(0.5f, turned.aspect, 0.001f)
        assertNull(parseTrack("""{"x":[0,1],"y":[0,1]}""", 2026, 18))

        assertTrue(flat.isFor(f1.races[1]))
        assertFalse(flat.isFor(f1.races[2]))
        assertEquals(flat, F1Track.fromJson(flat.toJson()))
    }

    @Test
    fun `standings split into two columns, the left one taking the odd row`() {
        assertEquals((1..5).toList() to (6..10).toList(), standingColumns((1..10).toList()))
        assertEquals((1..4).toList() to (5..7).toList(), standingColumns((1..7).toList()))
    }

    @Test
    fun `countdowns read in days, hours, or minutes and seconds`() {
        val now = utc(2026, 10, 9, 0)
        assertEquals("2 d 5 h", countdownText(now, utc(2026, 10, 11, 5)))
        assertEquals("4 h 30 m", countdownText(now, utc(2026, 10, 9, 4, 30)))
        assertEquals("12:05", countdownText(now, now.plusSeconds(12 * 60 + 5)))
        assertEquals("0:00", countdownText(now, now.minusSeconds(5)))
    }

    @Test
    fun `the standings show the top rows, moves since last round, and a favourite further down`() {
        val (top, extra) = standingRows(f1.drivers, f1.previousDrivers, 3, "HAM")
        assertEquals(listOf("NOR", "PIA", "VER"), top.map { it.entry.id })
        assertEquals(listOf(1, -1, 0), top.map { it.move })
        assertEquals("HAM", extra!!.entry.id)
        assertEquals(6, extra.entry.position)
        // A favourite already in the top rows is not shown twice.
        assertNull(standingRows(f1.drivers, f1.previousDrivers, 3, "PIA").second)
        assertNull(standingRows(f1.drivers, emptyMap(), 3, "").first[0].move)
        assertEquals(0xFF8C8C8C, teamColour("someone_new"))
    }

    @Test
    fun `standings values are points or the gap to the leader`() {
        val w = com.ikverse.deskglow.widgets.F1StandingsWidget
        assertEquals("331", w.valueText(331.0, 331.0, gap = false))
        assertEquals("200.5", w.valueText(200.5, 331.0, gap = false))
        assertEquals("—", w.valueText(331.0, 331.0, gap = true))
        assertEquals("−130.5", w.valueText(200.5, 331.0, gap = true))
    }

    @Test
    fun `cached F1 answers survive a round trip`() {
        val raw = com.ikverse.deskglow.F1Samples.raw
        assertEquals(raw, F1Raw.fromJson(raw.toJson()))
    }

    @Test
    fun `the alarm says when, and the bar empties over the last twelve hours`() {
        val w = com.ikverse.deskglow.widgets.AlarmWidget
        val now = LocalDateTime.of(2026, 10, 8, 23, 5)
        assertEquals("in 7 h 25 m · Tomorrow", w.whenText(now, LocalDateTime.of(2026, 10, 9, 6, 30)))
        assertEquals("in 25 m · Today", w.whenText(now.withHour(6), LocalDateTime.of(2026, 10, 8, 6, 30)))
        assert(w.whenText(now, LocalDateTime.of(2026, 10, 12, 8, 0)).endsWith(" · Mon 12 Oct"))
        assertEquals(1f, w.timeLeft(now, now.plusHours(20)), 0f)
        assertEquals(0.5f, w.timeLeft(now, now.plusHours(6)), 0.001f)
        assertEquals(0f, w.timeLeft(now, now.minusMinutes(1)), 0f)
    }

    @Test
    fun `prayer times read in English or Arabic`() {
        val w = com.ikverse.deskglow.widgets.PrayerWidget
        assertEquals("3:00 PM", w.clockText(java.time.LocalTime.of(15, 0), h24 = false, arabic = false, arabicDigits = false))
        assertEquals("15:00", w.clockText(java.time.LocalTime.of(15, 0), h24 = true, arabic = false, arabicDigits = false))
        assertEquals("٤:٢٧ ص", w.clockText(java.time.LocalTime.of(4, 27), h24 = false, arabic = true, arabicDigits = true))
        assertEquals("4:27", w.clockText(java.time.LocalTime.of(4, 27), h24 = false, arabic = false, arabicDigits = false, suffix = false))
        val now = today.atTime(13, 40)
        assertEquals("in 1 h 20 m", w.countdownText(now, today.atTime(15, 0), arabic = false, arabicDigits = false))
        assertEquals("بعد ١ س ٢٠ د", w.countdownText(now, today.atTime(15, 0), arabic = true, arabicDigits = true))
    }


    // ---- live timing: Singapore Sprint Qualifying as the feed held it after the flag, trimmed ----

    private val sprintQuali = org.json.JSONObject(javaClass.classLoader!!.getResource("f1live-sprint-quali.json")!!.readText())

    private fun snapshotOf(topics: org.json.JSONObject) = LiveTiming().apply {
        topics.keys().forEach { apply(LiveMessage(it, org.json.JSONObject(topics.getJSONObject(it).toString()), full = true)) }
    }

    @Test
    fun `a finished qualifying session is read from the feed's snapshot`() {
        val s = snapshotOf(sprintQuali).session()!!
        assertEquals(11379, s.key)
        assertEquals("Singapore GP", s.meeting)
        assertEquals("Sprint Qualifying", s.name)
        assertEquals(utc(2026, 10, 9, 12, 30), s.start) // 20:30 at the track, eight hours ahead of UTC
        assertTrue(s.finished)
        assertFalse(s.running)
        assertEquals(3, s.part)
        assertEquals(22, s.rows.size)
        assertEquals(listOf("VER", "RUS", "LEC", "PIA"), s.rows.take(4).map { it.code })
        // The leader shows its best lap in the last part; the rest their gap in it.
        assertEquals("1:31.156", s.rows[0].gap)
        assertEquals("+0.120", s.rows[1].gap)
        // Still in the last part but with no lap in it: no gap, rather than an older one that would read as faster.
        assertEquals("HAD", s.rows[8].code)
        assertEquals("", s.rows[8].gap)
        // Knocked out in the second part: its gap from there, dimmed.
        val col = s.rows[10]
        assertEquals("COL", col.code)
        assertTrue(col.knockedOut)
        assertEquals("+1.003", col.gap)
        assertEquals(0xFFF47600, s.rows.first { it.code == "NOR" }.teamColour)
    }

    @Test
    fun `live changes are merged in - places swap, a list entry changes, a red flag stops the session`() {
        val live = org.json.JSONObject(sprintQuali.toString())
        live.put("SessionStatus", org.json.JSONObject("""{"Status":"Started","Started":"Started"}"""))
        live.getJSONObject("SessionInfo").put("SessionStatus", "Started")
        live.getJSONObject("TimingData").put("SessionPart", 1)
        val timing = snapshotOf(live)
        assertTrue(timing.session()!!.running)
        assertFalse(timing.session()!!.finished)

        timing.apply(LiveMessage("TimingData", org.json.JSONObject("""{"Lines":{"63":{"Line":1,"Position":"1"},"3":{"Line":2,"Position":"2"}}}"""), full = false))
        timing.apply(LiveMessage("TimingData", org.json.JSONObject("""{"Lines":{"3":{"Stats":{"0":{"TimeDiffToFastest":"+0.200","TimeDifftoPositionAhead":"+0.200"}}}}}"""), full = false))
        var s = timing.session()!!
        assertEquals(listOf("RUS", "VER"), s.rows.take(2).map { it.code })
        assertEquals("+0.200", s.rows[1].gap)
        assertEquals(TrackFlag.Clear, s.flag)

        timing.apply(LiveMessage("TrackStatus", org.json.JSONObject("""{"Status":"5","Message":"Red"}"""), full = false))
        timing.apply(LiveMessage("SessionStatus", org.json.JSONObject("""{"Status":"Aborted"}"""), full = false))
        s = timing.session()!!
        assertEquals(TrackFlag.Red, s.flag)
        assertTrue(s.running)

        // The end of the first part is not the end of qualifying.
        timing.apply(LiveMessage("SessionStatus", org.json.JSONObject("""{"Status":"Finished"}"""), full = false))
        assertFalse(timing.session()!!.finished)
        timing.apply(LiveMessage("TimingData", org.json.JSONObject("""{"SessionPart":3}"""), full = false))
        assertTrue(timing.session()!!.finished)
    }

    @Test
    fun `a race is read with gaps, intervals, laps and a retirement`() {
        val topics = mapOf(
            "SessionInfo" to org.json.JSONObject("""{"Key":11388,"Type":"Race","Name":"Race","StartDate":"2026-10-11T20:00:00","GmtOffset":"08:00:00","Meeting":{"Name":"Singapore Grand Prix"}}"""),
            "SessionStatus" to org.json.JSONObject("""{"Status":"Started"}"""),
            "TrackStatus" to org.json.JSONObject("""{"Status":"4","Message":"SCDeployed"}"""),
            "LapCount" to org.json.JSONObject("""{"CurrentLap":23,"TotalLaps":62}"""),
            "DriverList" to org.json.JSONObject("""{"1":{"Tla":"NOR","TeamColour":"F47600"},"63":{"Tla":"RUS","TeamColour":"00D7B6"},"16":{"Tla":"LEC","TeamColour":"ED1131"}}"""),
            "TimingData" to org.json.JSONObject(
                """{"Lines":{"63":{"Position":"2","GapToLeader":"+1.234","IntervalToPositionAhead":{"Value":"+1.234"},"InPit":true},
                "1":{"Position":"1","GapToLeader":"LAP 23","IntervalToPositionAhead":{"Value":"LAP 23"}},
                "16":{"Position":"3","GapToLeader":"1L","IntervalToPositionAhead":{"Value":"1L"},"Retired":true}}}""",
            ),
        )
        val s = parseLiveSession(topics)!!
        assertTrue(s.isRace)
        assertEquals(TrackFlag.SafetyCar, s.flag)
        assertEquals(23, s.lap)
        assertEquals(62, s.totalLaps)
        assertEquals(listOf("NOR", "RUS", "LEC"), s.rows.map { it.code })
        assertEquals("Leader", s.rows[0].gap)
        assertEquals("+1.234", s.rows[1].interval)
        assertTrue(s.rows[1].inPit)
        assertTrue(s.rows[2].out)
        assertEquals("Lap 23/62", com.ikverse.deskglow.widgets.sessionProgress(s, java.time.Instant.EPOCH))
    }

    @Test
    fun `the session clock counts down between the feed's updates`() {
        val s = parseLiveSession(mapOf(
            "SessionInfo" to org.json.JSONObject("""{"Key":1,"Type":"Qualifying","Name":"Sprint Qualifying","StartDate":"2026-10-09T20:30:00","GmtOffset":"08:00:00"}"""),
            "SessionStatus" to org.json.JSONObject("""{"Status":"Started"}"""),
            "TimingData" to org.json.JSONObject("""{"SessionPart":2,"NoEntries":[22,16,10],"Lines":{}}"""),
            "ExtrapolatedClock" to org.json.JSONObject("""{"Utc":"2026-10-09T12:35:00.0246118Z","Remaining":"00:06:59","Extrapolating":true}"""),
        ))!!
        val at = java.time.Instant.parse("2026-10-09T12:35:00Z")
        assertEquals("SQ2 · 6:59 left", com.ikverse.deskglow.widgets.sessionProgress(s, at))
        assertEquals("SQ2 · 4:59 left", com.ikverse.deskglow.widgets.sessionProgress(s, at.plusSeconds(120)))
        assertEquals("SQ2 · 0:00 left", com.ikverse.deskglow.widgets.sessionProgress(s, at.plusSeconds(3600)))
    }

    @Test
    fun `SignalR frames - the subscription's answer is every topic in full, a feed call is one change`() {
        val rs = "\u001e"
        val body = "{}" + rs +
            """{"type":3,"invocationId":"1","result":{"TrackStatus":{"Status":"1"},"SessionStatus":{"Status":"Started"},"Heartbeat":null}}""" + rs +
            """{"type":1,"target":"feed","arguments":["TrackStatus",{"Status":"2","Message":"Yellow"},"2026-10-09T12:35:21Z"]}""" + rs +
            """{"type":1,"target":"feed","arguments":["Position.z","eJyLjgUAARUAuQ==","2026-10-09T12:35:21Z"]}""" + rs +
            """{"type":6}""" + rs
        val messages = parseSignalR(body)
        assertEquals(setOf("TrackStatus" to true, "SessionStatus" to true), messages.take(2).map { it.topic to it.full }.toSet())
        assertEquals("TrackStatus" to false, messages[2].topic to messages[2].full)
        assertEquals(3, messages.size)
        assertEquals("2", messages[2].data.getString("Status"))
        assertTrue(runCatching { parseSignalR("""{"type":7,"error":"bye"}""" + rs) }.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun `the widget connects from five minutes before a session, and the later of two overlapping windows wins`() {
        val japan = f1.races[1]
        assertNull(liveWindowSession(f1.races, utc(2026, 10, 9, 2, 20)))
        assertEquals(japan.sessions[0], liveWindowSession(f1.races, utc(2026, 10, 9, 2, 26)))
        // First practice's window runs on to 06:30, but the second practice's opens at 05:55.
        assertEquals(japan.sessions[0], liveWindowSession(f1.races, utc(2026, 10, 9, 5, 50)))
        assertEquals(japan.sessions[1], liveWindowSession(f1.races, utc(2026, 10, 9, 5, 56)))
        assertNull(liveWindowSession(f1.races, utc(2026, 10, 9, 12, 0)))
    }

    @Test
    fun `a result is fetched again when the widget missed the last session`() {
        val fp1 = LiveSession(1, "Japanese GP", "Practice 1", "Practice", utc(2026, 10, 9, 2, 30), "Ends", true, emptyList())
        assertTrue(needsCatchUp(null, f1.races, utc(2026, 10, 9, 12, 0)))
        // Second practice ended at 07:00 and the saved result is first practice's.
        assertTrue(needsCatchUp(fp1, f1.races, utc(2026, 10, 9, 12, 0)))
        assertFalse(needsCatchUp(fp1, f1.races, utc(2026, 10, 9, 4, 0)))
        assertFalse(needsCatchUp(fp1.copy(start = utc(2026, 10, 9, 6, 0)), f1.races, utc(2026, 10, 9, 12, 0)))
    }

    private fun finished(name: String, type: String, start: java.time.Instant) = LiveSession(
        1, "Japanese GP", name, type, start, "Finalised", true,
        listOf("PIA", "NOR", "VER", "RUS").mapIndexed { i, code -> LiveRow(i + 1, "${i + 1}", code, 0xFFFF8000, "", "") },
    )

    @Test
    fun `a session's top 3 shows from its end until an hour before the next session`() {
        val japan = f1.races[1]
        // Second practice started a couple of minutes late; at noon on Friday the next is third practice, on Saturday.
        val fp2 = finished("Practice 2", "Practice", utc(2026, 10, 9, 6, 2))
        val friday = sessionTop(f1.races, fp2, utc(2026, 10, 9, 12, 0))!!
        assertEquals("FP2", friday.session.kind)
        assertEquals(listOf("PIA", "NOR", "VER"), friday.rows.map { it.code })
        assertEquals(japan, friday.nextRace)
        assertEquals("FP3", friday.next!!.kind)
        // Third practice is at 02:30 on Saturday: still the top 3 at 01:25, the countdown from 01:30.
        assertNotNull(sessionTop(f1.races, fp2, utc(2026, 10, 10, 1, 25)))
        assertNull(sessionTop(f1.races, fp2, utc(2026, 10, 10, 1, 30)))
        // And nothing while third practice runs.
        assertNull(sessionTop(f1.races, fp2, utc(2026, 10, 10, 2, 45)))

        val quali = finished("Qualifying", "Qualifying", utc(2026, 10, 10, 6, 0))
        val saturday = sessionTop(f1.races, quali, utc(2026, 10, 10, 12, 0))!!
        assertEquals("Qualifying", saturday.session.kind)
        assertEquals("Race", saturday.next!!.kind)
        assertNull(sessionTop(f1.races, quali, utc(2026, 10, 11, 4, 5)))
    }

    @Test
    fun `a session's top 3 is never another session's`() {
        // Second practice is over but the result in hand is still the first's: the countdown, until the newer one comes.
        val fp1 = finished("Practice 1", "Practice", utc(2026, 10, 9, 2, 30))
        assertNull(sessionTop(f1.races, fp1, utc(2026, 10, 9, 12, 0)))
        // Last weekend's race, a week on.
        val singapore = finished("Race", "Race", utc(2026, 10, 4, 12, 0))
        assertNull(sessionTop(f1.races, singapore, utc(2026, 10, 9, 12, 0)))
        // Unfinished, or with nobody classified.
        val fp2 = finished("Practice 2", "Practice", utc(2026, 10, 9, 6, 0))
        assertNull(sessionTop(f1.races, fp2.copy(finished = false), utc(2026, 10, 9, 12, 0)))
        assertNull(sessionTop(f1.races, fp2.copy(rows = emptyList()), utc(2026, 10, 9, 12, 0)))
        assertNull(sessionTop(f1.races, null, utc(2026, 10, 9, 12, 0)))
    }

    @Test
    fun `a race's top 3 from timing lasts until a day before the next weekend, as the podium does`() {
        val race = finished("Race", "Race", utc(2026, 10, 11, 5, 0))
        val sunday = sessionTop(f1.races, race, utc(2026, 10, 11, 7, 30))!!
        assertEquals("Race", sunday.session.kind)
        assertEquals("Japanese GP", sunday.race.name)
        assertEquals("United States GP", sunday.nextRace!!.name)
        assertEquals("FP1", sunday.next!!.kind)
        // Austin's first practice is at 17:30 on the 23rd.
        assertNotNull(sessionTop(f1.races, race, utc(2026, 10, 22, 17, 25)))
        assertNull(sessionTop(f1.races, race, utc(2026, 10, 22, 17, 30)))
    }

    @Test
    fun `a saved result comes back as it was`() {
        val s = snapshotOf(sprintQuali).session()!!
        val back = LiveSession.fromJson(s.toJson())
        assertEquals(s.copy(flag = TrackFlag.Clear, part = null, remaining = null, clockAt = null, clockRunning = false), back)
    }

    @Test
    fun `an OpenF1 result is read, qualifying times and race gaps alike`() {
        val session = org.json.JSONObject("""{"session_key":11379,"session_type":"Qualifying","session_name":"Sprint Qualifying","date_start":"2026-10-09T12:30:00+00:00","meeting_key":1296}""")
        val drivers = """[{"driver_number":1,"name_acronym":"VER","team_colour":"4781D7"},{"driver_number":63,"name_acronym":"RUS","team_colour":"00D7B6"},{"driver_number":55,"name_acronym":"SAI","team_colour":"1868DB"}]"""
        val results = """[{"position":2,"driver_number":63,"gap_to_leader":[0.096,0.368,0.12],"duration":[93.573,92.614,91.276]},
            {"position":1,"driver_number":1,"gap_to_leader":[0,0,0],"duration":[93.477,92.701,91.156]},
            {"position":22,"driver_number":55,"gap_to_leader":[3.899,null,null],"duration":[97.376,null,null],"dns":false}]"""
        val s = parseOpenF1Result(session, "Singapore Grand Prix", results, drivers)!!
        assertEquals("Singapore GP", s.meeting)
        assertTrue(s.finished)
        assertEquals(listOf("VER", "RUS", "SAI"), s.rows.map { it.code })
        assertEquals("1:31.156", s.rows[0].gap)
        assertEquals("+0.120", s.rows[1].gap)
        assertEquals("+3.899", s.rows[2].gap)
        assertEquals("+3.779", s.rows[2].interval)
        assertNull(parseOpenF1Result(session, "", "[]", drivers))
    }

    @Test
    fun `the hours, the sun times, the UV index and the zone are read when present, and left empty when not`() {
        val weather = parseForecast(
            """{"utc_offset_seconds":10800,
            "current":{"temperature_2m":25.0,"weather_code":2,"is_day":1},
            "hourly":{"time":["2026-10-10T14:00","2026-10-10T15:00","2026-10-10T16:00"],"temperature_2m":[25.1,24.0,22.5],"weather_code":[2,61,3],"is_day":[1,1,0]},
            "daily":{"temperature_2m_max":[30.1],"temperature_2m_min":[18.0],"sunrise":["2026-10-10T05:42"],"sunset":["2026-10-10T17:30"],"uv_index_max":[6.4]}}""", nowMs = 1L,
        )
        assertEquals(3, weather.hours.size)
        assertEquals(java.time.LocalDateTime.of(2026, 10, 10, 15, 0), weather.hours[1].time)
        assertEquals(24.0, weather.hours[1].temperatureC, 0.0)
        assertEquals(61, weather.hours[1].code)
        assertEquals(false, weather.hours[2].isDay)
        assertEquals(java.time.LocalDateTime.of(2026, 10, 10, 5, 42), weather.sunrise)
        assertEquals(java.time.LocalDateTime.of(2026, 10, 10, 17, 30), weather.sunset)
        assertEquals(6.4, weather.uvIndex!!, 0.0)
        assertEquals(10800, weather.utcOffsetSeconds)

        val plain = parseForecast(forecast, nowMs = 1L)
        assertEquals(emptyList<Any>(), plain.hours)
        assertNull(plain.sunrise)
        assertNull(plain.uvIndex)
        assertEquals(0, plain.utcOffsetSeconds)
    }

    @Test
    fun `the city's clock is the phone's moved to the city's zone`() {
        val weather = Weather(20.0, 1, true, 25.0, 15.0, 0, utcOffsetSeconds = 3 * 3600)
        val phone = java.time.LocalDateTime.of(2026, 10, 10, 12, 0)
        val utc = phone.atZone(java.time.ZoneId.systemDefault()).toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDateTime()
        assertEquals(utc.plusHours(3), weather.cityTime(phone))
    }
}
