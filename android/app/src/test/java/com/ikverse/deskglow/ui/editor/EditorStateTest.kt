package com.ikverse.deskglow.ui.editor

import com.ikverse.deskglow.fonts.PickedFont
import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.ClockWidget
import com.ikverse.deskglow.widgets.Common
import com.ikverse.deskglow.widgets.DefaultLayout
import com.ikverse.deskglow.widgets.StatWidget
import com.ikverse.deskglow.widgets.StyleKind
import com.ikverse.deskglow.widgets.WeatherWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTest {
    private val saved = mutableListOf<Layout>()
    private val state = EditorState(DefaultLayout.create()) { saved += it }

    private fun noOverlaps(layout: Layout, canvas: Orientation = Orientation.Portrait) {
        val boxes = layout.items.filter { it.visible }.map { it.box }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("${boxes[i]} / ${boxes[j]}", boxes[i].overlaps(boxes[j]))
        boxes.forEach { assertTrue(it.bottom <= canvas.height && it.right <= canvas.width) }
    }

    @Test
    fun `every change is saved at once`() {
        state.add(StatWidget)
        assertEquals(state.layout, saved.last())
    }

    @Test
    fun `adding places the widget without overlap and selects it`() {
        assertTrue(state.add(StatWidget))
        val added = state.selected!!
        assertEquals("stat", added.type)
        assertEquals(SheetTab.Settings, state.tab)
        assertFalse(state.pickerOpen)
        noOverlaps(state.layout)
    }

    @Test
    fun `adding to a full screen says there is no room and adds nothing`() {
        val full = EditorState(Layout(listOf(WidgetItem("w1", "ring", Box(0, 0, Orientation.Portrait.width, Orientation.Portrait.height))))) {}
        assertFalse(full.add(WeatherWidget))
        assertEquals(1, full.layout.items.size)
        assertEquals(EditorState.NO_ROOM, full.toast?.message)
    }

    @Test
    fun `deleting offers undo, and undo puts it back in the same place and order`() {
        val before = state.layout
        val weather = before.items.first { it.type == "weather" }
        state.select(weather.id)
        state.delete(weather.id)
        assertNull(state.layout.find(weather.id))
        assertNull(state.selectedId)
        assertEquals("Weather deleted", state.toast?.message)
        state.toast!!.undo!!.invoke()
        assertEquals(before, state.layout)
        assertEquals(weather.id, state.selectedId)
    }

    @Test
    fun `undo pushes down anything that moved into the space meanwhile`() {
        val weather = state.layout.items.first { it.type == "weather" }
        state.delete(weather.id)
        val undo = state.toast!!.undo!!
        state.add(StatWidget) // may take the freed space
        undo()
        assertNotNull(state.layout.find(weather.id))
        assertEquals(weather.box, state.layout.find(weather.id)!!.box)
        noOverlaps(state.layout)
    }

    @Test
    fun `dragging pushes others and they come back while still held, then the result is kept`() {
        val ring = state.layout.items.first { it.type == "ring" }
        val before = state.layout
        state.beginDrag(ring.id, resize = true)
        assertEquals(ring.id, state.dragId)
        state.dragTo(0f, 100f)
        noOverlaps(state.layout)
        assertEquals(364, state.layout.find(ring.id)!!.box.h)
        state.dragTo(0f, 0f)
        assertEquals(before, state.layout)
        state.dragTo(0f, 40f)
        state.endDrag()
        assertNull(state.dragId)
        assertEquals(304, state.layout.find(ring.id)!!.box.h)
    }

    @Test
    fun `hiding and showing again never lands on another widget`() {
        val clock = state.layout.items.first { it.type == "clock" }
        state.setVisible(clock.id, false)
        val date = state.layout.items.first { it.type == "date" }
        state.select(date.id)
        state.beginDrag(date.id, resize = false)
        state.dragTo(-28f, -80f) // onto where the clock was
        state.endDrag()
        state.setVisible(clock.id, true)
        assertTrue(state.layout.find(clock.id)!!.visible)
        noOverlaps(state.layout)
    }

    @Test
    fun `settings changes are made consistent, so Arabic numerals move the clock off seven-segment`() {
        val clock = state.layout.items.first { it.type == "clock" }
        state.select(clock.id)
        state.set(ClockWidget.STYLE, "seg")
        state.set(Common.ARABIC, true)
        assertEquals("squared", state.settingsOf(state.selected!!)[ClockWidget.STYLE])
    }

    @Test
    fun `reset goes back to the default layout`() {
        state.add(StatWidget)
        state.reset()
        assertEquals(DefaultLayout.create(), state.layout)
        assertNull(state.selectedId)
    }

    // ---- landscape: the same editor on the 848 x 412 canvas ----

    private val saved2 = mutableListOf<Layout>()
    private val landscape = EditorState(DefaultLayout.create(Orientation.Landscape), Orientation.Landscape) { saved2 += it }

    @Test
    fun `a landscape editor adds a widget inside the landscape canvas without overlap`() {
        assertTrue(landscape.add(StatWidget))
        noOverlaps(landscape.layout, Orientation.Landscape)
        assertEquals(DefaultLayout.create(Orientation.Landscape).items.size + 1, landscape.layout.items.size)
    }

    @Test
    fun `a landscape drag goes as far as the landscape canvas does, and is saved`() {
        val clock = landscape.layout.items.first { it.type == "clock" }
        landscape.beginDrag(clock.id, resize = false)
        landscape.dragTo(2000f, 2000f) // as far right and down as the canvas goes
        landscape.endDrag()
        val box = landscape.layout.find(clock.id)!!.box
        assertEquals(Orientation.Landscape.width - box.w, box.x)
        assertEquals(Orientation.Landscape.height - box.h, box.y)
        noOverlaps(landscape.layout, Orientation.Landscape)
        assertEquals(landscape.layout, saved2.last())
    }

    @Test
    fun `reset in the landscape editor goes back to the landscape default, not the portrait one`() {
        landscape.add(StatWidget)
        landscape.reset()
        assertEquals(DefaultLayout.create(Orientation.Landscape), landscape.layout)
        assertTrue(landscape.layout != DefaultLayout.create(Orientation.Portrait))
    }

    @Test
    fun `adding to a full landscape canvas says there is no room`() {
        val full = EditorState(
            Layout(listOf(WidgetItem("w1", "ring", Box(0, 0, Orientation.Landscape.width, Orientation.Landscape.height)))),
            Orientation.Landscape,
        ) {}
        assertFalse(full.add(WeatherWidget))
        assertEquals(EditorState.NO_ROOM, full.toast?.message)
    }

    @Test
    fun `the style strip offers the right choices in English and Arabic`() {
        val picked = listOf(PickedFont("Lobster", 400, latin = true, arabic = false), PickedFont("Changa", 500, latin = true, arabic = true))
        val english = styleOptions(StyleKind.Clock, ClockWidget.defaults, picked)
        assertEquals(listOf("squared", "thin", "bold", "seg", "dots", "outline", "rounded", "stacked"), english.take(8).map { it.id })
        assertTrue(english.all { it.enabled })
        assertTrue(english.any { it.id == "b:bebas_neue" } && english.any { it.id == "g:Lobster@400" })
        val arabic = styleOptions(StyleKind.Clock, ClockWidget.defaults.with(Common.ARABIC, true), picked)
        assertFalse(arabic.first { it.id == "seg" }.enabled)
        assertFalse(arabic.any { it.id == "b:bebas_neue" || it.id == "g:Lobster@400" })
        assertTrue(arabic.any { it.id == "b:cairo" } && arabic.any { it.id == "g:Changa@500" })
        val date = styleOptions(StyleKind.Date, com.ikverse.deskglow.widgets.DateWidget.defaults, picked)
        assertEquals("thin", date.first().id)
    }
}
