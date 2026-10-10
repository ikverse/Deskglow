package com.ikverse.deskglow.widgets

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.chrono.HijrahChronology
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/** The time split for drawing: hours, minutes and (when shown) seconds. */
data class TimeParts(val hours: String, val minutes: String, val seconds: String?) {
    val text: String get() = listOfNotNull(hours, minutes, seconds).joinToString(":")
}

/** Turns the time and date into the text the clock and date widgets show, in English or Arabic. */
object TimeText {
    private val EGYPT = Locale.forLanguageTag("ar-EG")

    fun parts(time: LocalDateTime, h24: Boolean, seconds: Boolean, arabicDigits: Boolean, leadingZero: Boolean = false): TimeParts {
        val hour = if (h24) time.hour else (time.hour % 12).let { if (it == 0) 12 else it }
        val hours = if (h24 || leadingZero) "%02d".format(Locale.US, hour) else hour.toString()
        val parts = TimeParts(
            hours,
            "%02d".format(Locale.US, time.minute),
            if (seconds) "%02d".format(Locale.US, time.second) else null,
        )
        return if (arabicDigits) TimeParts(toArabic(parts.hours), toArabic(parts.minutes), parts.seconds?.let(::toArabic)) else parts
    }

    /** "AM" or "PM" for a 12-hour clock, and in Arabic "ص" or "م". */
    fun meridiem(time: LocalDateTime, arabic: Boolean): String =
        if (arabic) (if (time.hour < 12) "ص" else "م") else (if (time.hour < 12) "AM" else "PM")

    /**
     * [time] unless it is stale. A screen that has just appeared draws once with the last time its
     * feed saw (perhaps hours ago) before the feed catches up; this keeps that one frame correct.
     */
    fun fresh(time: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): LocalDateTime =
        if (time.isBefore(now.minusSeconds(61)) || time.isAfter(now.plusSeconds(61))) now else time

    /** Western digits to Arabic-Indic ones (٠١٢٣٤٥٦٧٨٩); everything else is left alone. */
    fun toArabic(text: String): String =
        buildString(text.length) { text.forEach { append(if (it in '0'..'9') '٠' + (it - '0') else it) } }

    /**
     * The date in one of six forms. In English: "Wed, Oct 7", "Wednesday 7 October", "07/10/2026".
     * In Arabic (Egyptian usage): "الأربعاء، ٧ أكتوبر", "الأربعاء ٧ أكتوبر ٢٠٢٦", "٧/١٠/٢٠٢٦".
     * Also the day alone, the day and month alone, and the Hijri date (Umm al-Qura calendar).
     */
    fun date(date: LocalDate, format: String, arabic: Boolean, arabicDigits: Boolean): String {
        val formatter = if (arabic) {
            val pattern = when (format) {
                "long" -> "EEEE d MMMM y"
                "numeric" -> "d/M/y"
                "day" -> "EEEE"
                "daymonth" -> "d MMMM"
                "hijri" -> "d MMMM y"
                else -> "EEEE، d MMMM"
            }
            DateTimeFormatter.ofPattern(pattern, EGYPT)
                .withDecimalStyle(if (arabicDigits) DecimalStyle.STANDARD.withZeroDigit('٠') else DecimalStyle.STANDARD)
                .let { if (format == "hijri") it.withChronology(HijrahChronology.INSTANCE) else it }
        } else {
            when (format) {
                "long" -> DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
                "numeric" -> DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.UK)
                "day" -> DateTimeFormatter.ofPattern("EEEE", Locale.UK)
                "daymonth" -> DateTimeFormatter.ofPattern("d MMMM", Locale.UK)
                "hijri" -> DateTimeFormatter.ofPattern("d MMMM y", Locale.UK).withChronology(HijrahChronology.INSTANCE)
                else -> DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
            }
        }
        return formatter.format(date)
    }

    val DATE_FORMATS = listOf(
        "short" to "Wed, Oct 7", "long" to "Wednesday 7 October", "numeric" to "07/10/2026",
        "day" to "Day only", "daymonth" to "7 October", "hijri" to "Hijri date",
    )
    val DATE_FORMATS_ARABIC = listOf(
        "short" to "الأربعاء، ٧ أكتوبر", "long" to "الأربعاء ٧ أكتوبر ٢٠٢٦", "numeric" to "٧/١٠/٢٠٢٦",
        "day" to "اليوم فقط", "daymonth" to "٧ أكتوبر", "hijri" to "التاريخ الهجري",
    )
}
