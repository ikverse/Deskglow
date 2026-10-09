package com.ikverse.deskglow.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.store.MAX_PAGES
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
        graph.addPage(portrait)
        graph.prefs.setLastPage(portrait, 1)
        show()
        compose.onRoot().performTouchInput {
            down(0, Offset(600f, 600f)); down(1, Offset(700f, 600f))
            moveTo(0, Offset(200f, 600f)); moveTo(1, Offset(300f, 600f))
            up(0); up(1)
        }
        // Already on the last screen, so a swipe forward stays there, and that is what is remembered.
        assertEquals(1, graph.prefs.lastPage(portrait))
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
}
