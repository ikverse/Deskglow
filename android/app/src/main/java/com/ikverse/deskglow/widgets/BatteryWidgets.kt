package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.BatteryState
import com.ikverse.deskglow.data.ChargeStatus
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.IntKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.util.Locale
import kotlin.math.min

/** Text sized in pixels, so it fills a widget the same way whatever the phone's font-size setting. */
@Composable
fun pxToSp(px: Float): TextUnit = with(LocalDensity.current) { px.toSp() }

@Composable
fun pxToDp(px: Float): Dp = with(LocalDensity.current) { px.toDp() }

object RingWidget : WidgetType {
    val ACCENT = ColourKey("accent", 0xFF44B98A.toInt())
    val THICKNESS = IntKey("thickness", 6)
    val SHOW_LABEL = FlagKey("showLabel", true)
    val SHOW_BOLT = FlagKey("showBolt", true)

    override val id = "ring"
    override val label = "Charging ring"
    override val blurb = "Battery level as a ring"
    override val width = 264
    override val height = 264
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ColourField("Ring colour", ACCENT),
        SliderField("Ring thickness", THICKNESS, 3..12),
        ToggleField("Show status text", SHOW_LABEL),
        ToggleField("Show bolt icon", SHOW_BOLT),
        Common.colourField,
        Common.brightnessField,
    )

    @Composable
    override fun Content(settings: Settings) {
        val battery by LocalFeeds.current.battery.collectAsStateWithLifecycle()
        val colour = Color(settings[Common.COLOUR])
        val accent = Color(settings[ACCENT])
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val side = min(constraints.maxWidth, constraints.maxHeight).toFloat()
            Box(Modifier.size(pxToDp(side)), contentAlignment = Alignment.Center) {
                val thickness = settings[THICKNESS] / 100f * side
                Canvas(Modifier.fillMaxSize()) {
                    val inset = side * 0.04f + thickness / 2
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    val stroke = Stroke(thickness, cap = StrokeCap.Round)
                    drawArc(Color(0xFF1B1B1B), 135f, 270f, false, Offset(inset, inset), arcSize, style = stroke)
                    val sweep = 270f * battery.level.coerceIn(0, 100) / 100f
                    if (sweep > 0f) drawArc(accent, 135f, sweep, false, Offset(inset, inset), arcSize, style = stroke)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (settings[SHOW_BOLT] && battery.plugged) {
                        Bolt(accent, Modifier.size(pxToDp(side * 0.08f), pxToDp(side * 0.11f)))
                        Spacer(Modifier.height(pxToDp(side * 0.024f)))
                    }
                    Text("${battery.level}%", color = colour, fontSize = pxToSp(side * 0.165f), fontWeight = FontWeight.Medium, maxLines = 1)
                    if (settings[SHOW_LABEL]) {
                        Spacer(Modifier.height(pxToDp(side * 0.024f)))
                        Text(statusText(battery), color = Muted, fontSize = pxToSp(side * 0.056f), letterSpacing = 0.06.em, maxLines = 1)
                    }
                }
            }
        }
    }

    private fun statusText(battery: BatteryState) = when (battery.status) {
        ChargeStatus.Full -> "FULL"
        ChargeStatus.Charging -> "CHARGING"
        else -> if (battery.plugged) "PLUGGED IN" else "ON BATTERY"
    }
}

@Composable
fun Bolt(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.62f, 0f)
            lineTo(0f, h * 0.58f)
            lineTo(w * 0.46f, h * 0.58f)
            lineTo(w * 0.38f, h)
            lineTo(w, h * 0.42f)
            lineTo(w * 0.54f, h * 0.42f)
            close()
        }
        drawPath(path, color)
    }
}

object StatWidget : WidgetType {
    val METRIC = TextKey("metric", "temp")
    val SHOW_LABEL = FlagKey("showLabel", true)
    val ACCENT = ColourKey("accent", 0xFF44B98A.toInt())

    private val METRICS = listOf(
        "temp" to "Temperature", "voltage" to "Voltage", "power" to "Power",
        "current" to "Current", "time" to "Time to full", "level" to "Battery level",
    )

    override val id = "stat"
    override val label = "Stat"
    override val blurb = "One battery number"
    override val width = 112
    override val height = 56
    override val defaults: Settings = Common.base()

    override fun title(settings: Settings) = "Stat · " + (METRICS.firstOrNull { it.first == settings[METRIC] }?.second ?: "?")

    override fun fields(settings: Settings) = listOf(
        ChoiceField("Shows", METRIC, METRICS),
        Common.alignField,
        ToggleField("Show label", SHOW_LABEL),
        ColourField("Unit colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    /** The value, its unit and its label for [metric]. A dash where the phone does not say. */
    fun reading(metric: String, battery: BatteryState): Triple<String, String, String> = when (metric) {
        "voltage" -> Triple(String.format(Locale.US, "%.3f", battery.volts), "V", "Voltage")
        "power" -> Triple(battery.watts?.let { String.format(Locale.US, "%.2f", it) } ?: "—", "W", "Power")
        "current" -> Triple(battery.currentMa?.toString() ?: "—", "mA", "Current")
        "level" -> Triple(battery.level.toString(), "%", "Battery")
        "time" -> Triple(timeToFull(battery), "", "Estimate")
        else -> Triple(String.format(Locale.US, "%.1f", battery.temperatureC), "°C", "Temp")
    }

    private fun timeToFull(battery: BatteryState): String {
        if (battery.status == ChargeStatus.Full) return "Full"
        val ms = battery.timeToFullMs ?: return "—"
        val minutes = (ms / 60_000).toInt()
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    /** Power, current and the time to full change between Android's battery broadcasts, so they need the polling feed. */
    private fun needsPolling(metric: String) = metric == "power" || metric == "current" || metric == "time"

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val battery by (if (needsPolling(settings[METRIC])) feeds.batteryPower else feeds.battery).collectAsStateWithLifecycle()
        val (value, unit, label) = reading(settings[METRIC], battery)
        val align = settings[Common.ALIGN]
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val horizontal = when (align) { "center" -> Alignment.CenterHorizontally; "right" -> Alignment.End; else -> Alignment.Start }
            val textAlign = when (align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal) {
                val valueSize = min(h * 0.56f, w * 0.22f)
                Text(
                    buildAnnotatedString {
                        append(value)
                        if (unit.isNotEmpty()) withStyle(SpanStyle(color = Color(settings[ACCENT]), fontSize = pxToSp(valueSize * 0.64f), fontWeight = FontWeight.Normal)) {
                            append(" $unit")
                        }
                    },
                    color = Color(settings[Common.COLOUR]),
                    fontSize = pxToSp(valueSize),
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = textAlign,
                )
                if (settings[SHOW_LABEL]) {
                    Spacer(Modifier.height(pxToDp(h * 0.06f)))
                    Text(label, color = Muted, fontSize = pxToSp(min(h * 0.26f, w * 0.11f)), maxLines = 1, textAlign = textAlign)
                }
            }
        }
    }
}
