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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.data.NotificationState
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
import kotlin.math.min

/** A one-line hint, shown only in the editor and previews (on the display itself the widget stays empty). */
@Composable
internal fun EditorHint(text: String, size: Float) {
    if (!LocalEditing.current) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = pxToSp(size), maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

internal fun horizontal(align: String) = when (align) { "center" -> Alignment.CenterHorizontally; "right" -> Alignment.End; else -> Alignment.Start }
internal fun textAlign(align: String) = when (align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start }

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

    /** The layouts on offer, as (id, name). The ids are stored in saved layouts, so they keep their first names. */
    val LAYOUTS = listOf("side" to "Classic", "stacked" to "Card", "compact" to "Ticker", "big" to "Poster")

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

    /** The details the owner turned on, each with its symbol: thermometer 21°, droplet 48%, wind 14 km/h, umbrella 10%. */
    fun details(settings: Settings, weather: Weather): List<Pair<Glyph, String>> {
        val f = settings[UNITS] == "f"
        return listOfNotNull(
            weather.feelsLikeC?.takeIf { settings[SHOW_FEELS] }?.let { Glyph.Thermometer to formatTemperature(it, f) },
            weather.humidityPercent?.takeIf { settings[SHOW_HUMIDITY] }?.let { Glyph.Droplet to "$it%" },
            weather.windKmh?.takeIf { settings[SHOW_WIND] }?.let { Glyph.Wind to formatWind(it, f) },
            weather.rainChancePercent?.takeIf { settings[SHOW_RAIN] }?.let { Glyph.Umbrella to "$it%" },
        )
    }

    /** The weather drawn in the layout [LAYOUT] picks, filling whatever box it is given. */
    @Composable
    fun WeatherBody(settings: Settings, cityName: String, weather: Weather) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val look = WeatherLook(
                settings = settings,
                weather = weather,
                h = constraints.maxHeight.toFloat(),
                w = constraints.maxWidth.toFloat(),
                place = listOfNotNull(conditionOf(weather.code).takeIf { settings[SHOW_CONDITION] }, cityName.takeIf { settings[SHOW_CITY] }).joinToString(" · "),
                details = details(settings, weather),
            )
            when (settings[LAYOUT]) {
                "stacked" -> look.Card()
                "compact" -> look.Ticker()
                "big" -> look.Poster()
                else -> look.Classic()
            }
        }
    }
}

/** "14 km/h", or "9 mph" when the widget is in Fahrenheit. */
fun formatWind(kmh: Double, miles: Boolean): String =
    if (miles) String.format(Locale.US, "%d mph", Math.round(kmh * 0.621371)) else String.format(Locale.US, "%d km/h", Math.round(kmh))

/** Everything the four weather layouts share: the readings, the colours, and how big a box they have. */
private class WeatherLook(
    val settings: Settings,
    val weather: Weather,
    val h: Float,
    val w: Float,
    /** "Partly cloudy · Cairo", or less, or empty. */
    val place: String,
    val details: List<Pair<Glyph, String>>,
) {
    val f = settings[WeatherWidget.UNITS] == "f"
    val colour = Color(settings[Common.COLOUR])
    val accent = Color(settings[WeatherWidget.ACCENT])
    val align = settings[Common.ALIGN]
    val showIcon = settings[WeatherWidget.SHOW_ICON]
    val showRange = settings[WeatherWidget.SHOW_RANGE]
    val scale = settings[WeatherWidget.SIZE] / 100f
    val outline = settings[WeatherWidget.ICON_STYLE] == "outline"
    val sky = skyOf(weather.code)

    @Composable
    fun Icon(px: Float, modifier: Modifier = Modifier, alpha: Float = 1f) =
        WeatherIcon(sky, weather.isDay, accent.copy(alpha = alpha), modifier.size(pxToDp(px)), outline, colour.copy(alpha = alpha))

    /** The temperature with a small degree sign set level with the top of the digits, which is how weather apps set it. */
    @Composable
    fun Temperature(px: Float, weight: FontWeight = FontWeight.Light) {
        val number = Math.round(if (f) weather.temperatureC * 9 / 5 + 32 else weather.temperatureC).toString()
        Row(verticalAlignment = Alignment.Top) {
            Text(number, color = colour, fontSize = pxToSp(px), fontWeight = weight, maxLines = 1, softWrap = false)
            Text("°", color = colour.copy(alpha = 0.8f), fontSize = pxToSp(px * 0.5f), fontWeight = weight, maxLines = 1, softWrap = false)
        }
    }

    @Composable
    fun Small(text: String, px: Float, modifier: Modifier = Modifier) =
        Text(text, color = Muted, fontSize = pxToSp(px), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align), modifier = modifier)

    /** "↑26°  ↓17°". */
    val rangeText get() = "↑${formatTemperature(weather.highC, f)}  ↓${formatTemperature(weather.lowC, f)}"

    /** The details as symbol-and-value pairs on one line. */
    @Composable
    fun Details(px: Float) = Row(verticalAlignment = Alignment.CenterVertically) {
        details.forEachIndexed { i, (glyph, value) ->
            if (i > 0) Spacer(Modifier.width(pxToDp(px * 0.7f)))
            DetailGlyph(glyph, Muted, Modifier.size(pxToDp(px * 0.95f)))
            Spacer(Modifier.width(pxToDp(px * 0.22f)))
            Text(value, color = Muted, fontSize = pxToSp(px), maxLines = 1, softWrap = false)
        }
    }

    /** How much to shrink by so lines of these heights (in pixels) fit the box. */
    fun fit(vararg heights: Float) = min(1f, h * 0.96f / heights.sum())

    val arrangement get() = when (align) { "center" -> Arrangement.Center; "right" -> Arrangement.End; else -> Arrangement.Start }

    /** Icon, big temperature, a hairline, then the words and details stacked beside it. */
    @Composable
    fun Classic() {
        val lines = listOf(place.isNotEmpty(), showRange, details.isNotEmpty()).count { it }
        val small = min(h * 0.19f, w * 0.065f)
        val k = fit(small * 1.3f * lines)
        Row(Modifier.fillMaxSize(), horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
            if (showIcon) {
                Icon(h * 0.66f)
                Spacer(Modifier.width(pxToDp(h * 0.08f)))
            }
            Temperature(min(h * 0.62f, w * 0.2f) * scale)
            if (lines > 0) {
                Spacer(Modifier.width(pxToDp(h * 0.12f)))
                Box(Modifier.width(pxToDp((h * 0.012f).coerceAtLeast(1f))).height(pxToDp(h * 0.62f)).background(Color(0xFF333333)))
                Spacer(Modifier.width(pxToDp(h * 0.12f)))
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
                    if (place.isNotEmpty()) Small(place, small * k)
                    if (showRange) Small(rangeText, small * k)
                    if (details.isNotEmpty()) Details(small * k * 0.92f)
                }
            }
        }
    }

    /** Icon on top, the temperature, then today's low-to-high bar with a dot for now, then the details. */
    @Composable
    fun Card() {
        val icon = h * 0.3f
        val temp = min(h * 0.3f, w * 0.14f) * scale
        val small = min(h * 0.12f, w * 0.06f)
        val k = fit(
            if (showIcon) icon else 0f, temp * 1.15f,
            if (place.isNotEmpty()) small * 1.3f else 0f, if (showRange) small * 1.6f else 0f, if (details.isNotEmpty()) small * 1.3f else 0f,
        )
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
            if (showIcon) Icon(icon * k)
            Temperature(temp * k)
            if (place.isNotEmpty()) Small(place, small * k)
            if (showRange) RangeBar(small * k, Modifier.width(pxToDp(min(w * 0.9f, h * 2.6f))).padding(vertical = pxToDp(small * k * 0.2f)))
            if (details.isNotEmpty()) Details(small * k * 0.95f)
        }
    }

    /** "17° ━━━●━━ 26°": today's range as a track, with a dot where the temperature is now. */
    @Composable
    fun RangeBar(px: Float, modifier: Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(formatTemperature(weather.lowC, f), color = Muted, fontSize = pxToSp(px), maxLines = 1, softWrap = false)
        Canvas(Modifier.weight(1f).height(pxToDp(px)).padding(horizontal = pxToDp(px * 0.5f))) {
            val y = size.height / 2
            val thick = size.height * 0.22f
            val span = (weather.highC - weather.lowC).takeIf { it > 0.1 } ?: 1.0
            val at = ((weather.temperatureC - weather.lowC) / span).toFloat().coerceIn(0f, 1f)
            drawLine(Color(0xFF2E2E2E), Offset(0f, y), Offset(size.width, y), thick, StrokeCap.Round)
            drawLine(lerp(colour, Color.Black, 0.45f), Offset(0f, y), Offset(size.width * at, y), thick, StrokeCap.Round)
            drawCircle(Color.Black, size.height * 0.36f, Offset(size.width * at, y))
            drawCircle(accent, size.height * 0.24f, Offset(size.width * at, y))
        }
        Text(formatTemperature(weather.highC, f), color = Muted, fontSize = pxToSp(px), maxLines = 1, softWrap = false)
    }

    /** Everything on one line: icon, temperature, condition, range and details, divided by thin dots. */
    @Composable
    fun Ticker() {
        val temp = min(h * 0.62f, w * 0.12f) * scale
        val small = min(h * 0.36f, w * 0.05f)
        Row(Modifier.fillMaxSize().clipToBounds(), horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
            if (showIcon) {
                Icon(min(h * 0.8f, temp * 1.25f))
                Spacer(Modifier.width(pxToDp(small * 0.4f)))
            }
            Temperature(temp, FontWeight.Normal)
            val parts = listOfNotNull(place.takeIf { it.isNotEmpty() }, rangeText.takeIf { showRange })
            parts.forEach {
                Dot(small)
                Text(it, color = Muted, fontSize = pxToSp(small), maxLines = 1, softWrap = false)
            }
            if (details.isNotEmpty()) {
                Dot(small)
                Details(small)
            }
        }
    }

    @Composable
    private fun Dot(px: Float) = Box(Modifier.padding(horizontal = pxToDp(px * 0.45f)).size(pxToDp(px * 0.16f)).background(Color(0xFF555555), CircleShape))

    /** A very large thin temperature over the weather icon drawn big and faint, with the words beneath. */
    @Composable
    fun Poster() {
        val small = min(h * 0.14f, w * 0.055f)
        val lines = listOf(place.isNotEmpty(), showRange || details.isNotEmpty()).count { it }
        val temp = min(h * 0.66f, w * 0.34f) * scale
        val k = fit(temp * 1.2f, small * 1.45f * lines)
        Box(Modifier.fillMaxSize()) {
            if (showIcon) {
                // Faint, so it reads as a backdrop and does not wear one spot of the screen.
                val iconAlign = if (align == "right") Alignment.CenterStart else Alignment.CenterEnd
                Icon(min(h * 1.05f, w * 0.6f), Modifier.align(iconAlign), alpha = 0.22f)
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                Temperature(temp * k, FontWeight.Thin)
                if (place.isNotEmpty()) Small(place, small * k)
                if (showRange || details.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showRange) Text(rangeText, color = Muted, fontSize = pxToSp(small * k), maxLines = 1, softWrap = false)
                    if (showRange && details.isNotEmpty()) Spacer(Modifier.width(pxToDp(small * k * 0.8f)))
                    if (details.isNotEmpty()) Details(small * k)
                }
            }
        }
    }
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
