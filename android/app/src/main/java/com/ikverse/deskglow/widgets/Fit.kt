package com.ikverse.deskglow.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A widget's box in canvas units (the layout canvas is 848 by 412), set by the code that draws widgets.
 * Text is sized from it rather than from pixels, so a widget looks the same in the editor, where the
 * canvas is drawn small, as on the display. Null where a widget is drawn outside a layout (the picker).
 */
val LocalCanvasBox = staticCompositionLocalOf<IntSize?> { null }

/** Pixels to a canvas unit, for a widget [w] pixels wide that is [ownWidth] units wide when first added (as it is in the picker). */
@Composable
fun canvasUnit(w: Float, ownWidth: Int): Float = w / (LocalCanvasBox.current?.width ?: ownWidth).coerceAtLeast(1)

/** How widgets fill their boxes. Sizes here are in canvas units. */
object Fitting {
    /** The size layouts are drawn at by design: below it every kind of text shrinks alike, above it the lesser kinds grow more slowly. */
    const val REFERENCE = 10f
    /** How far across the box content may reach; the rest is margin. */
    const val FILL = 0.92f
    /** Below this, a part that can be left out is left out to give the rest room. */
    const val COMFORT = 8f
    /** The most the main text grows to, however big the box. */
    const val LARGEST = 64f
    /** The least it shrinks to, however small. */
    const val SMALLEST = 3f
    /** Spare height goes into the gaps between lines, up to this many times their usual size. */
    const val SPREAD = 1.5f
    /** Secondary text grows as the main text to this power, labels and chips as it to [SMALL_RATE]. */
    const val SECOND_RATE = 0.55f
    const val SMALL_RATE = 0.4f
    /** Secondary text and labels never take more than this share of the box's height once past the design size. */
    const val SECOND_CAP = 0.2f
    const val SMALL_CAP = 0.12f
    /** A table row is no shorter than this before rows that can be left out are, and no taller than [LARGEST_ROW]. */
    const val SMALLEST_ROW = 12f
    const val LARGEST_ROW = 34f
    /** Hints in the editor are set between these. */
    const val HINT_MIN = 7f
    const val HINT_MAX = 16f

    /** [px] as secondary text: the same up to the design size ([unit] pixels to a canvas unit), growing more slowly past it. */
    fun second(px: Float, unit: Float): Float = grow(px, unit, SECOND_RATE)

    /** [px] as a label or chip: growing more slowly still past the design size. */
    fun small(px: Float, unit: Float): Float = grow(px, unit, SMALL_RATE)

    private fun grow(px: Float, unit: Float, rate: Float): Float {
        val reference = REFERENCE * unit
        return if (px <= reference || reference <= 0f) px else reference * (px / reference).pow(rate)
    }
}

/**
 * Text sizes for one fit, in pixels: [main] for what the widget is for (the time, the flag, the
 * temperature), [second] for what goes with it, [small] for labels and chips. [space] (1 or more) is how
 * much the gaps between lines are opened up to use spare height. [unit] is pixels to a canvas unit.
 */
@Immutable
class Scale(val main: Float, val second: Float, val small: Float, val space: Float, val unit: Float)

/**
 * What [Fit] hands a layout: which of its [arrangement]s and how many of its optional parts ([level])
 * to draw, at what [scale], in a box [w] by [h] pixels. While [probing], the layout is only being
 * measured: text that may be cut short (a long title, the news) should be set at a sample length, and
 * nothing should take its width from the box.
 */
@Immutable
class Fitted(val arrangement: Int, val level: Int, val scale: Scale, val w: Float, val h: Float, val probing: Boolean) {
    /** [text] cut to [chars] while measuring, so a long one is ellipsised rather than shrinking everything. */
    fun sample(text: String, chars: Int): String = if (probing && text.length > chars) text.take(chars) else text
}

private class Choice(val arrangement: Int, val level: Int, val u: Float, val space: Float)

private class FitMemo {
    var w = -1
    var h = -1
    var key: Any? = null
    var choice: Choice? = null
}

private data class Probe(val n: Int)
private object MainSlot

/**
 * Draws [content] as large as the box allows. The widget offers one or more [arrangements] (stacked,
 * side by side, on one line) and [levels] optional parts, least important last; this measures them and
 * picks the one that comes out largest with as many parts as stay comfortably readable. The main text
 * grows fully, the lesser kinds more slowly (see [Fitting]), and spare height opens up the gaps.
 *
 * The choice is kept until the box, [key] or the widget's settings change, so a live widget does not
 * jump about as its text changes length; it is only made again if new text would no longer fit.
 * [unit] is pixels to a canvas unit ([canvasUnit]). The content is centred upright and set across by
 * [align] (0 left, 1 right). Where the box is not fixed in a direction, this takes only what it needs.
 */
@Composable
fun Fit(
    unit: Float,
    key: Any?,
    modifier: Modifier = Modifier.fillMaxSize(),
    arrangements: Int = 1,
    levels: Int = 0,
    align: Float = 0f,
    content: @Composable (Fitted) -> Unit,
) {
    val memo = remember { FitMemo() }
    SubcomposeLayout(modifier) { c ->
        val w = if (c.hasBoundedWidth) c.maxWidth else c.minWidth
        val h = if (c.hasBoundedHeight) c.maxHeight else c.minHeight
        if (w <= 0 || h <= 0) return@SubcomposeLayout layout(c.minWidth, c.minHeight) {}
        val tw = w * Fitting.FILL
        val th = h * Fitting.FILL
        var probes = 0

        fun scaleAt(u: Float, space: Float): Scale {
            val main = u * unit
            val reference = Fitting.REFERENCE * unit
            val second = min(Fitting.second(main, unit), max(reference, h * Fitting.SECOND_CAP))
            val small = min(Fitting.small(main, unit), max(reference, h * Fitting.SMALL_CAP))
            return Scale(main, second, min(small, second), space, unit)
        }

        fun fitted(a: Int, l: Int, u: Float, space: Float, probing: Boolean) = Fitted(a, l, scaleAt(u, space), w.toFloat(), h.toFloat(), probing)

        fun size(f: Fitted): IntSize {
            var sw = 0
            var sh = 0
            // Measured only, never drawn: kept out of what screen readers (and tests) see.
            subcompose(Probe(probes++)) { Box(Modifier.clearAndSetSemantics {}) { content(f) } }.forEach {
                val p = it.measure(Constraints())
                sw = max(sw, p.width)
                sh = max(sh, p.height)
            }
            return IntSize(sw, sh)
        }

        fun ratio(s: IntSize): Float = min(if (s.width > 0) tw / s.width else Float.POSITIVE_INFINITY, if (s.height > 0) th / s.height else Float.POSITIVE_INFINITY)

        /** The largest main size, in canvas units, at which arrangement [a] with [l] parts fits, and its height there. */
        fun largest(a: Int, l: Int): Pair<Float, Int> {
            var lo = 0f
            var loHeight = 0
            var hi = Float.POSITIVE_INFINITY
            var u = Fitting.REFERENCE
            for (step in 0 until 9) {
                val s = size(fitted(a, l, u, 1f, true))
                val r = ratio(s)
                if (r >= 1f) {
                    if (u > lo) {
                        lo = u
                        loHeight = s.height
                    }
                } else hi = min(hi, u)
                if (lo >= Fitting.LARGEST || (hi - lo) <= lo * 0.015f) break
                // Guess from how far off this size was, but never by less than halving what is left to search:
                // sizes are whole pixels, so a guess alone can creep towards the answer and never reach it.
                val guess = if (r.isInfinite()) Fitting.LARGEST else u * r
                val middle = (lo + hi) / 2
                val next = when {
                    hi.isInfinite() -> max(guess, u * 1.05f)
                    // Nothing has fitted yet: only the guess (a shade under) has anything to go on.
                    lo == 0f -> guess * 0.98f
                    r >= 1f -> if (guess < hi) max(guess, middle) else middle
                    else -> if (guess > lo) min(guess, middle) else middle
                }
                u = next.coerceIn(Fitting.SMALLEST, Fitting.LARGEST)
                if (u == Fitting.SMALLEST && r < 1f && lo == 0f) break
            }
            if (lo == 0f) {
                lo = Fitting.SMALLEST
                loHeight = size(fitted(a, l, lo, 1f, true)).height
            }
            return lo to loHeight
        }

        fun search(): Choice {
            var best: Choice? = null
            var bestHeight = 0
            for (a in 0 until arrangements) {
                for (l in levels downTo 0) {
                    val (u, height) = largest(a, l)
                    if (u < Fitting.COMFORT && l > 0) continue
                    val current = best
                    // More parts first; then the larger, with the earlier arrangement kept on a near tie.
                    if (current == null || l > current.level || (l == current.level && u > current.u * 1.02f)) {
                        best = Choice(a, l, u, 1f)
                        bestHeight = height
                    }
                    break
                }
            }
            val chosen = best ?: Choice(0, 0, Fitting.SMALLEST, 1f)
            // Spare height opens up the gaps between lines, as far as SPREAD.
            val open = size(fitted(chosen.arrangement, chosen.level, chosen.u, Fitting.SPREAD, true)).height
            val space = when {
                open <= th -> Fitting.SPREAD
                open > bestHeight -> 1f + (Fitting.SPREAD - 1f) * ((th - bestHeight) / (open - bestHeight)).coerceIn(0f, 1f)
                else -> 1f
            }
            return Choice(chosen.arrangement, chosen.level, chosen.u, space)
        }

        var choice = memo.choice.takeIf { memo.w == w && memo.h == h && memo.key == key }
        if (choice != null) {
            // The text may have changed since: the size stays unless it no longer fits at all.
            val s = size(fitted(choice.arrangement, choice.level, choice.u, choice.space, true))
            if (s.width > w || s.height > h) choice = null
        }
        if (choice == null) {
            choice = search()
            memo.w = w
            memo.h = h
            memo.key = key
            memo.choice = choice
        }
        val shown = fitted(choice.arrangement, choice.level, choice.u, choice.space, false)
        val placeables = subcompose(MainSlot) { content(shown) }.map { it.measure(Constraints(maxWidth = w, maxHeight = h)) }
        val cw = placeables.maxOfOrNull { it.width } ?: 0
        val ch = placeables.maxOfOrNull { it.height } ?: 0
        val lw = if (c.hasFixedWidth) w else cw.coerceIn(c.minWidth, w)
        val lh = if (c.hasFixedHeight) h else ch.coerceIn(c.minHeight, h)
        layout(lw, lh) {
            placeables.forEach { it.place(((lw - it.width) * align).roundToInt(), (lh - it.height) / 2) }
        }
    }
}

/**
 * The height of each row of a table and how many rows it shows: [wanted] rows plus [fixed] rows' worth
 * of heading and footing share [height]; each row is at most [widthBound] tall (what its width allows),
 * at most [Fitting.LARGEST_ROW] canvas units, and rows past [least] are dropped while one would come
 * out under [Fitting.SMALLEST_ROW].
 */
fun tableRows(wanted: Int, fixed: Float, height: Float, widthBound: Float, unit: Float, least: Int = 3): Pair<Int, Float> {
    var count = wanted
    fun row(n: Int) = min(min(height / (n + fixed), widthBound), Fitting.LARGEST_ROW * unit)
    while (count > least && row(count) < Fitting.SMALLEST_ROW * unit) count--
    return count to row(count)
}
