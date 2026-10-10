package com.ikverse.deskglow.widgets

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.hypot
import kotlin.math.min

/**
 * Text drawn as large as fits its box, centred unless [align] says otherwise, sized by the ink itself (not the font's line
 * height), so a clock fills its box the way the mockup's did. [sample] fixes the height so the text
 * does not jump as it changes; with [stableDigits] the width is measured as if every digit were "0",
 * so a narrow "1" does not make the clock grow and shrink through the day.
 */
@Composable
fun FitText(
    text: String,
    typeface: Typeface?,
    color: Color,
    modifier: Modifier = Modifier,
    sample: String = text,
    stableDigits: Boolean = false,
    /** 0 left, 0.5 centre, 1 right: where the text sits when the box is wider than it. */
    align: Float = 0.5f,
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG) }
    // Measuring text is the expensive part, so it is done once per text and font, not on every draw.
    val fit = remember(text, typeface, sample, stableDigits) { measureFit(text, typeface, sample, stableDigits) }
    Canvas(modifier) {
        if (text.isEmpty()) return@Canvas
        val scale = min(size.width / fit.inkWidth, size.height / fit.inkHeight)
        paint.typeface = typeface ?: Typeface.DEFAULT
        paint.color = color.toArgb()
        paint.textSize = 100f * scale
        val x = (size.width - fit.inkWidth * scale) * align - fit.left * scale + fit.centringShift * 2 * align * scale
        val y = (size.height - fit.inkHeight * scale) / 2 - fit.top * scale
        drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
    }
}

/** What [FitText] needs to know about a text, measured at 100 px: the size of its ink and where it sits. */
private class TextFit(val inkWidth: Float, val inkHeight: Float, val left: Float, val top: Float, val centringShift: Float)

private fun measureFit(text: String, typeface: Typeface?, sample: String, stableDigits: Boolean): TextFit {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    paint.typeface = typeface ?: Typeface.DEFAULT
    paint.textSize = 100f
    val bounds = Rect()
    val sampleBounds = Rect()
    val measured = if (stableDigits) text.map { if (it in '0'..'9') '0' else it }.joinToString("") else text
    paint.getTextBounds(measured, 0, measured.length, bounds)
    paint.getTextBounds(sample, 0, sample.length, sampleBounds)
    val top = min(bounds.top, sampleBounds.top).toFloat()
    val bottom = maxOf(bounds.bottom, sampleBounds.bottom).toFloat()
    val shift = if (stableDigits) (paint.measureText(measured) - paint.measureText(text)) / 2 else 0f
    return TextFit(bounds.width().toFloat().coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f), bounds.left.toFloat(), top, shift)
}

/** A drawn clock (see [ClockStyles]) scaled to fit its box and set against [align] (0 left, 1 right). Paths are built once per new time. */
@Composable
fun ArtCanvas(art: ClockArt, color: Color, modifier: Modifier = Modifier, background: Color = Color.Black, align: Float = 0.5f) {
    val paths = remember(art) { art.parts.map { part -> part to pathsOf(part) } }
    Canvas(modifier) {
        val scale = min(size.width / art.width, size.height / art.height)
        val dx = (size.width - art.width * scale) * align - art.left * scale
        val dy = (size.height - art.height * scale) / 2 - art.top * scale
        withTransform({
            translate(dx, dy)
            scale(scale, scale, Offset.Zero)
        }) {
            for ((part, path) in paths) {
                when (part) {
                    is StrokePart -> drawPath(
                        path, if (part.cutout) background else color,
                        style = Stroke(part.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    is FillPart -> drawPath(path, color, alpha = part.alpha)
                    is DotPart -> for (centre in part.centres) {
                        drawCircle(if (part.cutout) background else color, part.radius, Offset(centre.x, centre.y), alpha = part.alpha)
                    }
                }
            }
        }
    }
}

private fun pathsOf(part: ArtPart): Path = Path().apply {
    when (part) {
        is StrokePart -> part.lines.forEach { line ->
            if (part.cornerRadius > 0f) addRounded(line, part.cornerRadius) else addLine(line)
        }
        is FillPart -> part.polygons.forEach { polygon ->
            polygon.forEachIndexed { index, p -> if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        is DotPart -> Unit
    }
}

private fun Path.addLine(line: Polyline) {
    line.points.forEachIndexed { index, p -> if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (line.closed) close()
}

/** A polyline whose corners are cut with curves of [radius] (never more than half of either side). */
private fun Path.addRounded(line: Polyline, radius: Float) {
    val pts = line.points
    val n = pts.size
    if (n < 3 && !line.closed) return addLine(line)
    fun toward(from: Pt, to: Pt, distance: Float): Pt {
        val length = hypot(to.x - from.x, to.y - from.y).coerceAtLeast(0.001f)
        val d = min(distance, length / 2)
        return Pt(from.x + (to.x - from.x) * d / length, from.y + (to.y - from.y) * d / length)
    }
    if (line.closed) {
        for (i in 0 until n) {
            val prev = pts[(i + n - 1) % n]
            val corner = pts[i]
            val next = pts[(i + 1) % n]
            val a = toward(corner, prev, radius)
            val b = toward(corner, next, radius)
            if (i == 0) moveTo(a.x, a.y) else lineTo(a.x, a.y)
            quadraticTo(corner.x, corner.y, b.x, b.y)
        }
        close()
    } else {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until n - 1) {
            val a = toward(pts[i], pts[i - 1], radius)
            val b = toward(pts[i], pts[i + 1], radius)
            lineTo(a.x, a.y)
            quadraticTo(pts[i].x, pts[i].y, b.x, b.y)
        }
        lineTo(pts[n - 1].x, pts[n - 1].y)
    }
}
