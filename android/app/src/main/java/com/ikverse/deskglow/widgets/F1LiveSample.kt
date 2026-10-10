package com.ikverse.deskglow.widgets

import com.ikverse.deskglow.data.EventKind
import com.ikverse.deskglow.data.LiveEvent
import com.ikverse.deskglow.data.LiveRow
import com.ikverse.deskglow.data.LiveSession
import com.ikverse.deskglow.data.TrackFlag
import com.ikverse.deskglow.data.lapTime
import java.time.Duration
import java.time.Instant

/**
 * A made-up session for the editor and its layout previews, which have nothing to show between
 * sessions. It is the same on every phone and never leaves the editor.
 */
object F1LiveSample {
    private class Car(val code: String, val number: String, val team: String, val colour: Long, val grid: Int, val tyre: Char, val tyreLaps: Int)

    private val cars = listOf(
        Car("VER", "3", "Red Bull Racing", 0xFF4781D7, 1, 'I', 8), Car("RUS", "63", "Mercedes", 0xFF00D7B6, 2, 'I', 8),
        Car("LEC", "16", "Ferrari", 0xFFED1131, 3, 'I', 8), Car("PIA", "81", "McLaren", 0xFFF47600, 4, 'I', 8),
        Car("NOR", "1", "McLaren", 0xFFF47600, 5, 'I', 8), Car("HAM", "44", "Ferrari", 0xFFED1131, 6, 'I', 8),
        Car("ANT", "12", "Mercedes", 0xFF00D7B6, 7, 'W', 3), Car("HAD", "6", "Red Bull Racing", 0xFF4781D7, 8, 'I', 8),
        Car("BEA", "87", "Haas F1 Team", 0xFF9C9FA2, 13, 'I', 8), Car("GAS", "10", "Alpine", 0xFFFF87BC, 10, 'I', 8),
        Car("COL", "43", "Alpine", 0xFFFF87BC, 11, 'I', 8), Car("ALO", "14", "Aston Martin", 0xFF229971, 15, 'I', 8),
    )

    /** The anchor of the sample's clocks: a moment is only meaningful against it. */
    val AT: Instant = Instant.parse("2026-10-10T09:50:00Z")

    private fun rowsOf(order: List<Int>, race: Boolean) = order.mapIndexed { i, c ->
        val car = cars[c]
        val gap = if (race) (if (i == 0) "Leader" else String.format(java.util.Locale.US, "+%.1f", i * 1.4 + (i / 4) * 0.6)) else String.format(java.util.Locale.US, "+%.3f", i * 0.137)
        LiveRow(
            position = i + 1, number = car.number, code = car.code, teamColour = car.colour, gap = gap,
            interval = if (i == 0) gap else String.format(java.util.Locale.US, "+%.1f", 0.8 + (i % 3) * 0.6),
            team = car.team, grid = car.grid, tyre = car.tyre, tyreLaps = car.tyreLaps, stops = 0,
            lastLap = "1:" + String.format(java.util.Locale.US, "%02d.%03d", 41 + i / 3, 100 + i * 57 % 900),
            bestLap = "1:" + String.format(java.util.Locale.US, "%02d.%03d", 38 + i / 4, 300 + i * 91 % 700),
            penaltySeconds = if (car.code == "BEA") 5 else 0, inPit = car.code == "COL", catching = i % 2 == 0,
            fastest = race && i == 3,
        )
    }

    /** A sprint under the safety car on lap 8 of 21. */
    val race: LiveSession = LiveSession(
        key = 1, meeting = "Singapore GP", name = "Sprint", type = "Race", start = AT.minus(Duration.ofMinutes(25)), status = "Started", finished = false,
        rows = rowsOf(listOf(0, 1, 2, 4, 3, 5, 6, 7, 8, 9, 10, 11), race = true),
        flag = TrackFlag.SafetyCar, lap = 8, totalLaps = 21, rainRisk = 80, raining = true,
        events = listOf(LiveEvent(EventKind.Incident, "[COL] stopped on track", AT.minusSeconds(40))),
    )

    /** Qualifying, second part, with six minutes left. */
    val qualifying: LiveSession = race.copy(
        name = "Qualifying", type = "Qualifying", flag = TrackFlag.Clear, lap = null, totalLaps = null, part = 2, cutoff = 8,
        remaining = Duration.ofSeconds(419), clockAt = AT, clockRunning = false, events = emptyList(), raining = false, rainRisk = null,
        rows = rowsOf(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), race = false).mapIndexed { i, it ->
            it.copy(grid = null, tyre = null, tyreLaps = null, penaltySeconds = 0, bestLap = lapTime(89.874 + i * 0.137))
        },
    )
}
