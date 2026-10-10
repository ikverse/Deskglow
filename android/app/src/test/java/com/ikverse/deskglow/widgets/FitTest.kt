package com.ikverse.deskglow.widgets

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** How [Fit] chooses an arrangement, how many parts and what size, and how the kinds of text grow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w900dp-h900dp-mdpi")
class FitTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** What the last real (not measuring) pass was handed. */
    private var shown: Fitted? = null

    @Composable
    private fun Block(w: Float, h: Float) = with(LocalDensity.current) { Box(Modifier.size(w.toDp(), h.toDp())) }

    private val box = mutableStateOf(0f to 0f)
    private val drawing = mutableStateOf<(@Composable (Fitted) -> Unit)?>(null)
    private val options = mutableStateOf(Triple(1, 0, null as Any?))

    /** [content] fitted into a box [w] by [h] pixels, a pixel to a canvas unit. The content is set once per test and changed after that. */
    private fun fit(w: Float, h: Float, arrangements: Int = 1, levels: Int = 0, key: Any? = null, content: @Composable (Fitted) -> Unit) {
        val first = drawing.value == null
        box.value = w to h
        options.value = Triple(arrangements, levels, key)
        drawing.value = content
        if (first) compose.setContent {
            val (bw, bh) = box.value
            val (a, l, k) = options.value
            // Read here, as a widget reads its data, so that new content is measured afresh.
            val draw = drawing.value
            with(LocalDensity.current) {
                Box(Modifier.size(bw.toDp(), bh.toDp())) {
                    Fit(1f, k, arrangements = a, levels = l) { f ->
                        if (!f.probing) shown = f
                        draw?.invoke(f)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Two blocks, each ten main units by four: side by side, or one above the other. */
    @Composable
    private fun Pair(f: Fitted) {
        val u = f.scale.main
        if (f.arrangement == 0) Row { Block(u * 10, u * 4); Block(u * 10, u * 4) } else Column { Block(u * 10, u * 4); Block(u * 10, u * 4) }
    }

    @Test
    fun `a wide box puts the parts side by side and a tall one stacks them`() {
        fit(800f, 100f, arrangements = 2) { Pair(it) }
        assertEquals(0, shown!!.arrangement)
        fit(200f, 800f, arrangements = 2) { Pair(it) }
        assertEquals(1, shown!!.arrangement)
    }

    @Test
    fun `the content grows until one side of the box runs out, leaving the margin`() {
        // Twenty units by four, in 800 by 100: the height runs out first, at about 100 * 0.92 / 4.
        fit(800f, 100f) { Block(it.scale.main * 20, it.scale.main * 4) }
        val u = shown!!.scale.main
        assertTrue("main size $u", u * 4 <= 100 * Fitting.FILL + 1 && u * 4 >= 100 * Fitting.FILL * 0.95f)
    }

    @Test
    fun `spare room shows the optional parts before it makes the text bigger`() {
        // Each part adds a line two units tall; a tall box has room for all of them at a comfortable size.
        fit(200f, 800f, levels = 2) { f -> Column { repeat(1 + f.level) { Block(f.scale.main * 10, f.scale.main * 2) } } }
        assertEquals(2, shown!!.level)
        // A thin strip has not: the parts are left out rather than everything shrinking below a comfortable size.
        fit(400f, 30f, levels = 2) { f -> Column { repeat(1 + f.level) { Block(f.scale.main * 10, f.scale.main * 2) } } }
        assertEquals(0, shown!!.level)
    }

    @Test
    fun `secondary text and labels grow more slowly than the main text, and alike below the design size`() {
        val unit = 3f
        val reference = Fitting.REFERENCE * unit
        assertEquals(reference * 0.5f, Fitting.second(reference * 0.5f, unit), 0.001f)
        assertEquals(reference * 0.5f, Fitting.small(reference * 0.5f, unit), 0.001f)
        val big = reference * 4
        assertTrue(Fitting.second(big, unit) < big)
        assertTrue(Fitting.small(big, unit) < Fitting.second(big, unit))
        assertTrue(Fitting.second(big, unit) > reference)
    }

    @Test
    fun `the size holds while the text changes, and shrinks only when new text would not fit`() {
        fun show(text: String) = fit(600f, 200f, key = "same") { f -> Text(text, fontSize = with(LocalDensity.current) { f.scale.main.toSp() }, maxLines = 1, softWrap = false) }
        show("HAM leads")
        val first = shown!!.scale.main
        show("VER")
        assertEquals(first, shown!!.scale.main, 0.001f)
        show("NOR leads")
        assertEquals(first, shown!!.scale.main, 0.001f)
        show("Lando Norris leads by more than four seconds")
        assertTrue(shown!!.scale.main < first)
    }

    @Test
    fun `a table drops rows that would be too short to read, and caps how tall a row grows`() {
        val (count, row) = tableRows(10, 1.4f, 120f, 1000f, 1f)
        assertTrue(count < 10 && count >= 3)
        assertTrue(row >= Fitting.SMALLEST_ROW || count == 3)
        val (all, tall) = tableRows(3, 1f, 1000f, 1000f, 1f)
        assertEquals(3, all)
        assertEquals(Fitting.LARGEST_ROW, tall, 0.001f)
    }
}
