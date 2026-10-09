package com.ikverse.deskglow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A circle as path data, for icons drawn on a 24 by 24 grid. */
private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

/** A rounded rectangle as path data, on the same grid. */
private fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r $r v${h - 2 * r}a$r $r 0 0 1 ${-r} $r h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r} v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

/**
 * The app's icons: round-capped lines on a 24 by 24 grid, drawn in code so there is nothing to bundle
 * or keep in step with a library. [strokes] are drawn as lines, [fills] as solid shapes.
 */
enum class Glyph(val strokes: List<String> = emptyList(), val fills: List<String> = emptyList()) {
    Back(listOf("M15 5l-7 7 7 7")),
    ChevronRight(listOf("M9 6l6 6-6 6")),
    ChevronDown(listOf("M6 9l6 6 6-6")),
    ChevronUp(listOf("M6 15l6-6 6 6")),
    Close(listOf("M6 6l12 12M18 6L6 18")),
    Plus(listOf("M12 5v14M5 12h14")),
    Check(listOf("M5 12l5 5 9-10")),
    Undo(listOf("M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3")),
    Redo(listOf("M15 14l5-5-5-5", "M20 9H10a6 6 0 0 0 0 12h3")),
    More(fills = listOf(circle(5f, 12f, 1.8f), circle(12f, 12f, 1.8f), circle(19f, 12f, 1.8f))),
    Trash(listOf("M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3")),
    Play(fills = listOf("M7 4.5l13 7.5-13 7.5z")),
    Sliders(listOf("M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2f), circle(10f, 17f, 2f))),
    Duplicate(listOf(box(8f, 8f, 12f, 12f, 2f), "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2")),
    Eye(listOf("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))),
    EyeOff(listOf("M3 3l18 18", "M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 9 6 9 6a15 15 0 0 1-3 3.4M6.6 6.6C4.3 8.1 3 12 3 12s4 6 9 6a8.9 8.9 0 0 0 4.4-1.2")),
    Sparkle(listOf("M12 3l1.8 4.2L18 9l-4.2 1.8L12 15l-1.8-4.2L6 9l4.2-1.8z", "M18 15l.8 1.9L21 18l-2.2.9L18 21l-.8-2.1L15 18l2.2-1.1z")),
    Grid(listOf(box(4f, 4f, 6.5f, 6.5f, 1.2f), box(13.5f, 4f, 6.5f, 6.5f, 1.2f), box(4f, 13.5f, 6.5f, 6.5f, 1.2f), box(13.5f, 13.5f, 6.5f, 6.5f, 1.2f))),
    Phone(listOf(box(7f, 3f, 10f, 18f, 2f))),
    PhoneSideways(listOf(box(3f, 7f, 18f, 10f, 2f))),
    Archive(listOf(box(3f, 4f, 18f, 5f, 1f), "M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4")),
    Shield(listOf("M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z")),
    Info(listOf(circle(12f, 12f, 9f), "M12 11v5M12 8h.01")),
    Search(listOf(circle(11f, 11f, 6f), "M20 20l-4.5-4.5")),
    Bell(listOf("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10 21h4")),
    Location(listOf("M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z", circle(12f, 10f, 2.5f))),
}

/** A [Glyph] at [size], in [tint]. It carries no description of its own: the button around it names the action. */
@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = Palette.Ink, size: Dp = 22.dp, weight: Float = 1.8f) {
    val strokes = remember(glyph) { glyph.strokes.map { PathParser().parsePathString(it).toPath() } }
    val fills = remember(glyph) { glyph.fills.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            for (path in strokes) drawPath(path, tint, style = Stroke(weight, cap = StrokeCap.Round, join = StrokeJoin.Round))
            for (path in fills) drawPath(path, tint)
        }
    }
}
