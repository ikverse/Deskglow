package com.ikverse.deskglow.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.FakeFontsHttp
import com.ikverse.deskglow.display.WidgetHost
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.ui.editor.EditorScreen
import com.ikverse.deskglow.widgets.StatWidget
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The canvas is a drawing in fixed coordinates, so a phone set to Arabic or Hebrew (right to left) must
 * not mirror it: a widget the layout puts on the left stays on the left, and dragging it right moves it right.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h848dp-xxhdpi")
class EditorRtlTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(context.filesDir, "layout.json").delete()
        graph = AppGraph(context, FakeFontsHttp(), FakeFeeds())
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                DeskglowTheme { WidgetHost(graph) { EditorScreen(graph) {} } }
            }
        }
    }

    private val layout: Layout get() = graph.layouts.layout.value
    private fun idOf(type: String) = layout.items.first { it.type == type }.id
    private fun stat(metric: String) = layout.items.first { it.type == "stat" && it.settings[StatWidget.METRIC] == metric }.id
    private fun left(id: String) = compose.onNodeWithTag("widget $id").getUnclippedBoundsInRoot().left

    @Test
    fun `a widget the layout puts on the left is on the left, not mirrored`() {
        // In the default layout the temperature stat sits at x = 20 and the time-to-full stat at x = 280.
        assertTrue(left(stat("temp")) < left(stat("time")))
    }

    @Test
    fun `dragging a widget to the right moves it to the right`() {
        val clock = idOf("clock")
        val before = left(clock)
        compose.onNodeWithTag("widget $clock").performTouchInput { swipe(center, center + Offset(150f, 0f), 400) }
        compose.waitForIdle()
        assertTrue("the widget went from $before to ${left(clock)}", left(clock) > before)
    }
}
