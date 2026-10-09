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

/**
 * The app's colours: warm near-black surfaces, a warm off-white for text, and one amber that glows.
 * Amber marks the active thing (the selected widget, the current screen, the main button) and nothing else.
 */
object Palette {
    val Page = Color(0xFF000000)
    val Sheet = Color(0xFF0F0E0C)
    val Raised = Color(0xFF181614)
    val Rule = Color(0xFF26231F)
    val Ink = Color(0xFFEDE6DA)
    val Muted = Color(0xFF8A8378)
    val Select = Color(0xFFFFB547)
    val Danger = Color(0xFFFF6B5A)
    val Accent = Color(0xFFFFB547)
    /** Text and marks drawn on an amber fill. */
    val OnAccent = Color(0xFF1C1205)
    /** The soft halo round the active element. */
    val Glow = Color(0x55FFB547)

    /** The outline of a tile in a strip. */
    val Edge = Color(0xFF2E2A25)
    /** The outline of a colour swatch. */
    val EdgeStrong = Color(0xFF4A453D)
    /** The message bar that carries Undo, and its outline. */
    val ToastFill = Color(0xFF232019)
    val ToastEdge = Color(0xFF38342C)
    /** The editor's top bar: near-black, a little see-through. */
    val Bar = Color(0xF00F0E0C)
}

@Composable
fun DeskglowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Select,
            onPrimary = Palette.OnAccent,
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

/** A thin dividing line. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Rule))
}
