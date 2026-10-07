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

        val plus40 = drag.update(0f, 40f)!!
        assertEquals(304, plus40.getValue(ring.id).h)
        assertEquals(556, plus40.getValue(id("stat", "temp")).y)
        assertTidy(plus40.values)

        val plus100 = drag.update(0f, 100f)!!
        assertEquals(364, plus100.getValue(ring.id).h)
        assertEquals(616, plus100.getValue(id("stat", "temp")).y)
        assertEquals(736, plus100.getValue(id("media")).y)
        assertTidy(plus100.values)

        // The same figures the mockup gave: the screen is full when the ring is 368 tall.
        val wall = drag.update(0f, 300f)!!
        assertEquals(368, wall.getValue(ring.id).h)
        assertEquals(Orientation.Portrait.height, wall.values.maxOf { it.bottom })
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
        assertEquals(Stage.MIN_SIZE, tiny.w)
        assertEquals(Stage.MIN_SIZE, tiny.h)
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
