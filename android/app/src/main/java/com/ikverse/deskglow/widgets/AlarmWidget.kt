package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    /** The old switch for the bar; now [TIME_LEFT], which a saved widget is brought to by [migrate]. */
    val SHOW_BAR = FlagKey("showBar", true)
    /** The time left shown as a "bar", a "ring" round the bell, or "none". */
    val TIME_LEFT = TextKey("timeLeft", "bar")
    /** "in" 7 h 25 m, or "sleep": 7 h 25 m of sleep. */
    val PHRASE = TextKey("phrase", "in")
    /** "always", or the hours ahead (24, 12, 6) beyond which the alarm is left off the screen. */
    val WITHIN = TextKey("within", "always")
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
        ChoiceField("Time left as", TIME_LEFT, listOf("bar" to "Bar", "ring" to "Ring", "none" to "Text only")),
        ChoiceField("Wording", PHRASE, listOf("in" to "in 7 h 25 m", "sleep" to "7 h 25 m of sleep")),
        ChoiceField("Show the alarm", WITHIN, listOf("always" to "Always", "24" to "Within a day", "12" to "Within 12 hours", "6" to "Within 6 hours")),
        ChoiceField("With no alarm set", WHEN_NONE, listOf("text" to "Say “No alarm”", "hide" to "Show nothing")),
        Common.timeFormatField(),
        Common.alignField,
        ColourField("Accent colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    /** The bar switch became "Time left as": a widget with it off shows text only, as before. */
    override fun migrate(settings: Settings): Settings =
        if (TIME_LEFT.name in settings.values) settings else settings.with(TIME_LEFT, if (settings[SHOW_BAR]) "bar" else "none")

    override fun note(settings: Settings) = "Shows the next alarm from any clock app on the phone."

    /** "in 7 h 25 m · Tomorrow", or with [sleep] "7 h 25 m of sleep · Tomorrow". */
    fun whenText(now: LocalDateTime, alarm: LocalDateTime, sleep: Boolean = false): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), alarm.toLocalDate())
        val day = when (days) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> alarm.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        }
        return (if (sleep) "${untilText(now, alarm)} of sleep" else "in ${untilText(now, alarm)}") + " · $day"
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
            if (alarm == null && settings[WHEN_NONE] == "hide") return@BoxWithConstraints EditorHint("No alarm set", h * 0.24f)
            val limit = settings[WITHIN].toLongOrNull()
            if (alarm != null && limit != null && Duration.between(now, alarm).toHours() >= limit) return@BoxWithConstraints EditorHint("The alarm is more than $limit hours away", h * 0.24f)
            val showBar = alarm != null && settings[TIME_LEFT] == "bar"
            val showRing = alarm != null && settings[TIME_LEFT] == "ring"
            // The bell beside the time in a wide box, above it in a tall one.
            Fit(canvasUnit(w, width), listOf(settings, alarm == null), arrangements = 2, align = alignFraction(align)) { f ->
                val s = f.scale
                val big = s.main * 2.4f
                val small = s.second
                val bell = big * 1.1f

                @Composable
                fun Lead() {
                    if (showRing && alarm != null) {
                        val left = timeLeft(now, alarm)
                        Box(Modifier.size(pxToDp(bell * 1.3f)), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val thick = size.minDimension * 0.07f
                                val corner = Offset(thick / 2, thick / 2)
                                val arc = Size(size.width - thick, size.height - thick)
                                drawArc(Color(0xFF262626), 0f, 360f, false, corner, arc, style = Stroke(thick))
                                if (left > 0f) drawArc(accent.copy(alpha = 0.85f), -90f, 360f * left, false, corner, arc, style = Stroke(thick, cap = StrokeCap.Round))
                            }
                            Bell(accent, Modifier.size(pxToDp(bell * 0.7f)))
                        }
                    } else {
                        Bell(if (alarm == null) Muted else accent, Modifier.size(pxToDp(bell)))
                    }
                }

                @Composable
                fun Words() {
                    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                        if (alarm == null) {
                            Text("No alarm", color = Muted, fontSize = pxToSp(big * 0.7f), maxLines = 1, softWrap = false)
                            return@Column
                        }
                        val time = alarm.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US))
                        Text(time, color = colour, fontSize = pxToSp(big), fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
                        Text(whenText(now, alarm, settings[PHRASE] == "sleep"), color = Muted, fontSize = pxToSp(small), maxLines = 1, softWrap = false)
                        if (showBar) {
                            Spacer(Modifier.height(pxToDp(small * 0.35f * s.space)))
                            val left = timeLeft(now, alarm)
                            Canvas(Modifier.width(pxToDp(big * 4.2f)).height(pxToDp((small * 0.3f).coerceAtLeast(3f)))) {
                                val y = size.height / 2
                                // The rounded ends reach half the bar's height past each end, so the line starts and stops that far in.
                                drawLine(Color(0xFF262626), Offset(y, y), Offset(size.width - y, y), size.height, StrokeCap.Round)
                                if (left > 0f) drawLine(accent.copy(alpha = 0.8f), Offset(y, y), Offset(y + (size.width - 2 * y) * left, y), size.height, StrokeCap.Round)
                            }
                        }
                    }
                }

                if (f.arrangement == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Lead()
                        Spacer(Modifier.width(pxToDp(big * 0.35f)))
                        Words()
                    }
                } else {
                    Column(horizontalAlignment = horizontal(align)) {
                        Lead()
                        Spacer(Modifier.height(pxToDp(small * 0.5f * s.space)))
                        Words()
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
