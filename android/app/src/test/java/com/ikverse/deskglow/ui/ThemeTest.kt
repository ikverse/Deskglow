package com.ikverse.deskglow.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule
    val compose = createComposeRule()

    /** Found on the phone: without this, every title and label was black on the near-black page. */
    @Test
    fun `plain text is white unless told otherwise`() {
        var colour = Color.Unspecified
        compose.setContent { DeskglowTheme { colour = LocalContentColor.current } }
        compose.waitForIdle()
        assertEquals(Palette.Ink, colour)
    }
}
