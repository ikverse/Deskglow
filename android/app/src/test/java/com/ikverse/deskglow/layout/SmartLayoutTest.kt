package com.ikverse.deskglow.layout

import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.ClockWidget
import com.ikverse.deskglow.widgets.DefaultLayout
import com.ikverse.deskglow.widgets.WeatherWidget
import com.ikverse.deskglow.widgets.Widgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class SmartLayoutTest {
    /** One of every widget, plus two more stats, as a long list of everything a person might add. */
    private val everything: List<WidgetItem> = (Widgets.all + listOf(Widgets.find("stat")!!, Widgets.find("stat")!!, Widgets.find("stat")!!)).mapIndexed { i, type ->
        WidgetItem("w${i + 1}", type.id, Box(0, 0, type.width, type.height), true, type.defaults)
    }

    private fun check(result: SmartLayout.Result, original: Layout, o: Orientation, label: String) {
        val visible = result.layout.items.filter { it.visible }
        val boxes = visible.map { it.box }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]
            val b = boxes[j]
            assertTrue("$label: $a / $b closer than 8", a.x >= b.right + 8 || b.x >= a.right + 8 || a.y >= b.bottom + 8 || b.y >= a.bottom + 8)
        }
        for (b in boxes) {
            assertTrue("$label: $b off canvas", b.x >= 0 && b.y >= 0 && b.right <= o.width && b.bottom <= o.height)
            assertTrue("$label: $b off the 4-unit grid", b.x % 4 == 0 && b.y % 4 == 0 && b.w % 4 == 0 && b.h % 4 == 0)
        }
        result.layout.items.filter { it.type == "ring" && it.visible }.forEach { assertEquals("$label: ring not square", it.box.w, it.box.h) }
        // Same widgets in the same order; hidden ones untouched; only weather layout and clock style may change in settings.
        assertEquals(original.items.map { it.id }, result.layout.items.map { it.id })
        for ((before, after) in original.items.zip(result.layout.items)) {
            if (!before.visible) assertEquals("$label: hidden widget changed", before, after)
            else assertEquals(before.type, after.type)
            if (after.type != "weather" && after.type != "clock") assertEquals(before.settings, after.settings)
        }
    }

    @Test
    fun `the catalogue itself is clean`() {
        assertEquals(emptyList<String>(), SmartLayout.catalogueProblems())
    }

    @Test
    fun `nothing visible gives nothing to arrange`() {
        val layout = Layout(everything.map { it.copy(visible = false) })
        assertNull(SmartLayout.arrange(layout, Orientation.Portrait, 0))
    }

    @Test
    fun `every round is valid for one to twelve widgets in either orientation`() {
        val rng = Random(42)
        for (o in Orientation.values()) for (n in 1..everything.size) for (trial in 0 until 3) {
            val layout = Layout(everything.shuffled(rng).take(n).mapIndexed { i, it -> it.copy(id = "w${i + 1}") })
            for (round in 0 until SmartLayout.POOL * 2) {
                val result = SmartLayout.arrange(layout, o, round)
                assertNotNull("${o.name} n=$n round=$round", result)
                check(result!!, layout, o, "${o.name} n=$n trial=$trial round=$round ${layout.items.map { it.type }}")
            }
        }
    }

    @Test
    fun `the default layouts arrange without hiding anything`() {
        for (o in Orientation.values()) {
            val layout = DefaultLayout.create(o)
            for (round in 0 until SmartLayout.POOL) {
                val result = SmartLayout.arrange(layout, o, round)!!
                check(result, layout, o, "${o.name} default round=$round")
                assertEquals("${o.name} round=$round ${result.name}", 0, result.hidden)
            }
        }
    }

    @Test
    fun `pressing again gives different compositions`() {
        for (o in Orientation.values()) {
            val layout = DefaultLayout.create(o)
            val seen = (0 until SmartLayout.POOL).map { SmartLayout.arrange(layout, o, it)!!.layout.items.map { i -> i.box } }.toSet()
            assertTrue("${o.name}: only ${seen.size} different", seen.size >= 4)
        }
    }

    @Test
    fun `hidden widgets are never touched, and nothing is invented`() {
        val layout = DefaultLayout.create().let { l -> Layout(l.items.mapIndexed { i, it -> if (i % 3 == 0) it.copy(visible = false) else it }) }
        val result = SmartLayout.arrange(layout, Orientation.Portrait, 3)!!
        check(result, layout, Orientation.Portrait, "partly hidden")
        assertEquals(layout.items.filter { !it.visible }, result.layout.items.filter { !it.visible })
    }

    @Test
    fun `a weather widget changing shape takes the layout that suits it`() {
        val weather = WidgetItem("w1", "weather", Box(0, 0, 176, 64), true, WeatherWidget.defaults)
        val clock = WidgetItem("w2", "clock", Box(0, 0, 220, 64), true, ClockWidget.defaults)
        for (o in Orientation.values()) for (round in 0 until SmartLayout.POOL * 3) {
            val out = SmartLayout.arrange(Layout(listOf(weather, clock)), o, round)!!.layout.items.first { it.type == "weather" }
            val box = out.box
            val expected = when {
                box.h >= 140 && box.w >= 150 -> "big"
                box.h >= 92 -> "stacked"
                box.h <= 48 -> "compact"
                else -> "side"
            }
            // Still the shape it started as: the person's own choice stands. Otherwise it fits the new shape.
            val unchanged = expected == "side"
            assertEquals("${o.name} round=$round $box", if (unchanged) weather.settings[WeatherWidget.LAYOUT] else expected, out.settings[WeatherWidget.LAYOUT])
        }
    }

    @Test
    fun `a stacked clock gives its old style back in a wide box`() {
        val clock = WidgetItem("w1", "clock", Box(0, 0, 220, 64), true, ClockWidget.defaults.with(ClockWidget.STYLE, "rounded"))
        val layout = Layout(listOf(clock))
        for (o in Orientation.values()) for (round in 0 until SmartLayout.POOL * 2) {
            val out = SmartLayout.arrange(layout, o, round)!!.layout.items.single()
            val style = out.settings[ClockWidget.STYLE]
            val tall = out.box.h >= 140 && out.box.h * 10 >= out.box.w * 8
            assertEquals("${o.name} round=$round ${out.box}", if (tall) "stacked" else "rounded", style)
        }
    }

    @Test
    fun `dump for looking at`() {
        val dir = System.getenv("SMART_DUMP") ?: return
        val sets = mapOf(
            "all" to everything,
            "default-portrait" to DefaultLayout.create(Orientation.Portrait).items,
            "default-landscape" to DefaultLayout.create(Orientation.Landscape).items,
            "few" to everything.filter { it.type in setOf("clock", "ring", "weather") },
            "time" to everything.filter { it.type in setOf("clock", "date", "alarm", "prayer", "notifs") },
        )
        val out = StringBuilder("[")
        for ((name, items) in sets) for (o in Orientation.values()) for (round in 0 until SmartLayout.POOL) {
            val layout = Layout(items.mapIndexed { i, it -> it.copy(id = "w${i + 1}") })
            val r = SmartLayout.arrange(layout, o, round)!!
            val boxes = r.layout.items.filter { it.visible }.joinToString(",") { """{"t":"${it.type}","x":${it.box.x},"y":${it.box.y},"w":${it.box.w},"h":${it.box.h}}""" }
            if (out.length > 1) out.append(',')
            out.append("""{"set":"$name","o":"${o.name}","round":$round,"name":"${r.name}","hidden":${r.hidden},"boxes":[$boxes]}""")
        }
        File(dir, "smart.json").writeText(out.append("]").toString())
    }
}
