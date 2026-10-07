package com.ikverse.deskglow.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.FakeFontsHttp
import com.ikverse.deskglow.display.WidgetHost
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.ui.editor.EditorScreen
import com.ikverse.deskglow.widgets.ClockWidget
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

/** The editor driven the way a person would: taps, drags and the sheet's controls, on a Note 9-sized screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the editor draws its grid into a real bitmap
@Config(qualifiers = "w412dp-h848dp-xxhdpi")
class EditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: AppGraph
    private val http = FakeFontsHttp()
    private var done = false

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(context.filesDir, "layout.json").delete()
        File(context.filesDir, "font-catalog.json").delete()
        graph = AppGraph(context, http, FakeFeeds())
        compose.setContent { DeskglowTheme { WidgetHost(graph) { EditorScreen(graph) { done = true } } } }
    }

    private val layout: Layout get() = graph.layouts.layout.value
    private fun idOf(type: String) = layout.items.first { it.type == type }.id

    private fun assertNoOverlaps() {
        val boxes = layout.items.filter { it.visible }.map { it.box }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("${boxes[i]} / ${boxes[j]}", boxes[i].overlaps(boxes[j]))
        boxes.forEach { assertTrue(it.bottom <= Orientation.Portrait.height) }
    }

    @Test
    fun `tapping a widget opens its settings`() {
        compose.onNodeWithTag("widget ${idOf("date")}").performClick()
        compose.onNodeWithText("Format").assertIsDisplayed()
        compose.onNodeWithText("عربي / Arabic").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a widget under the sheet is reached from the widget list`() {
        compose.onNodeWithText("Weather").performScrollTo().performClick()
        compose.onNodeWithText("Show high and low").assertIsDisplayed()
        compose.onNodeWithText("Weather data by Open-Meteo.com. Set your city on the Home screen.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `dragging a widget moves it and nothing overlaps`() {
        val clock = idOf("clock")
        val before = layout.find(clock)!!.box
        compose.onNodeWithTag("widget $clock").performTouchInput { swipe(center, center + Offset(0f, 300f), 400) }
        compose.waitForIdle()
        assertNotEquals(before, layout.find(clock)!!.box)
        assertNoOverlaps()
    }

    @Test
    fun `the corner handle resizes, pushing the widgets below`() {
        val ring = idOf("ring")
        compose.onNodeWithTag("widget $ring").performClick()
        val stat = layout.items.first { it.type == "stat" }.id
        val statBefore = layout.find(stat)!!.box.y
        compose.onNodeWithTag("handle").performTouchInput { swipe(center, center + Offset(0f, 150f), 400) }
        compose.waitForIdle()
        assertTrue(layout.find(ring)!!.box.h > 264)
        assertTrue(layout.find(stat)!!.box.y > statBefore)
        assertNoOverlaps()
    }

    @Test
    fun `the red cross deletes, and Undo brings it back`() {
        val before = layout
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick()
        compose.onNodeWithTag("delete").performClick()
        compose.onNodeWithText("Clock deleted").assertIsDisplayed()
        assertEquals(before.items.size - 1, layout.items.size)
        compose.onNodeWithText("Undo").performClick()
        assertEquals(before, layout)
    }

    @Test
    fun `the add picker adds a widget with a live preview of each kind`() {
        compose.onAllNodesWithText("+ Add widget")[0].performClick()
        compose.onNodeWithText("Add a widget").assertIsDisplayed()
        compose.onNodeWithText("Your next calendar item").performClick()
        assertEquals(DefaultLayout.create().items.size + 1, layout.items.size)
        assertNoOverlaps()
    }

    @Test
    fun `the clock style strip changes the style in one tap`() {
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick()
        compose.onNodeWithTag("strip").performScrollToNode(hasText("Dot matrix"))
        compose.onNodeWithText("Dot matrix").performClick()
        assertEquals("dots", layout.find(idOf("clock"))!!.settings[ClockWidget.STYLE])
    }

    @Test
    fun `More fonts lists the library, and picking one downloads it and selects it`() {
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick()
        compose.onNodeWithTag("strip").performScrollToNode(hasText("More fonts"))
        compose.onNodeWithText("More fonts").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Inter").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Inter").performClick()
        compose.waitUntil(5_000) { layout.find(idOf("clock"))!!.settings[ClockWidget.STYLE] == "g:Inter@500" }
        assertTrue(http.requests.any { it.contains("css2?family=Inter:wght@500") && !it.contains("text=") })
        assertTrue(http.requests.any { it.contains("text=") }) // previews ask only for the characters they show
    }

    @Test
    fun `Done leaves the editor`() {
        compose.onNodeWithText("Done").performClick()
        assertTrue(done)
    }
}
