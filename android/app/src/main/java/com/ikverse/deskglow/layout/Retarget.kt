package com.ikverse.deskglow.layout

import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Stage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The other way up. */
val Orientation.other: Orientation get() = if (this == Orientation.Portrait) Orientation.Landscape else Orientation.Portrait

/**
 * Rearranges a layout made for one [Orientation] so it suits the other, keeping the idea of it:
 * each widget keeps its place relative to the screen (top left stays top left, the middle stays the
 * middle), widgets that hug an edge or sit on a centre line keep doing so, and one that ran the full
 * width still does. Sizes are kept, so text stays as readable, except where a widget would not fit
 * the new canvas, when it shrinks in proportion. Widgets that then land on each other move to the
 * nearest free spot, in reading order. If everything cannot fit at full size, all of them shrink a
 * little at a time; any that still do not fit at half size are hidden rather than lost.
 */
object Retarget {
    /** [hidden]: how many widgets were left out because they could not fit. */
    data class Result(val layout: Layout, val hidden: Int)

    /** A widget this close to an edge counts as sitting against it. */
    private const val EDGE = 8
    private const val SHRINK_STEP = 0.1f
    private const val SHRINK_LIMIT = 0.5f

    fun convert(layout: Layout, from: Orientation, to: Orientation): Result {
        if (from == to) return Result(layout, 0)
        val order = layout.items.filter { it.visible }
            .sortedWith(compareBy({ it.box.y * 2 + it.box.h }, { it.box.x * 2 + it.box.w }))
        var shrink = 1f
        var attempt = place(order.map { it.id to it.box }, from, to, shrink)
        while (attempt.second.isNotEmpty() && shrink - SHRINK_STEP >= SHRINK_LIMIT - 0.001f) {
            shrink -= SHRINK_STEP
            attempt = place(order.map { it.id to it.box }, from, to, shrink)
        }
        val (placed, failed) = attempt
        val items = layout.items.map { item ->
            when {
                item.id in placed -> item.copy(box = placed.getValue(item.id))
                // Left out: hidden, with a box that is at least on the canvas should it be shown again.
                else -> item.copy(box = ideal(item.box, from, to, 1f), visible = if (item.visible) false else item.visible)
            }
        }
        return Result(Layout(items), failed.size)
    }

    /** Puts the widgets down one at a time, each at the free spot nearest to where it ideally goes. */
    private fun place(widgets: List<Pair<String, Box>>, from: Orientation, to: Orientation, shrink: Float): Pair<Map<String, Box>, List<String>> {
        val placed = ArrayList<Box>()
        val out = LinkedHashMap<String, Box>()
        val failed = ArrayList<String>()
        for ((id, source) in widgets) {
            val want = ideal(source, from, to, shrink)
            val xs = (listOf(want.x) + placed.flatMap { listOf(it.right, it.x - want.w, it.x) }).filter { it in 0..to.width - want.w }.distinct()
            val ys = (listOf(want.y) + placed.flatMap { listOf(it.bottom, it.y - want.h, it.y) }).filter { it in 0..to.height - want.h }.distinct()
            var best: Box? = null
            var bestCost = Long.MAX_VALUE
            for (y in ys) for (x in xs) {
                val box = Box(x, y, want.w, want.h)
                if (placed.any { it.overlaps(box) }) continue
                val cost = (x - want.x).toLong() * (x - want.x) + (y - want.y).toLong() * (y - want.y)
                // On a tie the later one goes further down and right, so reading order survives.
                val current = best
                if (cost < bestCost || (cost == bestCost && current != null && (y > current.y || (y == current.y && x > current.x)))) {
                    best = box
                    bestCost = cost
                }
            }
            if (best == null) failed += id else {
                placed += best
                out[id] = best
            }
        }
        return out to failed
    }

    /** Where and how big [b] would ideally be on the [to] canvas, before anything else is considered. */
    private fun ideal(b: Box, from: Orientation, to: Orientation, shrink: Float): Box {
        val spansWidth = b.w * 10 >= from.width * 9
        var w = if (spansWidth && to.width >= from.width) to.width.toFloat() else b.w.toFloat()
        var h = b.h.toFloat()
        // Too big for the new canvas: shrink in proportion, keeping its shape.
        val fit = min(to.width / w, to.height / h)
        if (fit < 1f) {
            w *= fit
            h *= fit
        }
        // A full-width widget stays full width however much the rest shrink.
        if (!(spansWidth && w >= to.width)) w *= shrink
        h *= shrink
        val width = Packer.snap(w).coerceIn(min(Stage.MIN_SIZE, to.width), to.width)
        val height = Packer.snap(h).coerceIn(min(Stage.MIN_SIZE, to.height), to.height)
        return Box(
            along(b.x, b.w, from.width, to.width, width),
            along(b.y, b.h, from.height, to.height, height),
            width, height,
        )
    }

    /** One axis of a widget's new position: pinned to the edge or centre it was on, else at the same relative place. */
    private fun along(pos: Int, size: Int, fromLen: Int, toLen: Int, newSize: Int): Int {
        val room = toLen - newSize
        val onCentre = abs(pos * 2 + size - fromLen) <= Stage.CENTRE_PULL * 2
        return when {
            size * 10 >= fromLen * 9 || onCentre -> room / 2
            pos <= EDGE -> 0
            fromLen - (pos + size) <= EDGE -> room
            else -> Packer.snap(((pos + size / 2f) / fromLen) * toLen - newSize / 2f).coerceIn(0, max(room, 0))
        }.coerceIn(0, max(room, 0))
    }
}
