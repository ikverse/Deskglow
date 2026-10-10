package com.ikverse.deskglow.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import com.ikverse.deskglow.data.BatteryState
import com.ikverse.deskglow.data.ChargeStatus
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WidgetSettingsTest {
    @Test
    fun `a clock saved with the old 24-hour switch keeps its hours, and every other keeps 12-hour time`() {
        val on = Settings(mapOf("h24" to true))
        assertEquals("24", ClockWidget.resolve(on)[Common.TIME_FORMAT])
        val off = Settings(mapOf("h24" to false))
        assertEquals("12", ClockWidget.resolve(off)[Common.TIME_FORMAT])
        assertEquals("12", ClockWidget.resolve(Settings())[Common.TIME_FORMAT])
        // A choice made since is never overwritten by the old switch.
        val chosen = Settings(mapOf("h24" to true, "timeFormat" to "phone"))
        assertEquals("phone", ClockWidget.resolve(chosen)[Common.TIME_FORMAT])
    }

    @Test
    fun `widgets that were always centred stay centred, whatever they saved before alignment existed`() {
        assertEquals("center", ClockWidget.resolve(Settings())[Common.ALIGN])
        assertEquals("center", DateWidget.resolve(Settings())[Common.ALIGN])
        assertEquals("center", NotificationsWidget.resolve(Settings())[Common.ALIGN])
        assertEquals("left", ClockWidget.resolve(Settings(mapOf("align" to "left")))[Common.ALIGN])
        // Widgets that already had alignment still start at the left.
        assertEquals("left", WeatherWidget.resolve(Settings())[Common.ALIGN])
    }

    @Test
    fun `alignment turns into a place in spare room and an arrangement`() {
        assertEquals(0f, alignFraction("left"), 0f)
        assertEquals(0.5f, alignFraction("center"), 0f)
        assertEquals(1f, alignFraction("right"), 0f)
        assertEquals(Arrangement.Start, arrangementOf("left"))
        assertEquals(Arrangement.Center, arrangementOf("center"))
        assertEquals(Arrangement.End, arrangementOf("right"))
    }

    @Test
    fun `the time format is offered the same way on every widget that shows a time`() {
        for (widget in listOf(ClockWidget, AlarmWidget, EventWidget, PrayerWidget)) {
            val fields = widget.fields(widget.resolve(Settings())).filterIsInstance<ChoiceField>().filter { it.label == "Time format" }
            assertEquals("${widget.id} has one time format", 1, fields.size)
            assertEquals(listOf("phone", "12", "24"), fields.single().options.map { it.first })
        }
        // The F1 schedule keeps the key it always stored its choice under.
        val schedule = F1ScheduleWidget.fields(F1ScheduleWidget.defaults).filterIsInstance<ChoiceField>().single { it.label == "Time format" }
        assertEquals("clock", schedule.key.name)
    }

    @Test
    fun `no widget lists one setting twice, and every on-off chip has a name`() {
        for (widget in Widgets.all) {
            val fields = widget.fields(widget.resolve(Settings()))
            val keys = fields.flatMap { field ->
                when (field) {
                    is ToggleField -> listOf(field.key.name)
                    is ShowField -> field.items.map { it.first.name }
                    is ChoiceField -> listOf(field.key.name)
                    is LayoutField -> listOf(field.key.name)
                    is SliderField -> listOf(field.key.name)
                    is ColourField -> listOf(field.key.name)
                    is StyleField -> listOf(field.key.name)
                }
            }
            assertEquals("${widget.id}: $keys", keys.size, keys.toSet().size)
            fields.filterIsInstance<ShowField>().forEach { show -> assertTrue("${widget.id} chips", show.items.size >= 2 && show.items.all { it.second.isNotBlank() }) }
        }
    }

    @Test
    fun `the second colour has the same name on every widget`() {
        val names = Widgets.all.flatMap { it.fields(it.resolve(Settings())) }.filterIsInstance<ColourField>().map { it.label }.toSet()
        assertEquals(setOf("Colour", "Accent colour"), names)
    }

    @Test
    fun `a clock saved with the old seconds switch keeps showing digits, and one without it shows none`() {
        assertEquals("digits", ClockWidget.resolve(Settings(mapOf("seconds" to true)))[ClockWidget.SECONDS_MODE])
        assertEquals("none", ClockWidget.resolve(Settings(mapOf("seconds" to false)))[ClockWidget.SECONDS_MODE])
        assertEquals("none", ClockWidget.resolve(Settings())[ClockWidget.SECONDS_MODE])
        assertEquals("line", ClockWidget.resolve(Settings(mapOf("seconds" to true, "secondsMode" to "line")))[ClockWidget.SECONDS_MODE])
    }

    @Test
    fun `the ring, the stat and now playing keep an old switch's meaning`() {
        assertEquals("status", RingWidget.resolve(Settings())[RingWidget.UNDER])
        assertEquals("none", RingWidget.resolve(Settings(mapOf("showLabel" to false)))[RingWidget.UNDER])
        assertEquals("power", RingWidget.resolve(Settings(mapOf("showLabel" to false, "under" to "power")))[RingWidget.UNDER])
        assertEquals("under", StatWidget.resolve(Settings())[StatWidget.LABEL_MODE])
        assertEquals("none", StatWidget.resolve(Settings(mapOf("showLabel" to false)))[StatWidget.LABEL_MODE])
        assertEquals("bar", MediaWidget.resolve(Settings())[MediaWidget.PROGRESS_MODE])
        assertEquals("none", MediaWidget.resolve(Settings(mapOf("showProgress" to false)))[MediaWidget.PROGRESS_MODE])
        assertEquals("times", MediaWidget.resolve(Settings(mapOf("showProgress" to false, "progressMode" to "times")))[MediaWidget.PROGRESS_MODE])
    }

    @Test
    fun `a weather widget with its icon off keeps it off, and the icon can be switched back on`() {
        val off = WeatherWidget.resolve(Settings(mapOf("showIcon" to false, "iconStyle" to "outline")))
        assertEquals("none", off[WeatherWidget.ICON_STYLE])
        assertEquals("filled", WeatherWidget.resolve(off.with(WeatherWidget.ICON_STYLE, "filled"))[WeatherWidget.ICON_STYLE])
        assertEquals("outline", WeatherWidget.resolve(Settings(mapOf("iconStyle" to "outline")))[WeatherWidget.ICON_STYLE])
    }

    @Test
    fun `under the ring's number, by choice`() {
        val charging = BatteryState(60, ChargeStatus.Charging, true, 4100, 335, 500, 5_400_000L)
        assertEquals("CHARGING", RingWidget.underText("status", charging))
        assertEquals("1h 30m to full", RingWidget.underText("time", charging))
        assertEquals("2.05 W", RingWidget.underText("power", charging))
        assertEquals("33.5 °C", RingWidget.underText("temp", charging))
        assertNull(RingWidget.underText("none", charging))
        assertEquals("FULL", RingWidget.underText("time", charging.copy(status = ChargeStatus.Full)))
        assertEquals("—", RingWidget.underText("power", charging.copy(currentMa = null)))
    }

    @Test
    fun `the ring turns red when low and amber at half, and keeps its colour above`() {
        val accent = Color(0xFF44B98A)
        assertEquals(Color(0xFFE5534B), RingWidget.levelColour(19, accent))
        assertEquals(Color(0xFFF5B942), RingWidget.levelColour(20, accent))
        assertEquals(Color(0xFFF5B942), RingWidget.levelColour(49, accent))
        assertEquals(accent, RingWidget.levelColour(50, accent))
    }

    @Test
    fun `an event reads in minutes inside the hour, if asked, and otherwise as it always did`() {
        val now = LocalDateTime.of(2026, 10, 7, 20, 5)
        val soon = EventState.Next("Call", now.plusMinutes(25), now.plusMinutes(85), false)
        assertEquals("in 25 min", EventWidget.whenText(soon, now, h24 = false, soon = true))
        assertEquals("8:30 PM · Today", EventWidget.whenText(soon, now, h24 = false))
        val running = EventState.Next("Call", now.minusMinutes(20), now.plusMinutes(40), false)
        assertEquals("ends in 40 min", EventWidget.whenText(running, now, h24 = false, soon = true))
        val later = EventState.Next("Call", now.plusMinutes(90), now.plusMinutes(150), false)
        assertEquals("9:35 PM · Today", EventWidget.whenText(later, now, h24 = false, soon = true))
        val allDay = EventState.Next("Holiday", now.toLocalDate().plusDays(1).atStartOfDay(), now.toLocalDate().plusDays(2).atStartOfDay(), true)
        assertEquals("All day · Tomorrow", EventWidget.whenText(allDay, now, h24 = false, soon = true))
    }

    @Test
    fun `track times read as minutes and seconds, with hours past an hour`() {
        assertEquals("0:00", mediaTime(0))
        assertEquals("1:23", mediaTime(83_000))
        assertEquals("3:45", mediaTime(225_900))
        assertEquals("1:02:03", mediaTime(3_723_000))
        assertEquals("0:00", mediaTime(-5))
    }

    @Test
    fun `a tinted temperature is blue in the cold, itself at 20 degrees and red in the heat`() {
        val base = Color.White
        assertEquals(Color(0xFF6EC1FF), tintFor(-5.0, base))
        assertEquals(base, tintFor(20.0, base))
        assertEquals(Color(0xFFFF6B4A), tintFor(40.0, base))
        assertEquals(Color(0xFFFFB347), tintFor(30.0, base))
    }

    @Test
    fun `prayer times and the alarm keep an old switch's meaning`() {
        assertEquals("row", PrayerWidget.resolve(Settings())[PrayerWidget.VIEW])
        assertEquals("next", PrayerWidget.resolve(Settings(mapOf("showAll" to false)))[PrayerWidget.VIEW])
        assertEquals("arc", PrayerWidget.resolve(Settings(mapOf("showAll" to false, "view" to "arc")))[PrayerWidget.VIEW])
        assertEquals("bar", AlarmWidget.resolve(Settings())[AlarmWidget.TIME_LEFT])
        assertEquals("none", AlarmWidget.resolve(Settings(mapOf("showBar" to false)))[AlarmWidget.TIME_LEFT])
        assertEquals("ring", AlarmWidget.resolve(Settings(mapOf("showBar" to false, "timeLeft" to "ring")))[AlarmWidget.TIME_LEFT])
        assertEquals("always", AlarmWidget.resolve(Settings())[AlarmWidget.WITHIN])
    }

    @Test
    fun `the alarm can be worded as sleep`() {
        val now = LocalDateTime.of(2026, 10, 7, 23, 5)
        val alarm = LocalDateTime.of(2026, 10, 8, 6, 30)
        assertEquals("in 7 h 25 m · Tomorrow", AlarmWidget.whenText(now, alarm))
        assertEquals("7 h 25 m of sleep · Tomorrow", AlarmWidget.whenText(now, alarm, sleep = true))
    }

    @Test
    fun `the prayer calculation methods are real, distinct and include the default`() {
        val ids = com.ikverse.deskglow.data.PrayerRepository.METHODS.map { it.first }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(5 in ids)
        // Aladhan's own numbers: 1 Karachi, 8 Gulf Region, 16 Dubai, 20 Kemenag.
        assertEquals("Karachi", com.ikverse.deskglow.data.PrayerRepository.METHODS.first { it.first == 1 }.second)
        assertEquals("Gulf Region", com.ikverse.deskglow.data.PrayerRepository.METHODS.first { it.first == 8 }.second)
        assertTrue(ids.all { it in 0..23 })
    }

    @Test
    fun `the schedule lists every session, or only the main ones`() {
        val sprintWeekend = com.ikverse.deskglow.F1Samples.data.races[2]
        assertEquals(listOf("FP1", "Sprint Quali", "Sprint", "Qualifying", "Race"), F1ScheduleWidget.sessionsOf(sprintWeekend, F1ScheduleWidget.defaults).map { it.kind })
        val main = F1ScheduleWidget.defaults.with(F1ScheduleWidget.SESSIONS, "main")
        assertEquals(listOf("Sprint", "Qualifying", "Race"), F1ScheduleWidget.sessionsOf(sprintWeekend, main).map { it.kind })
        val ordinary = com.ikverse.deskglow.F1Samples.data.races[1]
        assertEquals(listOf("Qualifying", "Race"), F1ScheduleWidget.sessionsOf(ordinary, main).map { it.kind })
        // A weekend with none of the main sessions known yet is not left empty.
        val bare = ordinary.copy(sessions = ordinary.sessions.filter { it.kind.startsWith("FP") })
        assertEquals(3, F1ScheduleWidget.sessionsOf(bare, main).size)
    }

    @Test
    fun `the standings and live widgets offer the new choices, and keep their old ones`() {
        val numbers = F1StandingsWidget.fields(F1StandingsWidget.defaults).filterIsInstance<ChoiceField>().single { it.label == "Numbers" }
        assertEquals(listOf("points", "gap", "ahead"), numbers.options.map { it.first })
        assertEquals("table", F1StandingsWidget.resolve(Settings())[F1StandingsWidget.LAYOUT])
        assertEquals("code", F1LiveWidget.resolve(Settings())[F1LiveWidget.NAMES])
        assertEquals(true, F1LiveWidget.resolve(Settings())[F1LiveWidget.SHOW_FLAG])
        assertEquals(false, F1LiveWidget.resolve(Settings())[F1LiveWidget.BORDER])
        assertEquals("next", F1WeekendWidget.resolve(Settings())[F1WeekendWidget.COUNT_TO])
    }

    @Test
    fun `the moon's phase is worked out to within a few hours of the real ones`() {
        fun near(expected: Double, at: String) {
            val phase = Moon.phase(java.time.Instant.parse(at))
            val distance = Math.min(Math.abs(phase - expected), 1 - Math.abs(phase - expected))
            assertTrue("$at: phase $phase, wanted about $expected", distance < 0.03)
        }
        near(0.0, "2025-09-21T19:54:00Z")   // a new moon
        near(0.5, "2025-10-07T03:47:00Z")   // a full moon
        near(0.0, "2025-10-21T12:25:00Z")   // the next new moon
        assertEquals("New moon", Moon.name(0.0))
        assertEquals("Waxing crescent", Moon.name(0.1))
        assertEquals("First quarter", Moon.name(0.25))
        assertEquals("Waxing gibbous", Moon.name(0.4))
        assertEquals("Full moon", Moon.name(0.5))
        assertEquals("Waning gibbous", Moon.name(0.6))
        assertEquals("Last quarter", Moon.name(0.75))
        assertEquals("Waning crescent", Moon.name(0.9))
        assertEquals(0, Moon.illumination(0.0))
        assertEquals(50, Moon.illumination(0.25))
        assertEquals(100, Moon.illumination(0.5))
    }

    @Test
    fun `daylight is counted from sunrise to sunset, and the night waits for the next sunrise`() {
        val rise = LocalDateTime.of(2026, 10, 10, 6, 0)
        val set = LocalDateTime.of(2026, 10, 10, 18, 0)
        assertNull(daylightFraction(rise, set, rise.minusMinutes(1)))
        assertEquals(0f, daylightFraction(rise, set, rise)!!, 0f)
        assertEquals(0.5f, daylightFraction(rise, set, rise.plusHours(6))!!, 0.001f)
        assertNull(daylightFraction(rise, set, set))
        assertEquals("Sunrise in 2 h 0 m", daylightText(rise, set, rise.minusHours(2)))
        assertEquals("6 h 0 m of daylight left", daylightText(rise, set, rise.plusHours(6)))
        assertEquals("Sunrise in 10 h 0 m", daylightText(rise, set, set.plusHours(2)))
        assertEquals("5:42 AM", sunTime(LocalDateTime.of(2026, 10, 10, 5, 42), h24 = false))
        assertEquals("17:30", sunTime(LocalDateTime.of(2026, 10, 10, 17, 30), h24 = true))
        assertEquals("3 PM", hourLabel(LocalDateTime.of(2026, 10, 10, 15, 0), h24 = false))
        assertEquals("15", hourLabel(LocalDateTime.of(2026, 10, 10, 15, 0), h24 = true))
    }

    @Test
    fun `the stat can show the charger, the health and the charge cycles`() {
        val battery = BatteryState(80, ChargeStatus.Charging, true, 4100, 335, 500, null, plugSource = 2, healthCode = 2, cycles = 212)
        assertEquals(Triple("USB", "", "Charger"), StatWidget.reading("charger", battery))
        assertEquals(Triple("Good", "", "Health"), StatWidget.reading("health", battery))
        assertEquals(Triple("212", "", "Cycles"), StatWidget.reading("cycles", battery))
        assertEquals("—", StatWidget.reading("cycles", battery.copy(cycles = null)).first)
        assertEquals("Wireless", StatWidget.chargerName(4))
        assertEquals("Wall", StatWidget.chargerName(1))
        assertEquals("Dock", StatWidget.chargerName(8))
        assertEquals("None", StatWidget.chargerName(0))
        assertEquals("Hot", StatWidget.healthName(3))
        assertEquals("Cold", StatWidget.healthName(7))
        assertEquals("—", StatWidget.healthName(0))
        val metrics = StatWidget.fields(StatWidget.defaults).filterIsInstance<ChoiceField>().single { it.label == "Shows" }.options.map { it.first }
        assertTrue(metrics.containsAll(listOf("charger", "health", "cycles")))
    }

    @Test
    fun `a widget's own favourite wins, blank follows Home, and none follows nobody`() {
        assertEquals("HAM", com.ikverse.deskglow.data.effectiveFavourite("HAM", "VER"))
        assertEquals("VER", com.ikverse.deskglow.data.effectiveFavourite("", "VER"))
        assertEquals("", com.ikverse.deskglow.data.effectiveFavourite("none", "VER"))
        assertEquals("", com.ikverse.deskglow.data.effectiveFavourite("", ""))
    }

    @Test
    fun `the followed team's colour comes from the team, or from the team the driver drives for`() {
        val data = com.ikverse.deskglow.F1Samples.data
        val mclaren = com.ikverse.deskglow.data.teamColour("mclaren").toInt()
        assertEquals(mclaren, com.ikverse.deskglow.data.favouriteColour(data, "NOR", ""))
        assertEquals(mclaren, com.ikverse.deskglow.data.favouriteColour(data, "", "mclaren"))
        // A team named outright beats the driver's.
        assertEquals(com.ikverse.deskglow.data.teamColour("ferrari").toInt(), com.ikverse.deskglow.data.favouriteColour(data, "NOR", "ferrari"))
        assertNull(com.ikverse.deskglow.data.favouriteColour(data, "", ""))
        assertNull(com.ikverse.deskglow.data.favouriteColour(data, "XXX", ""))
    }

    @Test
    fun `the F1 favourite choices offer my driver, none, and every driver`() {
        for ((widget, label) in listOf(F1WeekendWidget to "Favourite driver", F1LiveWidget to "Favourite driver")) {
            val field = widget.fields(widget.resolve(Settings())).filterIsInstance<ChoiceField>().single { it.label == label }
            assertEquals(listOf("", "none"), field.options.take(2).map { it.first })
        }
        val teams = F1StandingsWidget.fields(F1StandingsWidget.resolve(Settings(mapOf("table" to "constructors")))).filterIsInstance<ChoiceField>().single { it.label == "Favourite team" }
        assertEquals(listOf("", "none"), teams.options.take(2).map { it.first })
    }
}
