package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.LocalFeeds
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
import java.util.Locale
import kotlin.math.min

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
    override val blurb = "Temperature and sky"
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
        ShowField(
            "Show",
            listOf(
                SHOW_ICON to "Icon", SHOW_CONDITION to "Condition", SHOW_RANGE to "High and low", SHOW_CITY to "City",
                SHOW_FEELS to "Feels like", SHOW_HUMIDITY to "Humidity", SHOW_WIND to "Wind", SHOW_RAIN to "Chance of rain",
            ),
        ),
        ColourField("Accent colour", ACCENT),
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

    val arrangement get() = arrangementOf(align)

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
