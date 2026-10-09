package com.ikverse.deskglow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightLevelTest {
    @Test
    fun `a dark room is the dimmest and daylight is full brightness`() {
        assertEquals(0.02f, brightnessForLux(0f), 0.0001f)
        assertEquals(0.02f, brightnessForLux(-5f), 0.0001f) // a bad reading
        assertEquals(1f, brightnessForLux(10_000f), 0.0001f)
        assertEquals(1f, brightnessForLux(100_000f), 0.0001f)
    }

    @Test
    fun `brighter rooms never give a dimmer screen`() {
        val levels = listOf(0f, 1f, 5f, 20f, 100f, 400f, 1_000f, 5_000f, 10_000f).map(::brightnessForLux)
        assertEquals(levels.sorted(), levels)
    }

    @Test
    fun `a bedside room stays properly dim`() {
        assertTrue(brightnessForLux(10f) < 0.25f)
    }

    @Test
    fun `the first reading is applied and a tiny change after it is not`() {
        val room = RoomLight()
        assertNotNull(room.onLux(100f))
        assertNull(room.onLux(101f))
    }

    @Test
    fun `a brief shadow does not move the screen far but a lasting change does`() {
        val room = RoomLight()
        val steady = room.onLux(500f)!!
        val shadow = room.onLux(5f)
        assertTrue(shadow == null || steady - shadow < steady / 2)
        var last = steady
        repeat(30) { room.onLux(5f)?.let { last = it } }
        assertTrue(last < steady / 2)
    }
}
