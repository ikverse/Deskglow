package com.ikverse.deskglow.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.FakeFontsHttp
import com.ikverse.deskglow.display.LiveDisplay
import com.ikverse.deskglow.graph
import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.store.MAX_PAGES
import com.ikverse.deskglow.widgets.EventWidget
import com.ikverse.deskglow.widgets.LocalEditing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The display's several screens: keeping them, and moving between them with two fingers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h848dp-port-xxhdpi")
class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private var exited = false
    private var openedApp = false

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.filesDir.listFiles { f -> f.name.startsWith("layout") }?.forEach(File::delete)
        graph = AppGraph(context, FakeFontsHttp(), FakeFeeds())
    }

    private fun show() = compose.setContent { DeskglowTheme { com.ikverse.deskglow.display.WidgetHost(graph) { LiveDisplay(onExit = { exited = true }, onOpenApp = { openedApp = true }) } } }

    private val portrait = Orientation.Portrait
    private val landscape = Orientation.Landscape

    @Test
    fun `there is one screen until another is added, and no more than the limit`() {
        assertEquals(1, graph.prefs.pageCount(portrait).value)
        repeat(MAX_PAGES - 1) { graph.addPage(portrait) }
        assertEquals(MAX_PAGES, graph.prefs.pageCount(portrait).value)
        assertNull(graph.addPage(portrait))
        assertEquals(MAX_PAGES, graph.prefs.pageCount(portrait).value)
    }

    @Test
    fun `each orientation keeps its own number of screens`() {
        graph.addPage(portrait)
        graph.addPage(portrait)
        graph.addPage(landscape)
        assertEquals(3, graph.prefs.pageCount(portrait).value)
        assertEquals(2, graph.prefs.pageCount(landscape).value)
        graph.deletePage(portrait, 2)
        assertEquals(2, graph.prefs.pageCount(portrait).value)
        assertEquals(2, graph.prefs.pageCount(landscape).value)
    }

    @Test
    fun `an added screen is empty, or a copy of another`() {
        assertEquals(1, graph.addPage(portrait))
        assertEquals(Layout(emptyList()), graph.layoutsFor(portrait, 1).layout.value)
        assertEquals(2, graph.addPage(portrait, copyOf = 0))
        assertEquals(graph.layouts.layout.value, graph.layoutsFor(portrait, 2).layout.value)
        assertEquals(1, graph.prefs.pageCount(landscape).value)
    }

    @Test
    fun `deleting a screen moves the later ones up, and the last one stays`() {
        graph.addPage(portrait, copyOf = 0)
        graph.addPage(portrait)
        val third = graph.layoutsFor(portrait, 2).layout.value
        graph.deletePage(portrait, 1)
        assertEquals(2, graph.prefs.pageCount(portrait).value)
        assertEquals(third, graph.layoutsFor(portrait, 1).layout.value)
        graph.deletePage(portrait, 1)
        graph.deletePage(portrait, 0)
        assertEquals(1, graph.prefs.pageCount(portrait).value)
    }

    @Test
    fun `ensuring screens adds empty ones up to the number asked for`() {
        graph.ensurePages(landscape, 3)
        assertEquals(3, graph.prefs.pageCount(landscape).value)
        graph.ensurePages(landscape, 2)
        assertEquals(3, graph.prefs.pageCount(landscape).value)
    }

    @Test
    fun `the last screen is remembered per orientation, and follows a deletion`() {
        repeat(3) { graph.addPage(portrait) }
        graph.prefs.setLastPage(portrait, 3)
        assertEquals(0, graph.prefs.lastPage(landscape))
        graph.deletePage(portrait, 1)
        assertEquals(2, graph.prefs.lastPage(portrait))
        graph.deletePage(portrait, 2)
        assertEquals(1, graph.prefs.lastPage(portrait))
        graph.prefs.setLastPage(portrait, 9)
        assertEquals(1, graph.prefs.lastPage(portrait))
    }

    @Test
    fun `the display opens on the screen it was left on`() {
        // The first screen is empty and the second holds the only widget, an event one, which under test
        // (calendar not allowed) draws a hint while editing. The hint being drawn with no swipe at all means
        // the display opened on the second. The display reads the app's own graph, not the test's `graph`
        // (which only supplies the feeds), so the screens are set up there.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>().graph
        app.addPage(portrait)
        app.layouts.update(Layout(emptyList()))
        app.layoutsFor(portrait, 1).update(Layout(listOf(WidgetItem("e", EventWidget.id, Box(8, 8, 396, 120), true, EventWidget.defaults))))
        app.prefs.setLastPage(portrait, 1)
        compose.setContent {
            DeskglowTheme {
                CompositionLocalProvider(LocalEditing provides true) {
                    com.ikverse.deskglow.display.WidgetHost(graph) { LiveDisplay(onExit = {}, onOpenApp = {}) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Allow calendar access", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `two fingers moving sideways show the screen dots, and one finger does not`() {
        graph.addPage(portrait)
        show()
        compose.onNodeWithTag("screen dots", useUnmergedTree = true).assertDoesNotExist()
        compose.onRoot().performTouchInput { swipe(Offset(300f, 600f), Offset(40f, 600f)) }
        compose.onNodeWithTag("screen dots", useUnmergedTree = true).assertDoesNotExist()
        compose.onRoot().performTouchInput {
            down(0, Offset(600f, 600f))
            down(1, Offset(700f, 600f))
            moveTo(0, Offset(200f, 600f))
            moveTo(1, Offset(300f, 600f))
            up(0)
            up(1)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("screen dots", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a double tap closes the display, and a single tap does not`() {
        show()
        compose.onRoot().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(false, exited)
        compose.onRoot().performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(500) // past the wait for a third tap
        compose.waitForIdle()
        assertEquals(true, exited)
        assertEquals(false, openedApp)
    }

    private fun showInRoom(dark: Boolean) = compose.setContent {
        DeskglowTheme {
            com.ikverse.deskglow.display.WidgetHost(graph) { LiveDisplay(onExit = {}, onOpenApp = {}, dark = dark, blankAfterMs = 5_000) }
        }
    }

    @Test
    fun `a dark room with no touch fades the display to black, and a touch brings it back`() {
        graph.prefs.setBlankInDark(true)
        showInRoom(dark = true)
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithTag("blanked", useUnmergedTree = true).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.onNodeWithTag("blanked", useUnmergedTree = true).assertExists()
        compose.onRoot().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("blanked", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the display never blanks in a lit room, or with the setting off`() {
        graph.prefs.setBlankInDark(true)
        showInRoom(dark = false)
        compose.mainClock.advanceTimeBy(8_000)
        compose.waitForIdle()
        compose.onNodeWithTag("blanked", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a dark room does not blank a display whose setting is off`() {
        showInRoom(dark = true)
        compose.mainClock.advanceTimeBy(8_000)
        compose.waitForIdle()
        compose.onNodeWithTag("blanked", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a triple tap opens the app instead of just closing`() {
        show()
        compose.onRoot().performTouchInput {
            repeat(3) {
                click()
                advanceEventTime(100)
            }
        }
        compose.waitForIdle()
        assertEquals(true, openedApp)
        assertEquals(false, exited)
    }

    @Test
    fun `a double tap closes the display soon after the second tap lifts, not after a full double-tap interval`() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(250)
        assertEquals(true, exited)
        assertEquals(false, openedApp)
    }

    @Test
    fun `a triple tap with a relaxed pause between taps still opens the app`() {
        show()
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.onRoot().performTouchInput { click() }
            compose.mainClock.advanceTimeBy(150)
        }
        assertEquals(true, openedApp)
        assertEquals(false, exited)
    }
}
