package com.ikverse.deskglow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The app's few colours: plain text on near-black, thin rules, one blue for "selected" and one red for "delete". */
object Palette {
    val Page = Color(0xFF0B0B0B)
    val Sheet = Color(0xFF141414)
    val Raised = Color(0xFF1D1D1D)
    val Rule = Color(0xFF262626)
    val Ink = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8C8C8C)
    val Select = Color(0xFF4F9DFF)
    val Danger = Color(0xFFF23645)
    val Accent = Color(0xFF44B98A)
}

@Composable
fun DeskglowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Select,
            onPrimary = Color.White,
            background = Palette.Page,
            onBackground = Palette.Ink,
            surface = Palette.Sheet,
            onSurface = Palette.Ink,
            surfaceVariant = Palette.Raised,
            onSurfaceVariant = Palette.Muted,
            outline = Palette.Rule,
            error = Palette.Danger,
        ),
    ) {
        // Plain text takes its colour from here. Material leaves it black until a Surface sets it,
        // which made every title black on the near-black page.
        CompositionLocalProvider(LocalContentColor provides Palette.Ink, content = content)
    }
}

/** A thin dividing line, the app's only separator. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Rule))
}
