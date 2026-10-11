package com.ikverse.deskglow.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.FakeFontsHttp
import com.ikverse.deskglow.display.WidgetHost
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home with the phone on its side: the preview no longer pushes the Start button below the fold. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w848dp-h412dp-land-xxhdpi")
class HomeLandscapeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.filesDir.listFiles { f -> f.name.startsWith("layout") }?.forEach(File::delete)
    }

    @Test
    fun `the Start button and the status chips are on screen without scrolling`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext(), FakeFontsHttp(), FakeFeeds())
        compose.setContent {
            DeskglowTheme { WidgetHost(graph) { Box(Modifier.fillMaxSize().background(Palette.Page)) { HomeScreen(graph, go = {}) } } }
        }
        compose.onNodeWithText("Start Deskglow").assertIsDisplayed()
        compose.onNodeWithText("Edit layout").assertIsDisplayed()
    }
}
