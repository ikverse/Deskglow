package com.ikverse.deskglow.widgets

import android.text.format.DateFormat
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val SHOW_ALL = FlagKey("showAll", true)
    val ACCENT = ColourKey("accent", 0xFF5BC0A6.toInt())

    override val id = "prayer"
    override val label = "Prayer times"
    override val blurb = "The next prayer and a countdown, for where you are"
    override val width = 372
    override val height = 96
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = buildList {
        add(ChoiceField("Method", METHOD, PrayerRepository.METHODS.map { (n, name) -> n.toString() to name }))
        add(ChoiceField("Asr", SCHOOL, listOf("0" to "Standard", "1" to "Hanafi")))
        add(ToggleField("Show all five times", SHOW_ALL))
        addAll(Common.arabicFields(settings))
        add(Common.alignField)
        add(ColourField("Highlight colour", ACCENT))
        add(Common.colourField)
        add(Common.brightnessField)
    }

    override fun note(settings: Settings) =
        "Times by Aladhan.com, for where the phone is (location on), or your city on the Home screen."

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val state by feeds.prayer(settings[METHOD].toIntOrNull() ?: 5, settings[SCHOOL].toIntOrNull() ?: 0).collectAsStateWithLifecycle()
        val minute by feeds.minute.collectAsStateWithLifecycle()
        val h24 = DateFormat.is24HourFormat(LocalContext.current)
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
        val showAll = settings[PrayerWidget.SHOW_ALL]
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
