package com.ikverse.deskglow.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.FakeFontsHttp
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.Weather
import com.ikverse.deskglow.data.WeatherState
import com.ikverse.deskglow.display.WidgetHost
import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.store.City
import com.ikverse.deskglow.ui.editor.EditorScreen
import com.ikverse.deskglow.widgets.ClockWidget
import com.ikverse.deskglow.widgets.DefaultLayout
import com.ikverse.deskglow.widgets.F1ScheduleWidget
import com.ikverse.deskglow.widgets.F1WeekendWidget
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The app's own screens (Home, the editor, the picker, the setup screens), driven and, with native graphics, saved under build/screens for a look by eye. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h848dp-port-xxhdpi")
class RedesignTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private val feeds = FakeFeeds()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.filesDir.listFiles { f -> f.name.startsWith("layout") }?.forEach(File::delete)
        File(context.filesDir, "font-catalog.json").delete()
        graph = AppGraph(context, FakeFontsHttp(), feeds)
        feeds.weather.value = WeatherState.Ready(City("Cairo", "Cairo, Egypt", 30.06, 31.25), Weather(27.0, 2, true, 28.0, 18.0, 0))
        feeds.f1.value = F1State.Ready(F1Samples.data.copy(track = F1Samples.marinaBay))
        val now = LocalDateTime.ofInstant(LocalDateTime.of(2026, 10, 8, 19, 30).toInstant(ZoneOffset.UTC), ZoneId.systemDefault())
        feeds.minute.value = now
        feeds.second.value = now
    }

    private fun save(name: String) {
        val dir = File("build/screens").apply { mkdirs() }
        runCatching {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { System.err.println("Screen capture unavailable here: $it") }
    }

    private fun home(edit: (Orientation, Int) -> Unit = { _, _ -> }) =
        compose.setContent { DeskglowTheme { WidgetHost(graph) { Box(Modifier.fillMaxSize().background(Palette.Page)) { HomeScreen(graph, go = {}, edit = edit) } } } }

    private fun editor(layout: Layout? = null, orientation: Orientation = Orientation.Portrait) {
        if (layout != null) graph.layoutsFor(orientation).update(layout)
        compose.setContent { DeskglowTheme { WidgetHost(graph) { EditorScreen(graph, orientation) {} } } }
    }

    private val f1Layout = Layout(DefaultLayout.create().items.filter { it.type == "clock" || it.type == "date" } + listOf(
        WidgetItem("w20", F1WeekendWidget.id, Box(24, 232, 372, 112), true, F1WeekendWidget.defaults.with(F1WeekendWidget.SHOW_TRACK, true)),
        WidgetItem("w21", F1ScheduleWidget.id, Box(24, 368, 220, 200), true, F1ScheduleWidget.defaults),
    ))

    // ---- Home ----

    @Test
    fun `home shows the preview, the main button, status chips and the grouped settings`() {
        home()
        compose.onNodeWithText("Start Deskglow").assertIsDisplayed()
        compose.onNodeWithText("Portrait · screen 1 of 1").assertIsDisplayed()
        compose.onNodeWithText("Portrait layout").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Permissions").performScrollTo().assertIsDisplayed()
        save("home")
    }

    @Test
    fun `the preview pages through every screen, and Edit layout opens the one on show`() {
        graph.addPage(Orientation.Portrait)
        var opened: Pair<Orientation, Int>? = null
        home { orientation, page -> opened = orientation to page }
        compose.onNodeWithText("Portrait · screen 1 of 2").assertIsDisplayed()
        compose.onNodeWithTag("preview pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Portrait · screen 2 of 2").assertIsDisplayed()
        compose.onNodeWithText("Edit layout").performClick()
        assertEquals(Orientation.Portrait to 1, opened)
        compose.onNodeWithText("Landscape").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Landscape · screen 1 of 1").assertIsDisplayed()
        save("home-landscape-page")
    }

    @Test
    fun `the tabs under the preview switch between the portrait and landscape layouts`() {
        graph.addPage(Orientation.Portrait)
        var opened: Pair<Orientation, Int>? = null
        home { orientation, page -> opened = orientation to page }
        compose.onNodeWithText("Portrait · screen 1 of 2").assertIsDisplayed()
        // Swiping stays inside the layout: past the last portrait screen there is nothing more.
        compose.onNodeWithTag("preview pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("preview pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Portrait · screen 2 of 2").assertIsDisplayed()
        compose.onNodeWithText("Landscape").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Landscape · screen 1 of 1").assertIsDisplayed()
        compose.onNodeWithText("Edit layout").performClick()
        assertEquals(Orientation.Landscape to 0, opened)
        // Back on Portrait it is still on the screen it was left on.
        compose.onNodeWithText("Portrait").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Portrait · screen 2 of 2").assertIsDisplayed()
    }

    @Test
    fun `the layout rows open their editors`() {
        var opened: Pair<Orientation, Int>? = null
        home { orientation, page -> opened = orientation to page }
        compose.onNodeWithText("Landscape layout").performScrollTo().performClick()
        assertEquals(Orientation.Landscape to 0, opened)
    }

    // ---- the editor, as pictures ----

    @Test
    fun `the editor opens with a big canvas, the dock and a sheet that is only its tabs`() {
        editor(f1Layout)
        compose.waitForIdle()
        save("editor-start")
    }

    @Test
    fun `a selected widget is lit, with its action pill, and the sheet half up on its settings`() {
        editor(f1Layout)
        compose.onNodeWithTag("widget w20").performClick()
        compose.waitForIdle()
        save("editor-selected")
    }

    @Test
    fun `the sheet pulled to full height shows the widget again, with its layouts as pictures`() {
        editor(f1Layout)
        compose.onNodeWithTag("widget w20").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sheet").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(centerX, 30f), androidx.compose.ui.geometry.Offset(centerX, -500f), 100) }
        compose.waitForIdle()
        save("editor-sheet-full")
        compose.onNodeWithText("Hero · big track").performScrollTo()
        save("editor-sheet-layouts")
    }

    @Test
    fun `the widget list with its previews, eyes and bins`() {
        editor(f1Layout)
        compose.onNodeWithText("Widgets").performClick()
        compose.waitForIdle()
        save("editor-widgets-tab")
    }

    @Test
    fun `the picker with search, categories and every widget at one scale`() {
        editor(f1Layout)
        compose.onNodeWithContentDescription("Add widget").performClick()
        compose.waitForIdle()
        save("picker")
        compose.onNodeWithTag("picker search").performTextInput("f1")
        compose.waitForIdle()
        save("picker-search")
    }

    @Test
    fun `the screen switcher and the more menu`() {
        editor(f1Layout)
        compose.onNodeWithTag("screens").performClick()
        compose.waitForIdle()
        save("editor-switcher")
    }

    @Test
    fun `the landscape editor with its side panel`() {
        editor(null, Orientation.Landscape)
        compose.onNodeWithTag("widget ${graph.landscapeLayouts.layout.value.items.first { it.type == ClockWidget.id }.id}").performClick()
        compose.waitForIdle()
        save("editor-landscape-in-portrait")
    }

    // ---- the setup screens ----

    private fun screen(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { DeskglowTheme { WidgetHost(graph) { Box(Modifier.fillMaxSize().background(Palette.Page)) { content() } } } }

    @Test
    fun `the setup screens use cards and real buttons`() {
        screen { PermissionsScreen {} }
        compose.waitForIdle()
        save("setup-permissions")
    }

    @Test
    fun `the brightness and city and saved layouts screens`() {
        screen { BrightnessScreen(graph) {} }
        compose.waitForIdle()
        save("setup-brightness")
    }

    @Test
    fun `saved layouts and city`() {
        graph.snapshots.save("Evening", graph.pagesOf(Orientation.Portrait), graph.pagesOf(Orientation.Landscape))
        screen { SnapshotsScreen(graph) {} }
        compose.waitForIdle()
        save("setup-saved-layouts")
    }
}
