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
import com.ikverse.deskglow.data.HourForecast
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object WeatherWidget : WidgetType {
    val UNITS = TextKey("units", "c")
    val LAYOUT = TextKey("layout", "side")
    val ICON_STYLE = TextKey("iconStyle", "filled")
    val SIZE = IntKey("size", 100)
    /** The old switch for the icon; now "none" in [ICON_STYLE], which a saved weather widget is brought to by [migrate]. */
    val SHOW_ICON = FlagKey("showIcon", true)
    /** The temperature coloured by how warm it is. */
    val TINT = FlagKey("tint", false)
    val SHOW_SUNRISE = FlagKey("showSunrise", false)
    val SHOW_SUNSET = FlagKey("showSunset", false)
    val SHOW_UV = FlagKey("showUv", false)
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
    val LAYOUTS = listOf("side" to "Classic", "stacked" to "Card", "compact" to "Ticker", "big" to "Poster", "hours" to "Forecast")

    /** What the picker's tiles show when there is no weather yet. */
    val SAMPLE: Weather get() = Weather(
        22.0, 2, true, 26.0, 17.0, 0, feelsLikeC = 21.0, humidityPercent = 48, windKmh = 14.0, rainChancePercent = 10,
        hours = sampleHours(), sunrise = LocalDate.now().atTime(6, 0), sunset = LocalDate.now().atTime(18, 0), uvIndex = 6.0,
        utcOffsetSeconds = ZonedDateTime.now().offset.totalSeconds,
    )

    /** Twelve invented hours from the next one, for a picker tile before there is any real weather. */
    private fun sampleHours(): List<HourForecast> {
        val start = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1)
        val codes = listOf(2, 2, 3, 3, 61, 61, 3, 2, 2, 0, 0, 1)
        return codes.mapIndexed { i, code -> HourForecast(start.plusHours(i.toLong()), 22.0 - i * 0.6, code, start.plusHours(i.toLong()).hour in 6..17) }
    }

    override fun fields(settings: Settings) = listOf(
        StyleField("Layout", LAYOUT, StyleKind.Weather),
        ChoiceField("Units", UNITS, listOf("c" to "°C", "f" to "°F")),
        ChoiceField("Icon", ICON_STYLE, listOf("filled" to "Filled", "outline" to "Outline", "none" to "None")),
        SliderField("Temperature size", SIZE, 60..140, "%"),
        ToggleField("Tint by temperature", TINT),
        Common.alignField,
        ShowField(
            "Show",
            listOf(
                SHOW_CONDITION to "Condition", SHOW_RANGE to "High and low", SHOW_CITY to "City",
                SHOW_FEELS to "Feels like", SHOW_HUMIDITY to "Humidity", SHOW_WIND to "Wind", SHOW_RAIN to "Chance of rain",
                SHOW_SUNRISE to "Sunrise", SHOW_SUNSET to "Sunset", SHOW_UV to "UV index",
            ),
        ),
        ColourField("Accent colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    /** The icon switch became "None" in the icon choice: a widget with the icon off keeps it off. */
    override fun migrate(settings: Settings): Settings =
        if (settings[SHOW_ICON]) settings else settings.with(SHOW_ICON, true).with(ICON_STYLE, "none")

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
            // Only the forecast layout reads the clock: it picks the hours still to come.
            val now = if (settings[LAYOUT] == "hours") {
                val minute by LocalFeeds.current.minute.collectAsStateWithLifecycle()
                weather.cityTime(minute)
            } else null
            WeatherBody(settings, city.name, weather, now)
        }
    }

    /** The details the owner turned on, each with its symbol: thermometer 21°, droplet 48%, wind 14 km/h, umbrella 10%. */
    fun details(settings: Settings, weather: Weather, h24: Boolean = false): List<Pair<Glyph, String>> {
        val f = settings[UNITS] == "f"
        return listOfNotNull(
            weather.feelsLikeC?.takeIf { settings[SHOW_FEELS] }?.let { Glyph.Thermometer to formatTemperature(it, f) },
            weather.humidityPercent?.takeIf { settings[SHOW_HUMIDITY] }?.let { Glyph.Droplet to "$it%" },
            weather.windKmh?.takeIf { settings[SHOW_WIND] }?.let { Glyph.Wind to formatWind(it, f) },
            weather.rainChancePercent?.takeIf { settings[SHOW_RAIN] }?.let { Glyph.Umbrella to "$it%" },
            weather.sunrise?.takeIf { settings[SHOW_SUNRISE] }?.let { Glyph.Sunrise to sunTime(it, h24) },
            weather.sunset?.takeIf { settings[SHOW_SUNSET] }?.let { Glyph.Sunset to sunTime(it, h24) },
            weather.uvIndex?.takeIf { settings[SHOW_UV] }?.let { Glyph.Uv to Math.round(it).toString() },
        )
    }

    /** The weather drawn in the layout [LAYOUT] picks, filling whatever box it is given. */
    @Composable
    fun WeatherBody(settings: Settings, cityName: String, weather: Weather, now: LocalDateTime? = null) {
        val h24 = Common.use24Hour(settings)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val look = WeatherLook(
                settings = settings,
                weather = weather,
                h = constraints.maxHeight.toFloat(),
                w = constraints.maxWidth.toFloat(),
                place = listOfNotNull(conditionOf(weather.code).takeIf { settings[SHOW_CONDITION] }, cityName.takeIf { settings[SHOW_CITY] }).joinToString(" · "),
                details = details(settings, weather, h24),
                h24 = h24,
                now = now,
            )
            when (settings[LAYOUT]) {
                "hours" -> look.Hours()
                "stacked" -> look.Card()
                "compact" -> look.Ticker()
                "big" -> look.Poster()
                else -> look.Classic()
            }
        }
    }
}

/** The temperature's colour when tinted: blue in the cold, [base] around 20°, then amber and red in the heat. */
fun tintFor(celsius: Double, base: Color): Color {
    val cold = Color(0xFF6EC1FF)
    val warm = Color(0xFFFFB347)
    val hot = Color(0xFFFF6B4A)
    return when {
        celsius <= 20 -> lerp(cold, base, ((celsius + 5) / 25).toFloat().coerceIn(0f, 1f))
        celsius <= 30 -> lerp(base, warm, ((celsius - 20) / 10).toFloat().coerceIn(0f, 1f))
        else -> lerp(warm, hot, ((celsius - 30) / 10).toFloat().coerceIn(0f, 1f))
    }
}

/** How wide one detail (its symbol, a gap and its text) sets, in units of the text size. */
private fun detailUnits(value: String) = 1.17f + value.length * 0.56f

/** How wide a line of details sets, in units of the text size, gaps between them included. */
internal fun detailLineUnits(values: List<String>): Float =
    values.sumOf { detailUnits(it).toDouble() }.toFloat() + (values.size - 1).coerceAtLeast(0) * 0.7f

/** Shrinking the details by more than this to keep them on one line is not worth it: they go onto a second line. */
private const val DETAILS_MIN_SCALE = 0.8f

/** The most lines the details may take. */
private const val DETAILS_MAX_LINES = 3

/**
 * The details split for [room] at text size [px], as lists of indexes into [values]: on one line when it fits,
 * or shrinks by no more than a fifth to fit; otherwise on as few lines (up to three) as let each fit like that,
 * the lines about equal in width.
 */
internal fun splitDetails(values: List<String>, px: Float, room: Float): List<List<Int>> {
    if (values.size < 2) return listOf(values.indices.toList())
    for (count in 1 until DETAILS_MAX_LINES) {
        val lines = balancedLines(values, count)
        val widest = lines.maxOf { detailLineUnits(it.map { i -> values[i] }) } * px
        if (widest <= room || room / widest >= DETAILS_MIN_SCALE) return lines
    }
    return balancedLines(values, DETAILS_MAX_LINES)
}

/** [values] cut into [count] runs, in order, so that the widest run is as narrow as it can be. */
private fun balancedLines(values: List<String>, count: Int): List<List<Int>> {
    val n = minOf(count, values.size)
    fun width(from: Int, to: Int) = detailLineUnits(values.subList(from, to))
    var best = listOf(0, values.size)
    var narrowest = Float.MAX_VALUE
    fun search(cuts: List<Int>) {
        if (cuts.size == n - 1) {
            val edges = listOf(0) + cuts + values.size
            val widest = (0 until n).maxOf { width(edges[it], edges[it + 1]) }
            if (widest < narrowest) {
                narrowest = widest
                best = edges
            }
            return
        }
        val remaining = n - 2 - cuts.size
        for (cut in (cuts.lastOrNull() ?: 0) + 1..values.size - 1 - remaining) search(cuts + cut)
    }
    search(emptyList())
    return (0 until n).map { (best[it] until best[it + 1]).toList() }
}

/** The text size at which the widest of [lines] fits [room]: [px] if it already does, never less than half of it. */
internal fun detailsSize(values: List<String>, lines: List<List<Int>>, px: Float, room: Float): Float {
    val widest = lines.maxOf { line -> detailLineUnits(line.map { values[it] }) } * px
    return if (widest <= room) px else (px * room / widest).coerceAtLeast(px * 0.5f)
}

/** The lines the details are set on and the text size they are set at. */
internal class DetailsFit(val lines: List<List<Int>>, val px: Float)

/**
 * The details for [room] at about [px], with none cut in half: wrapped, then shrunk, and if even half size does
 * not hold them, the last ones left off.
 */
internal fun fitDetails(values: List<String>, px: Float, room: Float): DetailsFit {
    var lines = splitDetails(values, px, room)
    var size = detailsSize(values, lines, px, room)
    fun widest() = lines.maxOf { line -> detailLineUnits(line.map { values[it] }) } * size
    while (lines.sumOf { it.size } > 1 && widest() > room + 0.01f) {
        lines = (lines.dropLast(1) + listOf(lines.last().dropLast(1))).filter { it.isNotEmpty() }
        size = detailsSize(values, lines, px, room)
    }
    return DetailsFit(lines, size)
}

/** "5:42", or "5:42 AM" on a 12-hour clock: when the sun rises or sets. */
fun sunTime(time: LocalDateTime, h24: Boolean): String =
    time.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US))

/** "15" on a 24-hour clock, "3 PM" on a 12-hour one: the head of a forecast column. */
fun hourLabel(time: LocalDateTime, h24: Boolean): String =
    if (h24) String.format(Locale.US, "%02d", time.hour) else time.format(DateTimeFormatter.ofPattern("h a", Locale.US))

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
    val h24: Boolean = false,
    /** The city's clock, for the forecast layout. */
    val now: LocalDateTime? = null,
) {
    val f = settings[WeatherWidget.UNITS] == "f"
    val colour = Color(settings[Common.COLOUR])
    val accent = Color(settings[WeatherWidget.ACCENT])
    val align = settings[Common.ALIGN]
    val showIcon = settings[WeatherWidget.ICON_STYLE] != "none"
    val numberColour = if (settings[WeatherWidget.TINT]) tintFor(weather.temperatureC, colour) else colour
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
            Text(number, color = numberColour, fontSize = pxToSp(px), fontWeight = weight, maxLines = 1, softWrap = false)
            Text("°", color = numberColour.copy(alpha = 0.8f), fontSize = pxToSp(px * 0.5f), fontWeight = weight, maxLines = 1, softWrap = false)
        }
    }

    @Composable
    fun Small(text: String, px: Float, modifier: Modifier = Modifier) =
        Text(text, color = Muted, fontSize = pxToSp(px), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align), modifier = modifier)

    /** "↑26°  ↓17°". */
    val rangeText get() = "↑${formatTemperature(weather.highC, f)}  ↓${formatTemperature(weather.lowC, f)}"

    /** How the details sit: on one line or two, and at what text size. */
    class DetailsPlan(val lines: List<List<Int>>, val px: Float)

    /** The details for [room] wide at about [px]: wrapped onto a second line, or shrunk a little, so none is cut off. */
    fun planDetails(px: Float, room: Float): DetailsPlan {
        val fit = fitDetails(details.map { it.second }, px, room)
        return DetailsPlan(fit.lines, fit.px)
    }

    /** All the details on one line at [px]: the ticker's, a strip that clips by design. */
    fun oneLine(px: Float) = DetailsPlan(listOf(details.indices.toList()), px)

    /** The details as symbol-and-value pairs, on the lines [plan] gives them. */
    @Composable
    fun Details(plan: DetailsPlan, alignment: Alignment.Horizontal = Alignment.Start) = Column(horizontalAlignment = alignment) {
        plan.lines.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                line.forEachIndexed { i, index ->
                    val (glyph, value) = details[index]
                    if (i > 0) Spacer(Modifier.width(pxToDp(plan.px * 0.7f)))
                    DetailGlyph(glyph, Muted, Modifier.size(pxToDp(plan.px * 0.95f)))
                    Spacer(Modifier.width(pxToDp(plan.px * 0.22f)))
                    Text(value, color = Muted, fontSize = pxToSp(plan.px), maxLines = 1, softWrap = false)
                }
            }
        }
    }

    /** How many characters the temperature is, for reckoning how much room it leaves. */
    private val numberLength get() = Math.round(if (f) weather.temperatureC * 9 / 5 + 32 else weather.temperatureC).toString().length

    /** How much to shrink by so lines of these heights (in pixels) fit the box. */
    fun fit(vararg heights: Float) = min(1f, h * 0.96f / heights.sum())

    val arrangement get() = arrangementOf(align)

    /** Icon, big temperature, a hairline, then the words and details stacked beside it. */
    @Composable
    fun Classic() {
        val small = min(h * 0.19f, w * 0.065f)
        // What the icon, the temperature and the hairline leave for the words beside them.
        val tempPx = min(h * 0.62f, w * 0.2f) * scale
        val room = w - (if (showIcon) h * 0.66f + h * 0.08f else 0f) - tempPx * (0.56f * numberLength + 0.3f) - h * 0.24f - (h * 0.012f).coerceAtLeast(1f)
        val detailLines = if (details.isEmpty()) 0 else planDetails(small * 0.92f, room).lines.size
        val lines = listOf(place.isNotEmpty(), showRange).count { it } + detailLines
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
                    if (details.isNotEmpty()) Details(planDetails(small * k * 0.92f, room))
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
        val room = w * 0.92f
        val detailLines = if (details.isEmpty()) 0 else planDetails(small * 0.95f, room).lines.size
        val k = fit(
            if (showIcon) icon else 0f, temp * 1.15f,
            if (place.isNotEmpty()) small * 1.3f else 0f, if (showRange) small * 1.6f else 0f, small * 1.3f * detailLines,
        )
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
            if (showIcon) Icon(icon * k)
            Temperature(temp * k)
            if (place.isNotEmpty()) Small(place, small * k)
            if (showRange) RangeBar(small * k, Modifier.width(pxToDp(min(w * 0.9f, h * 2.6f))).padding(vertical = pxToDp(small * k * 0.2f)))
            if (details.isNotEmpty()) Details(planDetails(small * k * 0.95f, room), horizontal(align))
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
                Details(oneLine(small))
            }
        }
    }

    @Composable
    private fun Dot(px: Float) = Box(Modifier.padding(horizontal = pxToDp(px * 0.45f)).size(pxToDp(px * 0.16f)).background(Color(0xFF555555), CircleShape))

    /** The icon and temperature, then the next hours as columns: the hour, its sky and its temperature. */
    @Composable
    fun Hours() {
        val start = (now ?: LocalDateTime.now()).truncatedTo(ChronoUnit.HOURS).plusHours(1)
        val upcoming = weather.hours.filter { !it.time.isBefore(start) }
        val temp = min(h * 0.5f, w * 0.12f) * scale
        val small = min(h * 0.17f, w * 0.04f)
        val cell = small * 4.2f
        val left = (if (showIcon) h * 0.58f else 0f) + temp * 1.55f + h * 0.22f
        val count = ((w - left) / cell).toInt().coerceAtMost(8)
        if (upcoming.isEmpty() || count < 1) return Classic()
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (showIcon) {
                Icon(h * 0.5f)
                Spacer(Modifier.width(pxToDp(h * 0.08f)))
            }
            Temperature(temp)
            Spacer(Modifier.width(pxToDp(h * 0.1f)))
            Box(Modifier.width(pxToDp((h * 0.012f).coerceAtLeast(1f))).height(pxToDp(h * 0.62f)).background(Color(0xFF333333)))
            Spacer(Modifier.width(pxToDp(h * 0.1f)))
            upcoming.take(count).forEach { hour ->
                Column(Modifier.width(pxToDp(cell)), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(hourLabel(hour.time, h24), color = Muted, fontSize = pxToSp(small), maxLines = 1, softWrap = false)
                    WeatherIcon(skyOf(hour.code), hour.isDay, accent, Modifier.size(pxToDp(h * 0.3f)), outline, colour)
                    Text(formatTemperature(hour.temperatureC, f), color = colour, fontSize = pxToSp(small * 1.1f), maxLines = 1, softWrap = false)
                }
            }
        }
    }

    /** A very large thin temperature over the weather icon drawn big and faint, with the words beneath. */
    @Composable
    fun Poster() {
        val small = min(h * 0.14f, w * 0.055f)
        val lines = listOf(place.isNotEmpty(), showRange || details.isNotEmpty()).count { it }
        val temp = min(h * 0.66f, w * 0.34f) * scale
        // The range and the details share a row; what the range takes is not the details' to use.
        val room = w * 0.9f - if (showRange) (rangeText.length * 0.5f + 0.8f) * small else 0f
        val detailLines = if (details.isEmpty()) 0 else planDetails(small, room).lines.size
        val k = fit(temp * 1.2f, small * 1.45f * (lines + max(0, detailLines - 1)))
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
                    if (details.isNotEmpty()) Details(planDetails(small * k, room), horizontal(align))
                }
            }
        }
    }
}
