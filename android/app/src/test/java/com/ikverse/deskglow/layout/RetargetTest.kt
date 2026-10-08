package com.ikverse.deskglow.layout

import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.DefaultLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetargetTest {
    private fun assertTidy(layout: Layout, canvas: Orientation) {
        val boxes = layout.items.filter { it.visible }.map { it.box }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("${boxes[i]} overlaps ${boxes[j]}", boxes[i].overlaps(boxes[j]))
        boxes.forEach { assertTrue("$it is off the $canvas canvas", it.x >= 0 && it.y >= 0 && it.right <= canvas.width && it.bottom <= canvas.height) }
    }

    @Test
    fun `the default portrait layout becomes a tidy landscape one with every widget`() {
        val portrait = DefaultLayout.create(Orientation.Portrait)
        val result = Retarget.convert(portrait, Orientation.Portrait, Orientation.Landscape)
        assertEquals(0, result.hidden)
        assertEquals(portrait.items.map { it.id }, result.layout.items.map { it.id })
        assertTrue(result.layout.items.all { it.visible })
        assertTidy(result.layout, Orientation.Landscape)
    }

    @Test
    fun `the default landscape layout becomes a tidy portrait one with every widget`() {
        val landscape = DefaultLayout.create(Orientation.Landscape)
        val result = Retarget.convert(landscape, Orientation.Landscape, Orientation.Portrait)
        assertEquals(0, result.hidden)
        assertTidy(result.layout, Orientation.Portrait)
    }

    @Test
    fun `a widget keeps its place on the screen and its settings, ids and order`() {
        val topLeft = WidgetItem("a", "stat", Box(0, 0, 100, 40))
        val bottomRight = WidgetItem("b", "stat", Box(312, 808, 100, 40))
        val result = Retarget.convert(Layout(listOf(topLeft, bottomRight)), Orientation.Portrait, Orientation.Landscape)
        assertEquals(listOf("a", "b"), result.layout.items.map { it.id })
        assertEquals(Box(0, 0, 100, 40), result.layout.find("a")!!.box)
        assertEquals(Box(748, 372, 100, 40), result.layout.find("b")!!.box)
        assertEquals(topLeft.settings, result.layout.find("a")!!.settings)
    }

    @Test
    fun `a centred widget stays centred and a full-width one stays full width`() {
        val centred = WidgetItem("a", "stat", Box(156, 100, 100, 40))
        val wide = WidgetItem("b", "stat", Box(0, 300, 412, 52))
        val result = Retarget.convert(Layout(listOf(centred, wide)), Orientation.Portrait, Orientation.Landscape)
        val a = result.layout.find("a")!!.box
        assertEquals(Orientation.Landscape.width, a.x * 2 + a.w)
        val b = result.layout.find("b")!!.box
        assertEquals(0, b.x)
        assertEquals(Orientation.Landscape.width, b.w)
    }

    @Test
    fun `a widget wider than the new canvas shrinks in proportion`() {
        val wide = WidgetItem("a", "stat", Box(0, 0, 848, 200))
        val box = Retarget.convert(Layout(listOf(wide)), Orientation.Landscape, Orientation.Portrait).layout.find("a")!!.box
        assertEquals(Orientation.Portrait.width, box.w)
        assertEquals(96, box.h) // 200 x 412/848, on the 4-unit grid
    }

    @Test
    fun `widgets that land on each other are moved apart, in reading order`() {
        // Three in a row in landscape become a stack in portrait, left one first.
        val row = Layout(listOf(
            WidgetItem("a", "stat", Box(24, 160, 240, 80)),
            WidgetItem("b", "stat", Box(304, 160, 240, 80)),
            WidgetItem("c", "stat", Box(584, 160, 240, 80)),
        ))
        val out = Retarget.convert(row, Orientation.Landscape, Orientation.Portrait).layout
        assertTidy(out, Orientation.Portrait)
        assertTrue(out.find("a")!!.box.y < out.find("b")!!.box.y || out.find("a")!!.box.x < out.find("b")!!.box.x)
        assertTrue(out.find("b")!!.box.y < out.find("c")!!.box.y || out.find("b")!!.box.x < out.find("c")!!.box.x)
    }

    @Test
    fun `hidden widgets stay hidden and keep their settings`() {
        val hidden = WidgetItem("h", "clock", Box(10, 10, 100, 40), visible = false)
        val result = Retarget.convert(Layout(listOf(hidden)), Orientation.Portrait, Orientation.Landscape)
        val item = result.layout.find("h")!!
        assertFalse(item.visible)
        assertEquals(hidden.settings, item.settings)
        assertEquals(0, result.hidden)
    }

    @Test
    fun `when too many widgets cannot fit the rest are hidden, not lost`() {
        val many = Layout((1..30).map { WidgetItem("w$it", "ring", Box(0, 0, 264, 264)) })
        val result = Retarget.convert(many, Orientation.Portrait, Orientation.Landscape)
        assertEquals(30, result.layout.items.size)
        assertTrue(result.hidden > 0)
        assertEquals(result.hidden, result.layout.items.count { !it.visible })
        assertTidy(result.layout, Orientation.Landscape)
    }

    @Test
    fun `going there and back keeps the widgets in the same overall arrangement`() {
        val portrait = DefaultLayout.create(Orientation.Portrait)
        val there = Retarget.convert(portrait, Orientation.Portrait, Orientation.Landscape).layout
        val back = Retarget.convert(there, Orientation.Landscape, Orientation.Portrait).layout
        assertTidy(back, Orientation.Portrait)
        // The clock was above the ring, and the media bar below it, before; they still are.
        fun y(l: Layout, type: String) = l.items.first { it.type == type }.box.let { it.y + it.h / 2 }
        assertTrue(y(back, "clock") < y(back, "ring"))
        assertTrue(y(back, "ring") < y(back, "media"))
    }

    @Test
    fun `converting to the same orientation changes nothing`() {
        val layout = DefaultLayout.create()
        assertEquals(layout, Retarget.convert(layout, Orientation.Portrait, Orientation.Portrait).layout)
    }
}
