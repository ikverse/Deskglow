package com.ikverse.deskglow.widgets

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val SECONDS = FlagKey("seconds", false)

    override val id = "clock"
    override val label = "Clock"
    override val blurb = "Big time in any font"
    override val width = 220
    override val height = 64
    override val defaults: Settings = Common.base().with(Common.ALIGN, "center")

    override fun fields(settings: Settings): List<Field> = buildList {
        add(StyleField("Style", STYLE, StyleKind.Clock))
        add(Common.timeFormatField())
        add(ToggleField("Show seconds", SECONDS))
        addAll(Common.arabicFields(settings))
        add(Common.alignField)
        add(Common.colourField)
        add(Common.brightnessField)
    }

    /** The 24-hour switch became the time format: a clock set to 24-hour stays so, and every other keeps its 12-hour time rather than starting to follow the phone. */
    override fun migrate(settings: Settings): Settings =
        if (Common.TIME_FORMAT.name in settings.values) settings else settings.with(Common.TIME_FORMAT, if (settings[H24]) "24" else "12")

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
        val time by (if (settings[SECONDS]) feeds.second else feeds.minute).collectAsStateWithLifecycle()
        val arabic = Common.arabicDigits(settings)
        val parts = TimeText.parts(TimeText.fresh(time), Common.use24Hour(settings), settings[SECONDS], arabic)
        ClockFace(settings[STYLE], parts, Color(settings[Common.COLOUR]), arabic, align = alignFraction(settings[Common.ALIGN]))
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
