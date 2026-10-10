package com.ikverse.deskglow.widgets

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.BatteryState
import com.ikverse.deskglow.data.ChargeStatus
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

object StatWidget : WidgetType {
    val METRIC = TextKey("metric", "temp")
    /** The old switch for the label; now [LABEL_MODE], which a saved stat is brought to by [migrate]. */
    val SHOW_LABEL = FlagKey("showLabel", true)
    /** "under" the number, "beside" it or "none". */
    val LABEL_MODE = TextKey("labelMode", "under")
    /** A line under the number: how it has moved over the last ten minutes. */
    val SPARK = FlagKey("spark", false)

    /** The readings that are numbers moving over time, and so can be graphed. */
    val GRAPHABLE = setOf("temp", "voltage", "power", "current", "level")
    val ACCENT = ColourKey("accent", 0xFF44B98A.toInt())

    private val METRICS = listOf(
        "temp" to "Temperature", "voltage" to "Voltage", "power" to "Power",
        "current" to "Current", "time" to "Time to full", "level" to "Battery level",
        "charger" to "Charger", "health" to "Battery health", "cycles" to "Charge cycles",
    )

    override val id = "stat"
    override val label = "Stat"
    override val blurb = "One battery number"
    override val width = 112
    override val height = 56
    override val defaults: Settings = Common.base()

    override fun title(settings: Settings) = "Stat · " + (METRICS.firstOrNull { it.first == settings[METRIC] }?.second ?: "?")

    override fun fields(settings: Settings): List<Field> = buildList {
        add(ChoiceField("Shows", METRIC, METRICS))
        add(Common.alignField)
        add(ChoiceField("Label", LABEL_MODE, listOf("under" to "Under", "beside" to "Beside", "none" to "None")))
        if (settings[METRIC] in GRAPHABLE) add(ToggleField("Graph of the last 10 minutes", SPARK))
        add(ColourField("Accent colour", ACCENT))
        add(Common.colourField)
        add(Common.brightnessField)
    }

    /** [metric] as a number to graph, or null for the readings that are words. */
    fun numeric(metric: String, battery: BatteryState): Double? = when (metric) {
        "temp" -> battery.temperatureC
        "voltage" -> battery.volts
        "power" -> battery.watts
        "current" -> battery.currentMa?.toDouble()
        "level" -> battery.level.toDouble()
        else -> null
    }

    /** The value, its unit and its label for [metric]. A dash where the phone does not say. */
    fun reading(metric: String, battery: BatteryState): Triple<String, String, String> = when (metric) {
        "voltage" -> Triple(String.format(Locale.US, "%.3f", battery.volts), "V", "Voltage")
        "power" -> Triple(battery.watts?.let { String.format(Locale.US, "%.2f", it) } ?: "—", "W", "Power")
        "current" -> Triple(battery.currentMa?.toString() ?: "—", "mA", "Current")
        "level" -> Triple(battery.level.toString(), "%", "Battery")
        "time" -> Triple(timeToFull(battery), "", "To full")
        "charger" -> Triple(chargerName(battery.plugSource), "", "Charger")
        "health" -> Triple(healthName(battery.healthCode), "", "Health")
        "cycles" -> Triple(battery.cycles?.toString() ?: "—", "", "Cycles")
        else -> Triple(String.format(Locale.US, "%.1f", battery.temperatureC), "°C", "Temp")
    }

    /** What the phone is plugged into: "Wall", "USB", "Wireless", "Dock" or "None". */
    fun chargerName(plugSource: Int): String = when {
        plugSource and 4 != 0 -> "Wireless"
        plugSource and 8 != 0 -> "Dock"
        plugSource and 2 != 0 -> "USB"
        plugSource and 1 != 0 -> "Wall"
        else -> "None"
    }

    /** The battery's health in a word, or a dash where the phone does not say. */
    fun healthName(code: Int): String = when (code) {
        2 -> "Good"
        3 -> "Hot"
        4 -> "Dead"
        5 -> "Over volt"
        6 -> "Fault"
        7 -> "Cold"
        else -> "—"
    }

    private fun timeToFull(battery: BatteryState): String {
        if (battery.status == ChargeStatus.Full) return "Full"
        val ms = battery.timeToFullMs ?: return "—"
        val minutes = (ms / 60_000).toInt()
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    /** Power, current and the time to full change between Android's battery broadcasts, so they need the polling feed. */
    private fun needsPolling(metric: String) = metric == "power" || metric == "current" || metric == "time"

    /** The label switch became "Label": a stat with it off shows none, as before. */
    override fun migrate(settings: Settings): Settings =
        if (LABEL_MODE.name in settings.values) settings else settings.with(LABEL_MODE, if (settings[SHOW_LABEL]) "under" else "none")

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val metric = settings[METRIC]
        val graph = settings[SPARK] && metric in GRAPHABLE
        val battery by (if (graph || needsPolling(metric)) feeds.batteryPower else feeds.battery).collectAsStateWithLifecycle()
        val (value, unit, label) = reading(metric, battery)
        val align = settings[Common.ALIGN]
        // A reading every so often while this is on screen, however little the number moves in between.
        var readings by remember { mutableIntStateOf(0) }
        if (graph) {
            val latest = rememberUpdatedState(battery)
            LaunchedEffect(metric) {
                while (isActive) {
                    numeric(metric, latest.value)?.let { StatHistory.add(metric, it, SystemClock.elapsedRealtime()) }
                    readings++
                    delay(StatHistory.GAP_MS)
                }
            }
        }
        val history = if (graph) remember(readings, metric) { SystemClock.elapsedRealtime().let { now -> now to StatHistory.recent(metric, now) } } else null
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val mode = settings[LABEL_MODE]
            val beside = mode == "beside"
            // With the graph there is less room for the number and its label.
            val body = if (graph) h * 0.74f else h
            val valueSize = min(body * 0.56f, w * (if (beside) 0.15f else 0.22f))
            val number = buildAnnotatedString {
                append(value)
                if (unit.isNotEmpty()) withStyle(SpanStyle(color = Color(settings[ACCENT]), fontSize = pxToSp(valueSize * 0.64f), fontWeight = FontWeight.Normal)) {
                    append(" $unit")
                }
            }
            val colour = Color(settings[Common.COLOUR])
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (beside) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = arrangementOf(align), verticalAlignment = Alignment.Bottom) {
                            Text(number, color = colour, fontSize = pxToSp(valueSize), fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
                            Spacer(Modifier.width(pxToDp(h * 0.12f)))
                            Text(label, color = Muted, fontSize = pxToSp(min(body * 0.26f, w * 0.08f)), maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = pxToDp(h * 0.1f)))
                        }
                    } else {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                            Text(number, color = colour, fontSize = pxToSp(valueSize), fontWeight = FontWeight.Light, maxLines = 1, softWrap = false, textAlign = textAlign(align))
                            if (mode == "under") {
                                Spacer(Modifier.height(pxToDp(body * 0.06f)))
                                Text(label, color = Muted, fontSize = pxToSp(min(body * 0.26f, w * 0.11f)), maxLines = 1, textAlign = textAlign(align))
                            }
                        }
                    }
                }
                if (history != null) {
                    Spacer(Modifier.height(pxToDp(h * 0.04f)))
                    Spark(history.second, history.first, Color(settings[ACCENT]), Modifier.fillMaxWidth().height(pxToDp(h * 0.2f)))
                }
            }
        }
    }
}

/** The recent readings as a thin line, the last ten minutes across the width, with a dot at the latest. Nothing until there are two. */
@Composable
private fun Spark(samples: List<StatHistory.Sample>, nowMs: Long, colour: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val low = samples.minOf { it.value }
        val high = samples.maxOf { it.value }
        val flat = high - low < 1e-9
        val pad = size.height * 0.15f
        val stroke = (size.height * 0.1f).coerceAtLeast(1.5f)
        fun at(sample: StatHistory.Sample) = Offset(
            ((sample.atMs - (nowMs - StatHistory.KEEP_MS)).toFloat() / StatHistory.KEEP_MS * size.width).coerceIn(0f, size.width),
            if (flat) size.height / 2 else pad + (size.height - 2 * pad) * (1f - ((sample.value - low) / (high - low)).toFloat()),
        )
        val path = Path()
        samples.forEachIndexed { i, sample -> at(sample).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
        drawPath(path, lerp(Color.Black, colour, 0.85f), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(colour, stroke * 1.6f, at(samples.last()))
    }
}
