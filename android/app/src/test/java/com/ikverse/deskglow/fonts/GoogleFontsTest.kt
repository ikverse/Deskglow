package com.ikverse.deskglow.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleFontsTest {
    /** A cut of the real list from fonts.google.com (2026-10-07): the 12 most popular, 6 Arabic, one with neither script. */
    private val real = javaClass.classLoader!!.getResource("google-fonts-catalog.json")!!.readText()

    @Test
    fun `the real list is read most popular first, keeping Latin and Arabic families`() {
        val fonts = GoogleFonts.parseCatalog(real)
        assertEquals("Roboto", fonts.first().family)
        assertEquals(fonts.sortedBy { it.popularity }, fonts)
        assertFalse("a family with neither Latin nor Arabic is left out", fonts.any { it.family == "Allkin" })
        assertEquals(18, fonts.size)
    }

    @Test
    fun `Google's own families stay, as they are open source like the rest`() {
        val fonts = GoogleFonts.parseCatalog(real).map { it.family }
        assertTrue("Google Sans" in fonts)
        assertTrue("Noto Kufi Arabic" in fonts)
    }

    @Test
    fun `scripts, categories and upright weights are read`() {
        val fonts = GoogleFonts.parseCatalog(real).associateBy { it.family }
        val cairo = fonts.getValue("Cairo")
        assertTrue(cairo.arabic && cairo.latin)
        assertEquals(FontCategory.Sans, cairo.category)
        assertEquals(listOf(200, 300, 400, 500, 600, 700, 800, 900, 1000), cairo.weights)
        val lora = fonts.getValue("Lora")
        assertFalse(lora.arabic)
        assertEquals(FontCategory.Serif, lora.category)
        assertEquals(7, GoogleFonts.parseCatalog(real).count { it.arabic })
    }

    @Test
    fun `a guard before the JSON is skipped`() {
        assertEquals(GoogleFonts.parseCatalog(real), GoogleFonts.parseCatalog(")]}'\n$real"))
    }

    @Test
    fun `the copy kept on the phone reads back the same`() {
        val fonts = GoogleFonts.parseCatalog(real)
        assertEquals(fonts, GoogleFonts.decodeCatalog(GoogleFonts.encodeCatalog(fonts)))
    }

    @Test
    fun `font requests are built and answered the way Google's service expects`() {
        assertEquals("https://fonts.googleapis.com/css2?family=Dancing+Script:wght@700", GoogleFonts.cssUrl("Dancing Script", 700))
        assertEquals(
            "https://fonts.googleapis.com/css2?family=Cairo:wght@600&text=%D9%A0%D9%A1%3A",
            GoogleFonts.cssUrl("Cairo", 600, "٠١:"),
        )
        // The shape of the real answer to a non-browser client.
        val css = """@font-face {
  font-family: 'Cairo';
  font-style: normal;
  font-weight: 600;
  src: url(https://fonts.gstatic.com/l/font?kit=SLXgc1nY6HkvangtZmpQdkhzfH5lkSs2SgRjCAGMQ1z0hD45W1TwNje3sHIQq8_LUzo17tKJvDDlvklZvu4kd7hUISxaKKM&skey=ee6e3b9105e1a754&v=v31) format('truetype');
}"""
        assertEquals(
            "https://fonts.gstatic.com/l/font?kit=SLXgc1nY6HkvangtZmpQdkhzfH5lkSs2SgRjCAGMQ1z0hD45W1TwNje3sHIQq8_LUzo17tKJvDDlvklZvu4kd7hUISxaKKM&skey=ee6e3b9105e1a754&v=v31",
            GoogleFonts.fontUrl(css),
        )
        assertNull(GoogleFonts.fontUrl("src: url(https://x/y.woff2) format('woff2');"))
    }

    @Test
    fun `the weight nearest the target is chosen, the heavier on a tie`() {
        assertEquals(500, GoogleFonts.chooseWeight(listOf(300, 400, 500, 700), 500))
        assertEquals(600, GoogleFonts.chooseWeight(listOf(400, 600), 500))
        assertEquals(400, GoogleFonts.chooseWeight(listOf(400), 500))
    }

    @Test
    fun `font ids round trip`() {
        assertEquals("Dancing Script" to 700, FontIds.parseGoogle(FontIds.google("Dancing Script", 700)))
        assertNull(FontIds.parseGoogle("b:cairo"))
        assertNull(FontIds.parseGoogle("g:@400"))
        assertTrue(FontIds.isFont("g:Lobster@400") && FontIds.isFont("b:cairo") && FontIds.isFont("thin"))
        assertFalse(FontIds.isFont("squared"))
    }
}
