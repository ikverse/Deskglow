package com.ikverse.deskglow.layout

import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Stage
import kotlin.math.abs
import kotlin.math.roundToInt

/** A widget's id and where it sits. */
data class Placed(val id: String, val box: Box)

/**
 * Keeps widgets from ever overlapping: anything in the way of a widget being moved, resized, added
 * or brought back is pushed straight down, just far enough to clear it, and whatever that lands on is
 * pushed in turn. Nothing moves sideways and nothing else rearranges itself, so gaps the owner left
 * stay where they are. Everything works on the canvas of one [Orientation]; portrait is the default.
 */
object Packer {
    /** New widgets prefer spots the editor leaves uncovered ([Orientation.visibleY]); a hidden spot costs this much. */
    private const val HIDDEN_PENALTY = 300

    fun snap(value: Float): Int = (value / Stage.GRID).roundToInt() * Stage.GRID

    /**
     * Lays [others] out again with [fixed] held where it is. Each of the others starts from the place
     * given (not wherever an earlier call left it), so calling this on every step of a drag means a
     * widget pushed down comes back up when the pusher moves away. Top to bottom, each drops below
     * whatever it would touch. Returns every widget's box, or null if one would run off the bottom.
     */
    fun resolve(fixed: Placed, others: List<Placed>, orientation: Orientation = Orientation.Portrait): Map<String, Box>? {
        val placed = ArrayList<Box>(others.size + 1).apply { add(fixed.box) }
        val out = LinkedHashMap<String, Box>()
        out[fixed.id] = fixed.box
        for (other in others.filter { it.id != fixed.id }.sortedWith(compareBy({ it.box.y }, { it.box.x }))) {
            var box = other.box
            while (true) {
                val hit = placed.firstOrNull { it.overlaps(box) } ?: break
                box = box.copy(y = hit.bottom)
            }
            if (box.bottom > orientation.height) return null
            placed += box
            out[other.id] = box
        }
        return out
    }

    /**
     * Puts a widget of [w] by [h] on the screen without overlap. With [prefer] it goes exactly there,
     * pushing others down if they fit; otherwise (or if that does not fit) it goes wherever the least
     * has to move, preferring spots the editor shows. Null when there is no room anywhere.
     */
    fun place(
        id: String, w: Int, h: Int, prefer: Box?, others: List<Placed>,
        orientation: Orientation = Orientation.Portrait,
    ): Map<String, Box>? {
        val rest = others.filter { it.id != id }
        val visible = orientation.visibleY
        fun attempt(x: Int, y: Int): Pair<Map<String, Box>, Int>? {
            val result = resolve(Placed(id, Box(x, y, w, h)), rest, orientation) ?: return null
            val moved = rest.sumOf { abs(result.getValue(it.id).y - it.box.y) }
            return result to moved
        }
        if (prefer != null) attempt(prefer.x, prefer.y)?.let { return it.first }

        var best: Pair<Map<String, Box>, Int>? = null
        for (x in listOf(snap((orientation.width - w) / 2f), 0, orientation.width - w).distinct()) {
            var y = 0
            while (y + h <= orientation.height) {
                attempt(x, y)?.let { (result, moved) ->
                    val cost = moved + if (y >= visible.first && y + h <= visible.last) 0 else HIDDEN_PENALTY
                    val current = best
                    if (current == null || cost < current.second) best = result to cost
                }
                y += 8
            }
        }
        return best?.first
    }

    /** A layout saved before overlaps were prevented, tidied once: top to bottom, each drops below what it touches. */
    fun tidy(items: List<Placed>, orientation: Orientation = Orientation.Portrait): Map<String, Box> {
        val placed = ArrayList<Box>(items.size)
        val out = LinkedHashMap<String, Box>()
        for (item in items.sortedWith(compareBy({ it.box.y }, { it.box.x }))) {
            var box = item.box
            while (true) {
                val hit = placed.firstOrNull { it.overlaps(box) } ?: break
                box = box.copy(y = hit.bottom)
            }
            box = box.copy(y = minOf(box.y, orientation.height - box.h).coerceAtLeast(0))
            placed += box
            out[item.id] = box
        }
        return out
    }
}

/**
 * One drag of one widget, from the moment it is taken hold of until it is let go. Every step is
 * worked out from [base], the layout as it was at the start, so pushes undo themselves while the
 * widget is still held. When a push would run something off the screen, the widget stops at the wall:
 * the furthest point between the last step that fitted and the one asked for.
 */
class DragSession(
    val id: String,
    val resize: Boolean,
    private val start: Box,
    private val base: List<Placed>,
    private val orientation: Orientation = Orientation.Portrait,
) {
    private var last: Box = start

    /** Where the moved widget's centre stands against the canvas centre lines, as of the last [update]. */
    var centring: Centring = Centring.None
        private set

    /**
     * One side of a resized widget: it grows or shrinks by whole grid squares from its starting
     * size, so letting go where the drag began puts it back exactly. The canvas edge counts as a
     * stop too, so a widget can still be made exactly as wide as the screen.
     */
    private fun side(size: Int, grow: Float, from: Int, canvas: Int): Int {
        val fewest = -Math.floorDiv(size - Stage.MIN_SIZE, Stage.STEP)
        val squares = (grow / Stage.STEP).roundToInt().coerceAtLeast(fewest)
        return (size + squares * Stage.STEP).coerceAtMost(canvas - from)
    }

    /** [right], [bottom]: where the edges would be with no snapping. */
    private fun resized(right: Float, bottom: Float) = Box(
        start.x, start.y,
        side(start.w, right - start.right, start.x, orientation.width),
        side(start.h, bottom - start.bottom, start.y, orientation.height),
    )

    /** Moves (or resizes) by [dx], [dy] canvas units from where the drag started. Null if nothing fits at all. */
    fun update(dx: Float, dy: Float): Map<String, Box>? {
        val want = if (resize) {
            resized(start.right + dx, start.bottom + dy)
        } else {
            var x = Packer.snap(start.x + dx).coerceIn(0, orientation.width - start.w)
            var y = Packer.snap(start.y + dy).coerceIn(0, orientation.height - start.h)
            // Close to the middle of the canvas a widget locks onto it.
            if (abs(x * 2 + start.w - orientation.width) <= Stage.CENTRE_PULL * 2) x = (orientation.width - start.w) / 2
            if (abs(y * 2 + start.h - orientation.height) <= Stage.CENTRE_PULL * 2) y = (orientation.height - start.h) / 2
            Box(x, y, start.w, start.h)
        }
        var box = want
        var result = resolve(want)
        if (result == null) {
            var lo = 0f
            var hi = 1f
            var good = last
            repeat(6) {
                val t = (lo + hi) / 2
                val step = if (resize) {
                    resized(last.right + (want.right - last.right) * t, last.bottom + (want.bottom - last.bottom) * t)
                } else {
                    Box(
                        Packer.snap(last.x + (want.x - last.x) * t), Packer.snap(last.y + (want.y - last.y) * t),
                        want.w, want.h,
                    )
                }
                if (resolve(step) != null) { lo = t; good = step } else hi = t
            }
            box = good
            result = resolve(good)
        }
        if (result != null) {
            last = box
            if (!resize) centring = Centring.of(box, orientation)
        }
        return result
    }

    private fun resolve(box: Box) = Packer.resolve(Placed(id, box), base, orientation)
}

/**
 * How a moved widget stands against the canvas centre lines: [nearX] / [nearY] once its centre is
 * within reach of the vertical / horizontal line (the guide shows), [lockX] / [lockY] once it sits on it.
 */
data class Centring(val nearX: Boolean, val lockX: Boolean, val nearY: Boolean, val lockY: Boolean) {
    companion object {
        val None = Centring(false, false, false, false)

        fun of(box: Box, orientation: Orientation): Centring {
            // Twice the distance from the widget's centre to the canvas centre, so odd sizes stay whole numbers.
            val offX = abs(box.x * 2 + box.w - orientation.width)
            val offY = abs(box.y * 2 + box.h - orientation.height)
            return Centring(
                nearX = offX <= Stage.CENTRE_REACH * 2, lockX = offX <= 1,
                nearY = offY <= Stage.CENTRE_REACH * 2, lockY = offY <= 1,
            )
        }
    }
}
