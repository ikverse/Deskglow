package com.ikverse.deskglow.widgets

import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLayoutTest {
    @Test
    fun `each default fits its own canvas and nothing overlaps`() {
        for (canvas in Orientation.entries) {
            val boxes = DefaultLayout.create(canvas).items.map { it.box }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertFalse("$canvas: ${boxes[i]} overlaps ${boxes[j]}", boxes[i].overlaps(boxes[j]))
            }
            boxes.forEach { assertTrue("$canvas: $it is off the canvas", it.x >= 0 && it.y >= 0 && it.right <= canvas.width && it.bottom <= canvas.height) }
        }
    }

    @Test
    fun `the landscape default sits on the grid and keeps its 24-unit side margins`() {
        val boxes = DefaultLayout.create(Orientation.Landscape).items.map { it.box }
        boxes.forEach {
            assertTrue("$it is off the grid", listOf(it.x, it.y, it.w, it.h).all { v -> v % Stage.GRID == 0 })
            assertTrue("$it is inside the margins", it.x >= 24 && it.right <= Orientation.Landscape.width - 24)
        }
    }

    @Test
    fun `both orientations offer the same widgets, numbered the same way`() {
        val portrait = DefaultLayout.create(Orientation.Portrait).items
        val landscape = DefaultLayout.create(Orientation.Landscape).items
        assertEquals(portrait.map { it.id }, landscape.map { it.id })
        assertEquals(portrait.map { it.type }, landscape.map { it.type })
        assertEquals(
            portrait.mapNotNull { it.settings.values["metric"] }.sortedBy { it.toString() },
            landscape.mapNotNull { it.settings.values["metric"] }.sortedBy { it.toString() },
        )
    }

    @Test
    fun `portrait is what it always was, and is the default`() {
        assertEquals(DefaultLayout.create(), DefaultLayout.create(Orientation.Portrait))
        assertEquals("w12", DefaultLayout.create().nextId())
    }
}
