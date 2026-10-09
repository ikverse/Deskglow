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
        // 616 would be nearest 624, which runs the stack off the screen, so it stops at the dot before.
        assertEquals(608, state.layout.find(ring.id)!!.box.bottom)
        state.dragTo(0f, 0f)
        // The ring's edge is on its nearest dot; everything it pushed is home.
        assertEquals(512, state.layout.find(ring.id)!!.box.bottom)
        before.items.filter { it.id != ring.id }.forEach { assertEquals(it, state.layout.find(it.id)) }
        state.dragTo(0f, 40f)
        state.endDrag()
        assertNull(state.dragId)
        assertEquals(560, state.layout.find(ring.id)!!.box.bottom)
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

    @Test
    fun `a nudge from a screen reader moves or resizes by one step, saves, and stays on the canvas`() {
        val clock = state.layout.items.first { it.type == "clock" }
        state.nudge(clock.id, EditorState.STEP, 0, resize = false)
        // The clock began off the dots (y = 56), so it lands on them: 64.
        assertEquals(clock.box.copy(x = clock.box.x + EditorState.STEP, y = 64), state.layout.find(clock.id)!!.box)
        val moved = state.layout.find(clock.id)!!.box
        state.nudge(clock.id, EditorState.STEP, 0, resize = true)
        assertEquals(0, state.layout.find(clock.id)!!.box.right % EditorState.STEP)
        assertTrue(state.layout.find(clock.id)!!.box.w > moved.w)
        assertEquals(state.layout, saved.last())
        assertNull(state.dragId)
        assertEquals(clock.id, state.selectedId)
        // Pushed all the way to the left edge, it stops there.
        repeat(20) { state.nudge(clock.id, -EditorState.STEP, 0, resize = false) }
        assertEquals(0, state.layout.find(clock.id)!!.box.x)
        noOverlaps(state.layout)
    }

    @Test
    fun `a nudge into another widget pushes it, like a drag, and never overlaps`() {
        val date = state.layout.items.first { it.type == "date" }
        repeat(6) { state.nudge(date.id, 0, -EditorState.STEP, resize = false) } // up, into the clock
        assertTrue(state.layout.find(date.id)!!.box.y < date.box.y)
        noOverlaps(state.layout)
    }

    @Test
    fun `a resize ticks once per grid square, and not at all between squares`() {
        val buzzes = mutableListOf<Haptic>()
        state.haptic = { buzzes += it }
        val clock = state.layout.items.first { it.type == "clock" }
        state.beginDrag(clock.id, resize = true)
        state.dragTo(0f, 0f)
        val start = buzzes.size
        state.dragTo(2f, 0f) // not yet halfway to the next square
        assertEquals(start, buzzes.size)
        state.dragTo(20f, 0f)
        assertEquals(listOf(Haptic.Step), buzzes.drop(start).distinct())
        val after = buzzes.size
        state.dragTo(21f, 0f) // same square
        assertEquals(after, buzzes.size)
        state.endDrag()
    }

    @Test
    fun `reaching a centre line buzzes harder, once, and again only after leaving it`() {
        val buzzes = mutableListOf<Haptic>()
        state.haptic = { buzzes += it }
        val stat = state.layout.items.first { it.type == "stat" }
        val toCentre = ((Orientation.Portrait.width - stat.box.w) / 2 - stat.box.x).toFloat()
        state.beginDrag(stat.id, resize = false)
        state.dragTo(toCentre + 40f, 0f)
        assertEquals(emptyList<Haptic>(), buzzes.filter { it == Haptic.Centre })
        state.dragTo(toCentre + 3f, 0f)
        assertEquals(1, buzzes.count { it == Haptic.Centre })
        assertTrue(state.centring.lockX)
        state.dragTo(toCentre + 4f, 0f)
        assertEquals(1, buzzes.count { it == Haptic.Centre })
        state.dragTo(toCentre + 40f, 0f)
        state.dragTo(toCentre, 0f)
        assertEquals(2, buzzes.count { it == Haptic.Centre })
        state.endDrag()
        assertEquals(false, state.centring.nearX)
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

    private fun grouped(vararg boxes: Box): EditorState {
        val items = boxes.mapIndexed { i, box -> WidgetItem("w${i + 1}", "stat", box) }
        return EditorState(Layout(items)) {}.also { it.selectMode(true) }
    }

    @Test
    fun `select mode gathers widgets by tapping and leaves nothing selected`() {
        val s = grouped(Box(0, 0, 100, 40), Box(200, 0, 100, 40))
        s.toggleInGroup("w1"); s.toggleInGroup("w2")
        assertEquals(setOf("w1", "w2"), s.groupIds)
        s.toggleInGroup("w1")
        assertEquals(setOf("w2"), s.groupIds)
        assertNull(s.selectedId)
        s.selectMode(false)
        assertTrue(s.groupIds.isEmpty())
    }

    @Test
    fun `holding a widget starts select mode with it gathered, then toggles`() {
        val items = listOf(WidgetItem("w1", "stat", Box(0, 0, 100, 40)), WidgetItem("w2", "stat", Box(200, 0, 100, 40)))
        val s = EditorState(Layout(items)) {}
        s.holdWidget("w1")
        assertTrue(s.selecting)
        assertEquals(setOf("w1"), s.groupIds)
        s.holdWidget("w2")
        assertEquals(setOf("w1", "w2"), s.groupIds)
        s.holdWidget("w1")
        assertEquals(setOf("w2"), s.groupIds)
    }

    @Test
    fun `dragging one gathered widget moves them all`() {
        val s = grouped(Box(0, 0, 100, 40), Box(200, 20, 100, 40))
        s.toggleInGroup("w1"); s.toggleInGroup("w2")
        s.nudge("w1", 0, 96, resize = false)
        assertEquals(Box(0, 96, 100, 40), s.layout.find("w1")!!.box)
        assertEquals(Box(200, 116, 100, 40), s.layout.find("w2")!!.box)
    }

    @Test
    fun `aligning moves the group, offers undo, and undo moves it back`() {
        val s = grouped(Box(0, 0, 100, 40), Box(60, 100, 100, 40))
        s.toggleInGroup("w1"); s.toggleInGroup("w2")
        s.align(com.ikverse.deskglow.layout.Align.Left)
        assertEquals(0, s.layout.find("w2")!!.box.x)
        s.toast!!.undo!!()
        assertEquals(60, s.layout.find("w2")!!.box.x)
    }

    @Test
    fun `aligning widgets that would land on each other is refused`() {
        val s = grouped(Box(0, 0, 100, 40), Box(200, 0, 100, 40))
        s.toggleInGroup("w1"); s.toggleInGroup("w2")
        s.align(com.ikverse.deskglow.layout.Align.Left)
        assertEquals(EditorState.NO_ALIGN, s.toast?.message)
        assertEquals(200, s.layout.find("w2")!!.box.x)
    }

    // ---- history ----

    @Test
    fun `undo and redo step back and forward through changes`() {
        val start = state.layout
        assertFalse(state.canUndo)
        state.add(StatWidget)
        val added = state.layout
        assertTrue(state.canUndo)
        state.undo()
        assertEquals(start, state.layout)
        assertEquals(start, saved.last())
        assertTrue(state.canRedo)
        state.redo()
        assertEquals(added, state.layout)
        assertFalse(state.canRedo)
    }

    @Test
    fun `a whole drag is one undo step, and a drag that moves nothing is none`() {
        // A widget already on the dots, so a drag that goes nowhere really changes nothing.
        val s = EditorState(Layout(listOf(WidgetItem("w1", "clock", Box(48, 48, 224, 64)))), Orientation.Portrait) {}
        val start = s.layout
        s.beginDrag("w1", resize = false)
        s.dragTo(0f, 0f)
        s.endDrag()
        assertFalse(s.canUndo)
        s.beginDrag("w1", resize = false)
        s.dragTo(16f, 0f)
        s.dragTo(32f, 0f)
        s.dragTo(48f, 16f)
        s.endDrag()
        assertTrue(s.layout != start)
        s.undo()
        assertEquals(start, s.layout)
        assertFalse(s.canUndo)
    }

    @Test
    fun `snap all puts every corner on a dot without overlap, once, and undo puts it back`() {
        val start = state.layout
        state.snapAllToGrid()
        noOverlaps(state.layout)
        state.layout.items.forEach {
            assertEquals(0, it.box.x % 16)
            assertEquals(0, it.box.y % 16)
            assertTrue(it.box.right % 16 == 0 || it.box.right == Orientation.Portrait.width)
            assertTrue(it.box.w >= 32 && it.box.h >= 32)
        }
        val snapped = state.layout
        state.snapAllToGrid()
        assertEquals(snapped, state.layout)
        assertEquals("Already on the grid", state.toast?.message)
        state.undo()
        assertEquals(start, state.layout)
    }

    @Test
    fun `auto-arrange rearranges without overlap, undo puts it back, and pressing again gives another`() {
        val start = state.layout
        state.smartArrange()
        val first = state.layout
        assertTrue(first != start)
        noOverlaps(first)
        assertTrue(state.toast!!.message.startsWith("Arranged: "))
        assertEquals(first, saved.last())
        state.toast!!.undo!!.invoke()
        assertEquals(start, state.layout)
        state.smartArrange()
        val second = state.layout
        state.smartArrange()
        assertTrue(second != state.layout)
        noOverlaps(state.layout)
    }

    @Test
    fun `auto-arrange leaves hidden widgets alone and says so when nothing is visible`() {
        val hidden = state.layout.items.first().id
        state.setVisible(hidden, false)
        val before = state.layout.find(hidden)
        state.smartArrange()
        assertEquals(before, state.layout.find(hidden))
        val empty = EditorState(Layout(state.layout.items.map { it.copy(visible = false) })) {}
        empty.smartArrange()
        assertEquals("Add some widgets first", empty.toast?.message)
    }

    @Test
    fun `a new change clears redo`() {
        state.add(StatWidget)
        state.undo()
        assertTrue(state.canRedo)
        state.add(StatWidget)
        assertFalse(state.canRedo)
    }

    @Test
    fun `history keeps only the last fifty steps`() {
        val clock = state.layout.items.first { it.type == "clock" }
        repeat(EditorState.HISTORY_LIMIT + 10) { i -> state.nudge(clock.id, if (i % 2 == 0) 16 else -16, 0, resize = false) }
        var steps = 0
        while (state.canUndo) { state.undo(); steps++ }
        assertEquals(EditorState.HISTORY_LIMIT, steps)
    }

    @Test
    fun `quick changes to one setting are a single undo step`() {
        val clock = state.layout.items.first { it.type == "clock" }
        state.select(clock.id)
        val before = state.layout
        state.set(Common.OPACITY, 80)
        state.set(Common.OPACITY, 70)
        state.set(Common.OPACITY, 60)
        state.undo()
        assertEquals(before, state.layout)
    }

    @Test
    fun `undoing the creation of the selected widget clears the selection`() {
        state.add(StatWidget)
        val id = state.selectedId!!
        state.undo()
        assertNull(state.layout.find(id))
        assertNull(state.selectedId)
    }

    @Test
    fun `undo is ignored while a drag is held`() {
        state.add(StatWidget)
        val clock = state.layout.items.first { it.type == "clock" }
        state.beginDrag(clock.id, resize = false)
        state.undo()
        assertTrue(state.canUndo)
        state.endDrag()
    }

    // ---- copying to the other orientation ----

    @Test
    fun `copying rearranges this layout into the other orientation's repository, and undo puts the old one back`() {
        val written = mutableListOf<Layout>()
        val dir = java.nio.file.Files.createTempDirectory("copy").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val target = com.ikverse.deskglow.store.LayoutRepository(java.io.File(dir, "l.json"), scope, kotlinx.coroutines.Dispatchers.Unconfined, Orientation.Landscape)
        val original = target.layout.value
        val s = EditorState(DefaultLayout.create(), Orientation.Portrait, target) { written += it }
        s.copyConfirm = true
        s.copyToOther()
        assertFalse(s.copyConfirm)
        assertEquals(com.ikverse.deskglow.layout.Retarget.convert(s.layout, Orientation.Portrait, Orientation.Landscape).layout, target.layout.value)
        assertTrue(s.toast!!.message.startsWith("Copied to landscape"))
        s.toast!!.undo!!()
        assertEquals(original, target.layout.value)
        assertTrue(written.isEmpty()) // the layout being edited is not touched
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `deleting a gathered widget drops it from the group`() {
        val s = grouped(Box(0, 0, 100, 40), Box(200, 0, 100, 40))
        s.toggleInGroup("w1"); s.toggleInGroup("w2")
        s.delete("w1")
        assertEquals(setOf("w2"), s.groupIds)
    }
}
