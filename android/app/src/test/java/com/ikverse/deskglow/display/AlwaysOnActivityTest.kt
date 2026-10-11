package com.ikverse.deskglow.display

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The always-on display must survive the phone rotating just as it wakes, which recreates it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlwaysOnActivityTest {
    @Test
    fun `rotating while the screen is on recreates it without finishing it`() {
        val controller = Robolectric.buildActivity(AlwaysOnActivity::class.java).setup()
        val first = controller.get()
        // What a rotation does: stops the old instance while the screen is still on.
        controller.configurationChange(Configuration(first.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
        assertFalse(first.isFinishing)
    }
}
