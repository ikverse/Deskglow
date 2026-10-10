package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.fonts.BundledFonts
import com.ikverse.deskglow.fonts.FontIds
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey

object ClockWidget : WidgetType {
    /** A drawn style ("squared", "seg", ...), "thin"/"bold", or a font id. */
    val STYLE = TextKey("style", "squared")
    val H24 = FlagKey("h24", false)
    /** The old switch for seconds; now [SECONDS_MODE] ("none", "digits" or "line"), which a saved clock is brought to by [migrate]. */
    val SECONDS = FlagKey("seconds", false)
    val SECONDS_MODE = TextKey("secondsMode", "none")
    val LEADING_ZERO = FlagKey("leadingZero", false)
    val AMPM = FlagKey("ampm", false)

    override val id = "clock"
    override val label = "Clock"
    override val blurb = "Big time in any font"
    override val width = 220
    override val height = 64
    override val defaults: Settings = Common.base().with(Common.ALIGN, "center")

    override fun fields(settings: Settings): List<Field> = buildList {
        add(StyleField("Style", STYLE, StyleKind.Clock))
        add(Common.timeFormatField())
        add(ChoiceField("Seconds", SECONDS_MODE, listOf("none" to "Hidden", "digits" to "Digits", "line" to "Line")))
        add(ShowField("Details", listOf(LEADING_ZERO to "Leading zero", AMPM to "AM / PM")))
        addAll(Common.arabicFields(settings))
        add(Common.alignField)
        add(Common.colourField)
        add(Common.brightnessField)
    }

    /** The 24-hour switch became the time format: a clock set to 24-hour stays so, and every other keeps its 12-hour time rather than starting to follow the phone. */
    override fun migrate(settings: Settings): Settings {
        var s = settings
        if (Common.TIME_FORMAT.name !in s.values) s = s.with(Common.TIME_FORMAT, if (s[H24]) "24" else "12")
        // The seconds switch became a choice: on is digits, as it always was.
        if (SECONDS_MODE.name !in s.values) s = s.with(SECONDS_MODE, if (s[SECONDS]) "digits" else "none")
        return s
    }

    /** Arabic numerals cannot be shown in seven segments or in a font without Arabic; fall back to squared. */
    override fun normalise(settings: Settings): Settings {
        if (!Common.arabicDigits(settings)) return settings
        val style = settings[STYLE]
        val bundled = BundledFonts.find(style)
        val fits = when {
            ClockStyles.isDrawn(style) -> ClockStyles.supportsArabic(style)
            bundled != null -> bundled.arabic
            else -> true
        }
        return if (fits) settings else settings.with(STYLE, "squared")
    }

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val seconds = settings[SECONDS_MODE]
        val time by (if (seconds != "none") feeds.second else feeds.minute).collectAsStateWithLifecycle()
        val arabic = Common.arabicDigits(settings)
        val h24 = Common.use24Hour(settings)
        val now = TimeText.fresh(time)
        val parts = TimeText.parts(now, h24, seconds == "digits", arabic, leadingZero = settings[LEADING_ZERO])
        val colour = Color(settings[Common.COLOUR])
        val marker = if (settings[AMPM] && !h24) TimeText.meridiem(now, settings[Common.ARABIC]) else null
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    ClockFace(settings[STYLE], parts, colour, arabic, Modifier.weight(1f).fillMaxHeight(), alignFraction(settings[Common.ALIGN]))
                    if (marker != null) {
                        Spacer(Modifier.width(pxToDp(h * 0.12f)))
                        Text(marker, color = Muted, fontSize = pxToSp(h * 0.2f), maxLines = 1, softWrap = false)
                    }
                }
                if (seconds == "line") SecondsLine(now.second, colour, h)
            }
        }
    }
}

/** The minute drawn as a thin line under the time, filling a sixtieth more with each second. */
@Composable
private fun SecondsLine(second: Int, colour: Color, h: Float) {
    Spacer(Modifier.height(pxToDp(h * 0.07f)))
    Canvas(Modifier.fillMaxWidth().height(pxToDp((h * 0.035f).coerceAtLeast(2f)))) {
        val y = size.height / 2
        drawLine(Color(0xFF262626), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
        drawLine(colour.copy(alpha = 0.85f), Offset(0f, y), Offset(size.width * (second + 1) / 60f, y), size.height, StrokeCap.Round)
    }
}

/** The time in one style. Shared by the widget and the style strip's preview tiles. */
@Composable
fun ClockFace(style: String, parts: TimeParts, color: Color, arabicDigits: Boolean, modifier: Modifier = Modifier.fillMaxSize(), align: Float = 0.5f) {
    val art = remember(style, parts) { ClockStyles.build(style, parts) }
    if (art != null) {
        ArtCanvas(art, color, modifier, align = align)
    } else {
        val fonts = LocalFonts.current
        val typeface = remember(style) { fonts.typeface(if (FontIds.isFont(style)) style else FontIds.THIN) }
        FitText(
            text = parts.text,
            typeface = typeface,
            color = color,
            modifier = modifier,
            sample = if (arabicDigits) "٠١٢٣٤٥٦٧٨٩:" else "0123456789:",
            stableDigits = !arabicDigits,
            align = align,
        )
    }
}
