package com.ikverse.deskglow.ui.editor

import kotlin.math.abs

/** A colour's hue (0 to 360), saturation and value (0 to 1): the three the picker's pad and strip set. */
data class Hsv(val hue: Float, val saturation: Float, val value: Float) {
    fun toArgb(): Int = ColourHarmony.argbOf(hue, saturation, value)

    companion object {
        fun of(argb: Int): Hsv = ColourHarmony.hsvOf(argb)
    }
}

/** A colour the picker suggests, with the word for how it relates to the colour it was worked out from. */
data class Suggestion(val name: String, val argb: Int)

/** A named pair that sets a widget's colour and accent together. */
data class Duo(val name: String, val colour: Int, val accent: Int)

/**
 * The maths behind the colour picker's suggestions: colours that sit well beside a given one, worked
 * out round the colour wheel, and a short list of pairs chosen by hand. Everything here is plain
 * arithmetic, so it can be tested without a phone.
 */
object ColourHarmony {
    /** Suggestions are never dimmer than this: the display is black, and a dark colour vanishes on it. */
    private const val LEAST_VALUE = 0.78f
    /** A hue turned this far round the wheel stays recognisably a colour; greys are given no hue at all. */
    private const val LEAST_SATURATION = 0.55f
    /** Below this a colour is white or grey, and turning its hue means nothing. */
    private const val GREY = 0.18f

    /** Accents that sit well against white or grey text, where the wheel has nothing to say. */
    private val FOR_GREYS = listOf(
        Suggestion("Amber", 0xFFFFB547.toInt()), Suggestion("Mint", 0xFF44B98A.toInt()), Suggestion("Sky", 0xFF4FC3F7.toInt()),
        Suggestion("Coral", 0xFFF2766B.toInt()), Suggestion("Violet", 0xFFB48CF2.toInt()), Suggestion("Rose", 0xFFF26B8A.toInt()),
        Suggestion("Lime", 0xFFB5E853.toInt()), Suggestion("Ice", 0xFF9FDCFF.toInt()),
    )

    /**
     * Colours that go with [base]: its complement, the two either side of the complement, its two
     * neighbours, the two that make a triad with it, then a paler and a deeper version of itself. For a
     * white or grey base, a set of accents that read well against it instead.
     */
    fun suggestions(base: Int): List<Suggestion> {
        val hsv = hsvOf(base)
        if (hsv.saturation < GREY) return FOR_GREYS
        val s = maxOf(hsv.saturation, LEAST_SATURATION)
        val v = maxOf(hsv.value, LEAST_VALUE)
        fun turned(name: String, degrees: Float) = Suggestion(name, argbOf(wrap(hsv.hue + degrees), s, v))
        return listOf(
            turned("Complement", 180f),
            turned("Split", 150f),
            turned("Split", 210f),
            turned("Neighbour", -30f),
            turned("Neighbour", 30f),
            turned("Triad", 120f),
            turned("Triad", 240f),
            Suggestion("Paler", argbOf(hsv.hue, hsv.saturation * 0.4f, 1f)),
            Suggestion("Deeper", argbOf(hsv.hue, 1f, maxOf(hsv.value * 0.72f, 0.45f))),
        )
    }

    /** Pairs that look good together on a black screen: the text colour first, the accent second. */
    val DUOS = listOf(
        Duo("Ember", 0xFFFFF1DC.toInt(), 0xFFFFB547.toInt()),
        Duo("Mint", 0xFFEFFFF6.toInt(), 0xFF44B98A.toInt()),
        Duo("Ocean", 0xFFDFF3FF.toInt(), 0xFF3FA9F5.toInt()),
        Duo("Rosé", 0xFFFFE9EE.toInt(), 0xFFF26B8A.toInt()),
        Duo("Lavender", 0xFFEDE7FF.toInt(), 0xFFB48CF2.toInt()),
        Duo("Terminal", 0xFF9CFF9C.toInt(), 0xFF2ECC40.toInt()),
        Duo("Ice", 0xFFE6F7FF.toInt(), 0xFF8FD3FF.toInt()),
        Duo("Sunset", 0xFFFFD9B3.toInt(), 0xFFF2766B.toInt()),
        Duo("Copper", 0xFFF5E6D3.toInt(), 0xFFC97C3A.toInt()),
        Duo("Neon", 0xFFFFFFFF.toInt(), 0xFFFF2BD6.toInt()),
        Duo("Sage", 0xFFE8F0E3.toInt(), 0xFF7FB069.toInt()),
        Duo("Gold", 0xFFFFFFFF.toInt(), 0xFFD4AF37.toInt()),
    )

    private fun wrap(hue: Float): Float = ((hue % 360f) + 360f) % 360f

    /** Opaque ARGB from a hue (degrees), saturation and value (0 to 1). */
    fun argbOf(hue: Float, saturation: Float, value: Float): Int {
        val h = wrap(hue) / 60f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs(h % 2 - 1))
        val m = v - c
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(f: Float) = Math.round((f + m) * 255f).coerceIn(0, 255)
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    fun hsvOf(argb: Int): Hsv {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val hue = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return Hsv(wrap(hue), if (max == 0f) 0f else d / max, max)
    }
}
