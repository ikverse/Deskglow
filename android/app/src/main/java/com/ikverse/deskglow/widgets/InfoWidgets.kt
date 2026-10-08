package com.ikverse.deskglow.widgets

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.data.NotificationState
import com.ikverse.deskglow.data.Sky
import com.ikverse.deskglow.data.Weather
import com.ikverse.deskglow.data.WeatherState
import com.ikverse.deskglow.data.conditionOf
import com.ikverse.deskglow.data.formatTemperature
import com.ikverse.deskglow.data.skyOf
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.IntKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A one-line hint, shown only in the editor and previews (on the display itself the widget stays empty). */
@Composable
private fun EditorHint(text: String, size: Float) {
    if (!LocalEditing.current) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = pxToSp(size), maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

private fun horizontal(align: String) = when (align) { "center" -> Alignment.CenterHorizontally; "right" -> Alignment.End; else -> Alignment.Start }
private fun textAlign(align: String) = when (align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start }

object NotificationsWidget : WidgetType {
    val MAX = IntKey("max", 5)
    val MORE = FlagKey("more", true)

    override val id = "notifs"
    override val label = "Notifications"
    override val blurb = "Icons of apps with unread notifications"
    override val width = 164
    override val height = 28
    override val defaults: Settings = Common.base(0xFFCFCFCF)

    override fun fields(settings: Settings) = listOf(
        SliderField("Icons shown", MAX, 3..8),
        ToggleField("Show +N when there are more", MORE),
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "Needs notification access (Home › Permissions)."

    @Composable
    override fun Content(settings: Settings) {
        val state by LocalFeeds.current.notifications.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            when (val s = state) {
                NotificationState.NoAccess -> EditorHint("Allow notification access", h * 0.42f)
                is NotificationState.Apps -> {
                    val shown = s.apps.take(settings[MAX])
                    val extra = s.apps.size - shown.size
                    val colour = Color(settings[Common.COLOUR])
                    val icon = h * 0.62f
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        shown.forEach { app ->
                            val bitmap = app.icon
                            if (bitmap != null) {
                                Image(bitmap, contentDescription = null, colorFilter = ColorFilter.tint(colour), modifier = Modifier.padding(horizontal = pxToDp(icon * 0.32f)).size(pxToDp(icon)))
                            } else {
                                Canvas(Modifier.padding(horizontal = pxToDp(icon * 0.32f)).size(pxToDp(icon))) { drawCircle(colour, size.minDimension * 0.3f) }
                            }
                        }
                        if (settings[MORE] && extra > 0) Text("+$extra", color = Muted, fontSize = pxToSp(h * 0.46f))
                        if (shown.isEmpty()) EditorHint("No notifications", h * 0.42f)
                    }
                }
            }
        }
    }
}

object WeatherWidget : WidgetType {
    val UNITS = TextKey("units", "c")
    val LAYOUT = TextKey("layout", "side")
    val ICON_STYLE = TextKey("iconStyle", "filled")
    val SIZE = IntKey("size", 100)
    val SHOW_ICON = FlagKey("showIcon", true)
    val SHOW_CONDITION = FlagKey("showCondition", true)
    val SHOW_RANGE = FlagKey("showRange", true)
    val SHOW_CITY = FlagKey("showCity", true)
    val SHOW_FEELS = FlagKey("showFeels", false)
    val SHOW_HUMIDITY = FlagKey("showHumidity", false)
    val SHOW_WIND = FlagKey("showWind", false)
    val SHOW_RAIN = FlagKey("showRain", false)
    val ACCENT = ColourKey("accent", 0xFFF5B942.toInt())

    override val id = "weather"
    override val label = "Weather"
    override val blurb = "Temperature and conditions"
    override val width = 176
    override val height = 64
    override val defaults: Settings = Common.base()

    /** The layouts on offer, as (id, name). */
    val LAYOUTS = listOf("side" to "Side by side", "stacked" to "Stacked", "compact" to "Compact", "big" to "Big temperature")

    /** What the picker's tiles show when there is no weather yet. */
    val SAMPLE = Weather(22.0, 2, true, 26.0, 17.0, 0, feelsLikeC = 21.0, humidityPercent = 48, windKmh = 14.0, rainChancePercent = 10)

    override fun fields(settings: Settings) = listOf(
        StyleField("Layout", LAYOUT, StyleKind.Weather),
        ChoiceField("Units", UNITS, listOf("c" to "°C", "f" to "°F")),
        ChoiceField("Icon style", ICON_STYLE, listOf("filled" to "Filled", "outline" to "Outline")),
        SliderField("Temperature size", SIZE, 60..140, "%"),
        Common.alignField,
        ToggleField("Show icon", SHOW_ICON),
        ToggleField("Show condition", SHOW_CONDITION),
        ToggleField("Show high and low", SHOW_RANGE),
        ToggleField("Show city", SHOW_CITY),
        ToggleField("Show feels like", SHOW_FEELS),
        ToggleField("Show humidity", SHOW_HUMIDITY),
        ToggleField("Show wind", SHOW_WIND),
        ToggleField("Show chance of rain", SHOW_RAIN),
        ColourField("Icon colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "Weather data by Open-Meteo.com. Set your city on the Home screen."

    @Composable
    override fun Content(settings: Settings) {
        val state by LocalFeeds.current.weather.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val (city, weather) = when (val s = state) {
                WeatherState.NoCity -> return@BoxWithConstraints EditorHint("Set your city on the Home screen", h * 0.2f)
                is WeatherState.Loading -> return@BoxWithConstraints EditorHint("Loading weather…", h * 0.22f)
                is WeatherState.Failed -> s.city to (s.last ?: return@BoxWithConstraints EditorHint("No weather yet", h * 0.22f))
                is WeatherState.Ready -> s.city to s.weather
            }
            WeatherBody(settings, city.name, weather)
        }
    }

    /** The weather drawn in the layout [LAYOUT] picks, filling whatever box it is given. */
    @Composable
    fun WeatherBody(settings: Settings, cityName: String, weather: Weather) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            val f = settings[UNITS] == "f"
            val colour = Color(settings[Common.COLOUR])
            val layout = settings[LAYOUT]
            val align = settings[Common.ALIGN]
            val showIcon = settings[SHOW_ICON] && layout != "big"

            val line = listOfNotNull(conditionOf(weather.code).takeIf { settings[SHOW_CONDITION] }, cityName.takeIf { settings[SHOW_CITY] }).joinToString(" · ")
            val range = "H ${formatTemperature(weather.highC, f)}  L ${formatTemperature(weather.lowC, f)}".takeIf { settings[SHOW_RANGE] }
            val details = listOfNotNull(
                weather.feelsLikeC?.takeIf { settings[SHOW_FEELS] }?.let { "Feels ${formatTemperature(it, f)}" },
                weather.humidityPercent?.takeIf { settings[SHOW_HUMIDITY] }?.let { "$it% humidity" },
                weather.windKmh?.takeIf { settings[SHOW_WIND] }?.let { formatWind(it, f) },
                weather.rainChancePercent?.takeIf { settings[SHOW_RAIN] }?.let { "$it% rain" },
            ).joinToString(" · ")
            val texts = listOf(line, range.orEmpty(), details).filter { it.isNotEmpty() }
                .let { if (layout == "compact" && it.isNotEmpty()) listOf(it.joinToString("  ·  ")) else it }

            val tempBase = when (layout) {
                "stacked" -> min(h * 0.3f, w * 0.12f)
                "compact" -> min(h * 0.36f, w * 0.13f)
                "big" -> min(h * 0.6f, w * 0.22f)
                else -> min(h * 0.44f, w * 0.15f)
            } * settings[SIZE] / 100f
            val smallBase = min(h * 0.19f, w * 0.07f)
            val iconBase = when (layout) { "stacked" -> h * 0.34f; "compact" -> h * 0.36f; else -> h * 0.74f }
            val stacksIcon = layout == "stacked"
            // Shrink everything together when the lines would not fit the height.
            val need = tempBase * 1.12f + texts.size * smallBase * 1.15f + if (showIcon && stacksIcon) iconBase else 0f
            val fit = min(1f, h * 0.98f / need)
            val temp = tempBase * fit
            val small = smallBase * fit
            val icon = iconBase * fit

            @Composable
            fun Icon() = WeatherIcon(skyOf(weather.code), weather.isDay, Color(settings[ACCENT]), Modifier.size(pxToDp(icon)), outline = settings[ICON_STYLE] == "outline")

            @Composable
            fun Temperature() = Text(
                formatTemperature(weather.temperatureC, f), color = colour, fontSize = pxToSp(temp),
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light, maxLines = 1, textAlign = textAlign(align),
            )

            @Composable
            fun Lines() = texts.forEach {
                Text(it, color = Muted, fontSize = pxToSp(small), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align))
            }

            when (layout) {
                "side" -> Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = when (align) { "center" -> Arrangement.Center; "right" -> Arrangement.End; else -> Arrangement.Start },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showIcon) {
                        Icon()
                        Spacer(Modifier.width(pxToDp(h * 0.08f)))
                    }
                    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) { Temperature(); Lines() }
                }
                "compact" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showIcon) {
                            Icon()
                            Spacer(Modifier.width(pxToDp(h * 0.06f)))
                        }
                        Temperature()
                    }
                    Lines()
                }
                else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                    if (showIcon && stacksIcon) Icon()
                    Temperature()
                    Lines()
                }
            }
        }
    }
}

/** "14 km/h", or "9 mph" when the widget is in Fahrenheit. */
fun formatWind(kmh: Double, miles: Boolean): String =
    if (miles) String.format(Locale.US, "%d mph", Math.round(kmh * 0.621371)) else String.format(Locale.US, "%d km/h", Math.round(kmh))

/** A plain drawn weather icon: sun or moon, cloud, rain, snow, storm or fog. [outline] draws lines instead of solid shapes. */
@Composable
fun WeatherIcon(sky: Sky, day: Boolean, color: Color, modifier: Modifier, outline: Boolean = false) {
    Canvas(modifier) {
        val s = size.minDimension
        val style: DrawStyle = if (outline) Stroke(s * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round) else Fill
        when (sky) {
            Sky.Clear -> if (day) sun(color, Offset(s / 2, s / 2), s * 0.2f, style) else moon(color, Offset(s / 2, s / 2), s * 0.3f, style)
            Sky.PartlyCloudy -> {
                if (day) sun(color, Offset(s * 0.36f, s * 0.36f), s * 0.14f, style) else moon(color, Offset(s * 0.36f, s * 0.34f), s * 0.2f, style)
                cloud(color, s, 0.18f, style, coversBehind = true)
            }
            Sky.Cloudy -> cloud(color, s, 0.08f, style)
            Sky.Fog -> for (i in 0..2) drawLine(color, Offset(s * 0.18f, s * (0.38f + i * 0.14f)), Offset(s * 0.82f, s * (0.38f + i * 0.14f)), s * 0.06f, StrokeCap.Round)
            Sky.Drizzle, Sky.Rain -> {
                cloud(color, s, -0.06f, style)
                val drops = if (sky == Sky.Rain) 3 else 2
                for (i in 0 until drops) {
                    val x = s * (0.36f + i * 0.14f)
                    drawLine(color, Offset(x, s * 0.7f), Offset(x - s * 0.05f, s * 0.86f), s * 0.05f, StrokeCap.Round)
                }
            }
            Sky.Snow -> {
                cloud(color, s, -0.06f, style)
                for (i in 0..2) drawCircle(color, s * 0.035f, Offset(s * (0.34f + i * 0.16f), s * 0.8f))
            }
            Sky.Storm -> {
                cloud(color, s, -0.08f, style)
                val bolt = Path().apply {
                    moveTo(s * 0.52f, s * 0.6f); lineTo(s * 0.4f, s * 0.78f); lineTo(s * 0.5f, s * 0.78f)
                    lineTo(s * 0.44f, s * 0.96f); lineTo(s * 0.62f, s * 0.72f); lineTo(s * 0.52f, s * 0.72f); close()
                }
                drawPath(bolt, color, style = style)
            }
        }
    }
}

private fun DrawScope.sun(color: Color, centre: Offset, r: Float, style: DrawStyle) {
    drawCircle(color, r, centre, style = style)
    for (i in 0 until 8) {
        val a = i * PI / 4
        val c = cos(a).toFloat()
        val n = sin(a).toFloat()
        drawLine(color, Offset(centre.x + c * r * 1.55f, centre.y + n * r * 1.55f), Offset(centre.x + c * r * 2.2f, centre.y + n * r * 2.2f), r * 0.38f, StrokeCap.Round)
    }
}

private fun DrawScope.moon(color: Color, centre: Offset, r: Float, style: DrawStyle) {
    if (style is Stroke) {
        // A crescent: the disc less a smaller disc, so only its edge is drawn.
        val disc = Path().apply { addOval(Rect(centre, r)) }
        val bite = Path().apply { addOval(Rect(Offset(centre.x + r * 0.45f, centre.y - r * 0.3f), r * 0.85f)) }
        drawPath(Path.combine(PathOperation.Difference, disc, bite), color, style = style)
    } else {
        drawCircle(color, r, centre)
        drawCircle(Color.Black, r * 0.85f, Offset(centre.x + r * 0.45f, centre.y - r * 0.3f))
    }
}

/**
 * A cloud of three puffs on a flat base; [lift] moves it up (positive) or down. As an outline only its
 * edge is drawn; [coversBehind] (a cloud in front of a sun or moon) also blanks what is inside it.
 */
private fun DrawScope.cloud(color: Color, s: Float, lift: Float, style: DrawStyle, coversBehind: Boolean = false) {
    val y = s * (0.62f - lift)
    if (style is Stroke) {
        val parts = listOf(
            Path().apply { addOval(Rect(Offset(s * 0.36f, y - s * 0.02f), s * 0.16f)) },
            Path().apply { addOval(Rect(Offset(s * 0.56f, y - s * 0.08f), s * 0.21f)) },
            Path().apply { addOval(Rect(Offset(s * 0.74f, y + s * 0.02f), s * 0.13f)) },
            Path().apply { addRect(Rect(Offset(s * 0.36f, y), Size(s * 0.38f, s * 0.15f))) },
            Path().apply { addOval(Rect(Offset(s * 0.36f, y + s * 0.075f), s * 0.075f)) },
            Path().apply { addOval(Rect(Offset(s * 0.74f, y + s * 0.075f), s * 0.075f)) },
        )
        val shape = parts.reduce { a, b -> Path.combine(PathOperation.Union, a, b) }
        if (coversBehind) drawPath(shape, Color.Black)
        drawPath(shape, color, style = style)
        return
    }
    drawCircle(color, s * 0.16f, Offset(s * 0.36f, y - s * 0.02f))
    drawCircle(color, s * 0.21f, Offset(s * 0.56f, y - s * 0.08f))
    drawCircle(color, s * 0.13f, Offset(s * 0.74f, y + s * 0.02f))
    drawRect(color, Offset(s * 0.36f, y), Size(s * 0.38f, s * 0.15f))
    drawCircle(color, s * 0.075f, Offset(s * 0.36f, y + s * 0.075f))
    drawCircle(color, s * 0.075f, Offset(s * 0.74f, y + s * 0.075f))
}

object EventWidget : WidgetType {
    val SHOW_HEADING = FlagKey("showHeading", true)
    val SHOW_TIME = FlagKey("showTime", true)

    override val id = "event"
    override val label = "Next event"
    override val blurb = "Your next calendar item"
    override val width = 176
    override val height = 64
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ToggleField("Show “NEXT” heading", SHOW_HEADING),
        ToggleField("Show time", SHOW_TIME),
        Common.alignField,
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "Needs calendar access (Home › Permissions)."

    @Composable
    override fun Content(settings: Settings) {
        val state by LocalFeeds.current.nextEvent.collectAsStateWithLifecycle()
        val minute by LocalFeeds.current.minute.collectAsStateWithLifecycle()
        val context = LocalContext.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            val event = when (val s = state) {
                EventState.NoAccess -> return@BoxWithConstraints EditorHint("Allow calendar access", h * 0.22f)
                EventState.None -> null
                is EventState.Next -> s
            }
            val align = settings[Common.ALIGN]
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                val heading = if (event != null && !event.allDay && event.start <= minute) "NOW" else "NEXT"
                if (settings[SHOW_HEADING]) Text(heading, color = Muted, fontSize = pxToSp(min(h * 0.17f, w * 0.06f)), letterSpacing = 0.08.em, maxLines = 1, textAlign = textAlign(align))
                Text(
                    event?.title ?: "No upcoming events",
                    color = if (event == null) Muted else Color(settings[Common.COLOUR]),
                    fontSize = pxToSp(min(h * 0.32f, w * 0.11f)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align),
                )
                if (event != null && settings[SHOW_TIME]) {
                    Text(
                        whenText(event, minute, DateFormat.is24HourFormat(context)),
                        color = Muted, fontSize = pxToSp(min(h * 0.21f, w * 0.075f)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align),
                    )
                }
            }
        }
    }

    /** "8:30 PM · Today", "All day · Tomorrow", "9:00 AM · Fri 9 Oct". */
    fun whenText(event: EventState.Next, now: LocalDateTime, h24: Boolean): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), event.start.toLocalDate())
        val day = when {
            days <= 0L -> "Today"
            days == 1L -> "Tomorrow"
            else -> event.start.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        }
        if (event.allDay) return "All day · $day"
        val time = event.start.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US))
        return "$time · $day"
    }
}

object MediaWidget : WidgetType {
    val SHOW_ARTIST = FlagKey("showArtist", true)
    val SHOW_PROGRESS = FlagKey("showProgress", true)

    override val id = "media"
    override val label = "Now playing"
    override val blurb = "Track and artist"
    override val width = 372
    override val height = 52
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ToggleField("Show artist", SHOW_ARTIST),
        ToggleField("Show progress bar", SHOW_PROGRESS),
        Common.alignField,
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "Needs notification access (Home › Permissions)."

    @Composable
    override fun Content(settings: Settings) {
        val state by LocalFeeds.current.media.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            if (state == MediaState.NoAccess) return@BoxWithConstraints EditorHint("Allow notification access", h * 0.3f)
            val track = state as? MediaState.Track
            val colour = Color(settings[Common.COLOUR])
            val align = settings[Common.ALIGN]
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                MusicNote(Muted, Modifier.size(pxToDp(h * 0.62f)))
                Spacer(Modifier.width(pxToDp(h * 0.2f)))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                    val titleSize = pxToSp(min(h * 0.34f, w * 0.05f))
                    if (track == null) {
                        Text("Nothing playing", color = Muted, fontSize = titleSize, maxLines = 1)
                    } else {
                        Text(track.title, color = colour, fontSize = titleSize, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align))
                        if (settings[SHOW_ARTIST] && track.artist.isNotBlank()) {
                            Text(track.artist, color = Muted, fontSize = pxToSp(min(h * 0.24f, w * 0.04f)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align))
                        }
                        if (settings[SHOW_PROGRESS] && track.durationMs > 0) {
                            Spacer(Modifier.height(pxToDp(h * 0.06f)))
                            Progress(track, colour, Modifier.fillMaxWidth().height(pxToDp((h * 0.04f).coerceAtLeast(2f))))
                        }
                    }
                }
            }
        }
    }
}

/** The progress bar. While playing it moves on by itself every few seconds, worked out from when the position was read. */
@Composable
private fun Progress(track: MediaState.Track, color: Color, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track) {
        while (track.playing) {
            now = SystemClock.elapsedRealtime()
            delay(5_000)
        }
    }
    val position = if (track.playing) track.positionMs + ((now - track.positionAtElapsedMs) * track.speed).toLong() else track.positionMs
    val fraction = (position.toFloat() / track.durationMs).coerceIn(0f, 1f)
    Box(modifier.background(Color(0xFF222222))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(color))
    }
}

@Composable
private fun MusicNote(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = Stroke(s * 0.075f, cap = StrokeCap.Round)
        val path = Path().apply { moveTo(s * 0.375f, s * 0.71f); lineTo(s * 0.375f, s * 0.23f); lineTo(s * 0.79f, s * 0.15f); lineTo(s * 0.79f, s * 0.62f) }
        drawPath(path, color, style = stroke)
        drawCircle(color, s * 0.11f, Offset(s * 0.283f, s * 0.73f))
        drawCircle(color, s * 0.11f, Offset(s * 0.7f, s * 0.646f))
    }
}
