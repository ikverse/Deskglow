package com.ikverse.deskglow.widgets

import com.ikverse.deskglow.data.LivePhase
import com.ikverse.deskglow.data.LiveRow
import com.ikverse.deskglow.data.LiveSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class F1LiveFixesTest {
    private val start = Instant.parse("2026-10-11T05:00:00Z")

    private fun race(rows: List<LiveRow>, restart: Instant? = null) =
        LiveSession(9, "Japanese GP", "Race", "Race", start, "Started", false, rows, restart = restart)

    private fun row(position: Int, code: String, fastest: Boolean = false, team: String = "") =
        LiveRow(position, "$position", code, 0xFFFF8000, "", "", team = team, fastest = fastest)

    @Test
    fun `a restart time stops being shown once it has passed`() {
        val at = start.plusSeconds(3_600)
        val session = race(emptyList(), restart = at)
        assertEquals(at, session.restartAfter(at.minusSeconds(600)))
        assertEquals(at, session.restartAfter(at.plusSeconds(30))) // a minute's grace
        assertNull(session.restartAfter(at.plusSeconds(1_800)))
        // A red flag half an hour after an old announcement: no "Resumes" with the old time.
        val status = statusOf(session, LivePhase.Red, at.plusSeconds(1_800), h24 = true)
        assertTrue(status.detail.startsWith("Resumes").not())
    }

    @Test
    fun `a new fastest lap is news only when it is the followed car's or team's`() {
        val tracker = FollowTracker()
        val now = start.plusSeconds(600)
        tracker.observe(race(listOf(row(1, "VER", fastest = true), row(2, "NOR"), row(3, "PIA"))), "PIA", now) { it.code == "PIA" }
        // Another car takes it: nothing to say about the followed one.
        val other = tracker.observe(race(listOf(row(1, "VER"), row(2, "NOR", fastest = true), row(3, "PIA"))), "PIA", now) { it.code == "PIA" }
        assertNull(other)
        val mine = tracker.observe(race(listOf(row(1, "VER"), row(2, "NOR"), row(3, "PIA", fastest = true))), "PIA", now) { it.code == "PIA" }
        assertTrue(mine!!.text.startsWith("Fastest lap [PIA]"))
    }
}
