package com.ikverse.deskglow.data

import com.ikverse.deskglow.widgets.teamLeaders
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * The track map played back like a stream behind real life: a real lap's pace kept with each outline, each
 * car's crossings of the timing points, where those points are learned to be, a car drawn between two known
 * crossings, the marshal sectors under yellow, and who race control's flags were for.
 */
class F1PlaybackTest {
    private val t0 = Instant.parse("2026-11-28T15:00:00Z")
    private fun at(ms: Long) = t0.plusMillis(ms)

    /** A square lap, slow along the first side (half the lap's time) and quick along the other three. */
    private val square = parseTrack(
        """{"rotation":0,"x":[0,100,100,0],"y":[0,0,100,100],"trackPositionTime":[0,50,66,83],"candidateLap":{"lapTime":100},
            "marshalSectors":[{"number":1,"trackPosition":{"x":0,"y":0}},{"number":2,"trackPosition":{"x":100,"y":100}}]}""",
        2026, 1,
    )!!

    @Test
    fun `an outline keeps the real lap's pace, so half the lap's time is not half its length`() {
        assertTrue(square.timed)
        // Half the time is spent on the first quarter of the length.
        assertEquals(0.25f, square.distanceAtTime(0.5f), 1e-3f)
        assertEquals(0.5f, square.timeAtDistance(0.25f), 1e-3f)
        assertEquals(square.pointAt(0.25f), square.pointAtTime(0.5f))
        // Without a real lap, time is taken as length.
        val plain = parseTrack("""{"x":[0,100,100,0],"y":[0,0,100,100]}""", 2026, 1)!!
        assertEquals(0.3f, plain.distanceAtTime(0.3f), 1e-6f)
    }

    @Test
    fun `the marshal sectors are placed along the outline, and kept when it is saved`() {
        assertEquals(listOf(1, 2), square.sectors.map { it.number })
        assertEquals(0f, square.sectors[0].at, 1e-4f)
        assertEquals(0.5f, square.sectors[1].at, 1e-4f)
        assertEquals(0.5f to 0f, square.sectorSpan(2))
        val again = F1Track.fromJson(square.toJson())
        assertEquals(square.times, again.times)
        assertEquals(square.sectors, again.sectors)
        assertEquals(F1Track.VERSION, again.version)
        // One saved before these were kept reads as an older version, to be fetched again.
        val old = JSONObject(square.toJson()).apply { remove("v"); remove("times"); remove("sectors") }.toString()
        assertTrue(F1Track.fromJson(old).version < F1Track.VERSION)
    }

    /** Four timing points; laps of 100 s; the car crosses them at [shares] of each lap. */
    private fun laps(laps: Int, shares: List<Double> = listOf(0.1, 0.5, 0.75), lapMs: Long = 100_000L, offset: Long = 0L): List<Crossing> =
        (0 until laps).flatMap { lap ->
            val base = offset + lap * lapMs
            shares.mapIndexed { k, s -> Crossing(k + 1, 4, at(base + (s * lapMs).toLong())) } + Crossing(4, 4, at(base + lapMs))
        }.let { listOf(Crossing(4, 4, at(offset))) + it }

    @Test
    fun `where the timing points are is learned from the cars' laps, and guessed until then`() {
        val guessed = timingPoints(emptyMap(), 4, null)
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f), guessed.at.toList())
        val learned = timingPoints(mapOf("1" to laps(3), "4" to laps(2, offset = 3_000)), 4, null)
        assertEquals(0.1f, learned.at[1], 1e-3f)
        assertEquals(0.5f, learned.at[2], 1e-3f)
        assertEquals(0.75f, learned.at[3], 1e-3f)
        // A slow lap (the safety car) says little about where the points are.
        val slow = laps(4, shares = listOf(0.3, 0.6, 0.9), lapMs = 200_000L, offset = 1_000)
        assertEquals(0.1f, timingPoints(mapOf("1" to laps(3), "4" to laps(3, offset = 2_000), "16" to slow.take(5)), 4, null).at[1], 1e-3f)
    }

    @Test
    fun `between two crossings a car is drawn exactly, in the time it took`() {
        val points = TimingPoints(4, floatArrayOf(0f, 0.1f, 0.5f, 0.75f, 1f))
        val crossings = laps(1)
        // At 30 s the car is between the first point (10 s, 0.1) and the second (50 s, 0.5): halfway in time.
        assertEquals(0.3f, playbackShare(crossings, points, at(30_000), 100_000)!!, 1e-4f)
        // Over the line, it goes on into the next lap the short way.
        val round = listOf(Crossing(3, 4, at(0)), Crossing(4, 4, at(10_000)), Crossing(1, 4, at(20_000)))
        assertEquals(0.875f, playbackShare(round, points, at(5_000), 100_000)!!, 1e-4f)
        assertEquals(0.05f, playbackShare(round, points, at(15_000), 100_000)!!, 1e-4f)
        assertNull(playbackShare(crossings, points, t0.minusSeconds(1), 100_000))
    }

    @Test
    fun `past its last crossing a car goes on at its pace, but never to the next point`() {
        val points = TimingPoints(4, floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f))
        val crossings = listOf(Crossing(1, 4, at(0)), Crossing(2, 4, at(25_000)))
        // Its last stretch took 25 s for a quarter of the lap: 5 s on, it is a twentieth further.
        assertEquals(0.55f, playbackShare(crossings, points, at(30_000), 100_000)!!, 1e-3f)
        // A minute on with nothing heard, it waits short of the third point.
        val waiting = playbackShare(crossings, points, at(85_000), 100_000)!!
        assertTrue(waiting < 0.75f && waiting > 0.7f)
    }

    @Test
    fun `the timing records each car's crossings, once each, and drops them on a snapshot`() {
        fun json(text: String) = JSONObject(text)
        val timing = LiveTiming()
        timing.apply(LiveMessage("SessionInfo", json("""{"Type":"Race","Name":"Race","Meeting":{"Name":"Qatar Grand Prix"},"StartDate":"2026-11-29T19:00:00","GmtOffset":"03:00:00"}"""), true), t0)
        timing.apply(LiveMessage("SessionStatus", json("""{"Status":"Started"}"""), true), t0)
        timing.apply(LiveMessage("TimingData", json("""{"Lines":{"4":{"Position":"1","Line":1,"Sectors":[{"Segments":[{"Status":0},{"Status":0}]},{"Segments":[{"Status":0},{"Status":0}]}]}}}"""), true), t0)
        fun segment(sector: Int, index: Int, ms: Long) =
            timing.apply(LiveMessage("TimingData", json("""{"Lines":{"4":{"Sectors":{"$sector":{"Segments":{"$index":{"Status":2049}}}}}}}"""), false), at(ms))
        segment(0, 0, 1_000)
        segment(0, 1, 4_000)
        segment(0, 1, 4_500)
        segment(1, 0, 9_000)
        assertEquals(listOf(Crossing(1, 4, at(1_000)), Crossing(2, 4, at(4_000)), Crossing(3, 4, at(9_000))), timing.session()!!.crossings["4"])
    }

    @Test
    fun `sector yellows are lit and cleared one by one, and the flag names the car race control wrote about`() {
        fun message(utc: String, text: String, flag: String? = null, scope: String? = null, sector: Int? = null) =
            JSONObject().put("Utc", utc).put("Message", text).putOpt("Flag", flag).putOpt("Scope", scope).putOpt("Sector", sector)
        fun control(vararg m: JSONObject) = readRaceControl(JSONObject().put("Messages", org.json.JSONArray(m.toList())), ZoneOffset.ofHours(3))
        val yellow = control(
            message("2026-11-29T16:10:00", "CAR 27 (HUL) STOPPED AT TURN 6"),
            message("2026-11-29T16:10:20", "DOUBLE YELLOW IN TRACK SECTOR 7", "DOUBLE YELLOW", "Sector", 7),
            message("2026-11-29T16:10:30", "YELLOW IN TRACK SECTOR 8", "YELLOW", "Sector", 8),
            message("2026-11-29T16:20:00", "CAR 1 (NOR) TIME 1:23.456 DELETED - TRACK LIMITS AT TURN 10"),
            message("2026-11-29T16:20:10", "YELLOW IN TRACK SECTOR 12", "YELLOW", "Sector", 12),
            message("2026-11-29T16:21:00", "CLEAR IN TRACK SECTOR 8", "CLEAR", "Sector", 8),
        )
        assertEquals(mapOf(7 to "DOUBLE YELLOW", 12 to "YELLOW"), yellow.sectorFlags)
        val texts = yellow.events.map { it.text }
        assertTrue(texts.toString(), "Double yellow · sector 7 · [HUL]" in texts)
        assertTrue(texts.toString(), "Yellow · sector 8 · [HUL]" in texts)
        // A lap time deleted is not what brought a flag out.
        assertTrue(texts.toString(), "Yellow · sector 12" in texts)
        val cleared = control(
            message("2026-11-29T16:10:20", "DOUBLE YELLOW IN TRACK SECTOR 7", "DOUBLE YELLOW", "Sector", 7),
            message("2026-11-29T16:12:00", "TRACK CLEAR", "CLEAR", "Track"),
        )
        assertTrue(cleared.sectorFlags.isEmpty())
    }

    @Test
    fun `the car stopped in a sector is found from its last crossing`() {
        val points = TimingPoints(4, floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f))
        // The third point is at half the lap's time: a quarter of the square's length, in sector 1.
        assertEquals(1, sectorOf(square, listOf(Crossing(2, 4, at(0))), points))
        assertEquals(2, sectorOf(square, listOf(Crossing(3, 4, at(0))), points))
    }

    @Test
    fun `the map names the best-placed car of each team`() {
        fun row(position: Int, number: String, team: String) = LiveRow(position, number, number, null, "", "", team = team)
        val rows = listOf(row(1, "1", "McLaren"), row(2, "3", "Red Bull Racing"), row(3, "81", "McLaren"), row(4, "6", "Red Bull Racing"), row(5, "44", "Ferrari"))
        assertEquals(setOf("1", "3", "44"), teamLeaders(rows))
    }
}
