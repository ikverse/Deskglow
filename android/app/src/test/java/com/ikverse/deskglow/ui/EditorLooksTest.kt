package com.ikverse.deskglow.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.FakeFontsHttp
import com.ikverse.deskglow.display.WidgetHost
import com.ikverse.deskglow.ui.editor.EditorScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The editor in the states where its layout is under strain: a narrow phone, large text, Arabic numerals.
 * Each test checks that the screen stands up and saves a frame under build/screens for a look by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorLooksTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(context.filesDir, "layout.json").delete()
        File(context.filesDir, "font-catalog.json").delete()
        graph = AppGraph(context, FakeFontsHttp(), FakeFeeds())
    }

    private fun open() {
        compose.setContent { DeskglowTheme { WidgetHost(graph) { EditorScreen(graph) {} } } }
        compose.waitForIdle()
    }

    private fun selectClock() {
        val clock = graph.layouts.layout.value.items.first { it.type == "clock" }.id
        compose.onNodeWithTag("widget $clock").performClick().performClick()
    }

    private fun save(name: String) {
        compose.waitForIdle() // let a tap's recomposition finish before the frame is taken
        val dir = File("build/screens").apply { mkdirs() }
        runCatching {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { System.err.println("Screen capture unavailable here: $it") }
    }

    @Test
    @Config(qualifiers = "w412dp-h848dp-xxhdpi")
    fun `the colour swatches, with their larger touch targets`() {
        open()
        selectClock()
        compose.onNodeWithContentDescription("Green").performScrollTo()
        save("editor-swatches")
    }

    @Test
    @Config(qualifiers = "w412dp-h848dp-xxhdpi")
    fun `seven-segment is greyed out with its reason shown in full when Arabic numerals are on`() {
        open()
        selectClock()
        compose.onNodeWithText("عربي / Arabic").performScrollTo().performClick()
        compose.onNodeWithTag("strip").performScrollToNode(hasText("Seven-segment · Western only"))
        save("editor-arabic-strip")
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun `the font list on a narrow phone scrolls its category buttons instead of losing them`() {
        open()
        selectClock()
        compose.onNodeWithTag("strip").performScrollToNode(hasText("More fonts"))
        compose.onNodeWithText("More fonts").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Inter").fetchSemanticsNodes().isNotEmpty() }
        save("editor-more-fonts-narrow")
    }

    @Test
    @Config(qualifiers = "w412dp-h848dp-xxhdpi")
    fun `the editor at twice the text size`() {
        RuntimeEnvironment.setFontScale(2f)
        open()
        save("editor-large-text")
        selectClock()
        save("editor-large-text-settings")
    }
}
