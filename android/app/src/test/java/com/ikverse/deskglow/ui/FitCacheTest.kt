package com.ikverse.deskglow.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.display.DisplayContent
import com.ikverse.deskglow.fonts.FontResolver
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.widgets.DefaultLayout
import com.ikverse.deskglow.widgets.FitStats
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A layout drawn a second time (leaving a screen and coming back) finds its sizes in the shared cache. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h848dp-xxhdpi")
class FitCacheTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a layout drawn twice searches its sizes only once`() {
        val feeds = FakeFeeds()
        feeds.minute.value = LocalDateTime.of(2026, 10, 7, 20, 5)
        feeds.second.value = LocalDateTime.of(2026, 10, 7, 20, 5)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val layout = DefaultLayout.create(Orientation.Portrait)
        val on = mutableStateOf(true)
        FitStats.reset()
        compose.setContent {
            CompositionLocalProvider(LocalFeeds provides feeds, LocalFonts provides FontResolver(context, null)) {
                DeskglowTheme { if (on.value) DisplayContent(layout, burnIn = false, orientation = Orientation.Portrait) }
            }
        }
        compose.waitForIdle()
        val first = FitStats.searches
        assertTrue("the first pass should have searched", first > 0)

        on.value = false
        compose.waitForIdle()
        on.value = true
        compose.waitForIdle()
        assertEquals("the second pass should reuse every result", first, FitStats.searches)
    }
}
