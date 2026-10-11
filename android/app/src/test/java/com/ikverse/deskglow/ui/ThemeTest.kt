package com.ikverse.deskglow.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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

    /** Material's own violet-grey slider track and grey dialogs show unless the roles are mapped to the palette. */
    @Test
    fun `material roles that would show a default colour are mapped to the palette`() {
        var scheme: androidx.compose.material3.ColorScheme? = null
        compose.setContent { DeskglowTheme { scheme = MaterialTheme.colorScheme } }
        compose.waitForIdle()
        assertEquals(Palette.Edge, scheme!!.secondaryContainer)
        assertEquals(Palette.Ink, scheme!!.onSecondaryContainer)
        assertEquals(Palette.Sheet, scheme!!.surfaceContainerHigh)
        assertEquals(Palette.Raised, scheme!!.surfaceContainerHighest)
        assertEquals(Palette.Edge, scheme!!.outlineVariant)
    }
}
