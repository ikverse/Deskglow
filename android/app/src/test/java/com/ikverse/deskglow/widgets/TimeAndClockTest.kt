package com.ikverse.deskglow.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TimeAndClockTest {
    private val evening = LocalDateTime.of(2026, 10, 7, 20, 5, 9)
    private val wednesday = LocalDate.of(2026, 10, 7)

    @Test
    fun `the time in 12 and 24 hours, with and without seconds`() {
        assertEquals("8:05", TimeText.parts(evening, h24 = false, seconds = false, arabicDigits = false).text)
        assertEquals("20:05", TimeText.parts(evening, h24 = true, seconds = false, arabicDigits = false).text)
        assertEquals("20:05:09", TimeText.parts(evening, h24 = true, seconds = true, arabicDigits = false).text)
        assertEquals("12:00", TimeText.parts(evening.withHour(0).withMinute(0), h24 = false, seconds = false, arabicDigits = false).text)
        assertEquals("00:00", TimeText.parts(evening.withHour(0).withMinute(0), h24 = true, seconds = false, arabicDigits = false).text)
    }

    @Test
    fun `the time in Arabic numerals`() {
        assertEquals("٨:٠٥", TimeText.parts(evening, h24 = false, seconds = false, arabicDigits = true).text)
        assertEquals("٢٠:٠٥:٠٩", TimeText.parts(evening, h24 = true, seconds = true, arabicDigits = true).text)
    }

    @Test
    fun `the date in English, as the mockup showed it`() {
        assertEquals("Wed, Oct 7", TimeText.date(wednesday, "short", arabic = false, arabicDigits = false))
        assertEquals("Wednesday 7 October", TimeText.date(wednesday, "long", arabic = false, arabicDigits = false))
        assertEquals("07/10/2026", TimeText.date(wednesday, "numeric", arabic = false, arabicDigits = false))
    }

    @Test
    fun `the date in Egyptian Arabic, with either kind of numerals`() {
        assertEquals("الأربعاء، ٧ أكتوبر", TimeText.date(wednesday, "short", arabic = true, arabicDigits = true))
        assertEquals("الأربعاء ٧ أكتوبر ٢٠٢٦", TimeText.date(wednesday, "long", arabic = true, arabicDigits = true))
        assertEquals("٧/١٠/٢٠٢٦", TimeText.date(wednesday, "numeric", arabic = true, arabicDigits = true))
        assertEquals("الأربعاء، 7 أكتوبر", TimeText.date(wednesday, "short", arabic = true, arabicDigits = false))
    }

    @Test
    fun `a stale time from a feed that was asleep is replaced by now`() {
        val now = LocalDateTime.of(2026, 10, 7, 22, 0)
        assertEquals(now, TimeText.fresh(now.minusHours(3), now))
        assertEquals(now.minusSeconds(30), TimeText.fresh(now.minusSeconds(30), now))
    }

    @Test
    fun `every drawn style has every digit, Western and Arabic`() {
        for (digit in "0123456789٠١٢٣٤٥٦٧٨٩") {
            assertNotNull("squared $digit", ClockStyles.SQUARED[digit])
            val rows = ClockStyles.DOTS[digit]?.split(' ')
            assertNotNull("dots $digit", rows)
            assertEquals("dots $digit has 7 rows", 7, rows!!.size)
            rows.forEach { assertTrue("dots $digit row '$it'", it.length == 5 && it.all { c -> c == '0' || c == '1' }) }
        }
        for (digit in "0123456789") assertNotNull(ClockStyles.SEGMENTS_ON[digit])
    }

    @Test
    fun `each drawn style builds art for a real time, in both scripts`() {
        val western = TimeText.parts(evening, h24 = false, seconds = true, arabicDigits = false)
        val arabic = TimeText.parts(evening, h24 = false, seconds = true, arabicDigits = true)
        for ((style, _) in ClockStyles.DRAWN) {
            for (parts in listOf(western, arabic)) {
                val art = ClockStyles.build(style, parts)
                assertNotNull("$style ${parts.text}", art)
                assertTrue("$style has size", art!!.width > 0 && art.height > 0)
                val marks = art.parts.sumOf { part ->
                    when (part) {
                        is StrokePart -> part.lines.size
                        is FillPart -> part.polygons.size
                        is DotPart -> part.centres.size
                    }
                }
                assertTrue("$style ${parts.text} draws something", marks > 0)
            }
        }
        assertNull("fonts are drawn as text, not art", ClockStyles.build("b:cinzel", western))
    }

    @Test
    fun `seven-segment shows Western digits when handed Arabic ones, and is marked as unable to`() {
        val arabic = TimeText.parts(evening, h24 = false, seconds = false, arabicDigits = true)
        val western = TimeText.parts(evening, h24 = false, seconds = false, arabicDigits = false)
        assertEquals(ClockStyles.build("seg", western), ClockStyles.build("seg", arabic))
        assertTrue(!ClockStyles.supportsArabic("seg"))
    }

    @Test
    fun `turning on Arabic numerals moves the clock off styles that cannot show them`() {
        val seg = ClockWidget.defaults.with(ClockWidget.STYLE, "seg")
        assertEquals("seg", ClockWidget.normalise(seg)[ClockWidget.STYLE])
        val arabicSeg = seg.with(Common.ARABIC, true)
        assertEquals("squared", ClockWidget.normalise(arabicSeg)[ClockWidget.STYLE])
        val westernNumerals = arabicSeg.with(Common.NUMERALS, "western")
        assertEquals("seg", ClockWidget.normalise(westernNumerals)[ClockWidget.STYLE])
        val latinFont = ClockWidget.defaults.with(ClockWidget.STYLE, "b:bebas_neue").with(Common.ARABIC, true)
        assertEquals("squared", ClockWidget.normalise(latinFont)[ClockWidget.STYLE])
        val arabicFont = latinFont.with(ClockWidget.STYLE, "b:cairo")
        assertEquals("b:cairo", ClockWidget.normalise(arabicFont)[ClockWidget.STYLE])
        val date = DateWidget.defaults.with(DateWidget.FONT, "b:monoton").with(Common.ARABIC, true)
        assertEquals("thin", DateWidget.normalise(date)[DateWidget.FONT])
    }

    @Test
    fun `battery readings are shown with their units, or a dash when unknown`() {
        val battery = com.ikverse.deskglow.data.BatteryState(
            level = 100, status = com.ikverse.deskglow.data.ChargeStatus.Full, plugged = true,
            voltageMv = 4224, temperatureTenths = 335, currentMa = 296,
        )
        assertEquals(Triple("33.5", "°C", "Temp"), StatWidget.reading("temp", battery))
        assertEquals(Triple("4.224", "V", "Voltage"), StatWidget.reading("voltage", battery))
        assertEquals(Triple("1.25", "W", "Power"), StatWidget.reading("power", battery))
        assertEquals(Triple("296", "mA", "Current"), StatWidget.reading("current", battery))
        assertEquals(Triple("Full", "", "Estimate"), StatWidget.reading("time", battery))
        val charging = battery.copy(status = com.ikverse.deskglow.data.ChargeStatus.Charging, timeToFullMs = 104 * 60_000L, currentMa = null)
        assertEquals("1h 44m", StatWidget.reading("time", charging).first)
        assertEquals("—", StatWidget.reading("power", charging).first)
    }
}
