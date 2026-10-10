package com.ikverse.deskglow.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        ColourField("Accent colour", ACCENT),
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
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
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
                    textAlign = textAlign(align),
                )
                if (settings[SHOW_LABEL]) {
                    Spacer(Modifier.height(pxToDp(h * 0.06f)))
                    Text(label, color = Muted, fontSize = pxToSp(min(h * 0.26f, w * 0.11f)), maxLines = 1, textAlign = textAlign(align))
                }
            }
        }
    }
}
