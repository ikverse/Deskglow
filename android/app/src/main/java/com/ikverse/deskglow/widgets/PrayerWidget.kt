package com.ikverse.deskglow.widgets

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.Prayer
import com.ikverse.deskglow.data.PrayerDay
import com.ikverse.deskglow.data.PrayerRepository
import com.ikverse.deskglow.data.PrayerState
import com.ikverse.deskglow.data.nextPrayer
import com.ikverse.deskglow.data.untilText
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.min

object PrayerWidget : WidgetType {
    val METHOD = TextKey("method", "5")
    val SCHOOL = TextKey("school", "0")
    /** The old switch for the five times; now [VIEW], which a saved widget is brought to by [migrate]. */
    val SHOW_ALL = FlagKey("showAll", true)
    /** "next" prayer only, "row" of all five under it, or the day's five on an "arc" with the sun's place now. */
    val VIEW = TextKey("view", "row")
    val ACCENT = ColourKey("accent", 0xFF5BC0A6.toInt())

    override val id = "prayer"
    override val label = "Prayer times"
    override val blurb = "Next prayer countdown"
    override val width = 372
    override val height = 96
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = buildList {
        add(ChoiceField("Method", METHOD, PrayerRepository.METHODS.map { (n, name) -> n.toString() to name }))
        add(ChoiceField("Asr", SCHOOL, listOf("0" to "Standard", "1" to "Hanafi")))
        add(LayoutField("Layout", VIEW, listOf("next" to "Next only", "row" to "Row of five", "arc" to "Sun arc")))
        addAll(Common.arabicFields(settings))
        add(Common.timeFormatField())
        add(Common.alignField)
        add(ColourField("Accent colour", ACCENT))
        add(Common.colourField)
        add(Common.brightnessField)
    }

    /** The five-times switch became the layout: a widget with it off shows the next prayer only, as before. */
    override fun migrate(settings: Settings): Settings =
        if (VIEW.name in settings.values) settings else settings.with(VIEW, if (settings[SHOW_ALL]) "row" else "next")

    override fun note(settings: Settings) =
        "Times by Aladhan.com, for where the phone is (location on), or your city on the Home screen."

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val state by feeds.prayer(settings[METHOD].toIntOrNull() ?: 5, settings[SCHOOL].toIntOrNull() ?: 0).collectAsStateWithLifecycle()
        val minute by feeds.minute.collectAsStateWithLifecycle()
        val h24 = Common.use24Hour(settings)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val days = when (val s = state) {
                PrayerState.NoLocation -> return@BoxWithConstraints EditorHint("Turn on location, or set your city on the Home screen", h * 0.16f)
                is PrayerState.Loading -> return@BoxWithConstraints EditorHint("Loading prayer times…", h * 0.18f)
                is PrayerState.Failed -> s.last ?: return@BoxWithConstraints EditorHint("No prayer times yet", h * 0.18f)
                is PrayerState.Ready -> s.days
            }
            val now = minute
            val next = nextPrayer(days, now) ?: return@BoxWithConstraints EditorHint("No prayer times yet", h * 0.18f)
            val direction = if (settings[Common.ARABIC]) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                PrayerFace(settings, days, next, now, h24)
            }
        }
    }

    /** The prayer's time as the widget shows it: "3:12 PM", "15:12", "٣:١٢ م"; without AM/PM when [suffix] is off. */
    fun clockText(time: LocalTime, h24: Boolean, arabic: Boolean, arabicDigits: Boolean, suffix: Boolean = true): String {
        val hour = if (h24) time.hour else (time.hour % 12).let { if (it == 0) 12 else it }
        val digits = if (h24) String.format(Locale.US, "%02d:%02d", hour, time.minute) else String.format(Locale.US, "%d:%02d", hour, time.minute)
        val text = if (h24 || !suffix) digits else digits + if (arabic) (if (time.hour < 12) " ص" else " م") else (if (time.hour < 12) " AM" else " PM")
        return if (arabicDigits) TimeText.toArabic(text) else text
    }

    /** "in 1 h 20 m", or in Arabic "بعد ١ س ٢٠ د". */
    fun countdownText(now: LocalDateTime, then: LocalDateTime, arabic: Boolean, arabicDigits: Boolean): String {
        val english = untilText(now, then)
        if (!arabic) return "in $english"
        val arabicText = "بعد " + english.replace(" h", " س").replace(" m", " د")
        return if (arabicDigits) TimeText.toArabic(arabicText) else arabicText
    }
}

@Composable
private fun PrayerFace(settings: Settings, days: List<PrayerDay>, next: Pair<Prayer, LocalDateTime>, now: LocalDateTime, h24: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        val w = constraints.maxWidth.toFloat()
        val arabic = settings[Common.ARABIC]
        val digits = Common.arabicDigits(settings)
        val colour = Color(settings[Common.COLOUR])
        val accent = Color(settings[PrayerWidget.ACCENT])
        // Laid out right to left in Arabic, where "end" is the left edge; Left and Right still mean the screen's sides.
        val align = settings[Common.ALIGN].let { if (arabic) when (it) { "left" -> "right"; "right" -> "left"; else -> it } else it }
        val view = settings[PrayerWidget.VIEW]
        val showAll = view != "next"
        val (prayer, at) = next
        fun name(p: Prayer) = if (arabic) p.arabic else p.english

        // Arabic letters stand taller than Latin ones at the same size, so they are set a little smaller to fit.
        val k = if (arabic) 0.8f else 1f
        val big = k * if (showAll) min(h * 0.3f, w * 0.075f) else min(h * 0.46f, w * 0.1f)
        val small = k * if (showAll) min(h * 0.15f, w * 0.042f) else min(h * 0.22f, w * 0.05f)
        val cell = k * min(h * 0.13f, w * 0.036f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(name(prayer), color = colour, fontSize = pxToSp(big), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(pxToDp(big * 0.4f)))
                Text(
                    PrayerWidget.clockText(at.toLocalTime(), h24, arabic, digits),
                    color = colour, fontSize = pxToSp(big), fontWeight = FontWeight.Light, maxLines = 1, softWrap = false,
                )
            }
            Text(PrayerWidget.countdownText(now, at, arabic, digits), color = accent, fontSize = pxToSp(small), maxLines = 1, softWrap = false)
            if (showAll) {
                Spacer(Modifier.height(pxToDp(h * 0.07f)))
                // The day the next prayer falls on: after Isha that is tomorrow.
                val day = days.firstOrNull { it.date == at.toLocalDate() } ?: days.first()
                if (view == "arc") {
                    SunArc(day, now, prayer, accent, cell, ::name, { PrayerWidget.clockText(it, h24, arabic, digits, suffix = false) }, Modifier.weight(1f).fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Prayer.entries.forEach { p ->
                            val time = day.date.atTime(day.times.getValue(p))
                            val isNext = p == prayer && day.date == at.toLocalDate()
                            val shade = when {
                                isNext -> accent
                                time.isBefore(now) -> Muted.copy(alpha = 0.45f)
                                else -> Muted
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(name(p), color = shade, fontSize = pxToSp(cell), letterSpacing = if (arabic) 0.em else 0.04.em, maxLines = 1, softWrap = false)
                                Text(
                                    PrayerWidget.clockText(time.toLocalTime(), h24, arabic, digits, suffix = false), color = if (isNext) accent else shade,
                                    fontSize = pxToSp(cell * 1.1f), maxLines = 1, softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The day's five prayers as evenly spaced dots along an arc, in the order the sun meets them, with a brighter dot
 * where the day is now and each prayer's name and time beneath its dot.
 */
@Composable
private fun SunArc(
    day: PrayerDay, now: LocalDateTime, next: Prayer, accent: Color, labelPx: Float,
    name: (Prayer) -> String, time: (LocalTime) -> String, modifier: Modifier,
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    Canvas(modifier) {
        val seconds = Prayer.entries.map { day.times.getValue(it).toSecondOfDay().toFloat() }
        // The dots are evenly spaced so no two names meet; the sun moves between them in proportion to the time.
        val fractions = seconds.indices.map { it / (seconds.size - 1f) }
        val here = when {
            now.toLocalDate().isBefore(day.date) -> 0f
            now.toLocalDate().isAfter(day.date) -> 1f
            else -> {
                val t = now.toLocalTime().toSecondOfDay().toFloat()
                val i = seconds.indexOfLast { it <= t }
                when {
                    i < 0 -> 0f
                    i >= seconds.lastIndex -> 1f
                    else -> fractions[i] + (fractions[i + 1] - fractions[i]) * (t - seconds[i]) / (seconds[i + 1] - seconds[i]).coerceAtLeast(1f)
                }
            }
        }
        val labelHeight = labelPx * 2.5f
        val a = size.width / 2 - labelPx * 1.6f
        val b = (size.height - labelHeight - labelPx * 0.4f).coerceAtLeast(1f)
        val cx = size.width / 2
        val cy = size.height - labelHeight
        fun at(f: Float): Offset {
            val angle = Math.PI * (1 - f)
            return Offset(cx + a * kotlin.math.cos(angle).toFloat(), cy - b * kotlin.math.sin(angle).toFloat())
        }
        val steps = 72
        val line = (labelPx * 0.12f).coerceAtLeast(1.5f)
        for (i in 0 until steps) {
            val f0 = i / steps.toFloat()
            val f1 = (i + 1) / steps.toFloat()
            val colour = if (f1 <= here) accent.copy(alpha = 0.55f) else Color(0xFF2E2E2E)
            drawLine(colour, at(f0), at(f1), line, StrokeCap.Round)
        }
        val shade = { p: Prayer, i: Int ->
            when {
                p == next -> accent
                fractions[i] <= here -> Muted.copy(alpha = 0.45f)
                else -> Muted
            }
        }
        Prayer.entries.forEachIndexed { i, p ->
            val spot = at(fractions[i])
            val colour = shade(p, i)
            drawCircle(Color.Black, labelPx * 0.42f, spot)
            drawCircle(colour, labelPx * 0.3f, spot)
            paint.color = colour.toArgb()
            paint.textSize = labelPx
            drawContext.canvas.nativeCanvas.drawText(name(p), spot.x, cy + labelPx * 1.15f, paint)
            paint.textSize = labelPx * 1.1f
            drawContext.canvas.nativeCanvas.drawText(time(day.times.getValue(p)), spot.x, cy + labelPx * 2.4f, paint)
        }
        val sun = at(here)
        drawCircle(accent.copy(alpha = 0.25f), labelPx * 0.9f, sun)
        drawCircle(accent, labelPx * 0.5f, sun)
    }
}
