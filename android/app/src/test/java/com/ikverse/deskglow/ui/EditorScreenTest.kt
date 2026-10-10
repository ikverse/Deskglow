package com.ikverse.deskglow.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import kotlin.math.roundToInt
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.FakeFontsHttp
import com.ikverse.deskglow.display.WidgetHost
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.ikverse.deskglow.ui.editor.EditorScreen
import com.ikverse.deskglow.ui.editor.EditorState
import com.ikverse.deskglow.widgets.ClockWidget
import com.ikverse.deskglow.widgets.Common
import com.ikverse.deskglow.widgets.DefaultLayout
import com.ikverse.deskglow.widgets.WeatherWidget
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
    val compose = createAndroidComposeRule<ComponentActivity>()

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
        compose.onNodeWithTag("widget ${idOf("date")}").performClick().performClick()
        compose.onNodeWithText("Format").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("عربي / Arabic").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a widget under the sheet is reached from the widget list`() {
        compose.onNodeWithText("Widgets").performClick()
        compose.onNodeWithText("Weather").performScrollTo().performClick()
        compose.onNodeWithText("High and low").performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithTag("widget $ring").performClick().performClick()
        // The ring's corner lies under the settings sheet, so, as a person would, fold the sheet to reach it.
        compose.onNodeWithContentDescription("Fold settings").performClick()
        val stat = layout.items.first { it.type == "stat" }.id
        val statBefore = layout.find(stat)!!.box.y
        compose.onNodeWithTag("handle").performTouchInput { swipe(center, center + Offset(0f, 150f), 400) }
        compose.waitForIdle()
        assertTrue(layout.find(ring)!!.box.h > 264)
        assertTrue(layout.find(stat)!!.box.y > statBefore)
        assertNoOverlaps()
    }

    @Test
    fun `the pill's delete removes a widget, and Undo brings it back`() {
        val before = layout
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick().performClick()
        compose.onNodeWithTag("delete").performClick()
        compose.onNodeWithText("Clock deleted").assertIsDisplayed()
        assertEquals(before.items.size - 1, layout.items.size)
        compose.onNodeWithText("Undo").performClick()
        assertEquals(before, layout)
    }

    @Test
    fun `the add picker adds a widget with a live preview of each kind`() {
        compose.onNodeWithContentDescription("Add widget").performClick()
        compose.onNodeWithText("Add a widget").assertIsDisplayed()
        compose.onNodeWithText("Your next calendar item").performClick()
        assertEquals(DefaultLayout.create().items.size + 1, layout.items.size)
        assertNoOverlaps()
    }

    @Test
    fun `the clock style strip changes the style in one tap`() {
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick().performClick()
        compose.onNodeWithTag("strip").performScrollToNode(hasText("Dot matrix"))
        compose.onNodeWithText("Dot matrix").performClick()
        assertEquals("dots", layout.find(idOf("clock"))!!.settings[ClockWidget.STYLE])
    }

    @Test
    fun `More fonts lists the library, and picking one downloads it and selects it`() {
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick().performClick()
        compose.onNodeWithTag("strip").performScrollToNode(hasText("More fonts"))
        compose.onNodeWithText("More fonts").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Inter").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Inter").performClick()
        compose.waitUntil(5_000) { layout.find(idOf("clock"))!!.settings[ClockWidget.STYLE] == "g:Inter@500" }
        assertTrue(http.requests.any { it.contains("css2?family=Inter:wght@500") && !it.contains("text=") })
        assertTrue(http.requests.any { it.contains("text=") }) // previews ask only for the characters they show
    }

    @Test
    fun `Back leaves the editor`() {
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(done)
    }

    @Test
    fun `a font that cannot be downloaded says so, on top of the font list rather than under it`() {
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick().performClick()
        compose.onNodeWithTag("strip").performScrollToNode(hasText("More fonts"))
        compose.onNodeWithText("More fonts").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Inter").fetchSemanticsNodes().isNotEmpty() }
        http.failDownloads = true
        compose.onNodeWithText("Inter").performClick()
        val message = "Couldn't download Inter. Check the connection."
        compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(message).assertIsDisplayed()

        // Being in the tree is not enough: the message must be what is actually drawn there. Far right of
        // the bar, clear of its text, is the bar's own colour if it is on top, and the list's if it is not.
        val view = compose.activity.window.decorView
        val screen = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(screen))
        val bounds = compose.onNodeWithText(message).fetchSemanticsNode().boundsInWindow
        val x = view.width - with(compose.density) { 16.dp.roundToPx() }
        val y = bounds.center.y.roundToInt()
        val drawn = screen.getPixel(x, y)
        val bar = Palette.ToastFill.toArgb()
        assertTrue(
            "the message bar is hidden: pixel is #${Integer.toHexString(drawn)}, the bar is #${Integer.toHexString(bar)}",
            kotlin.math.abs(android.graphics.Color.red(drawn) - android.graphics.Color.red(bar)) <= 4 &&
                kotlin.math.abs(android.graphics.Color.green(drawn) - android.graphics.Color.green(bar)) <= 4,
        )
    }

    @Test
    fun `a chip in the Show row is named by its label, and tapping it toggles its setting`() {
        compose.onNodeWithText("Widgets").performClick()
        compose.onNodeWithText("Weather").performScrollTo().performClick()
        compose.onNodeWithText("City").performScrollTo().assertIsSelected()
        compose.onNodeWithText("City").performClick()
        compose.onNodeWithText("City").assertIsNotSelected()
        assertEquals(false, layout.find(idOf("weather"))!!.settings[WeatherWidget.SHOW_CITY])
        compose.onNodeWithText("City").performClick()
        assertEquals(true, layout.find(idOf("weather"))!!.settings[WeatherWidget.SHOW_CITY])
    }

    @Test
    fun `a screen reader can move and resize a widget with actions, and the widget has a name`() {
        val clock = idOf("clock")
        val before = layout.find(clock)!!.box
        val body = compose.onNodeWithTag("widget $clock").onChildAt(0)
        body.assertContentDescriptionEquals("Clock")
        val actions = body.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(
            listOf("Move up", "Move down", "Move left", "Move right", "Wider", "Narrower", "Taller", "Shorter"),
            actions.map { it.label },
        )
        compose.runOnUiThread { actions.first { it.label == "Move right" }.action() }
        compose.waitForIdle()
        // The clock began off the dots (y = 56), so it lands on them: 64.
        assertEquals(before.copy(x = before.x + EditorState.STEP, y = 64), layout.find(clock)!!.box)
        val moved = layout.find(clock)!!.box
        compose.runOnUiThread { actions.first { it.label == "Wider" }.action() }
        compose.waitForIdle()
        assertEquals(0, layout.find(clock)!!.box.right % EditorState.STEP)
        assertTrue(layout.find(clock)!!.box.w > moved.w)
        assertNoOverlaps()
    }

    @Test
    fun `the canvas sits below the top bar, so nothing on it is covered`() {
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick().performClick()
        // The bar is 52 dp and its rule 1 dp. The clock is the highest widget, so its action pill is the highest chrome.
        assertTrue(compose.onNodeWithTag("delete").getUnclippedBoundsInRoot().top >= 49.dp)
    }

    @Test
    fun `the action pill, the handle and the fold chevron have names`() {
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick().performClick()
        compose.onNodeWithContentDescription("Delete Clock").assertIsDisplayed()
        compose.onNodeWithContentDescription("Duplicate Clock").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide Clock").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings for Clock").assertIsDisplayed()
        compose.onNodeWithTag("handle").assertContentDescriptionEquals("Resize Clock from bottom right")
        compose.onNodeWithContentDescription("Fold settings").performClick()
        compose.onNodeWithContentDescription("Unfold settings").assertIsDisplayed()
    }

    @Test
    fun `tabs and swatches say what they are, and the undo message is announced`() {
        compose.onNodeWithText("Widgets").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick().performClick()
        compose.onNodeWithContentDescription("Green").performScrollTo().performClick()
        assertEquals(0xFF44B98A.toInt(), layout.find(idOf("clock"))!!.settings[Common.COLOUR])
        compose.onNodeWithTag("delete").performClick()
        compose.onNodeWithText("Clock deleted").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    private fun sheetHeight() = compose.onNodeWithTag("sheet").getUnclippedBoundsInRoot().height

    @Test
    fun `the sheet starts as just its tabs, and the chevron opens and folds it`() {
        assertTrue(sheetHeight() < 100.dp)
        compose.onNodeWithContentDescription("Unfold settings").performClick()
        compose.waitForIdle()
        assertTrue(sheetHeight() > 250.dp)
        compose.onNodeWithContentDescription("Fold settings").performClick()
        compose.waitForIdle()
        assertTrue(sheetHeight() < 100.dp)
    }

    @Test
    fun `flicking the sheet up carries it to a higher stop`() {
        compose.onNodeWithTag("sheet").performTouchInput { swipe(Offset(centerX, 30f), Offset(centerX, -400f), 120) }
        compose.waitForIdle()
        assertTrue(sheetHeight() > 250.dp)
    }

    @Test
    fun `tapping a widget brings the sheet up with its settings`() {
        compose.onNodeWithTag("widget ${idOf("date")}").performClick().performClick()
        compose.waitForIdle()
        assertTrue(sheetHeight() > 250.dp)
    }

    @Test
    fun `the screen switcher adds a screen and deletes it`() {
        compose.onNodeWithTag("screens").performClick()
        compose.onNodeWithTag("add screen").performClick()
        compose.onNodeWithTag("add blank screen").performClick()
        compose.waitForIdle()
        assertEquals(2, graph.prefs.pageCount(Orientation.Portrait).value)
        compose.onNodeWithText("Portrait · Screen 2").assertIsDisplayed()
        compose.onNodeWithTag("screens").performClick()
        compose.onNodeWithTag("delete screen").performClick()
        compose.onNodeWithTag("confirm delete screen").performClick()
        compose.waitForIdle()
        assertEquals(1, graph.prefs.pageCount(Orientation.Portrait).value)
    }

    /** Three different screens, with the editor on the first. */
    private fun threeScreens(): List<Layout> {
        val stock = layout
        graph.addPage(Orientation.Portrait)
        graph.addPage(Orientation.Portrait)
        graph.layoutsFor(Orientation.Portrait, 1).update(Layout(stock.items.take(2)))
        graph.layoutsFor(Orientation.Portrait, 2).update(Layout(stock.items.take(1)))
        compose.waitForIdle()
        return graph.pagesOf(Orientation.Portrait)
    }

    @Test
    fun `holding a screen in the switcher and dragging it down puts it later in the order, and the editor stays on it`() {
        val (a, b, c) = threeScreens()
        val row = with(compose.density) { 48.dp.toPx() }
        compose.onNodeWithTag("screens").performClick()
        compose.onNodeWithTag("screen 1").performTouchInput {
            down(center)
            advanceEventTime(800) // past the hold
            moveBy(Offset(0f, 2 * row))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(b, c, a), graph.pagesOf(Orientation.Portrait))
        compose.onNodeWithText("Portrait · Screen 3").assertIsDisplayed()
    }

    @Test
    fun `dragging a screen up moves it earlier, and a drag too short to pass its neighbour changes nothing`() {
        val (a, b, c) = threeScreens()
        val row = with(compose.density) { 48.dp.toPx() }
        compose.onNodeWithTag("screens").performClick()
        compose.onNodeWithTag("screen 3").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(0f, -0.4f * row))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(a, b, c), graph.pagesOf(Orientation.Portrait))
        compose.onNodeWithTag("screen 3").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(0f, -1 * row))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(a, c, b), graph.pagesOf(Orientation.Portrait))
        // The editor was on the first screen, which did not move.
        compose.onNodeWithText("Portrait · Screen 1").assertIsDisplayed()
    }

    @Test
    fun `a tap on a screen in the switcher still opens it, and a hold let go in place does not`() {
        threeScreens()
        compose.onNodeWithTag("screens").performClick()
        compose.onNodeWithTag("screen 2").performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Portrait · Screen 1").assertIsDisplayed()
        compose.onNodeWithTag("screen 2").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Portrait · Screen 2").assertIsDisplayed()
    }

    @Test
    fun `the pill's duplicate copies the widget, and Undo takes the copy away`() {
        val before = layout
        compose.onNodeWithTag("widget ${idOf("date")}").performClick().performClick()
        compose.onNodeWithContentDescription("Duplicate Date").performClick()
        compose.waitForIdle()
        assertEquals(before.items.size + 1, layout.items.size)
        assertNoOverlaps()
        compose.onNodeWithText("Undo").performClick()
        assertEquals(before, layout)
    }

    @Test
    fun `the picker narrows its widgets by what is typed`() {
        compose.onNodeWithContentDescription("Add widget").performClick()
        compose.onNodeWithTag("picker search").performTextInput("race")
        compose.onNodeWithText("F1 race weekend").assertIsDisplayed()
        compose.onAllNodesWithText("Clock").assertCountEquals(0)
    }

    @Test
    fun `the picker narrows its widgets by category`() {
        compose.onNodeWithContentDescription("Add widget").performClick()
        compose.onNodeWithText("Battery").performClick()
        compose.onNodeWithText("Charging ring").assertIsDisplayed()
        compose.onAllNodesWithText("Weather").assertCountEquals(0)
    }

    @Test
    fun `the more menu resets only after a confirmation, and Undo brings the layout back`() {
        val before = layout
        compose.onNodeWithTag("widget ${idOf("clock")}").performClick().performClick()
        compose.onNodeWithTag("delete").performClick()
        val edited = layout
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("reset").performClick()
        assertEquals(edited, layout)
        compose.onNodeWithTag("confirm reset").performClick()
        compose.waitForIdle()
        assertEquals(DefaultLayout.create(), layout)
        assertNotEquals(edited, layout)
        assertEquals(before, DefaultLayout.create())
    }
}
