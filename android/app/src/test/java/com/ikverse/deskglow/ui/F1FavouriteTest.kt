package com.ikverse.deskglow.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.FakeFontsHttp
import com.ikverse.deskglow.data.F1Favourite
import com.ikverse.deskglow.store.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The driver and team the F1 widgets follow: picked on one screen, kept, and read back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h848dp-port-xxhdpi")
class F1FavouriteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        graph = AppGraph(context, FakeFontsHttp(), FakeFeeds())
    }

    @Test
    fun `a driver and a team are picked on the screen, and none clears them`() {
        compose.setContent { DeskglowTheme { F1Screen(graph) { } } }
        assertEquals(F1Favourite(), graph.prefs.f1Favourite.value)
        compose.onNodeWithText("VER \u00b7 Max Verstappen").performScrollTo().performClick()
        assertEquals("VER", graph.prefs.f1Favourite.value.driver)
        compose.onNodeWithText("Ferrari").performScrollTo().performClick()
        assertEquals(F1Favourite("VER", "ferrari"), graph.prefs.f1Favourite.value)
        compose.onNodeWithText("HAM \u00b7 Lewis Hamilton").performScrollTo().performClick()
        assertEquals("HAM", graph.prefs.f1Favourite.value.driver)
        assertEquals("ferrari", graph.prefs.f1Favourite.value.team)
    }

    @Test
    fun `what is picked is kept for the next start`() {
        graph.prefs.setF1Driver("NOR")
        graph.prefs.setF1Team("mclaren")
        val again = AppPrefs(ApplicationProvider.getApplicationContext())
        assertEquals(F1Favourite("NOR", "mclaren"), again.f1Favourite.value)
        again.setF1Driver("")
        assertEquals(F1Favourite("", "mclaren"), AppPrefs(ApplicationProvider.getApplicationContext()).f1Favourite.value)
    }
}
