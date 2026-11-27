package com.ikverse.deskglow.widgets

import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1Race
import com.ikverse.deskglow.data.F1Session
import com.ikverse.deskglow.data.LiveSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Which screen PitWall shows when. The calendar is the samples': Singapore (race 4 October, run),
 * Japan (first practice 9 October 02:30 UTC, race 11 October 05:00) and Austin (race 25 October 19:00), the last.
 */
class PitWallModeTest {
    private val data = F1Samples.data
    private fun at(text: String) = Instant.parse(text)

    private fun session(start: String, finished: Boolean = false, lost: Boolean = false) = LiveSession(
        key = 1, meeting = "Japanese GP", name = "Practice 1", type = "Practice", start = at(start),
        status = if (finished) "Finalised" else "Started", finished = finished, rows = emptyList(), signalLost = lost,
    )

    private fun mode(now: String, live: F1LiveState = F1LiveState.Waiting, next: List<F1Race> = emptyList()) = pitWallMode(data, live, at(now), next)

    private val melbourne = F1Race(
        1, "Australian GP", "Melbourne, Australia",
        listOf(F1Session("FP1", at("2027-03-05T01:30:00Z")), F1Session("Qualifying", at("2027-03-06T05:00:00Z")), F1Session("Race", at("2027-03-07T04:00:00Z"))),
    )

    @Test
    fun `between weekends it is the race to come, with the last podium`() {
        val m = mode("2026-10-06T00:00:00Z") as PitWallMode.Between
        assertEquals("Japanese GP", m.next.name)
        assertEquals("Singapore GP", m.last?.raceName)
    }

    @Test
    fun `the weekend starts five minutes before its first session`() {
        assertTrue(mode("2026-10-09T02:24:00Z") is PitWallMode.Between)
        val m = mode("2026-10-09T02:26:00Z") as PitWallMode.Weekend
        assertEquals("Japanese GP", m.race.name)
        assertNull(m.result)
        assertEquals("FP1", m.next?.kind)
    }

    @Test
    fun `a session the feed is following is live, whatever the calendar says`() {
        val live = F1LiveState.Live(session("2026-10-09T02:30:00Z"))
        assertTrue(mode("2026-10-09T02:40:00Z", live) is PitWallMode.Live)
        // Running long past its slot, it is still the live one.
        assertTrue(mode("2026-10-09T04:10:00Z", live) is PitWallMode.Live)
    }

    @Test
    fun `a lost connection past the session's window is not live`() {
        val lost = F1LiveState.Live(session("2026-10-04T12:00:00Z", lost = true))
        assertTrue(mode("2026-10-06T00:00:00Z", lost) is PitWallMode.Between)
        // Inside the window the last thing seen stays up.
        assertTrue(mode("2026-10-09T02:40:00Z", F1LiveState.Live(session("2026-10-09T02:30:00Z", lost = true))) is PitWallMode.Live)
    }

    @Test
    fun `between a weekend's sessions it shows the last result, if it is this weekend's`() {
        val own = mode("2026-10-09T04:00:00Z", F1LiveState.Result(session("2026-10-09T02:30:00Z", finished = true))) as PitWallMode.Weekend
        assertNotNull(own.result)
        assertEquals("FP2", own.next?.kind)
        val old = mode("2026-10-09T04:00:00Z", F1LiveState.Result(session("2026-10-04T12:00:00Z", finished = true))) as PitWallMode.Weekend
        assertNull(old.result)
    }

    @Test
    fun `the weekend holds for twelve hours after its race, then gives way to the next`() {
        // Japan's race ends at 07:00.
        val held = mode("2026-10-11T18:00:00Z") as PitWallMode.Weekend
        assertNull(held.next)
        val after = mode("2026-10-11T20:00:00Z") as PitWallMode.Between
        assertEquals("United States GP", after.next.name)
    }

    @Test
    fun `after the last race it is the off-season, looking back on that season`() {
        // Austin's race ends at 21:00 on the 25th.
        assertTrue(mode("2026-10-26T08:00:00Z") is PitWallMode.Weekend)
        val off = mode("2026-10-26T10:00:00Z") as PitWallMode.OffSeason
        assertEquals(2026, off.season)
        assertNull(off.first)
        assertEquals(melbourne, (mode("2026-12-20T00:00:00Z", next = listOf(melbourne)) as PitWallMode.OffSeason).first)
    }

    @Test
    fun `a week before the new season's first session the off-season ends`() {
        val next = listOf(melbourne)
        assertTrue(mode("2027-02-25T00:00:00Z", next = next) is PitWallMode.OffSeason)
        val m = mode("2027-02-27T00:00:00Z", next = next) as PitWallMode.Between
        assertEquals("Australian GP", m.next.name)
        // Last year's podium has nothing to do with it.
        assertNull(m.last)
        assertTrue(mode("2027-03-05T01:27:00Z", next = next) is PitWallMode.Weekend)
    }

    @Test
    fun `a calendar that has moved on to the new season is still the off-season until a week before it`() {
        val rolled = data.copy(races = listOf(melbourne), lastResult = null, drivers = emptyList(), constructors = emptyList())
        val off = pitWallMode(rolled, F1LiveState.Waiting, at("2027-01-10T00:00:00Z")) as PitWallMode.OffSeason
        assertEquals(2026, off.season)
        assertEquals(melbourne, off.first)
        assertTrue(pitWallMode(rolled, F1LiveState.Waiting, melbourne.first.start.minus(Duration.ofDays(6))) is PitWallMode.Between)
    }

    @Test
    fun `an empty calendar looks back on last year, and no calendar at all shows nothing`() {
        val off = pitWallMode(data.copy(races = emptyList()), F1LiveState.Waiting, at("2027-01-05T00:00:00Z")) as PitWallMode.OffSeason
        assertEquals(2026, off.season)
        assertEquals(PitWallMode.Empty, pitWallMode(null, F1LiveState.Waiting, at("2027-01-05T00:00:00Z")))
        assertTrue(pitWallMode(null, F1LiveState.Live(session("2026-10-09T02:30:00Z")), at("2026-10-09T02:40:00Z")) is PitWallMode.Live)
    }

    @Test
    fun `a session on by the calendar whose timing has not come through is shown as connecting`() {
        // Japan's first practice runs 02:30 to 03:30; the feed has said nothing of it yet.
        val waiting = mode("2026-10-09T02:40:00Z", F1LiveState.Result(session("2026-10-04T12:00:00Z", finished = true))) as PitWallMode.Weekend
        assertEquals("FP1", waiting.on?.kind)
        // Its own result in, it is over whatever the calendar says.
        val over = mode("2026-10-09T03:20:00Z", F1LiveState.Result(session("2026-10-09T02:30:00Z", finished = true))) as PitWallMode.Weekend
        assertNull(over.on)
        // Before it starts, nothing is on.
        assertNull((mode("2026-10-09T02:27:00Z") as PitWallMode.Weekend).on)
    }
}
