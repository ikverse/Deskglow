package com.ikverse.deskglow.widgets

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.data.LocalFeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The "Main size" and "Small text size" sliders: through [Fit], in the Ring, and in [ClearStack]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w900dp-h900dp-mdpi")
class TextSizesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun emptyCache() = FitStats.reset()

    @Composable
    private fun Block(w: Float, h: Float) = with(LocalDensity.current) { Box(Modifier.size(w.toDp(), h.toDp())) }

    /** A main block beside a secondary one, sized in main and secondary units. */
    private class Shape(val boxW: Float, val boxH: Float, val mainW: Float, val mainH: Float, val secondW: Float, val secondH: Float) {
        fun width(s: Scale) = s.main * mainW + s.second * secondW
        fun height(s: Scale) = maxOf(s.main * mainH, s.second * secondH)
    }

    /** The main block ten by four beside a secondary one six by two, in 400 by 100 pixels: the main block's height is the limit. */
    private val cramped = Shape(400f, 100f, 10f, 4f, 6f, 2f)

    /** A main block four by four beside a long secondary one twenty by two, in 400 by 200: the secondary one takes most of the width. */
    private val roomy = Shape(400f, 200f, 4f, 4f, 20f, 2f)

    /** [shape] fitted at [sizes]; what it was shown at goes to [into] under [name]. */
    @Composable
    private fun Fitted(into: MutableMap<String, Scale>, name: String, sizes: TextSizes?, shape: Shape = cramped) {
        val body = @Composable {
            with(LocalDensity.current) {
                Box(Modifier.size(shape.boxW.toDp(), shape.boxH.toDp())) {
                    Fit(1f, name) { f ->
                        if (!f.probing) into[name] = f.scale
                        Row {
                            Block(f.scale.main * shape.mainW, f.scale.main * shape.mainH)
                            Block(f.scale.second * shape.secondW, f.scale.second * shape.secondH)
                        }
                    }
                }
            }
        }
        if (sizes == null) body() else CompositionLocalProvider(LocalTextSizes provides sizes) { body() }
    }

    /** [shape] fitted at each of [sizes], by name. */
    private fun fitAll(shape: Shape, vararg sizes: Pair<String, TextSizes>): Map<String, Scale> {
        val shown = mutableMapOf<String, Scale>()
        compose.setContent { Column { sizes.forEach { (name, s) -> Fitted(shown, name, s, shape) } } }
        compose.waitForIdle()
        for ((name, s) in shown) {
            assertTrue("$name fits across", shape.width(s) <= shape.boxW)
            assertTrue("$name fits down", shape.height(s) <= shape.boxH)
        }
        return shown
    }

    @Test
    fun `at 100 percent a widget is fitted exactly as it was before the sliders`() {
        val shown = mutableMapOf<String, Scale>()
        compose.setContent {
            Column {
                Fitted(shown, "none", null)
                Fitted(shown, "hundred", TextSizes(1f, 1f))
            }
        }
        compose.waitForIdle()
        val before = shown.getValue("none")
        val after = shown.getValue("hundred")
        assertEquals(before.main, after.main, 0f)
        assertEquals(before.second, after.second, 0f)
        assertEquals(before.small, after.small, 0f)
    }

    @Test
    fun `a larger main size grows the main text by just that much, taking room from the rest`() {
        val shown = fitAll(roomy, "plain" to TextSizes(1f, 1f), "main" to TextSizes(1.4f, 1f))
        val plain = shown.getValue("plain")
        val main = shown.getValue("main")
        assertEquals(1.4f, main.main / plain.main, 0.005f)
        assertTrue(main.second < plain.second)
        assertTrue(main.second >= plain.second * Fitting.KEEP)
    }

    @Test
    fun `a larger main size in a box with no room left still fits it`() {
        val shown = fitAll(cramped, "plain" to TextSizes(1f, 1f), "main" to TextSizes(1.4f, 1f))
        assertTrue(shown.getValue("main").main >= shown.getValue("plain").main)
    }

    @Test
    fun `a smaller main size shrinks the main text by just that much and leaves the rest`() {
        val shown = fitAll(roomy, "plain" to TextSizes(1f, 1f), "main" to TextSizes(0.6f, 1f))
        val plain = shown.getValue("plain")
        val main = shown.getValue("main")
        assertEquals(0.6f, main.main / plain.main, 0.005f)
        assertEquals(plain.second, main.second, 0f)
    }

    @Test
    fun `a larger small text size grows the small text and takes its room from the main`() {
        val shown = fitAll(cramped, "plain" to TextSizes(1f, 1f), "small" to TextSizes(1f, 1.4f))
        val plain = shown.getValue("plain")
        val small = shown.getValue("small")
        assertTrue(small.second > plain.second * 1.1f)
        assertTrue(small.main <= plain.main)
    }

    @Test
    fun `the ring's number and words stay inside the thickest ring at the largest sizes`() {
        val side = 264f
        val settings = RingWidget.defaults.with(RingWidget.THICKNESS, 12).with(RingWidget.LAYOUT, "circle").with(RingWidget.UNDER, "power")
        val feeds = FakeFeeds()
        compose.setContent {
            CompositionLocalProvider(LocalFeeds provides feeds, LocalTextSizes provides TextSizes(1.4f, 1.4f)) {
                with(LocalDensity.current) { Box(Modifier.size(side.toDp(), side.toDp())) { RingWidget.Content(settings) } }
            }
        }
        compose.waitForIdle()
        val ink = ringInk(side, side * 0.12f, "circle")
        val density = Density(1f)
        for (text in listOf("100%", RingWidget.underText("power", feeds.battery.value)!!)) {
            val bounds = compose.onNodeWithText(text).getBoundsInRoot()
            val r = with(density) { Rect(bounds.left.toPx(), bounds.top.toPx(), bounds.right.toPx(), bounds.bottom.toPx()) }
            assertFalse("$text at $r touches the ring", ink.hits(r))
        }
    }

    @Test
    fun `a stack over a drawing shrinks clear of it and leaves out what it must`() {
        // A bar across the middle of a 200 by 200 box, leaving 60 pixels clear above and below it.
        val ink = InkMap.of(200f, 200f).apply { rect(Rect(0f, 60f, 200f, 140f)) }
        compose.setContent {
            with(LocalDensity.current) {
                Box(Modifier.size(200f.toDp(), 200f.toDp())) {
                    ClearStack(
                        ink,
                        listOf(
                            ClearPart { k -> Block(40f * k, 50f * k); Text("kept", fontSize = (1f).toSp()) },
                            ClearPart(required = false) { k -> Block(40f * k, 50f * k); Text("dropped", fontSize = (1f).toSp()) },
                        ),
                        gap = 0f,
                        align = 0f,
                        floor = 0.8f,
                    )
                }
            }
        }
        compose.waitForIdle()
        // Both together are 100 tall at full size and would need to shrink below 80% to fit above the bar,
        // so the one that may go goes, and the one that stays is drawn at full size.
        compose.onNodeWithText("dropped").assertDoesNotExist()
        val kept = compose.onNodeWithText("kept").getBoundsInRoot()
        assertTrue(kept.bottom.value <= 60f)
    }

    @Test
    fun `the ink of a ring leaves its hole clear`() {
        val ink = ringInk(200f, 24f, "circle")
        assertFalse(ink.hits(Rect(Offset(70f, 70f), Offset(130f, 130f))))
        assertTrue(ink.hits(Rect(Offset(0f, 90f), Offset(30f, 110f))))
        assertTrue("outside the box counts as covered", ink.hits(Rect(Offset(-5f, 90f), Offset(10f, 110f))))
    }
}
