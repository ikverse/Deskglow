package com.ikverse.deskglow.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.FakeFontsHttp
import com.ikverse.deskglow.display.WidgetHost
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.ui.editor.EditorScreen
import com.ikverse.deskglow.widgets.DefaultLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The landscape editor driven the way a person would, on a Note 9 lying on its side. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w848dp-h412dp-land-xxhdpi")
class EditorLandscapeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private var done = false
    private var open by mutableStateOf(true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(context.filesDir, "layout.json").delete()
        File(context.filesDir, "layout-landscape.json").delete()
        File(context.filesDir, "font-catalog.json").delete()
        graph = AppGraph(context, FakeFontsHttp(), FakeFeeds())
        compose.setContent {
            DeskglowTheme {
                WidgetHost(graph) {
                    if (open) EditorScreen(graph, Orientation.Landscape) { done = true }
                }
            }
        }
    }

    private val layout: Layout get() = graph.landscapeLayouts.layout.value
    private fun idOf(type: String) = layout.items.first { it.type == type }.id

    private fun assertInsideAndNoOverlaps() {
        val boxes = layout.items.filter { it.visible }.map { it.box }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("${boxes[i]} / ${boxes[j]}", boxes[i].overlaps(boxes[j]))
        boxes.forEach { assertTrue("$it", it.right <= Orientation.Landscape.width && it.bottom <= Orientation.Landscape.height) }
    }

    @Test
    fun `it starts from the landscape default, and the portrait layout is not touched`() {
        assertEquals(DefaultLayout.create(Orientation.Landscape), layout)
        assertEquals(DefaultLayout.create(Orientation.Portrait), graph.layouts.layout.value)
    }

    @Test
    fun `the settings are a panel beside the canvas, and tapping a widget fills it`() {
        compose.onNodeWithTag("sheet").assertIsDisplayed()
        compose.onNodeWithTag("widget ${idOf("date")}").performClick()
        compose.onNodeWithText("Format").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `dragging a widget moves it, keeps the panel in place, and leaves the portrait layout alone`() {
        val clock = idOf("clock")
        val before = layout.find(clock)!!.box
        compose.onNodeWithTag("widget $clock").performTouchInput { swipe(center, center + Offset(0f, 150f), 400) }
        compose.waitForIdle()
        assertNotEquals(before, layout.find(clock)!!.box)
        assertInsideAndNoOverlaps()
        compose.onNodeWithTag("sheet").assertIsDisplayed()
        assertEquals(DefaultLayout.create(Orientation.Portrait), graph.layouts.layout.value)
    }

    @Test
    fun `the add picker lists every widget, and adding one keeps it inside the canvas`() {
        compose.onNodeWithContentDescription("Add widget").performClick()
        compose.onNodeWithText("Add a widget").assertIsDisplayed()
        compose.onNodeWithText("Your next calendar item").performClick()
        assertEquals(DefaultLayout.create(Orientation.Landscape).items.size + 1, layout.items.size)
        assertInsideAndNoOverlaps()
    }

    @Test
    fun `Back leaves the editor`() {
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(done)
    }

    @Test
    fun `the screen is not held while the landscape editor is open`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
        compose.runOnUiThread { open = false }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }

    @Test
    @Config(qualifiers = "w412dp-h848dp-port-xxhdpi")
    fun `the landscape layout can be edited with the phone upright`() {
        compose.onNodeWithTag("sheet").assertIsDisplayed()
        val clock = idOf("clock")
        val before = layout.find(clock)!!.box
        compose.onNodeWithTag("widget $clock").performTouchInput { swipe(center, center + Offset(0f, 60f), 400) }
        compose.waitForIdle()
        assertNotEquals(before, layout.find(clock)!!.box)
        assertInsideAndNoOverlaps()
        compose.onNodeWithTag("widget ${idOf("date")}").performClick()
        compose.onNodeWithText("Format").performScrollTo().assertIsDisplayed()
    }
}
