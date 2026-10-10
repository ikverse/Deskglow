package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.WeatherState
import com.ikverse.deskglow.data.untilText
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Today's daylight as the sun's arc, and tonight's moon. Sun times come with the weather; the moon is worked out here. */
object SunMoonWidget : WidgetType {
    val SHOW_TIMES = FlagKey("showTimes", true)
    val SHOW_LEFT = FlagKey("showLeft", true)
    val SHOW_MOON = FlagKey("showMoon", true)
    val ACCENT = ColourKey("accent", 0xFFF5B942.toInt())

    override val id = "sunmoon"
    override val label = "Sun & moon"
    override val blurb = "Daylight left and the moon's phase"
    override val width = 240
    override val height = 88
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ShowField("Show", listOf(SHOW_TIMES to "Sunrise and sunset", SHOW_LEFT to "Daylight left", SHOW_MOON to "Moon")),
        Common.timeFormatField(),
        ColourField("Accent colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) =
        "Sun times by Open-Meteo.com for your weather city (set on the Home screen). The moon is worked out on the phone."

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val state by feeds.weather.collectAsStateWithLifecycle()
        val minute by feeds.minute.collectAsStateWithLifecycle()
        val h24 = Common.use24Hour(settings)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            val weather = when (val s = state) {
                WeatherState.NoCity -> return@BoxWithConstraints EditorHint("Set your city on the Home screen", h * 0.2f)
                is WeatherState.Loading -> return@BoxWithConstraints EditorHint("Loading sun times…", h * 0.2f)
                is WeatherState.Failed -> s.last ?: return@BoxWithConstraints EditorHint("No sun times yet", h * 0.2f)
                is WeatherState.Ready -> s.weather
            }
            val rise = weather.sunrise
            val set = weather.sunset
            if (rise == null || set == null) return@BoxWithConstraints EditorHint("Loading sun times…", h * 0.2f)
            SunMoonFace(settings, rise, set, weather.cityTime(minute), minute, h24, w, h)
        }
    }
}

/** The arc with its words under it, and the moon beside it when asked for. */
@Composable
private fun SunMoonFace(settings: Settings, rise: LocalDateTime, set: LocalDateTime, now: LocalDateTime, phoneNow: LocalDateTime, h24: Boolean, w: Float, h: Float) {
    val colour = Color(settings[Common.COLOUR])
    val accent = Color(settings[SunMoonWidget.ACCENT])
    val moon = settings[SunMoonWidget.SHOW_MOON]
    val moonSize = min(h * 0.6f, w * 0.26f)
    val riseText = sunTime(rise, h24)
    val setText = sunTime(set, h24)
    val leftText = daylightText(rise, set, now)
    // The words under the arc share the arc's width: the daylight left is dropped first, then the type shrinks.
    val room = w - (if (moon) moonSize + h * 0.14f else 0f)
    var small = min(h * 0.17f, w * 0.05f)
    val times = settings[SunMoonWidget.SHOW_TIMES]
    val timesWidth = if (times) (riseText.length + setText.length) * 0.5f * small else 0f
    val showLeft = settings[SunMoonWidget.SHOW_LEFT] && timesWidth + (leftText.length + 2) * 0.4f * small <= room
    if (timesWidth > room) small *= room / timesWidth
    val daylight = daylightFraction(rise, set, now)
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.Center) {
            SunArc(daylight, accent, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(pxToDp(small * 0.3f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (times) Text(riseText, color = colour, fontSize = pxToSp(small), maxLines = 1, softWrap = false)
                if (showLeft) Text(leftText, color = Muted, fontSize = pxToSp(small * 0.95f), maxLines = 1, softWrap = false)
                if (times) Text(setText, color = colour, fontSize = pxToSp(small), maxLines = 1, softWrap = false)
            }
        }
        if (moon) {
            val phase = Moon.phase(phoneNow.atZone(java.time.ZoneId.systemDefault()).toInstant())
            Spacer(Modifier.width(pxToDp(h * 0.14f)))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                MoonDisc(phase, colour, Modifier.size(pxToDp(moonSize)))
                Spacer(Modifier.height(pxToDp(small * 0.25f)))
                Text("${Moon.illumination(phase)}%", color = Muted, fontSize = pxToSp(small * 0.9f), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** How far through the daylight [now] is: 0 at sunrise, 1 at sunset; null in the night. */
fun daylightFraction(rise: LocalDateTime, set: LocalDateTime, now: LocalDateTime): Float? {
    if (now.isBefore(rise) || !now.isBefore(set)) return null
    val total = java.time.Duration.between(rise, set).toMinutes().coerceAtLeast(1)
    return (java.time.Duration.between(rise, now).toMinutes().toFloat() / total).coerceIn(0f, 1f)
}

/** "6 h 12 m of daylight left", or in the night "Sunrise in 7 h 40 m". */
fun daylightText(rise: LocalDateTime, set: LocalDateTime, now: LocalDateTime): String = when {
    now.isBefore(rise) -> "Sunrise in " + untilText(now, rise)
    now.isBefore(set) -> untilText(now, set) + " of daylight left"
    else -> "Sunrise in " + untilText(now, rise.plusDays(1))
}

/** The sun's path from one horizon to the other, lit up to where it is now (when it is up). */
@Composable
private fun SunArc(daylight: Float?, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        val line = (size.height * 0.035f).coerceAtLeast(1.5f)
        val pad = size.height * 0.14f
        val a = size.width / 2 - pad
        val b = (size.height - pad * 2).coerceAtLeast(1f)
        val cx = size.width / 2
        val base = size.height - pad
        fun at(f: Float): Offset {
            val angle = PI * (1 - f)
            return Offset(cx + a * cos(angle).toFloat(), base - b * sin(angle).toFloat())
        }
        drawLine(Color(0xFF2A2A2A), Offset(pad * 0.3f, base), Offset(size.width - pad * 0.3f, base), line, StrokeCap.Round)
        val steps = 60
        for (i in 0 until steps) {
            val f0 = i / steps.toFloat()
            val f1 = (i + 1) / steps.toFloat()
            val lit = daylight != null && f1 <= daylight
            drawLine(if (lit) lerp(Color(0xFF2E2E2E), accent, 0.7f) else Color(0xFF2E2E2E), at(f0), at(f1), line, StrokeCap.Round)
        }
        if (daylight != null) {
            val sun = at(daylight)
            drawCircle(accent.copy(alpha = 0.25f), pad * 1.5f, sun)
            drawCircle(accent, pad * 0.85f, sun)
        }
    }
}

/** The moon with the lit part of it in [lit], as seen from the northern hemisphere. */
@Composable
fun MoonDisc(phase: Double, lit: Color, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFF1E1E1E), r, c)
        val waxing = phase < 0.5
        val k = cos(2 * PI * phase).toFloat()
        val disc = Path().apply { addOval(Rect(c, r)) }
        val half = Path().apply {
            addRect(if (waxing) Rect(c.x, c.y - r, c.x + r, c.y + r) else Rect(c.x - r, c.y - r, c.x, c.y + r))
        }
        val litHalf = Path.combine(PathOperation.Intersect, disc, half)
        val terminator = Path().apply { addOval(Rect(c.x - r * abs(k), c.y - r, c.x + r * abs(k), c.y + r)) }
        // A crescent is the lit half less the terminator's ellipse; a gibbous moon is the lit half plus it.
        val shape = Path.combine(if (k > 0) PathOperation.Difference else PathOperation.Union, litHalf, terminator)
        drawPath(shape, lit)
    }
}

/** The moon's phase, worked out from the mean length of a lunation: good to within about half a day. */
object Moon {
    private const val LUNATION_DAYS = 29.530588853
    /** A new moon: 6 January 2000, 18:14 UTC. */
    private const val NEW_MOON_EPOCH_SECONDS = 947_182_440L

    /** 0 at a new moon, 0.25 at the first quarter, 0.5 at a full moon, 0.75 at the last quarter. */
    fun phase(at: Instant): Double {
        val days = (at.epochSecond - NEW_MOON_EPOCH_SECONDS) / 86_400.0
        val cycles = days / LUNATION_DAYS
        return cycles - Math.floor(cycles)
    }

    /** How much of the disc is lit, in percent. */
    fun illumination(phase: Double): Int = Math.round((1 - cos(2 * PI * phase)) / 2 * 100).toInt()

    fun name(phase: Double): String = when {
        phase < 0.0339 || phase >= 0.9661 -> "New moon"
        phase < 0.2161 -> "Waxing crescent"
        phase < 0.2839 -> "First quarter"
        phase < 0.4661 -> "Waxing gibbous"
        phase < 0.5339 -> "Full moon"
        phase < 0.7161 -> "Waning gibbous"
        phase < 0.7839 -> "Last quarter"
        else -> "Waning crescent"
    }
}
