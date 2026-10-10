package com.ikverse.deskglow.layout

import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.TextKey
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.ClockWidget
import com.ikverse.deskglow.widgets.WeatherWidget
import com.ikverse.deskglow.widgets.Widgets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Auto-arrange: lays the visible widgets out afresh, in a composition that looks designed. No model
 * is involved, only rules and a score.
 *
 * Rules: a catalogue of hand-drawn patterns (slots for kinds of widget, on the real canvas), plus a
 * few generic flows that cope with any mix of widgets. Each pattern is filled with the widgets that
 * suit its slots, then varied (mirrored, its hero scaled, the whole thing slid or spread down the
 * screen). Score: every variation is checked (no overlaps, on the canvas, sizes the widgets can
 * draw themselves in) and marked for balance, margins, alignment, hierarchy and use of the space.
 *
 * Each [arrange] call takes a [round]: the best-scoring patterns are cycled through, one per round,
 * so pressing Auto-arrange again gives a different composition.
 */
object SmartLayout {
    /** [hidden]: widgets that fitted nowhere and were switched off. */
    class Result(val layout: Layout, val name: String, val hidden: Int)

    /** At most this many patterns are cycled through before a fresh set is drawn up. */
    const val POOL = 8

    /** Even a crowded screen gets this many compositions to cycle through, the best there are. */
    private const val MIN_POOL = 3

    /** Past those, only patterns scoring within this of the best are worth showing. */
    private const val KEEP_WITHIN = 22.0

    /** Added at random to each variation's score, so equally good arrangements come out in varying order. */
    private const val JITTER = 4.0

    /** Nothing goes nearer the sides of the canvas than this. */
    private const val EDGE = 8

    /** Widgets never come nearer each other than this, along at least one axis. */
    private const val MIN_GAP = 8

    private const val GAP_X = 12
    private const val GAP_Y = 16

    /** Remembers a clock's style while Auto-arrange has swapped it for Stacked, so a later arrangement can give it back. */
    private val STYLE_BEFORE = TextKey("styleBeforeArrange", "")

    private val HERO_SCALES = listOf(1.0, 0.88, 1.12)

    // ---- what each kind of widget can be drawn as ----

    private enum class Family { Hero, Ring, Chip, Card, Banner, Table }

    /** The sizes a widget looks right at, and the letter patterns use for it. Declared most important first. */
    private class Spec(val letter: String, val family: Family, val minW: Int, val maxW: Int, val minH: Int, val maxH: Int)

    private val SPECS = linkedMapOf(
        "clock" to Spec("C", Family.Hero, 120, 800, 40, 300),
        "ring" to Spec("R", Family.Ring, 120, 320, 120, 320),
        "f1weekend" to Spec("FW", Family.Banner, 280, 848, 96, 220),
        "f1standings" to Spec("FS", Family.Table, 160, 400, 160, 400),
        "weather" to Spec("W", Family.Card, 120, 400, 48, 300),
        "prayer" to Spec("P", Family.Banner, 280, 848, 80, 200),
        "event" to Spec("E", Family.Card, 120, 400, 48, 200),
        "alarm" to Spec("A", Family.Card, 120, 400, 48, 96),
        "sunmoon" to Spec("SM", Family.Card, 160, 400, 64, 140),
        "media" to Spec("M", Family.Banner, 200, 848, 44, 96),
        "stat" to Spec("S", Family.Chip, 80, 276, 40, 96),
        "date" to Spec("D", Family.Chip, 100, 400, 20, 60),
        "notifs" to Spec("N", Family.Chip, 100, 400, 24, 48),
    )
    private val UNKNOWN = Spec("?", Family.Card, 32, 848, 24, 412)
    private val LETTER_TO_TYPE = SPECS.entries.associate { it.value.letter to it.key }
    private val RANK = SPECS.keys.withIndex().associate { (i, type) -> type to i }

    private fun specOf(item: WidgetItem) = SPECS[item.type] ?: UNKNOWN
    private fun rankOf(item: WidgetItem) = RANK[item.type] ?: SPECS.size

    private fun Int.floor4() = Math.floorDiv(this, 4) * 4
    private fun Int.round4() = Math.floorDiv(this + 2, 4) * 4

    private fun inRange(spec: Spec, box: Box) = box.w in spec.minW..spec.maxW && box.h in spec.minH..spec.maxH

    /** [slot] filled by a widget of [spec]: kept in its size range (a ring square), centred in the slot, on the 4-unit grid. */
    private fun fitted(spec: Spec, slot: Box): Box = sized(spec, slot.w, slot.h).let { (w, h) ->
        Box((slot.x + (slot.w - w) / 2).round4(), (slot.y + (slot.h - h) / 2).round4(), w, h)
    }

    private fun sized(spec: Spec, w: Int, h: Int): Pair<Int, Int> {
        val cw = w.coerceIn(spec.minW, spec.maxW)
        val ch = h.coerceIn(spec.minH, spec.maxH)
        return if (spec.family == Family.Ring) min(cw, ch).floor4().let { it to it } else cw.floor4() to ch.floor4()
    }

    // ---- the catalogue ----

    private class Slot(val letter: String, val box: Box)
    private class Pattern(val name: String, val slots: List<Slot>)

    /** "C 96 56 220 64, D ..." : a letter for the kind of widget, then x, y, width, height. */
    private fun pattern(name: String, spec: String) = Pattern(
        name,
        spec.split(',').map { token ->
            val t = token.trim().split(' ')
            Slot(t[0], Box(t[1].toInt(), t[2].toInt(), t[3].toInt(), t[4].toInt()))
        },
    )

    private val PORTRAIT = listOf(
        pattern("Classic stack", "C 96 56 220 64,D 124 136 164 24,N 124 172 164 28,R 74 252 264 264,S 20 524 112 56,S 148 524 116 56,S 280 524 112 56,W 20 612 176 64,E 216 612 176 64,M 20 696 372 52"),
        pattern("Hero and grid", "C 24 64 364 112,D 24 184 176 24,N 208 184 180 28,W 24 240 176 96,E 212 240 176 96,R 24 352 176 176,S 212 352 176 80,S 212 448 176 80,A 24 544 364 64,M 24 624 364 56"),
        pattern("Orbit", "C 96 48 220 64,D 124 120 164 24,R 96 256 220 220,S 16 192 96 56,S 300 192 96 56,S 16 488 96 56,S 300 488 96 56,W 24 600 176 64,E 212 600 176 64,M 24 688 364 52"),
        pattern("Magazine cover", "D 24 48 200 24,C 24 80 364 200,N 24 288 200 28,W 24 360 192 156,R 232 360 156 156,S 24 540 112 56,S 150 540 112 56,S 276 540 112 56,M 24 760 364 52"),
        pattern("Left rail", "C 32 64 260 80,D 32 152 200 24,N 32 184 200 28,R 32 240 200 200,S 32 456 112 56,S 160 456 112 56,W 32 528 240 64,E 32 604 240 64,M 32 684 300 52"),
        pattern("Bento box", "C 24 56 364 96,R 24 164 176 176,W 212 164 176 82,E 212 258 176 82,S 24 352 113 64,S 149 352 113 64,S 274 352 114 64,D 24 428 176 40,N 212 428 176 40,M 24 480 364 56,A 24 548 364 64"),
        pattern("Dashboard rows", "C 24 48 236 72,D 268 56 120 24,N 268 88 120 28,S 24 144 113 56,S 149 144 113 56,S 274 144 114 56,R 24 224 176 176,W 212 224 176 82,E 212 318 176 82,P 24 424 364 96,M 24 536 364 52"),
        pattern("Focus", "C 46 300 320 120,D 124 428 164 24,S 24 720 113 48,S 149 720 113 48,S 274 720 114 48,N 124 780 164 28"),
        pattern("Bookends", "C 24 48 364 96,D 24 152 364 24,R 106 280 200 200,W 24 624 176 64,E 212 624 176 64,M 24 712 364 64"),
        pattern("Tower", "C 56 48 300 80,D 56 140 300 24,N 56 172 300 28,W 56 212 300 64,E 56 288 300 64,A 56 364 300 64,S 56 440 92 56,S 160 440 92 56,S 264 440 92 56,M 56 508 300 52"),
        pattern("Zigzag", "C 24 56 240 72,W 172 148 216 72,E 24 240 216 72,S 24 360 140 56,S 24 432 140 56,R 196 332 192 192,A 24 544 240 64,M 124 632 264 52"),
        pattern("Two families", "C 24 64 176 112,D 24 184 176 24,A 24 220 176 64,R 212 64 176 176,S 212 252 176 56,S 212 320 176 56,W 24 296 176 80,N 24 388 176 28,M 24 440 364 52"),
        pattern("Sidebar", "S 16 64 96 56,S 16 132 96 56,S 16 200 96 56,S 16 268 96 56,C 124 64 264 80,D 124 152 264 24,R 156 200 200 200,W 124 420 264 64,E 124 496 264 64,M 16 600 372 52"),
        pattern("Golden ratio", "C 24 48 364 140,R 24 200 224 224,W 260 200 128 108,E 260 316 128 108,S 24 436 112 56,S 148 436 104 56,N 260 436 128 40,M 24 508 364 52"),
        pattern("Rule of thirds", "C 24 248 250 84,D 24 340 200 24,R 268 440 120 120,W 24 580 176 64,E 212 580 176 64,S 24 664 113 56,S 149 664 113 56,S 274 664 114 56,M 24 740 364 52"),
        pattern("Status strip", "N 24 40 120 24,D 152 40 116 24,S 276 32 112 40,C 24 300 364 140,W 24 620 176 64,E 212 620 176 64,M 24 712 364 52"),
        pattern("Mirror image", "C 96 56 220 64,D 124 128 164 24,W 24 184 176 64,E 212 184 176 64,R 106 280 200 200,S 24 512 112 56,S 150 512 112 56,S 276 512 112 56,A 86 600 240 64,M 20 692 372 52"),
        pattern("Frame", "C 24 32 240 64,D 272 48 116 24,S 24 360 96 56,S 292 360 96 56,R 136 292 140 140,W 24 712 176 64,E 212 712 176 64,N 124 800 164 28"),
        pattern("Horizon", "C 24 480 364 120,D 24 608 176 24,N 208 608 180 28,S 24 660 113 56,S 149 660 113 56,S 274 660 114 56,M 24 740 364 52"),
        pattern("Constellation", "C 40 96 220 64,D 220 180 164 24,R 24 260 180 180,W 220 300 176 64,S 236 400 112 56,E 40 500 176 64,S 260 580 112 56,M 60 680 300 52"),
        pattern("Weather poster", "C 24 56 364 96,W 24 176 364 280,D 24 472 180 24,E 24 512 364 64,S 24 596 113 56,S 149 596 113 56,S 274 596 114 56,M 24 676 364 52"),
        pattern("Race day", "C 24 48 236 72,D 268 64 120 24,FW 20 144 372 112,FS 24 272 176 208,R 212 272 176 176,W 212 456 176 64,E 24 496 176 64,M 24 600 364 52"),
        pattern("Prayer focus", "C 96 56 220 64,D 124 128 164 24,P 20 176 372 96,A 86 288 240 64,R 106 376 200 200,S 24 600 112 56,S 150 600 112 56,S 276 600 112 56,M 20 688 372 52"),
        pattern("Diagonal", "C 24 48 240 72,D 24 128 200 24,N 24 160 200 28,R 136 240 140 140,W 212 440 176 64,E 212 512 176 64,S 276 584 112 56,A 148 648 240 64,M 24 728 364 52"),
    )

    private val LANDSCAPE = listOf(
        pattern("Three columns", "C 24 40 220 64,D 24 116 164 24,N 24 152 164 28,R 292 40 264 264,S 712 40 112 56,S 708 112 116 56,S 712 184 112 56,W 24 220 176 64,E 24 296 176 64,M 240 336 372 52"),
        pattern("Hero half", "C 24 96 400 140,D 24 244 240 24,N 24 276 240 28,R 448 24 176 176,W 640 24 184 82,E 640 118 184 82,S 448 216 120 56,S 576 216 120 56,S 704 216 120 56,M 448 296 376 52"),
        pattern("Header and cards", "C 24 24 320 96,D 360 40 200 24,N 360 72 200 28,S 600 24 108 56,S 716 24 108 56,W 24 152 190 96,E 226 152 190 96,A 428 152 190 96,R 640 140 184 184,M 24 280 592 52"),
        pattern("Centre stage", "R 292 56 264 264,C 24 72 240 72,D 24 152 200 24,N 24 184 200 28,W 24 240 240 64,S 584 56 240 56,S 584 128 240 56,E 584 200 240 64,A 584 280 240 64,M 238 360 372 44"),
        pattern("Nightstand", "C 74 60 700 220,D 74 296 340 32,N 434 296 340 32,S 74 344 160 52,S 344 344 160 52,S 614 344 160 52"),
        pattern("Bento", "C 24 24 400 120,R 24 156 232 232,W 268 156 156 112,E 268 276 156 112,S 436 24 124 64,S 568 24 124 64,S 700 24 124 64,P 436 100 388 96,M 436 208 388 52,A 436 272 388 64,D 436 348 388 40"),
        pattern("Right rail", "C 24 40 500 140,D 24 188 260 28,W 24 240 240 72,E 284 240 240 72,M 24 328 500 52,R 548 96 152 152,S 712 24 112 64,S 712 100 112 64,S 712 176 112 64,S 712 252 112 64"),
        pattern("Ticker bands", "D 24 16 180 24,N 216 16 180 28,S 600 8 108 40,S 716 8 108 40,C 24 80 520 180,R 572 64 252 252,M 24 340 520 52"),
        pattern("Golden split", "C 24 24 500 160,R 24 196 192 192,W 228 196 296 92,E 228 296 296 92,S 548 24 276 56,S 548 92 276 56,S 548 160 276 56,A 548 228 276 64,M 548 336 276 52"),
        pattern("Mirror image", "C 314 32 220 64,D 342 104 164 24,R 320 148 208 208,W 24 40 240 72,E 584 40 240 72,S 24 128 240 56,S 584 128 240 56,S 24 200 240 56,S 584 200 240 56,M 238 364 372 44"),
    )

    private fun catalogue(o: Orientation) = if (o == Orientation.Portrait) PORTRAIT else LANDSCAPE

    // ---- turning a pattern, or a flow, into boxes ----

    /** A composition before any variation: boxes for the widgets that have a place, the ids that have none. */
    private class Base(
        val name: String,
        val boxes: Map<String, Box>,
        val hidden: List<String>,
        /** Slots of the pattern no widget filled. */
        val empty: Int,
        /** Widgets squeezed in wherever there was room, because no slot took them. */
        val leftover: Int,
        val mirrorable: Boolean,
    )

    /** Each slot takes a widget of its own kind, or failing that one of the same family that suits its size. */
    private fun fill(pattern: Pattern, items: List<WidgetItem>, o: Orientation): Base {
        val free = items.toMutableList()
        val chosen = arrayOfNulls<WidgetItem>(pattern.slots.size)
        pattern.slots.forEachIndexed { i, slot ->
            val type = LETTER_TO_TYPE.getValue(slot.letter)
            free.firstOrNull { it.type == type }?.let { chosen[i] = it; free.remove(it) }
        }
        pattern.slots.forEachIndexed { i, slot ->
            if (chosen[i] != null) return@forEachIndexed
            val family = SPECS.getValue(LETTER_TO_TYPE.getValue(slot.letter)).family
            free.firstOrNull { specOf(it).family == family && inRange(specOf(it), slot.box) }?.let { chosen[i] = it; free.remove(it) }
        }
        val boxes = LinkedHashMap<String, Box>()
        var empty = 0
        chosen.forEachIndexed { i, item ->
            if (item == null) empty++ else boxes[item.id] = fitted(specOf(item), pattern.slots[i].box)
        }
        val leftover = free.size
        val hidden = ArrayList<String>()
        for (item in free.sortedBy(::rankOf)) {
            val spot = findSpot(item, boxes.values, o)
            if (spot != null) boxes[item.id] = spot else hidden += item.id
        }
        return Base(pattern.name, boxes, hidden, empty, leftover, mirrorable = true)
    }

    /** The first free place, scanning down from the top, for a widget no pattern slot wanted; smaller if need be. */
    private fun findSpot(item: WidgetItem, taken: Collection<Box>, o: Orientation): Box? {
        val spec = specOf(item)
        val type = Widgets.find(item.type)
        val w0 = type?.width ?: item.box.w
        val h0 = type?.height ?: item.box.h
        for (scale in listOf(1.0, 0.85, 0.7)) {
            val (w, h) = sized(spec, (w0 * scale).toInt(), (h0 * scale).toInt())
            for (y in 0..(o.height - h) step 4) {
                for (x in listOf(((o.width - w) / 2).round4(), 16, o.width - 16 - w).distinct()) {
                    val box = Box(x, y, w, h)
                    if (x >= EDGE && box.right <= o.width - EDGE && taken.none { it.overlaps(box) }) return box
                }
            }
        }
        return null
    }

    private class Member(val item: WidgetItem, val w: Int, val h: Int)

    private class Row(val members: List<Member>) {
        val h get() = members.maxOf { it.h }
        val w get() = members.sumOf { it.w } + (members.size - 1) * GAP_X
    }

    /**
     * Any set of widgets in [columns] columns: the clock, date and notifications first, then the ring,
     * the stats in rows of up to three, the cards in pairs, and the wide banners last. Rows shrink to
     * fit the height; when they cannot, the last rows are switched off. Null when a widget is too wide
     * for a column.
     */
    private fun flow(name: String, items: List<WidgetItem>, o: Orientation, columns: Int, leftAligned: Boolean = false): Base? {
        val margin = 24
        val gutter = 24
        val colW = ((o.width - 2 * margin - (columns - 1) * gutter) / columns).floor4()
        fun member(item: WidgetItem, w: Int, h: Int) = specOf(item).let { sized(it, w, h) }.let { (sw, sh) -> Member(item, sw, sh) }
        fun ofType(vararg types: String) = items.filter { it.type in types }

        val rows = ArrayList<Row>()
        ofType("clock").forEach { rows += Row(listOf(member(it, min(colW, 336), min(colW, 336) * 29 / 100))) }
        ofType("date", "notifs").chunked(if (colW >= 300) 2 else 1).forEach { pair ->
            val w = if (pair.size == 2) (colW - GAP_X) / 2 else min(colW, 200)
            rows += Row(pair.map { member(it, w, if (it.type == "date") 28 else 32) })
        }
        ofType("ring").forEach { rows += Row(listOf(member(it, min(colW, 232), min(colW, 232)))) }
        val perRow = max(1, min(3, (colW + GAP_X) / (92 + GAP_X)))
        ofType("stat").chunked(perRow).forEach { chunk ->
            val w = min(140, (colW - (chunk.size - 1) * GAP_X) / chunk.size)
            rows += Row(chunk.map { member(it, w, 56) })
        }
        ofType("weather", "event", "alarm", "sunmoon").chunked(if (colW >= 340) 2 else 1).forEach { pair ->
            if (pair.size == 2) rows += Row(pair.map { member(it, (colW - GAP_X) / 2, 72) })
            else rows += Row(listOf(member(pair[0], colW, 80)))
        }
        ofType("f1weekend").forEach { rows += Row(listOf(member(it, colW, 112))) }
        ofType("prayer").forEach { rows += Row(listOf(member(it, colW, 96))) }
        ofType("media").forEach { rows += Row(listOf(member(it, colW, 52))) }
        ofType("f1standings").forEach { rows += Row(listOf(member(it, colW, 208))) }
        items.filter { it.type !in SPECS }.forEach { rows += Row(listOf(member(it, min(colW, it.box.w), it.box.h))) }

        if (rows.any { row -> row.w > colW }) return null

        val columnRows = List(columns) { ArrayList<Row>() }
        val target = rows.sumOf { it.h + GAP_Y } / columns
        var c = 0
        var used = 0
        for (row in rows) {
            if (c < columns - 1 && used + row.h / 2 > target) { c++; used = 0 }
            columnRows[c] += row
            used += row.h + GAP_Y
        }

        val available = o.height - o.top - o.bottom
        fun factor(list: List<Row>) = (available - (list.size - 1) * GAP_Y).toDouble() / list.sumOf { it.h }
        val boxes = LinkedHashMap<String, Box>()
        val hidden = ArrayList<String>()
        columnRows.forEachIndexed { index, list ->
            while (list.isNotEmpty() && factor(list) < 0.7) hidden += list.removeAt(list.lastIndex).members.map { it.item.id }
            val k = if (list.isEmpty()) 1.0 else min(1.0, factor(list))
            var y = o.top
            val x0 = margin + index * (colW + gutter)
            for (row in list) {
                val scaled = row.members.map { m ->
                    val (w, h) = if (specOf(m.item).family == Family.Ring) (m.h * k).toInt().floor4().let { it to it } else m.w to (m.h * k).toInt().floor4()
                    Member(m.item, w, max(h, specOf(m.item).minH.floor4()))
                }
                val rowH = scaled.maxOf { it.h }
                var x = if (leftAligned) x0 else (x0 + (colW - (scaled.sumOf { it.w } + (scaled.size - 1) * GAP_X)) / 2).floor4()
                for (m in scaled) {
                    boxes[m.item.id] = Box(x, (y + (rowH - m.h) / 2).round4(), m.w, m.h)
                    x += m.w + GAP_X
                }
                y += rowH + GAP_Y
            }
        }
        return Base(name, boxes, hidden, empty = 0, leftover = 0, mirrorable = columns > 1 || leftAligned)
    }

    private val Orientation.top get() = if (this == Orientation.Portrait) 40 else 16
    private val Orientation.bottom get() = if (this == Orientation.Portrait) 32 else 16

    // ---- variations ----

    private enum class Anchor { Keep, Top, Middle, Bottom, Spread }

    /** The whole composition slid up, down or to the middle of the screen, or its gaps opened out to fill it. */
    private fun anchored(boxes: Map<String, Box>, mode: Anchor, o: Orientation): Map<String, Box> {
        if (mode == Anchor.Keep) return boxes
        val top = boxes.values.minOf { it.y }
        val bottom = boxes.values.maxOf { it.bottom }
        val lo = o.top
        val hi = o.height - o.bottom
        fun shift(dy: Int) = boxes.mapValues { (_, b) -> b.copy(y = b.y + dy) }
        return when (mode) {
            Anchor.Top -> shift((lo - top).round4())
            Anchor.Bottom -> shift((hi - bottom).round4())
            Anchor.Middle -> shift(((lo + hi - top - bottom) / 2).round4())
            else -> {
                // Positions stretch while sizes stay, so widgets that cleared each other still do.
                val k = ((hi - lo).toDouble() / max(1, bottom - top)).coerceIn(1.0, 1.4)
                boxes.mapValues { (_, b) -> b.copy(y = (lo + (b.y - top) * k).toInt().round4()) }
            }
        }
    }

    /** The biggest widget made bigger or smaller about its own centre; null if it would not change. */
    private fun heroScaled(boxes: Map<String, Box>, scale: Double, byId: Map<String, WidgetItem>): Map<String, Box>? {
        val (id, b) = boxes.entries.maxByOrNull { (_, v) -> v.w * v.h } ?: return null
        val (w, h) = sized(specOf(byId.getValue(id)), (b.w * scale).toInt(), (b.h * scale).toInt())
        if (w == b.w && h == b.h) return null
        return boxes + (id to Box((b.x + (b.w - w) / 2).round4(), (b.y + (b.h - h) / 2).round4(), w, h))
    }

    private fun apart(a: Box, b: Box) =
        a.x >= b.right + MIN_GAP || b.x >= a.right + MIN_GAP || a.y >= b.bottom + MIN_GAP || b.y >= a.bottom + MIN_GAP

    private fun isValid(boxes: Map<String, Box>, o: Orientation): Boolean {
        val list = boxes.values.toList()
        for (b in list) if (b.x < EDGE || b.right > o.width - EDGE || b.y < 0 || b.bottom > o.height || b.w < 20 || b.h < 20) return false
        for (i in list.indices) for (j in i + 1 until list.size) if (!apart(list[i], list[j])) return false
        return true
    }

    // ---- the score ----

    private class Candidate(val name: String, val boxes: Map<String, Box>, val hidden: List<String>, val score: Double)

    private fun score(boxes: Map<String, Box>, byId: Map<String, WidgetItem>, base: Base, o: Orientation): Double {
        val all = boxes.values.toList()
        val w = o.width.toDouble()
        val h = o.height.toDouble()
        var s = 100.0 - base.hidden.size * 100 - base.empty * 8.0 - base.leftover * 5.0

        // Balance: where the weight of the widgets sits against the middle of the screen.
        val area = all.sumOf { it.w.toDouble() * it.h }
        val centreX = all.sumOf { (it.x + it.w / 2.0) * it.w * it.h } / area
        s -= 40 * abs(centreX - w / 2) / w

        // Margins: the same room at both sides, and never cramped.
        val left = all.minOf { it.x }
        val right = all.minOf { o.width - it.right }
        s -= 30 * abs(left - right) / w
        if (min(left, right) < 12) s -= 5

        // Alignment: widgets sharing a left edge, right edge or centre line look intended.
        var shared = 0
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]
            val b = all[j]
            if (a.x == b.x || a.right == b.right || abs((2 * a.x + a.w) - (2 * b.x + b.w)) <= 2) shared++
        }
        s += 5 * min(2.5, shared.toDouble() / all.size)

        // Hierarchy: the biggest thing should be one worth looking at, and the clock should sit high.
        val biggest = boxes.entries.maxByOrNull { (_, b) -> b.w * b.h }!!.key
        if (byId.getValue(biggest).type in HEROES) s += 5
        if (o == Orientation.Portrait) {
            boxes.entries.firstOrNull { byId.getValue(it.key).type == "clock" }?.let { (_, b) -> if (b.y + b.h / 2 < h * 0.6) s += 3 }
        }

        // Use of space: neither crowded into a corner nor full of holes.
        val top = all.minOf { it.y }
        val bottom = all.maxOf { it.bottom }
        val spanW = all.maxOf { it.right } - left
        val spanArea = spanW.toDouble() * (bottom - top)
        val filled = area / spanArea
        s += 10 * min(filled, 0.7) - 20 * max(0.0, 0.35 - filled)
        s += 14 * min(0.85, spanArea / (w * h))
        s -= 15 * abs(top - (o.height - bottom)) / h

        // Rhythm: the gaps between bands of widgets should be about the same.
        val gaps = ArrayList<Double>()
        var reach = Int.MIN_VALUE
        for (b in all.sortedBy { it.y }) {
            if (reach != Int.MIN_VALUE && b.y > reach) gaps += (b.y - reach).toDouble()
            reach = max(reach, b.bottom)
        }
        if (gaps.size >= 2) {
            val mean = gaps.average()
            s -= 40 * sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size) / h
        }
        return s
    }

    private val HEROES = setOf("clock", "ring", "weather", "f1weekend", "f1standings", "prayer")

    /** The best variation of [base] by score, with a little luck added so near-ties vary from round to round. */
    private fun bestVariation(base: Base, byId: Map<String, WidgetItem>, o: Orientation, rng: Random): Candidate? {
        if (base.boxes.isEmpty()) return null
        var best: Candidate? = null
        for (mirror in if (base.mirrorable) listOf(false, true) else listOf(false)) {
            for (scale in HERO_SCALES) for (anchor in Anchor.values()) {
                var boxes: Map<String, Box> = base.boxes
                if (mirror) boxes = boxes.mapValues { (_, b) -> b.copy(x = o.width - b.right) }
                if (scale != 1.0) boxes = heroScaled(boxes, scale, byId) ?: continue
                boxes = anchored(boxes, anchor, o)
                if (!isValid(boxes, o)) continue
                val s = score(boxes, byId, base, o) + rng.nextDouble() * JITTER
                if (best == null || s > best.score) best = Candidate(base.name, boxes, base.hidden, s)
            }
        }
        return best
    }

    // ---- fitting a widget's look to the box it landed in ----

    private fun weatherLook(box: Box) = when {
        box.h >= 140 && box.w >= 150 -> "big"
        box.h >= 92 -> "stacked"
        box.h <= 48 -> "compact"
        else -> "side"
    }

    private fun isTall(box: Box) = box.h >= 140 && box.h * 10 >= box.w * 8

    /**
     * [item] in [box]. Weather takes the layout that suits its new shape (Poster, Card, Classic or
     * Ticker) when the shape has changed class; a clock in a tall box is drawn Stacked, and gets its
     * old style back in a wider one. Everything else about a widget is left alone.
     */
    private fun styled(item: WidgetItem, box: Box): WidgetItem {
        var settings = item.settings
        when (item.type) {
            "weather" -> {
                val look = weatherLook(box)
                if (look != weatherLook(item.box)) settings = settings.with(WeatherWidget.LAYOUT, look)
            }
            "clock" -> {
                val style = settings[ClockWidget.STYLE]
                if (isTall(box) && style != "stacked") {
                    settings = settings.with(STYLE_BEFORE, style).with(ClockWidget.STYLE, "stacked")
                } else if (!isTall(box) && style == "stacked") {
                    val before = settings[STYLE_BEFORE]
                    if (before.isNotEmpty()) settings = settings.with(ClockWidget.STYLE, before).with(STYLE_BEFORE, "")
                }
            }
        }
        return item.copy(box = box, settings = settings)
    }

    // ---- the entry point ----

    /**
     * [layout] with its visible widgets rearranged, or null when none are visible. Hidden widgets are
     * left exactly as they are. Different [round]s give different arrangements of the same widgets.
     */
    fun arrange(layout: Layout, orientation: Orientation, round: Int): Result? {
        val items = layout.items.filter { it.visible }
        if (items.isEmpty()) return null
        val byId = items.associateBy { it.id }
        val lap = Math.floorDiv(round, POOL)
        val rng = Random(lap * 7919L + items.size)

        val bases = catalogue(orientation).map { fill(it, items, orientation) } + listOfNotNull(
            flow("Centred column", items, orientation, 1).takeIf { orientation == Orientation.Portrait },
            flow("Left column", items, orientation, 1, leftAligned = true).takeIf { orientation == Orientation.Portrait },
            flow("Twin columns", items, orientation, 2),
            flow("Triple columns", items, orientation, 3).takeIf { orientation == Orientation.Landscape },
        )
        val ranked = bases.mapNotNull { bestVariation(it, byId, orientation, rng) }.sortedByDescending { it.score }
        if (ranked.isEmpty()) return null
        val pool = ranked.filterIndexed { i, c -> i < MIN_POOL || c.score >= ranked[0].score - KEEP_WITHIN }.take(POOL)
        val pick = pool[Math.floorMod(round, pool.size)]

        val boxes = pick.boxes
        val arranged = layout.items.map { item ->
            val box = boxes[item.id]
            when {
                box != null -> styled(item, box)
                item.id in pick.hidden -> item.copy(visible = false)
                else -> item
            }
        }
        return Result(Layout(arranged), pick.name, pick.hidden.size)
    }

    /** What is wrong with the catalogue's own drawings, for the tests: slots off the canvas or overlapping each other. */
    internal fun catalogueProblems(): List<String> = buildList {
        for (o in Orientation.values()) for (p in catalogue(o)) {
            val boxes = p.slots.mapIndexed { i, s -> "${s.letter}$i" to s.box }.toMap()
            if (!isValid(boxes, o)) add("${o.name}/${p.name}")
            for (s in p.slots) {
                val spec = SPECS.getValue(LETTER_TO_TYPE.getValue(s.letter))
                if (!inRange(spec, s.box)) add("${o.name}/${p.name}/${s.letter} size ${s.box.w}x${s.box.h}")
            }
        }
    }
}
