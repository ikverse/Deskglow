package com.ikverse.deskglow.widgets

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class StatHistoryTest {
    @Before
    fun fresh() = StatHistory.clear()

    @Test
    fun `readings are kept at most one every fifteen seconds`() {
        StatHistory.add("temp", 30.0, 0)
        StatHistory.add("temp", 31.0, 5_000)
        StatHistory.add("temp", 32.0, 14_999)
        StatHistory.add("temp", 33.0, 15_000)
        assertEquals(listOf(30.0, 33.0), StatHistory.recent("temp", 20_000).map { it.value })
    }

    @Test
    fun `readings older than ten minutes are dropped as they are read`() {
        StatHistory.add("level", 80.0, 0)
        StatHistory.add("level", 79.0, 300_000)
        StatHistory.add("level", 78.0, 590_000)
        assertEquals(listOf(80.0, 79.0, 78.0), StatHistory.recent("level", 600_000).map { it.value })
        assertEquals(listOf(79.0, 78.0), StatHistory.recent("level", 600_001).map { it.value })
        assertEquals(listOf(78.0), StatHistory.recent("level", 900_001).map { it.value })
        assertEquals(emptyList<Double>(), StatHistory.recent("level", 1_190_001).map { it.value })
    }

    @Test
    fun `no more than forty readings are held, the oldest going first`() {
        // Ten minutes at one reading every fifteen seconds is about forty: the cap holds even at the edge of that.
        repeat(60) { StatHistory.add("power", it.toDouble(), it * StatHistory.GAP_MS) }
        val kept = StatHistory.recent("power", 59 * StatHistory.GAP_MS)
        assertEquals(true, kept.size <= StatHistory.MAX)
        assertEquals(59.0, kept.last().value, 0.0)
    }

    @Test
    fun `each number has its own history, and clearing forgets them all`() {
        StatHistory.add("temp", 30.0, 0)
        StatHistory.add("level", 80.0, 0)
        assertEquals(listOf(30.0), StatHistory.recent("temp", 1_000).map { it.value })
        assertEquals(listOf(80.0), StatHistory.recent("level", 1_000).map { it.value })
        assertEquals(emptyList<Double>(), StatHistory.recent("voltage", 1_000).map { it.value })
        StatHistory.clear()
        assertEquals(emptyList<Double>(), StatHistory.recent("temp", 1_000).map { it.value })
    }
}
