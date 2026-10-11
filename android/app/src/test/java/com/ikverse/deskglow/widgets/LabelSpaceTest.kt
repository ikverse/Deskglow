package com.ikverse.deskglow.widgets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Labels set on a drawing: kept off its lines, off each other and off moving things, shrunk or left out when they must be. */
class LabelSpaceTest {
    /** A hairpin: down, round and back up, 12 pixels wide, in a 300 by 300 box. */
    private fun hairpin() = InkMap.of(300f, 300f).apply {
        line(listOf(Offset(100f, 20f), Offset(100f, 220f), Offset(150f, 260f), Offset(200f, 220f), Offset(200f, 20f)), 12f)
    }

    private fun at(x: Float, y: Float): (Size) -> Offset = { Offset(x, y) }

    @Test
    fun `a label that would sit on the line takes its next spot`() {
        val ink = hairpin()
        val placed = LabelSpace(ink).place(Size(30f, 12f), listOf(at(90f, 100f), at(120f, 100f)))
        assertNotNull(placed)
        assertEquals(Offset(120f, 100f), placed!!.topLeft)
        assertEquals(1f, placed.share)
    }

    @Test
    fun `a label too big for the gap between the straights shrinks into it`() {
        // 100 pixels between the straights, less the line: a label 100 wide only fits smaller.
        val placed = LabelSpace(hairpin()).place(Size(100f, 12f), listOf { size -> Offset(150f - size.width / 2, 100f) })
        assertNotNull(placed)
        assertFalse(hairpin().hits(Rect(placed!!.topLeft, Size(100f, 12f) * placed.share)))
        assertEquals(0.8f, placed.share, 0.001f)
    }

    @Test
    fun `labels keep off each other and an optional one with nowhere to go is left out`() {
        val space = LabelSpace(hairpin())
        assertNotNull(space.place(Size(60f, 20f), listOf(at(120f, 100f))))
        assertNull(space.place(Size(60f, 20f), listOf(at(120f, 100f))))
    }

    @Test
    fun `a required label is kept, at the smallest size, where it was first meant to go`() {
        val placed = LabelSpace(hairpin()).place(Size(60f, 20f), listOf(at(80f, 100f)), floor = 0.6f, required = true)
        assertNotNull(placed)
        assertEquals(Offset(80f, 100f), placed!!.topLeft)
        assertEquals(0.6f, placed.share)
    }

    @Test
    fun `a car in a crowded corner moves a name to a side where no other car is`() {
        val ink = hairpin()
        val other = Offset(160f, 90f)
        val placed = LabelSpace(ink).place(
            Size(30f, 12f),
            listOf(at(140f, 84f), at(120f, 120f)),
            blocked = { r -> r.touches(other, 8f) },
        )
        assertEquals(Offset(120f, 120f), placed!!.topLeft)
    }
}
