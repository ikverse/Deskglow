package com.ikverse.deskglow.layout

import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Stage
import com.ikverse.deskglow.widgets.DefaultLayout
import com.ikverse.deskglow.widgets.StatWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PackerTest {
    private val layout = DefaultLayout.create()
    private val placed = layout.items.map { Placed(it.id, it.box) }

    private fun id(type: String, metric: String? = null) =
        layout.items.first { it.type == type && (metric == null || it.settings[StatWidget.METRIC] == metric) }.id

    private fun assertTidy(boxes: Collection<Box>, canvas: Orientation = Orientation.Portrait) {
        val list = boxes.toList()
        for (i in list.indices) for (j in i + 1 until list.size) assertFalse("${list[i]} overlaps ${list[j]}", list[i].overlaps(list[j]))
        list.forEach { assertTrue("$it is off the screen", it.x >= 0 && it.y >= 0 && it.right <= canvas.width && it.bottom <= canvas.height) }
    }

    @Test
    fun `the default layout has no overlaps`() = assertTidy(layout.items.map { it.box })

    @Test
    fun `growing the ring pushes what is below it down, and shrinking it brings them back while still held`() {
        val ring = layout.find(id("ring"))!!
        val drag = DragSession(ring.id, resize = true, start = ring.box, base = placed)

        // The ring grows a grid square (16 units) at a time: 40 units of drag is three squares (2.5 rounds up).
        val plus40 = drag.update(0f, 40f)!!
        assertEquals(312, plus40.getValue(ring.id).h)
        assertEquals(564, plus40.getValue(id("stat", "temp")).y)
        assertTidy(plus40.values)

        val plus100 = drag.update(0f, 100f)!!
        assertEquals(360, plus100.getValue(ring.id).h)
        assertEquals(612, plus100.getValue(id("stat", "temp")).y)
        assertEquals(732, plus100.getValue(id("media")).y)
        assertTidy(plus100.values)

        // The next square would run the stack off the screen, so the ring stops at the last one that fits.
        val wall = drag.update(0f, 300f)!!
        assertEquals(360, wall.getValue(ring.id).h)
        assertTidy(wall.values)

        val home = drag.update(0f, 0f)!!
        assertEquals(placed.associate { it.id to it.box }, home)
    }

    @Test
    fun `moving a widget onto others pushes them, and moving it back puts them home`() {
        val media = layout.find(id("media"))!!
        val drag = DragSession(media.id, resize = false, start = media.box, base = placed)
        val up = drag.update(0f, -70f)!!
        assertEquals(628, up.getValue(media.id).y)
        assertTidy(up.values)
        assertEquals(placed.associate { it.id to it.box }, drag.update(0f, 0f))
    }

    @Test
    fun `positions and sizes snap to the 4-unit grid and stay on the screen`() {
        val clock = layout.find(id("clock"))!!
        val drag = DragSession(clock.id, resize = false, start = clock.box, base = placed)
        val moved = drag.update(-500f, -13f)!!.getValue(clock.id)
        assertEquals(0, moved.x)
        assertEquals(44, moved.y)
        val resize = DragSession(clock.id, resize = true, start = clock.box, base = placed)
        val tiny = resize.update(-1000f, -1000f)!!.getValue(clock.id)
        assertTrue(tiny.w >= Stage.MIN_SIZE && tiny.h >= Stage.MIN_SIZE)
        assertTrue(tiny.w < Stage.MIN_SIZE + Stage.STEP && tiny.h < Stage.MIN_SIZE + Stage.STEP)
    }

    @Test
    fun `a resize moves the edges in whole grid squares, and the screen edge is a stop too`() {
        val clock = layout.find(id("clock"))!!
        val drag = DragSession(clock.id, resize = true, start = clock.box, base = placed)
        for (dx in -40..40 step 3) {
            val box = drag.update(dx.toFloat(), 0f)!!.getValue(clock.id)
            assertEquals(0, (box.w - clock.box.w) % Stage.STEP)
        }
        assertEquals(clock.box, drag.update(0f, 0f)!!.getValue(clock.id))
        val wide = drag.update(1000f, 0f)!!.getValue(clock.id)
        assertEquals(Orientation.Portrait.width, wide.right)
    }

    @Test
    fun `a moved widget locks onto the centre lines and says so`() {
        val stat = layout.find(id("stat", "temp"))!!
        val centreX = (Orientation.Portrait.width - stat.box.w) / 2
        val drag = DragSession(stat.id, resize = false, start = stat.box, base = placed)
        drag.update((centreX - stat.box.x + 5).toFloat(), 0f)
        assertTrue(drag.centring.lockX)
        assertEquals(centreX, drag.update((centreX - stat.box.x + 5).toFloat(), 0f)!!.getValue(stat.id).x)
        drag.update((centreX - stat.box.x + 20).toFloat(), 0f)
        assertTrue(drag.centring.nearX)
        assertFalse(drag.centring.lockX)
        drag.update((centreX - stat.box.x + 100).toFloat(), 0f)
        assertFalse(drag.centring.nearX)
    }

    @Test
    fun `a thousand random drags never leave an overlap`() {
        val random = Random(7)
        var current: Layout = layout
        repeat(1000) {
            val item = current.items[random.nextInt(current.items.size)]
            val drag = DragSession(item.id, random.nextBoolean(), item.box, current.items.map { Placed(it.id, it.box) })
            val result = drag.update(random.nextInt(-300, 300).toFloat(), random.nextInt(-400, 400).toFloat())
            assertNotNull(result)
            assertTidy(result!!.values)
            current = current.withBoxes(result)
        }
    }

    @Test
    fun `a new stat goes into a free gap without moving anything, as in the mockup`() {
        val boxes = Packer.place("new", 112, 56, null, placed)!!
        assertEquals(Box(0, 120, 112, 56), boxes.getValue("new"))
        placed.forEach { assertEquals(it.box, boxes.getValue(it.id)) }
    }

    @Test
    fun `a widget that cannot fit anywhere is refused`() {
        val wall = listOf(Placed("full", Box(0, 0, Orientation.Portrait.width, Orientation.Portrait.height)))
        assertNull(Packer.place("new", 100, 100, null, wall))
    }

    @Test
    fun `putting a widget back in its old place pushes whatever moved into it`() {
        val boxes = Packer.place("back", 112, 56, Box(20, 524, 112, 56), placed.filter { it.id != id("stat", "temp") })!!
        assertEquals(Box(20, 524, 112, 56), boxes.getValue("back"))
        assertTidy(boxes.values)
    }

    @Test
    fun `an overlapping saved layout is tidied top to bottom`() {
        // The case tried in the mockup: three widgets stacked on each other and one off the bottom.
        val tidied = Packer.tidy(
            listOf(
                Placed("clock", Box(96, 56, 220, 64)),
                Placed("date", Box(100, 80, 164, 24)),
                Placed("ring", Box(74, 100, 264, 264)),
                Placed("media", Box(20, 830, 372, 52)),
            ),
        )
        assertEquals(56, tidied.getValue("clock").y)
        assertEquals(120, tidied.getValue("date").y)
        assertEquals(144, tidied.getValue("ring").y)
        assertEquals(796, tidied.getValue("media").y)
        assertTidy(tidied.values)
    }

    // ---- landscape: the same rules on an 848 x 412 canvas ----

    private val landscape = DefaultLayout.create(Orientation.Landscape)
    private val landscapePlaced = landscape.items.map { Placed(it.id, it.box) }

    @Test
    fun `the landscape default layout has no overlaps and fits the 848 by 412 canvas`() =
        assertTidy(landscape.items.map { it.box }, Orientation.Landscape)

    @Test
    fun `a landscape drag stops at the landscape walls, not the portrait ones`() {
        val clock = landscape.items.first { it.type == "clock" }
        val drag = DragSession(clock.id, resize = false, start = clock.box, base = landscapePlaced, orientation = Orientation.Landscape)
        val far = drag.update(2000f, 2000f)!!.getValue(clock.id)
        assertEquals(848 - clock.box.w, far.x)
        assertEquals(412 - clock.box.h, far.y)
    }

    @Test
    fun `a thousand random landscape drags never leave an overlap`() {
        val random = Random(11)
        var current: Layout = landscape
        repeat(1000) {
            val item = current.items[random.nextInt(current.items.size)]
            val drag = DragSession(
                item.id, random.nextBoolean(), item.box, current.items.map { Placed(it.id, it.box) }, Orientation.Landscape,
            )
            val result = drag.update(random.nextInt(-600, 600).toFloat(), random.nextInt(-300, 300).toFloat())
            assertNotNull(result)
            assertTidy(result!!.values, Orientation.Landscape)
            current = current.withBoxes(result)
        }
    }

    @Test
    fun `a widget taller than the landscape canvas is refused, though it would fit upright`() {
        assertNull(Packer.place("tall", 100, 500, null, emptyList(), Orientation.Landscape))
        assertNotNull(Packer.place("tall", 100, 500, null, emptyList(), Orientation.Portrait))
    }

    @Test
    fun `a new widget in landscape finds room inside the canvas`() {
        val boxes = Packer.place("new", 112, 56, null, landscapePlaced, Orientation.Landscape)!!
        assertTidy(boxes.values, Orientation.Landscape)
        landscapePlaced.forEach { assertEquals(it.box, boxes.getValue(it.id)) }
    }

    @Test
    fun `tidying a landscape layout pulls what hangs off the bottom back up`() {
        val tidied = Packer.tidy(
            listOf(Placed("a", Box(0, 0, 100, 100)), Placed("b", Box(0, 380, 100, 52))),
            Orientation.Landscape,
        )
        assertEquals(360, tidied.getValue("b").y) // 412 - 52; upright it would stay at 380
        assertTidy(tidied.values, Orientation.Landscape)
    }

    @Test
    fun `the window's shape picks the canvas`() {
        assertEquals(Orientation.Portrait, Orientation.of(412, 848))
        assertEquals(Orientation.Landscape, Orientation.of(848, 412))
        assertEquals(Orientation.Portrait, Orientation.of(500, 500))
    }
}
