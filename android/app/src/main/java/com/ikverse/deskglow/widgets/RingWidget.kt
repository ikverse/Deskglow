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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.min

object RingWidget : WidgetType {
    val ACCENT = ColourKey("accent", 0xFF44B98A.toInt())
    val THICKNESS = IntKey("thickness", 6)
    /** The old switch for the status text; now [UNDER], which a saved ring is brought to by [migrate]. */
    val SHOW_LABEL = FlagKey("showLabel", true)
    val SHOW_BOLT = FlagKey("showBolt", true)
    /** What is written under the number: "status", "time" (to full), "power", "temp" or "none". */
    val UNDER = TextKey("under", "status")
    /** "gauge" (three quarters of a circle), "circle" or "segments". */
    val LAYOUT = TextKey("layout", "gauge")
    /** Red when the battery is low and amber when it is half, in place of the ring's colour. */
    val LEVEL_COLOUR = FlagKey("levelColour", false)

    override val id = "ring"
    override val label = "Charging ring"
    override val blurb = "Battery level as a ring"
    override val width = 264
    override val height = 264
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        LayoutField("Layout", LAYOUT, listOf("gauge" to "Gauge", "circle" to "Circle", "segments" to "Segments")),
        ChoiceField("Under the number", UNDER, listOf("status" to "Status", "time" to "Time to full", "power" to "Power", "temp" to "Temperature", "none" to "Nothing")),
        ColourField("Accent colour", ACCENT),
        SliderField("Ring thickness", THICKNESS, 3..12),
        ShowField("Options", listOf(SHOW_BOLT to "Bolt", LEVEL_COLOUR to "Colour by level")),
        Common.colourField,
        Common.brightnessField,
    )

    /** The status text switch became "Under the number": a ring with it off shows nothing there, as before. */
    override fun migrate(settings: Settings): Settings =
        if (UNDER.name in settings.values) settings else settings.with(UNDER, if (settings[SHOW_LABEL]) "status" else "none")

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val under = settings[UNDER]
        // Power and the time to full change between Android's battery broadcasts, so they need the polling feed.
        val battery by (if (under == "power" || under == "time") feeds.batteryPower else feeds.battery).collectAsStateWithLifecycle()
        val colour = Color(settings[Common.COLOUR])
        val accent = Color(settings[ACCENT]).let { if (settings[LEVEL_COLOUR]) levelColour(battery.level, it) else it }
        val layout = settings[LAYOUT]
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val side = min(constraints.maxWidth, constraints.maxHeight).toFloat()
            Box(Modifier.size(pxToDp(side)), contentAlignment = Alignment.Center) {
                val thickness = settings[THICKNESS] / 100f * side
                Canvas(Modifier.fillMaxSize()) {
                    val inset = side * 0.04f + thickness / 2
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    val corner = Offset(inset, inset)
                    val track = Color(0xFF1B1B1B)
                    val level = battery.level.coerceIn(0, 100)
                    when (layout) {
                        "circle" -> {
                            val stroke = Stroke(thickness, cap = StrokeCap.Round)
                            drawArc(track, -90f, 360f, false, corner, arcSize, style = stroke)
                            if (level > 0) drawArc(accent, -90f, 360f * level / 100f, false, corner, arcSize, style = stroke)
                        }
                        "segments" -> {
                            val count = 20
                            val step = 270f / count
                            val gap = step * 0.3f
                            val lit = Math.round(level / 100f * count)
                            val stroke = Stroke(thickness, cap = StrokeCap.Butt)
                            for (i in 0 until count) {
                                drawArc(if (i < lit) accent else track, 135f + i * step + gap / 2, step - gap, false, corner, arcSize, style = stroke)
                            }
                        }
                        else -> {
                            val stroke = Stroke(thickness, cap = StrokeCap.Round)
                            drawArc(track, 135f, 270f, false, corner, arcSize, style = stroke)
                            val sweep = 270f * level / 100f
                            if (sweep > 0f) drawArc(accent, 135f, sweep, false, corner, arcSize, style = stroke)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (settings[SHOW_BOLT] && battery.plugged) {
                        Bolt(accent, Modifier.size(pxToDp(side * 0.08f), pxToDp(side * 0.11f)))
                        Spacer(Modifier.height(pxToDp(side * 0.024f)))
                    }
                    Text("${battery.level}%", color = colour, fontSize = pxToSp(side * 0.165f), fontWeight = FontWeight.Medium, maxLines = 1)
                    underText(under, battery)?.let { text ->
                        Spacer(Modifier.height(pxToDp(side * 0.024f)))
                        Text(text, color = Muted, fontSize = pxToSp(side * 0.056f), letterSpacing = 0.06.em, maxLines = 1)
                    }
                }
            }
        }
    }

    /** What is written under the percentage for the [under] choice; null for nothing. */
    fun underText(under: String, battery: BatteryState): String? = when (under) {
        "none" -> null
        "time" -> StatWidget.reading("time", battery).first.let { if (it == "Full" || it == "—") it.uppercase() else "$it to full" }
        "power" -> StatWidget.reading("power", battery).let { (value, unit, _) -> if (value == "—") value else "$value $unit" }
        "temp" -> StatWidget.reading("temp", battery).let { (value, unit, _) -> "$value $unit" }
        else -> statusText(battery)
    }

    /** [accent], or red below 20% and amber below 50%. */
    fun levelColour(level: Int, accent: Color): Color = when {
        level < 20 -> Color(0xFFE5534B)
        level < 50 -> Color(0xFFF5B942)
        else -> accent
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
