package com.ikverse.deskglow.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourHarmonyTest {
    private fun alpha(argb: Int) = argb ushr 24

    @Test
    fun `a colour goes to hue, strength and brightness and back`() {
        for (argb in listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFB547.toInt(), 0xFF44B98A.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt())) {
            assertEquals(Integer.toHexString(argb), argb, Hsv.of(argb).toArgb())
        }
        val amber = Hsv.of(0xFFFFB547.toInt())
        assertEquals(36f, amber.hue, 1f)
        assertEquals(0.72f, amber.saturation, 0.01f)
        assertEquals(1f, amber.value, 0.001f)
        assertEquals(0f, Hsv.of(0xFFFFFFFF.toInt()).saturation, 0f)
    }

    @Test
    fun `a coloured base gets nine suggestions round the wheel, none of them dim or grey`() {
        val base = 0xFF2F9BD8.toInt()
        val suggestions = ColourHarmony.suggestions(base)
        assertEquals(listOf("Complement", "Split", "Split", "Neighbour", "Neighbour", "Triad", "Triad", "Paler", "Deeper"), suggestions.map { it.name })
        val hue = Hsv.of(base).hue
        fun hueOf(name: String, index: Int = 0) = Hsv.of(suggestions.filter { it.name == name }[index].argb).hue
        fun apart(a: Float, b: Float) = minOf(Math.abs(a - b), 360 - Math.abs(a - b))
        assertEquals(180f, apart(hue, hueOf("Complement")), 1.5f)
        assertEquals(120f, apart(hue, hueOf("Triad", 0)), 1.5f)
        assertEquals(30f, apart(hue, hueOf("Neighbour", 0)), 1.5f)
        for (s in suggestions) {
            assertEquals(s.name, 0xFF, alpha(s.argb))
            val hsv = Hsv.of(s.argb)
            assertTrue("${s.name} is bright enough", hsv.value >= 0.45f)
            assertTrue("${s.name} is a colour", hsv.saturation >= 0.2f)
        }
        assertTrue(suggestions.map { it.argb }.toSet().size == suggestions.size)
    }

    @Test
    fun `white and grey get named accents instead of turns of the wheel`() {
        val forWhite = ColourHarmony.suggestions(0xFFFFFFFF.toInt())
        assertEquals(forWhite, ColourHarmony.suggestions(0xFF9A9A9A.toInt()))
        assertEquals(listOf("Amber", "Mint", "Sky", "Coral", "Violet", "Rose", "Lime", "Ice"), forWhite.map { it.name })
        assertTrue(forWhite.all { Hsv.of(it.argb).saturation > 0.3f && alpha(it.argb) == 0xFF })
    }

    @Test
    fun `the duos are distinct, named and opaque, with a bright colour and a saturated accent`() {
        val duos = ColourHarmony.DUOS
        assertTrue(duos.size >= 10)
        assertEquals(duos.size, duos.map { it.name }.toSet().size)
        assertEquals(duos.size, duos.map { it.colour to it.accent }.toSet().size)
        for (duo in duos) {
            assertTrue(duo.name, duo.name.isNotBlank())
            assertEquals(duo.name, 0xFF, alpha(duo.colour))
            assertEquals(duo.name, 0xFF, alpha(duo.accent))
            assertTrue("${duo.name} colour reads on black", Hsv.of(duo.colour).value >= 0.8f)
            assertTrue("${duo.name} accent is a colour", Hsv.of(duo.accent).saturation >= 0.3f)
        }
    }
}
