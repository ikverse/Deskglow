package com.ikverse.deskglow.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where a drawing puts paint, as a grid of [cell]-pixel squares over a [width] by [height] box, so text laid over the
 * drawing can keep off it. Shapes are marked from their geometry rather than read back from pixels, so it costs little
 * and works the same in tests. A square counts as painted if any part of a shape touches it, so the map errs towards
 * keeping text a little further off, never closer.
 */
class InkMap(val width: Float, val height: Float, val cell: Float) {
    private val cols = ceil(width / cell).toInt().coerceAtLeast(1)
    private val rows = ceil(height / cell).toInt().coerceAtLeast(1)
    private val inked = BooleanArray(cols * rows)

    /** A copy to add passing things to (the cars on a track) while this one stays as it is. */
    fun copy(): InkMap = InkMap(width, height, cell).also { inked.copyInto(it.inked) }

    /** A disc of [radius] round [centre]. */
    fun dot(centre: Offset, radius: Float) {
        for (r in row(centre.y - radius)..row(centre.y + radius)) {
            for (c in col(centre.x - radius)..col(centre.x + radius)) {
                // The nearest point of this square to the centre.
                val nx = centre.x.coerceIn(c * cell, (c + 1) * cell)
                val ny = centre.y.coerceIn(r * cell, (r + 1) * cell)
                if (hypot(nx - centre.x, ny - centre.y) <= radius) inked[r * cols + c] = true
            }
        }
    }

    /** A line through [points], [stroke] wide with round ends, back to the first point when [closed]. */
    fun line(points: List<Offset>, stroke: Float, closed: Boolean = false) {
        if (points.isEmpty()) return
        val step = cell / 2
        val ends = if (closed) points + points.first() else points
        dot(ends.first(), stroke / 2)
        for (i in 1 until ends.size) {
            val a = ends[i - 1]
            val b = ends[i]
            val n = ceil((b - a).getDistance() / step).toInt().coerceAtLeast(1)
            for (k in 1..n) dot(a + (b - a) * (k.toFloat() / n), stroke / 2)
        }
    }

    /** An arc of a circle of [radius] round [centre], [stroke] wide, from [startDegrees] (0 at three o'clock, clockwise as drawn) through [sweepDegrees]. */
    fun arc(centre: Offset, radius: Float, stroke: Float, startDegrees: Float, sweepDegrees: Float) {
        val length = radius * sweepDegrees / 180f * PI.toFloat()
        val n = ceil(length / (cell / 2)).toInt().coerceAtLeast(1)
        line(
            (0..n).map { k ->
                val a = (startDegrees + sweepDegrees * k / n) / 180f * PI.toFloat()
                Offset(centre.x + radius * cos(a), centre.y + radius * sin(a))
            },
            stroke,
        )
    }

    /** All of [r]. */
    fun rect(r: Rect) {
        if (r.isEmpty) return
        for (row in row(r.top)..row(r.bottom - 0.01f)) for (c in col(r.left)..col(r.right - 0.01f)) inked[row * cols + c] = true
    }

    /** Whether [r] would cover paint, or reach out of the box. */
    fun hits(r: Rect): Boolean {
        if (r.left < 0f || r.top < 0f || r.right > width || r.bottom > height) return true
        if (r.isEmpty) return false
        for (row in row(r.top)..row(r.bottom - 0.01f)) for (c in col(r.left)..col(r.right - 0.01f)) if (inked[row * cols + c]) return true
        return false
    }

    private fun col(x: Float) = floor(x / cell).toInt().coerceIn(0, cols - 1)
    private fun row(y: Float) = floor(y / cell).toInt().coerceIn(0, rows - 1)

    companion object {
        /** Squares this many to the shorter side: fine enough that text is kept off by a hair, not a margin. */
        const val DETAIL = 120

        /** An empty map for a [width] by [height] box. */
        fun of(width: Float, height: Float) = InkMap(width, height, (min(width, height) / DETAIL).coerceAtLeast(1f))
    }
}

/** One part of a [ClearStack]: drawn by [content] at a share (up to 1) of its full size. One that is not [required] may be left out. */
class ClearPart(val required: Boolean = true, val content: @Composable (Float) -> Unit)

/** The shares of full size a [ClearStack] or a [LabelSpace] tries, largest first. */
private fun shares(floor: Float, step: Float): List<Float> {
    val out = mutableListOf<Float>()
    var share = 1f
    while (share > floor + 0.001f) {
        out += share
        share -= step
    }
    return out + floor
}

private data class ClearProbe(val parts: List<Int>, val share: Float, val part: Int)
private object ClearShown

/**
 * [parts] stacked one above another over a drawing whose paint is [ink], [gap] pixels apart at full size, each centred
 * across and the stack set upright by [align] (0 top, 0.5 middle, 1 bottom). They are drawn at full size if none of them
 * then lands on paint, otherwise shrunk step by step towards [floor] of it until none does. If even that is not clear,
 * the parts that are not required are left out, the last first, and what stays is drawn at the largest clear size, or at
 * [floor] if none is. [ink] must be for the box this fills.
 */
@Composable
fun ClearStack(
    ink: InkMap,
    parts: List<ClearPart>,
    gap: Float,
    modifier: Modifier = Modifier.fillMaxSize(),
    align: Float = 0.5f,
    floor: Float = 0.5f,
) {
    SubcomposeLayout(modifier) { c ->
        val w = if (c.hasBoundedWidth) c.maxWidth else c.minWidth
        val h = if (c.hasBoundedHeight) c.maxHeight else c.minHeight

        fun tops(placeables: List<Placeable>, share: Float): List<Float> {
            val space = gap * share
            val total = placeables.sumOf { it.height } + space * (placeables.size - 1).coerceAtLeast(0)
            var y = (h - total) * align
            return placeables.map { p -> y.also { y += p.height + space } }
        }

        fun clear(kept: List<Int>, share: Float): Boolean {
            // Measured only, never drawn: kept out of what screen readers (and tests) see.
            val placeables = kept.map { i ->
                subcompose(ClearProbe(kept, share, i)) { Box(Modifier.clearAndSetSemantics {}) { parts[i].content(share) } }
                    .first().measure(Constraints())
            }
            return placeables.zip(tops(placeables, share)).none { (p, top) ->
                val left = (w - p.width) / 2f
                ink.hits(Rect(left, top, left + p.width, top + p.height))
            }
        }

        var kept = parts.indices.toList()
        var share = shares(floor, 0.05f).firstOrNull { clear(kept, it) }
        while (share == null) {
            val optional = kept.lastOrNull { !parts[it].required } ?: break
            kept = kept - optional
            share = shares(floor, 0.05f).firstOrNull { clear(kept, it) }
        }
        val chosen = share ?: floor
        val placeables = subcompose(ClearShown) { kept.forEach { i -> Box { parts[i].content(chosen) } } }
            .map { it.measure(Constraints(maxWidth = w, maxHeight = h)) }
        val tops = tops(placeables, chosen)
        layout(w, h) {
            placeables.forEachIndexed { i, p -> p.place(((w - p.width) / 2f).roundToInt(), tops[i].roundToInt()) }
        }
    }
}

/** Where [LabelSpace.place] put a label: its top left, and the share of its full size it is drawn at. */
class Placed(val topLeft: Offset, val share: Float)

/**
 * Labels painted onto a drawing one at a time, each kept off the drawing's [ink] and off the labels placed before it.
 * [ink] is changed as labels are placed, so give it a copy if the original is kept.
 */
class LabelSpace(private val ink: InkMap) {
    /**
     * Where a label [full] in size at full size goes: at the first of [spots] (each giving the top left for a label of a
     * size) where it is clear, at full size or shrunk in steps to [floor] of it. The place is then marked as taken.
     * Null when nowhere is clear and the label is not [required]; a required one goes to the first spot at [floor].
     * [blocked] is checked as well as the ink, for things that move every frame (other cars) and are checked exactly.
     * Text is taken to shrink in proportion to its font size, so a label is measured once.
     */
    fun place(full: Size, spots: List<(Size) -> Offset>, floor: Float = 0.6f, required: Boolean = false, blocked: (Rect) -> Boolean = { false }): Placed? {
        for (share in shares(floor, 0.1f)) {
            val size = full * share
            for (spot in spots) {
                val at = spot(size)
                val r = Rect(at, size)
                if (!ink.hits(r) && !blocked(r)) {
                    ink.rect(r)
                    return Placed(at, share)
                }
            }
        }
        if (!required || spots.isEmpty()) return null
        val size = full * floor
        val at = spots.first()(size)
        ink.rect(Rect(at, size))
        return Placed(at, floor)
    }
}

/** Whether [r] and the disc of [radius] round [centre] overlap. */
fun Rect.touches(centre: Offset, radius: Float): Boolean =
    hypot(centre.x.coerceIn(left, right) - centre.x, centre.y.coerceIn(top, bottom) - centre.y) < radius

/** The paint of a ring drawn as a circle of [side] with a stroke [thickness] wide, inset as the Charging ring draws it. */
internal fun ringInk(side: Float, thickness: Float, layout: String): InkMap {
    val ink = InkMap.of(side, side)
    val inset = side * 0.04f + thickness / 2
    val radius = side / 2 - inset
    val centre = Offset(side / 2, side / 2)
    when (layout) {
        "circle" -> ink.arc(centre, radius, thickness, -90f, 360f)
        // The segments lie along the gauge's three quarters; the gaps between them are too small to set text in.
        else -> ink.arc(centre, radius, thickness, 135f, 270f)
    }
    return ink
}
