package com.ikverse.deskglow.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class F1MapMotionTest {
    private val lapMs = 90_000L
    private val frame = 33_000_000L

    /** Where [motion] draws the car after [frames] frames chasing [target]s, one per frame. */
    private fun run(motion: MapMotion, start: Long, targets: List<Float>): List<Float> =
        targets.mapIndexed { i, t -> motion.place("1", t, lapMs, start + i * frame) }

    @Test
    fun `a car first seen is drawn where the timing puts it`() {
        assertEquals(0.4f, MapMotion().place("1", 0.4f, lapMs, 0L), 1e-6f)
    }

    @Test
    fun `a jump on a new mini-sector is closed over several frames, not at once`() {
        val motion = MapMotion()
        motion.place("1", 0.30f, lapMs, 0L)
        // The timing moves the car on by 5% of a lap in one go.
        val drawn = run(motion, frame, List(60) { 0.35f })
        assertTrue("the first frame does not jump", drawn.first() < 0.32f)
        assertTrue("it moves on every frame", drawn.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("and catches up within a couple of seconds", drawn.last() >= 0.349f)
    }

    @Test
    fun `a car ahead of the timing waits for it rather than going back`() {
        val motion = MapMotion()
        motion.place("1", 0.50f, lapMs, 0L)
        val drawn = run(motion, frame, List(30) { 0.48f })
        assertTrue(drawn.all { it >= 0.50f - 1e-6f })
    }

    @Test
    fun `round the line it goes on, the short way`() {
        val motion = MapMotion()
        motion.place("1", 0.99f, lapMs, 0L)
        val drawn = run(motion, frame, List(60) { 0.02f })
        assertTrue(drawn.last() in 0.015f..0.03f)
    }

    @Test
    fun `far off, or after a long pause, the car is put where the timing says`() {
        val motion = MapMotion()
        motion.place("1", 0.10f, lapMs, 0L)
        assertEquals(0.60f, motion.place("1", 0.60f, lapMs, frame), 1e-6f)
        assertEquals(0.62f, motion.place("1", 0.62f, lapMs, frame + 10_000_000_000L), 1e-6f)
    }

    @Test
    fun `a car gone from the track starts afresh when it is back`() {
        val motion = MapMotion()
        motion.place("1", 0.10f, lapMs, 0L)
        motion.keepOnly(emptySet())
        assertEquals(0.12f, motion.place("1", 0.12f, lapMs, frame), 1e-6f)
    }
}
