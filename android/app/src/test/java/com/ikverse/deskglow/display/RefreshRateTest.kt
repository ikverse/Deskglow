package com.ikverse.deskglow.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshRateTest {
    private fun mode(id: Int, hz: Float, width: Int = 1080, height: Int = 2340) = PanelMode(id, width, height, hz)

    private val sixtyAndOneTwenty = listOf(mode(1, 60f), mode(2, 120f))

    @Test
    fun `resting picks 60 and swiping picks 120 on a two-rate panel`() {
        assertEquals(1, chooseMode(sixtyAndOneTwenty, mode(2, 120f), fast = false)?.id)
        assertEquals(2, chooseMode(sixtyAndOneTwenty, mode(1, 60f), fast = true)?.id)
    }

    @Test
    fun `resting goes as low as 48 but no lower`() {
        val modes = listOf(mode(1, 24f), mode(2, 30f), mode(3, 48f), mode(4, 60f), mode(5, 120f))
        assertEquals(3, chooseMode(modes, mode(5, 120f), fast = false)?.id)
        assertEquals(5, chooseMode(modes, mode(3, 48f), fast = true)?.id)
    }

    @Test
    fun `a rate that is a hair under 60 still counts as 60`() {
        val modes = listOf(mode(1, 59.96f), mode(2, 119.9f))
        assertEquals(1, chooseMode(modes, mode(2, 119.9f), fast = false)?.id)
    }

    @Test
    fun `only modes at the current resolution are considered`() {
        val modes = listOf(mode(1, 60f), mode(2, 120f), mode(3, 48f, width = 720, height = 1560), mode(4, 240f, width = 720, height = 1560))
        assertEquals(1, chooseMode(modes, mode(2, 120f), fast = false)?.id)
        assertEquals(2, chooseMode(modes, mode(1, 60f), fast = true)?.id)
    }

    @Test
    fun `a panel with one mode, or none that qualifies, is left alone or kept`() {
        assertEquals(1, chooseMode(listOf(mode(1, 60f)), mode(1, 60f), fast = false)?.id)
        assertNull(chooseMode(listOf(mode(1, 30f)), mode(1, 30f), fast = false))
        assertNull(chooseMode(emptyList(), mode(1, 60f), fast = true))
    }
}
