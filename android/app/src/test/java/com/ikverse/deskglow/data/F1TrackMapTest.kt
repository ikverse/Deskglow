package com.ikverse.deskglow.data

import com.ikverse.deskglow.widgets.F1LiveSample
import com.ikverse.deskglow.widgets.turned
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * What the track map is drawn from: each car's last mini-sector from the timing, how far on from it
 * the car is drawn, the circuit's number from the feed, and the outline with its corners from MultiViewer.
 */
class F1TrackMapTest {
    private val start = Instant.parse("2026-10-11T12:00:00Z")

    private fun json(text: String) = JSONObject(text)

    /** Two sectors of three mini-sectors and one of two: eight in all. */
    private fun sectors(vararg statuses: Int): String {
        val sizes = listOf(3, 3, 2)
        var i = 0
        return sizes.joinToString(",", "[", "]") { n ->
            (0 until n).joinToString(",", """{"Segments":[""", "]}") { """{"Status":${statuses.getOrElse(i++) { 0 }}}""" }
        }
    }

    private fun snapshot(vararg statuses: Int) = LiveMessage(
        "TimingData", json("""{"Lines":{"4":{"Position":"1","Line":1,"Sectors":${sectors(*statuses)}}}}"""), full = true,
    )

    private fun segment(sector: Int, index: Int, status: Int) =
        LiveMessage("TimingData", json("""{"Lines":{"4":{"Sectors":{"$sector":{"Segments":{"$index":{"Status":$status}}}}}}}"""), full = false)

    private fun LiveTiming.withInfo() = apply {
        apply(LiveMessage("SessionInfo", json("""{"Type":"Race","Name":"Race","Meeting":{"Name":"Singapore Grand Prix","Circuit":{"Key":61,"ShortName":"Singapore"}},"StartDate":"2026-10-11T20:00:00","GmtOffset":"08:00:00"}"""), full = true), start)
        apply(LiveMessage("SessionStatus", json("""{"Status":"Started"}"""), full = true), start)
    }

    private fun spotOf(timing: LiveTiming) = timing.session()!!.rows.first().spot

    @Test
    fun `a car timed through a mini-sector is put at the end of it, counting across the sectors`() {
        val timing = LiveTiming().withInfo()
        timing.apply(snapshot(), start)
        assertNull(spotOf(timing))
        timing.apply(segment(1, 1, 2049), start.plusSeconds(30))
        // The second of the second sector is the fifth of eight.
        assertEquals(TrackSpot(5, 8, start.plusSeconds(30)), spotOf(timing))
        timing.apply(segment(2, 0, 2051), start.plusSeconds(40))
        assertEquals(TrackSpot(7, 8, start.plusSeconds(40)), spotOf(timing))
    }

    @Test
    fun `a mini-sector cleared for the new lap, or timed in the pit lane, does not move the car`() {
        val timing = LiveTiming().withInfo()
        timing.apply(snapshot(), start)
        timing.apply(segment(0, 2, 2048), start)
        timing.apply(segment(2, 1, 0), start.plusSeconds(5))
        assertEquals(TrackSpot(3, 8, start), spotOf(timing))
        timing.apply(segment(1, 0, 2064), start.plusSeconds(9))
        assertEquals(TrackSpot(3, 8, start), spotOf(timing))
    }

    @Test
    fun `a snapshot puts a car at the end of its unbroken run from the line, and a full lap waits for the next`() {
        val partway = LiveTiming().withInfo()
        partway.apply(snapshot(2048, 2049, 2049, 2048, 0, 0, 2049, 2049), start)
        assertEquals(TrackSpot(4, 8, start), spotOf(partway))
        val whole = LiveTiming().withInfo()
        whole.apply(snapshot(2048, 2049, 2049, 2048, 2048, 2051, 2049, 2049), start)
        assertNull(spotOf(whole))
        val pits = LiveTiming().withInfo()
        pits.apply(snapshot(2064, 2064, 0, 0, 0, 0, 0, 0), start)
        assertNull(spotOf(pits))
    }

    @Test
    fun `the circuit's number comes from the feed's session info`() {
        val timing = LiveTiming().withInfo()
        timing.apply(snapshot(), start)
        assertEquals(61, timing.session()!!.circuit)
    }

    @Test
    fun `a car is drawn on from its mini-sector at its pace, but never into the next`() {
        val row = LiveRow(1, "4", "NOR", null, "", "", spot = TrackSpot(4, 8, start))
        // An 80-second lap is ten seconds a mini-sector: five seconds on is half way through the fifth.
        assertEquals(4.5f / 8, row.lapFraction(start.plusSeconds(5), 80_000)!!, 1e-4f)
        assertEquals(4.9f / 8, row.lapFraction(start.plusSeconds(60), 80_000)!!, 1e-4f)
        // Through the last, it is back at the line.
        assertEquals(0f, LiveRow(1, "4", "NOR", null, "", "", spot = TrackSpot(8, 8, start)).lapFraction(start, 80_000)!!, 1e-4f)
        assertNull(LiveRow(1, "4", "NOR", null, "", "").lapFraction(start, 80_000))
    }

    @Test
    fun `a typical lap is the middle of the last laps, or a guess before there are any`() {
        val rows = listOf("1:30.000", "1:32.000", "1:50.000").mapIndexed { i, lap -> LiveRow(i + 1, "$i", "C$i", null, "", "", lastLap = lap) }
        assertEquals(92_000L, F1LiveSample.race.copy(rows = rows).typicalLapMs)
        assertEquals(95_000L, F1LiveSample.race.copy(rows = rows.map { it.copy(lastLap = "", bestLap = "") }).typicalLapMs)
    }

    @Test
    fun `a point along the outline is measured by distance, from its first point`() {
        // A square, one unit a side, walked clockwise from its top left.
        val square = F1Track(0, 0, listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f), 1f)
        assertEquals(0f to 0f, square.pointAt(0f))
        assertEquals(0.5f to 0f, square.pointAt(0.125f))
        assertEquals(1f to 0.5f, square.pointAt(0.375f))
        assertEquals(0f to 0.5f, square.pointAt(0.875f))
        assertEquals(0.5f to 0f, square.pointAt(1.125f))
    }

    @Test
    fun `an outline turned a quarter is as wide as it was tall, and its corners turn with it`() {
        val track = F1Track(0, 0, listOf(0f to 0f, 1f to 0f, 1f to 0.5f, 0f to 0.5f), 2f, listOf(F1Corner(1, 1f, 0f)))
        val quarter = turned(track, 90)
        assertEquals(0.5f, quarter.aspect, 1e-4f)
        assertEquals(listOf(0.5f to 0f, 0.5f to 1f, 0f to 1f, 0f to 0f), quarter.points)
        assertEquals(F1Corner(1, 0.5f, 1f), quarter.corners.single())
        assertEquals(track.points, turned(turned(track, 180), 180).points)
        assertEquals(track, turned(track, 0))
    }

    @Test
    fun `an outline keeps its corners when saved, and one saved without them still reads`() {
        val track = F1LiveSample.track
        assertEquals(track.corners, F1Track.fromJson(track.toJson()).corners)
        val old = JSONObject(track.toJson()).apply { remove("corners") }.toString()
        assertTrue(F1Track.fromJson(old).corners.isEmpty())
    }

    @Test
    fun `MultiViewer's corners are turned and scaled with its outline`() {
        val body = """{"rotation":90,"x":[0,100,100,0],"y":[0,0,200,200],"corners":[{"number":3,"trackPosition":{"x":100,"y":200}}]}"""
        val track = parseTrack(body, 2026, 0)!!
        // Turned a quarter, the 100 by 200 box lies on its side: 200 wide, 100 tall.
        assertEquals(2f, track.aspect, 1e-4f)
        val corner = track.corners.single()
        assertEquals(3, corner.number)
        assertTrue(track.points.any { (x, y) -> kotlin.math.abs(x - corner.x) < 1e-4f && kotlin.math.abs(y - corner.y) < 1e-4f })
    }

    @Test
    fun `the outline is fetched by the feed's circuit number, the season before standing in`() {
        val asked = mutableListOf<String>()
        val body = """{"rotation":0,"x":[0,100,100,0],"y":[0,0,50,50],"corners":[]}"""
        val track = fetchCircuit({ url -> asked += url; if (url.endsWith("/2026")) throw IOException("not yet") else body.toByteArray() }, 61, 2026)
        assertNotNull(track)
        assertEquals(listOf("${F1Repository.MULTIVIEWER}/circuits/61/2026", "${F1Repository.MULTIVIEWER}/circuits/61/2025"), asked)
        assertNull(fetchCircuit({ throw IOException("down") }, 61, 2026))
    }
}
