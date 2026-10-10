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
import kotlin.math.min

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
        ColourField("Accent colour", ACCENT),
        SliderField("Ring thickness", THICKNESS, 3..12),
        ShowField("Show", listOf(SHOW_LABEL to "Status text", SHOW_BOLT to "Bolt")),
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
