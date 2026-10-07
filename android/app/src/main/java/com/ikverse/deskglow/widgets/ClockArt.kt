package com.ikverse.deskglow.widgets

/** A point in a glyph's own drawing space. */
data class Pt(val x: Float, val y: Float)

/** A stroked line through [points]; [closed] joins the last point back to the first. */
data class Polyline(val points: List<Pt>, val closed: Boolean = false) {
    fun map(f: (Pt) -> Pt) = Polyline(points.map(f), closed)
}

/** What a drawn clock is made of, in its own units; the widget scales it to fit its box. */
sealed interface ArtPart

/** Lines drawn with round ends. [cornerRadius] above zero rounds every corner. [cutout] draws in the background colour. */
data class StrokePart(val lines: List<Polyline>, val width: Float, val cornerRadius: Float = 0f, val cutout: Boolean = false) : ArtPart

data class FillPart(val polygons: List<List<Pt>>, val alpha: Float = 1f) : ArtPart

data class DotPart(val centres: List<Pt>, val radius: Float, val alpha: Float = 1f, val cutout: Boolean = false) : ArtPart

data class ClockArt(val left: Float, val top: Float, val width: Float, val height: Float, val parts: List<ArtPart>)

/**
 * The drawn clock styles: geometry only, so it is cheap to build and easy to test. Each style has
 * Western digits and (except seven-segment, whose seven bars cannot form ٢, ٣ or ٤) Arabic ones.
 */
object ClockStyles {
    val DRAWN = listOf("squared" to "Squared", "seg" to "Seven-segment", "dots" to "Dot matrix", "outline" to "Outline", "rounded" to "Rounded", "stacked" to "Stacked")

    fun isDrawn(style: String) = DRAWN.any { it.first == style }

    /** Styles that can show Arabic numerals. */
    fun supportsArabic(style: String) = style != "seg"

    /** The art for [time] in [style], or null for a style drawn as text (a font). */
    fun build(style: String, time: TimeParts): ClockArt? = when (style) {
        "squared" -> squared(time.text)
        "outline" -> outline(time.text)
        "rounded" -> rounded(time.text)
        "stacked" -> stacked(time)
        "seg" -> segments(toWestern(time.text))
        "dots" -> dots(time.text)
        else -> null
    }

    private fun toWestern(text: String) =
        buildString { text.forEach { append(if (it in '٠'..'٩') '0' + (it - '٠') else it) } }

    // ---- squared: 96 x 100 boxes, round-jointed strokes ----

    private const val SQ_W = 96f
    private const val SQ_GAP = 14f
    private const val SQ_COLON = 12f

    private fun line(vararg xy: Float, closed: Boolean = false) =
        Polyline(xy.toList().chunked(2).map { (x, y) -> Pt(x, y) }, closed)

    internal val SQUARED: Map<Char, List<Polyline>> = mapOf(
        '0' to listOf(line(6f, 6f, 90f, 6f, 90f, 94f, 6f, 94f, closed = true)),
        '1' to listOf(line(24f, 24f, 48f, 6f, 48f, 94f)),
        '2' to listOf(line(6f, 6f, 90f, 6f, 90f, 50f, 6f, 50f, 6f, 94f, 90f, 94f)),
        '3' to listOf(line(6f, 6f, 90f, 6f, 90f, 94f, 6f, 94f), line(34f, 50f, 90f, 50f)),
        '4' to listOf(line(6f, 6f, 6f, 50f, 90f, 50f), line(90f, 6f, 90f, 94f)),
        '5' to listOf(line(90f, 6f, 6f, 6f, 6f, 50f, 90f, 50f, 90f, 94f, 6f, 94f)),
        '6' to listOf(line(90f, 6f, 6f, 6f, 6f, 94f, 90f, 94f, 90f, 50f, 6f, 50f)),
        '7' to listOf(line(6f, 6f, 90f, 6f, 90f, 94f)),
        '8' to listOf(line(6f, 6f, 90f, 6f, 90f, 94f, 6f, 94f, closed = true), line(6f, 50f, 90f, 50f)),
        '9' to listOf(line(90f, 50f, 6f, 50f, 6f, 6f, 90f, 6f, 90f, 94f, 6f, 94f)),
        // Arabic-Indic digits, in the same box and the same squared hand.
        '٠' to listOf(line(41f, 48f, 55f, 48f, 55f, 62f, 41f, 62f, closed = true)), // ٠ a small dot
        '١' to listOf(line(48f, 6f, 48f, 94f)), // ١
        '٢' to listOf(line(36f, 94f, 36f, 30f, 66f, 30f, 66f, 6f)), // ٢ a stem with one tooth
        '٣' to listOf(line(26f, 94f, 26f, 30f, 70f, 30f, 70f, 6f), line(48f, 30f, 48f, 6f)), // ٣ two teeth
        '٤' to listOf(line(68f, 6f, 30f, 6f, 30f, 48f, 60f, 48f), line(30f, 48f, 30f, 94f, 70f, 94f)), // ٤
        '٥' to listOf(line(28f, 38f, 68f, 38f, 68f, 94f, 28f, 94f, closed = true)), // ٥ a ring
        '٦' to listOf(line(30f, 6f, 66f, 6f, 66f, 94f)), // ٦
        '٧' to listOf(line(24f, 6f, 48f, 94f, 72f, 6f)), // ٧ a V
        '٨' to listOf(line(24f, 94f, 48f, 6f, 72f, 94f)), // ٨ an upturned V
        '٩' to listOf(line(70f, 44f, 26f, 44f, 26f, 6f, 70f, 6f, 70f, 94f)), // ٩
    )

    /** A run of squared glyphs and colons, laid left to right. Returns the lines, colon dots and total width. */
    private fun squaredRun(text: String, map: (Pt) -> Pt = { it }, width: Float = SQ_W, gap: Float = SQ_GAP, colon: Float = SQ_COLON): Triple<List<Polyline>, List<Pt>, Float> {
        val lines = ArrayList<Polyline>()
        val dots = ArrayList<Pt>()
        var x = 0f
        for (ch in text) {
            if (ch == ':') {
                dots += Pt(x + colon / 2, 32f)
                dots += Pt(x + colon / 2, 68f)
                x += colon + gap
            } else {
                val glyph = SQUARED[ch] ?: continue
                val dx = x
                glyph.forEach { lines += it.map(map).map { p -> Pt(p.x + dx, p.y) } }
                x += width + gap
            }
        }
        return Triple(lines, dots, (x - gap).coerceAtLeast(1f))
    }

    private fun squared(text: String): ClockArt {
        val (lines, dots, width) = squaredRun(text)
        return ClockArt(0f, 0f, width, 100f, listOf(StrokePart(lines, 12f), DotPart(dots, 6f)))
    }

    /** The squared digits drawn as a thick line with a background-coloured line inside, so only the two edges show. */
    private fun outline(text: String): ClockArt {
        val (lines, dots, width) = squaredRun(text)
        return ClockArt(
            -2f, -2f, width + 4f, 104f,
            listOf(StrokePart(lines, 16f), StrokePart(lines, 6f, cutout = true), DotPart(dots, 8f), DotPart(dots, 3f, cutout = true)),
        )
    }

    /** Hours over minutes (over seconds), each row centred. */
    private fun stacked(time: TimeParts): ClockArt {
        val rows = listOfNotNull(time.hours, time.minutes, time.seconds).map { squaredRun(it) }
        val maxWidth = rows.maxOf { it.third }
        val lines = ArrayList<Polyline>()
        rows.forEachIndexed { index, (rowLines, _, rowWidth) ->
            val dx = (maxWidth - rowWidth) / 2
            val dy = index * 120f
            rowLines.forEach { line -> lines += line.map { Pt(it.x + dx, it.y + dy) } }
        }
        return ClockArt(0f, 0f, maxWidth, rows.size * 100f + (rows.size - 1) * 20f, listOf(StrokePart(lines, 12f)))
    }

    /** The squared glyphs narrowed to 70 wide, drawn thicker with big rounded corners. */
    private fun rounded(text: String): ClockArt {
        val narrow: (Pt) -> Pt = { Pt(10f + (it.x - 6f) * 50f / 84f, 10f + (it.y - 6f) * 80f / 88f) }
        val (lines, dots, width) = squaredRun(text, narrow, width = 70f, gap = 12f, colon = 18f)
        val colonDots = dots.map { Pt(it.x, if (it.y < 50f) 34f else 66f) }
        return ClockArt(0f, 0f, width, 100f, listOf(StrokePart(lines, 18f, cornerRadius = 22f), DotPart(colonDots, 9f)))
    }

    // ---- seven-segment: all seven bars per digit, unlit ones faint, leaning like an LCD ----

    private val SEGMENT_SHAPES: Map<Char, List<Pt>> = mapOf(
        'a' to pts(7f, 5f, 12f, 0f, 48f, 0f, 53f, 5f, 48f, 10f, 12f, 10f),
        'g' to pts(7f, 50f, 12f, 45f, 48f, 45f, 53f, 50f, 48f, 55f, 12f, 55f),
        'd' to pts(7f, 95f, 12f, 90f, 48f, 90f, 53f, 95f, 48f, 100f, 12f, 100f),
        'f' to pts(5f, 8f, 10f, 13f, 10f, 43f, 5f, 48f, 0f, 43f, 0f, 13f),
        'e' to pts(5f, 52f, 10f, 57f, 10f, 87f, 5f, 92f, 0f, 87f, 0f, 57f),
        'b' to pts(55f, 8f, 60f, 13f, 60f, 43f, 55f, 48f, 50f, 43f, 50f, 13f),
        'c' to pts(55f, 52f, 60f, 57f, 60f, 87f, 55f, 92f, 50f, 87f, 50f, 57f),
    )
    internal val SEGMENTS_ON = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
        '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg",
    )
    private const val SKEW = 0.1228f // tan 7°

    private fun pts(vararg xy: Float) = xy.toList().chunked(2).map { (x, y) -> Pt(x, y) }

    private fun segments(text: String): ClockArt {
        val lit = ArrayList<List<Pt>>()
        val unlit = ArrayList<List<Pt>>()
        var x = 0f
        for (ch in text) {
            if (ch == ':') {
                lit += square(x, 28f, 11f)
                lit += square(x, 61f, 11f)
                x += 27f
                continue
            }
            val on = SEGMENTS_ON[ch] ?: continue
            for ((name, shape) in SEGMENT_SHAPES) {
                val moved = shape.map { Pt(it.x + x, it.y) }
                if (name in on) lit += moved else unlit += moved
            }
            x += 76f
        }
        val lean: (List<Pt>) -> List<Pt> = { poly -> poly.map { Pt(it.x - SKEW * it.y, it.y) } }
        val width = (x - 16f).coerceAtLeast(1f)
        return ClockArt(-13f, -1f, width + 14f, 102f, listOf(FillPart(unlit.map(lean), alpha = 0.09f), FillPart(lit.map(lean))))
    }

    private fun square(x: Float, y: Float, size: Float) = listOf(Pt(x, y), Pt(x + size, y), Pt(x + size, y + size), Pt(x, y + size))

    // ---- dot matrix: 5 x 7 dots per digit, unlit dots faint ----

    internal val DOTS: Map<Char, String> = mapOf(
        '0' to "01110 10001 10011 10101 11001 10001 01110", '1' to "00100 01100 00100 00100 00100 00100 01110",
        '2' to "01110 10001 00001 00010 00100 01000 11111", '3' to "11110 00001 00001 01110 00001 00001 11110",
        '4' to "00010 00110 01010 10010 11111 00010 00010", '5' to "11111 10000 11110 00001 00001 10001 01110",
        '6' to "00110 01000 10000 11110 10001 10001 01110", '7' to "11111 00001 00010 00100 01000 01000 01000",
        '8' to "01110 10001 10001 01110 10001 10001 01110", '9' to "01110 10001 10001 01111 00001 00010 01100",
        '٠' to "00000 00000 00100 01110 00100 00000 00000", '١' to "00100 00100 00100 00100 00100 00100 00100",
        '٢' to "01001 01110 01000 01000 01000 01000 01000", '٣' to "10101 11111 10000 10000 10000 10000 10000",
        '٤' to "01111 10000 01110 10000 10000 10000 01111", '٥' to "00000 01110 10001 10001 10001 10001 01110",
        '٦' to "11110 00010 00010 00010 00010 00010 00010", '٧' to "10001 10001 01010 01010 01010 00100 00100",
        '٨' to "00100 00100 01010 01010 01010 10001 10001", '٩' to "01110 10001 10001 01111 00001 00001 00001",
    )

    private fun dots(text: String): ClockArt {
        val lit = ArrayList<Pt>()
        val unlit = ArrayList<Pt>()
        var x = 0f
        for (ch in text) {
            if (ch == ':') {
                for (row in 0 until 7) (if (row == 2 || row == 4) lit else unlit).add(Pt(x + 0.5f, row + 0.5f))
                x += 2f
                continue
            }
            val rows = DOTS[ch]?.split(' ') ?: continue
            rows.forEachIndexed { row, bits ->
                bits.forEachIndexed { col, bit -> (if (bit == '1') lit else unlit).add(Pt(x + col + 0.5f, row + 0.5f)) }
            }
            x += 6f
        }
        return ClockArt(0f, 0f, (x - 1f).coerceAtLeast(1f), 7f, listOf(DotPart(unlit, 0.42f, alpha = 0.1f), DotPart(lit, 0.42f)))
    }
}
