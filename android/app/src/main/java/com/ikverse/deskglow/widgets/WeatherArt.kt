package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.ikverse.deskglow.data.Sky
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The weather drawings. Every shape is laid out on a unit square and scaled to the canvas, so an
 * icon looks the same at 20 pixels in a ticker and at 300 behind a poster temperature.
 *
 * Two tones: the sun, the moon and lightning take the accent colour; clouds and what falls from
 * them take a softened version of the text colour, so a cloud in front of the sun reads as nearer.
 * Where one shape overlaps another a thin black gap is cut first, which keeps them apart on the
 * black display without needing any shading.
 */

/** The weather icon in [accent] (sun, moon, lightning) and [ink] (clouds, rain, snow, fog). */
@Composable
fun WeatherIcon(sky: Sky, day: Boolean, accent: Color, modifier: Modifier, outline: Boolean = false, ink: Color = Color.White) {
    Canvas(modifier) {
        val art = WeatherArt(this, size.minDimension, accent, lerp(ink, Color.Black, 0.18f), outline)
        art.draw(sky, day)
    }
}

private class WeatherArt(val scope: DrawScope, val s: Float, val accent: Color, val ink: Color, val outline: Boolean) {
    private val line = s * 0.045f
    private val stroke = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val gap = Stroke(line * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val style get() = if (outline) stroke else Fill

    private fun p(x: Float, y: Float) = Offset(x * s, y * s)

    fun draw(sky: Sky, day: Boolean) = with(scope) {
        when (sky) {
            Sky.Clear -> if (day) sun(0.5f, 0.5f, 0.19f) else night(0.5f, 0.5f, 0.27f, stars = true)
            Sky.PartlyCloudy -> {
                if (day) sun(0.36f, 0.36f, 0.135f) else night(0.38f, 0.34f, 0.19f, stars = false)
                cloud(0.08f, 0.1f, 0.86f, cutGap = true)
            }
            Sky.Cloudy -> {
                // A second, dimmer cloud behind gives depth.
                cloud(0.2f, -0.06f, 0.7f, colour = lerp(ink, Color.Black, 0.45f))
                cloud(0.0f, 0.08f, 0.9f, cutGap = true)
            }
            Sky.Fog -> {
                val rows = listOf(0.2f to 0.7f, 0.3f to 0.84f, 0.16f to 0.62f, 0.36f to 0.8f)
                rows.forEachIndexed { i, (from, to) ->
                    val y = 0.36f + i * 0.13f
                    drawLine(ink, p(from, y), p(to, y), s * 0.055f, StrokeCap.Round)
                }
            }
            Sky.Drizzle -> {
                cloud(0.0f, -0.1f, 0.9f)
                listOf(0.36f to 0.74f, 0.5f to 0.84f, 0.64f to 0.76f, 0.43f to 0.92f, 0.57f to 0.95f).forEach { (x, y) ->
                    drawCircle(ink, s * 0.03f, p(x, y))
                }
            }
            Sky.Rain -> {
                cloud(0.0f, -0.1f, 0.9f)
                // Slanted dashes of mixed lengths, so the rain looks like it is falling, not printed.
                listOf(Triple(0.38f, 0.72f, 0.14f), Triple(0.52f, 0.76f, 0.2f), Triple(0.66f, 0.72f, 0.12f), Triple(0.45f, 0.9f, 0.07f)).forEach { (x, y, len) ->
                    drawLine(ink, p(x, y), p(x - len * 0.35f, y + len), s * 0.045f, StrokeCap.Round)
                }
            }
            Sky.Snow -> {
                cloud(0.0f, -0.1f, 0.9f)
                listOf(Triple(0.36f, 0.8f, 0.055f), Triple(0.56f, 0.86f, 0.065f), Triple(0.72f, 0.77f, 0.045f)).forEach { (x, y, r) -> flake(x, y, r) }
            }
            Sky.Storm -> {
                cloud(0.0f, -0.1f, 0.9f)
                val points = listOf(0.55f to 0.5f, 0.38f to 0.76f, 0.5f to 0.76f, 0.42f to 0.98f, 0.68f to 0.66f, 0.55f to 0.66f, 0.64f to 0.5f)
                val bolt = Path().apply {
                    points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
                    close()
                }
                // Cut out of the cloud: a black edge first, then the bolt.
                drawPath(bolt, Color.Black, style = gap)
                drawPath(bolt, Color.Black)
                drawPath(bolt, accent, style = style)
            }
        }
    }

    /** A disc with eight tapered rays standing off it. */
    private fun DrawScope.sun(cx: Float, cy: Float, r: Float) {
        val centre = p(cx, cy)
        drawCircle(accent, r * s, centre, style = style)
        for (i in 0 until 8) {
            val a = i * PI / 4 + PI / 8
            val c = cos(a).toFloat()
            val n = sin(a).toFloat()
            val inner = r * 1.42f
            val outer = r * (if (i % 2 == 0) 2.05f else 1.8f)
            if (outline) {
                drawLine(accent, p(cx + c * inner, cy + n * inner), p(cx + c * outer, cy + n * outer), line, StrokeCap.Round)
            } else {
                // A wedge: wide at the disc, narrowing to a point.
                val half = r * 0.2f
                val ray = Path().apply {
                    moveTo((cx + c * inner - n * half) * s, (cy + n * inner + c * half) * s)
                    lineTo((cx + c * outer) * s, (cy + n * outer) * s)
                    lineTo((cx + c * inner + n * half) * s, (cy + n * inner - c * half) * s)
                    close()
                }
                drawPath(ray, accent)
            }
        }
    }

    /** A crescent moon, with two small four-point stars on a clear night. */
    private fun DrawScope.night(cx: Float, cy: Float, r: Float, stars: Boolean) {
        val disc = Path().apply { addOval(Rect(p(cx, cy), r * s)) }
        val bite = Path().apply { addOval(Rect(p(cx + r * 0.5f, cy - r * 0.32f), r * 0.82f * s)) }
        drawPath(Path.combine(PathOperation.Difference, disc, bite), accent, style = style)
        if (stars) {
            sparkle(cx + r * 0.95f, cy + r * 0.2f, r * 0.26f)
            sparkle(cx + r * 0.35f, cy - r * 1.05f, r * 0.17f)
        }
    }

    private fun DrawScope.sparkle(cx: Float, cy: Float, r: Float) {
        val c = p(cx, cy)
        val star = Path().apply {
            moveTo(c.x, c.y - r * s)
            quadraticTo(c.x, c.y, c.x + r * s, c.y)
            quadraticTo(c.x, c.y, c.x, c.y + r * s)
            quadraticTo(c.x, c.y, c.x - r * s, c.y)
            quadraticTo(c.x, c.y, c.x, c.y - r * s)
            close()
        }
        drawPath(star, accent)
    }

    /** A six-point snowflake: three crossing strokes. */
    private fun DrawScope.flake(cx: Float, cy: Float, r: Float) {
        val w = s * 0.024f
        for (i in 0 until 3) {
            val a = i * PI / 3 + PI / 2
            val c = cos(a).toFloat()
            val n = sin(a).toFloat()
            drawLine(ink, p(cx - c * r, cy - n * r), p(cx + c * r, cy + n * r), w, StrokeCap.Round)
        }
    }

    /**
     * One smooth cloud with a flat base, moved by ([dx], [dy]) and scaled by [k] about the middle.
     * [cutGap] first draws a black edge around it, so whatever is behind (the sun) stops short of it.
     */
    private fun DrawScope.cloud(dx: Float, dy: Float, k: Float, cutGap: Boolean = false, colour: Color = ink) {
        fun q(x: Float, y: Float) = Offset((0.5f + (x - 0.5f) * k + dx) * s, (0.5f + (y - 0.5f) * k + dy) * s)
        val shape = Path().apply {
            val a = q(0.27f, 0.74f); moveTo(a.x, a.y)
            cubic(this, q(0.15f, 0.74f), q(0.11f, 0.6f), q(0.2f, 0.55f))
            cubic(this, q(0.19f, 0.44f), q(0.3f, 0.39f), q(0.38f, 0.44f))
            cubic(this, q(0.42f, 0.29f), q(0.62f, 0.27f), q(0.66f, 0.42f))
            cubic(this, q(0.79f, 0.39f), q(0.9f, 0.5f), q(0.84f, 0.61f))
            cubic(this, q(0.91f, 0.67f), q(0.86f, 0.74f), q(0.76f, 0.74f))
            close()
        }
        if (cutGap) drawPath(shape, Color.Black, style = gap)
        if (outline) drawPath(shape, Color.Black)
        drawPath(shape, colour, style = style)
    }

    private fun cubic(path: Path, c1: Offset, c2: Offset, end: Offset) = path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
}

/** The small symbols beside the weather details. */
enum class Glyph { Thermometer, Droplet, Wind, Umbrella, Sunrise, Sunset, Uv }

/** One detail symbol, drawn in [color] with the same line weight at any size. */
@Composable
fun DetailGlyph(glyph: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val stroke = Stroke(s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            Glyph.Thermometer -> {
                drawRoundRect(color, p(0.4f, 0.08f), androidx.compose.ui.geometry.Size(0.2f * s, 0.6f * s), androidx.compose.ui.geometry.CornerRadius(0.1f * s), style = stroke)
                drawCircle(color, 0.16f * s, p(0.5f, 0.76f))
                drawLine(color, p(0.5f, 0.4f), p(0.5f, 0.7f), s * 0.1f, StrokeCap.Round)
            }
            Glyph.Droplet -> {
                val drop = Path().apply {
                    moveTo(0.5f * s, 0.08f * s)
                    cubicTo(0.5f * s, 0.08f * s, 0.18f * s, 0.47f * s, 0.18f * s, 0.64f * s)
                    cubicTo(0.18f * s, 0.82f * s, 0.33f * s, 0.94f * s, 0.5f * s, 0.94f * s)
                    cubicTo(0.67f * s, 0.94f * s, 0.82f * s, 0.82f * s, 0.82f * s, 0.64f * s)
                    cubicTo(0.82f * s, 0.47f * s, 0.5f * s, 0.08f * s, 0.5f * s, 0.08f * s)
                    close()
                }
                drawPath(drop, color)
            }
            Glyph.Wind -> {
                val gusts = Path().apply {
                    moveTo(0.08f * s, 0.36f * s); lineTo(0.6f * s, 0.36f * s)
                    cubicTo(0.82f * s, 0.36f * s, 0.82f * s, 0.1f * s, 0.62f * s, 0.14f * s)
                    moveTo(0.08f * s, 0.58f * s); lineTo(0.76f * s, 0.58f * s)
                    cubicTo(0.98f * s, 0.58f * s, 0.98f * s, 0.86f * s, 0.78f * s, 0.84f * s)
                    moveTo(0.2f * s, 0.8f * s); lineTo(0.46f * s, 0.8f * s)
                }
                drawPath(gusts, color, style = stroke)
            }
            Glyph.Sunrise, Glyph.Sunset -> {
                drawLine(color, p(0.06f, 0.74f), p(0.94f, 0.74f), s * 0.1f, StrokeCap.Round)
                drawArc(color, 180f, 180f, false, p(0.27f, 0.5f), androidx.compose.ui.geometry.Size(0.46f * s, 0.46f * s), style = stroke)
                val up = glyph == Glyph.Sunrise
                drawLine(color, p(0.5f, 0.1f), p(0.5f, 0.34f), s * 0.1f, StrokeCap.Round)
                val tip = if (up) 0.1f else 0.34f
                val back = if (up) 0.24f else 0.2f
                drawLine(color, p(0.38f, back), p(0.5f, tip), s * 0.1f, StrokeCap.Round)
                drawLine(color, p(0.62f, back), p(0.5f, tip), s * 0.1f, StrokeCap.Round)
            }
            Glyph.Uv -> {
                drawCircle(color, 0.2f * s, p(0.5f, 0.5f), style = stroke)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    val c = kotlin.math.cos(a).toFloat()
                    val n = kotlin.math.sin(a).toFloat()
                    drawLine(color, p(0.5f + c * 0.32f, 0.5f + n * 0.32f), p(0.5f + c * 0.44f, 0.5f + n * 0.44f), s * 0.09f, StrokeCap.Round)
                }
            }
            Glyph.Umbrella -> {
                val canopy = Path().apply {
                    moveTo(0.08f * s, 0.5f * s)
                    cubicTo(0.12f * s, 0.12f * s, 0.88f * s, 0.12f * s, 0.92f * s, 0.5f * s)
                    close()
                }
                drawPath(canopy, color)
                val handle = Path().apply {
                    moveTo(0.5f * s, 0.5f * s); lineTo(0.5f * s, 0.8f * s)
                    cubicTo(0.5f * s, 0.94f * s, 0.32f * s, 0.94f * s, 0.32f * s, 0.8f * s)
                }
                drawPath(handle, color, style = stroke)
            }
        }
    }
}
