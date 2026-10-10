package com.ikverse.deskglow.widgets

import com.ikverse.deskglow.data.EventKind
import com.ikverse.deskglow.data.F1Corner
import com.ikverse.deskglow.data.F1Track
import com.ikverse.deskglow.data.TrackSpot
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
            // Strung out round the map behind the leader, a little more than a mini-sector apart.
            spot = if (race) TrackSpot(Math.floorMod(17 - (i * 1.6).toInt(), SEGMENTS).let { if (it == 0) SEGMENTS else it }, SEGMENTS, AT) else null,
        )
    }

    private const val SEGMENTS = 24

    /** Marina Bay as MultiViewer drew it, thinned to 90 points, for the track map in the editor. */
    val track: F1Track = F1Track(
        2026, 18,
        floatArrayOf(
            0.926f, 0.477f, 0.945f, 0.409f, 0.960f, 0.361f, 0.975f, 0.313f, 0.993f, 0.262f, 1.000f, 0.230f, 0.996f, 0.210f, 0.973f,
            0.190f, 0.956f, 0.163f, 0.944f, 0.146f, 0.924f, 0.146f, 0.910f, 0.160f, 0.898f, 0.182f, 0.890f, 0.218f, 0.888f,
            0.262f, 0.885f, 0.298f, 0.877f, 0.336f, 0.864f, 0.365f, 0.846f, 0.379f, 0.816f, 0.378f, 0.788f, 0.368f, 0.755f,
            0.350f, 0.712f, 0.325f, 0.672f, 0.303f, 0.613f, 0.269f, 0.570f, 0.241f, 0.531f, 0.198f, 0.496f, 0.146f, 0.471f,
            0.106f, 0.451f, 0.074f, 0.428f, 0.051f, 0.403f, 0.049f, 0.376f, 0.062f, 0.352f, 0.080f, 0.327f, 0.102f, 0.306f,
            0.114f, 0.291f, 0.101f, 0.285f, 0.083f, 0.278f, 0.054f, 0.267f, 0.024f, 0.252f, 0.007f, 0.231f, 0.000f, 0.200f,
            0.012f, 0.170f, 0.034f, 0.132f, 0.064f, 0.101f, 0.086f, 0.060f, 0.116f, 0.018f, 0.143f, 0.003f, 0.160f, 0.000f,
            0.192f, 0.013f, 0.216f, 0.031f, 0.237f, 0.028f, 0.271f, 0.024f, 0.301f, 0.034f, 0.323f, 0.049f, 0.356f, 0.057f,
            0.391f, 0.062f, 0.406f, 0.073f, 0.412f, 0.087f, 0.401f, 0.102f, 0.379f, 0.119f, 0.354f, 0.143f, 0.317f, 0.173f,
            0.274f, 0.207f, 0.230f, 0.237f, 0.195f, 0.268f, 0.164f, 0.289f, 0.146f, 0.309f, 0.147f, 0.323f, 0.168f, 0.334f,
            0.191f, 0.346f, 0.223f, 0.362f, 0.257f, 0.393f, 0.299f, 0.437f, 0.326f, 0.487f, 0.353f, 0.528f, 0.376f, 0.577f,
            0.401f, 0.621f, 0.423f, 0.649f, 0.438f, 0.666f, 0.457f, 0.664f, 0.479f, 0.668f, 0.505f, 0.687f, 0.526f, 0.712f,
            0.540f, 0.748f, 0.558f, 0.790f, 0.580f, 0.831f, 0.596f, 0.865f, 0.586f, 0.901f, 0.548f, 0.916f, 0.512f,
        ).toList().chunked(2) { (x, y) -> x to y },
        1.679f,
        corners = listOf(
            F1Corner(1, 0.999f, 0.217f), F1Corner(2, 0.967f, 0.184f), F1Corner(3, 0.942f, 0.145f), F1Corner(4, 0.890f, 0.220f),
            F1Corner(5, 0.837f, 0.380f), F1Corner(6, 0.583f, 0.250f), F1Corner(7, 0.429f, 0.051f), F1Corner(8, 0.297f, 0.109f),
            F1Corner(9, 0.240f, 0.001f), F1Corner(10, 0.006f, 0.156f), F1Corner(11, 0.034f, 0.253f), F1Corner(12, 0.028f, 0.306f),
            F1Corner(13, 0.067f, 0.410f), F1Corner(14, 0.315f, 0.153f), F1Corner(15, 0.382f, 0.286f), F1Corner(16, 0.666f, 0.453f),
            F1Corner(17, 0.668f, 0.503f), F1Corner(18, 0.820f, 0.593f), F1Corner(19, 0.889f, 0.564f),
        ),
    )

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
