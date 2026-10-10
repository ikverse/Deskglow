package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.untilText
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.min

object AlarmWidget : WidgetType {
    val SHOW_BAR = FlagKey("showBar", true)
    val WHEN_NONE = TextKey("whenNone", "text")
    val ACCENT = ColourKey("accent", 0xFFF5B942.toInt())

    override val id = "alarm"
    override val label = "Next alarm"
    override val blurb = "Time to your next alarm"
    override val width = 240
    override val height = 64
    override val defaults: Settings = Common.base()

    /** The bar empties over the last [BAR_HOURS] hours before the alarm, like the night running out. */
    const val BAR_HOURS = 12L

    override fun fields(settings: Settings) = listOf(
        ToggleField("Show time-left bar", SHOW_BAR),
        ChoiceField("With no alarm set", WHEN_NONE, listOf("text" to "Say “No alarm”", "hide" to "Show nothing")),
        Common.timeFormatField(),
        Common.alignField,
        ColourField("Accent colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "Shows the next alarm from any clock app on the phone."

    /** "in 7 h 25 m · Tomorrow". */
    fun whenText(now: LocalDateTime, alarm: LocalDateTime): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), alarm.toLocalDate())
        val day = when (days) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> alarm.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        }
        return "in ${untilText(now, alarm)} · $day"
    }

    /** How much of the last [BAR_HOURS] hours is still to go, from 1 (all of it) to 0 (ringing). */
    fun timeLeft(now: LocalDateTime, alarm: LocalDateTime): Float =
        (Duration.between(now, alarm).toMinutes() / (BAR_HOURS * 60f)).coerceIn(0f, 1f)

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val state by feeds.alarm.collectAsStateWithLifecycle()
        val minute by feeds.minute.collectAsStateWithLifecycle()
        val h24 = Common.use24Hour(settings)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            val now = minute
            val alarm = state.next?.takeIf { it.isAfter(now.minusMinutes(1)) }
            val colour = Color(settings[Common.COLOUR])
            val accent = Color(settings[ACCENT])
            val align = settings[Common.ALIGN]
            val arrangement = arrangementOf(align)
            if (alarm == null && settings[WHEN_NONE] == "hide") return@BoxWithConstraints EditorHint("No alarm set", h * 0.24f)
            val showBar = alarm != null && settings[SHOW_BAR]
            val big = min(h * (if (showBar) 0.42f else 0.5f), w * 0.15f)
            val small = min(h * 0.19f, w * 0.065f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
                Bell(if (alarm == null) Muted else accent, Modifier.size(pxToDp(min(h * 0.5f, big * 1.1f))))
                Spacer(Modifier.width(pxToDp(h * 0.14f)))
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                    if (alarm == null) {
                        Text("No alarm", color = Muted, fontSize = pxToSp(big * 0.7f), maxLines = 1, softWrap = false)
                        return@Column
                    }
                    val time = alarm.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US))
                    Text(time, color = colour, fontSize = pxToSp(big), fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
                    Text(whenText(now, alarm), color = Muted, fontSize = pxToSp(small), maxLines = 1, softWrap = false)
                    if (showBar) {
                        Spacer(Modifier.height(pxToDp(small * 0.35f)))
                        val left = timeLeft(now, alarm)
                        Canvas(Modifier.width(pxToDp(min(w * 0.6f, h * 2.4f))).height(pxToDp((h * 0.05f).coerceAtLeast(3f)))) {
                            val y = size.height / 2
                            drawLine(Color(0xFF262626), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                            if (left > 0f) drawLine(accent.copy(alpha = 0.8f), Offset(0f, y), Offset(size.width * left, y), size.height, StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

/** A bell: a rounded dome on a flared rim, with a knob on top and the clapper below. */
@Composable
fun Bell(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val dome = Path().apply {
            moveTo(0.16f * s, 0.74f * s)
            cubicTo(0.27f * s, 0.63f * s, 0.25f * s, 0.5f * s, 0.27f * s, 0.41f * s)
            cubicTo(0.3f * s, 0.22f * s, 0.41f * s, 0.15f * s, 0.5f * s, 0.15f * s)
            cubicTo(0.59f * s, 0.15f * s, 0.7f * s, 0.22f * s, 0.73f * s, 0.41f * s)
            cubicTo(0.75f * s, 0.5f * s, 0.73f * s, 0.63f * s, 0.84f * s, 0.74f * s)
            close()
        }
        drawPath(dome, color)
        drawCircle(color, 0.055f * s, Offset(0.5f * s, 0.1f * s))
        drawCircle(color, 0.085f * s, Offset(0.5f * s, 0.84f * s))
    }
}
