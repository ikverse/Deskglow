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

    /** A main block ten units by four beside a secondary one six by two, fitted into 400 by 200 pixels: wide enough that the width, shared by both, is what limits them; what it was shown at goes to [into]. */
    @Composable
    private fun Fitted(into: MutableMap<String, Scale>, name: String, sizes: TextSizes?) {
        val body = @Composable {
            with(LocalDensity.current) {
                Box(Modifier.size(400f.toDp(), 200f.toDp())) {
                    Fit(1f, name) { f ->
                        if (!f.probing) into[name] = f.scale
                        Row { Block(f.scale.main * 10, f.scale.main * 4); Block(f.scale.second * 6, f.scale.second * 2) }
                    }
                }
            }
        }
        if (sizes == null) body() else CompositionLocalProvider(LocalTextSizes provides sizes) { body() }
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
    fun `each slider grows its own kind of text and the whole still fits the box`() {
        val shown = mutableMapOf<String, Scale>()
        compose.setContent {
            Column {
                Fitted(shown, "plain", TextSizes(1f, 1f))
                Fitted(shown, "small", TextSizes(1f, 1.4f))
                Fitted(shown, "main", TextSizes(1.4f, 1f))
            }
        }
        compose.waitForIdle()
        val plain = shown.getValue("plain")
        val small = shown.getValue("small")
        val main = shown.getValue("main")
        // Each slider makes its own kind larger and the other no larger, as the weather's temperature size does:
        // the box is the same size, so what one kind gains the other gives up.
        assertTrue(small.second > plain.second * 1.1f)
        assertTrue(small.main <= plain.main)
        assertTrue(main.main > plain.main)
        assertTrue(main.second <= plain.second)
        for (s in listOf(plain, small, main)) {
            assertTrue("fits across", s.main * 10 + s.second * 6 <= 400f)
            assertTrue("fits down", s.main * 4 <= 200f)
        }
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
