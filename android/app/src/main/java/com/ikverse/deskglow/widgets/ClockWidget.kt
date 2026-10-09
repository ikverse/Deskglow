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
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings): List<Field> = buildList {
        add(StyleField("Style", STYLE, StyleKind.Clock))
        add(ToggleField("24-hour time", H24))
        add(ToggleField("Show seconds", SECONDS))
        addAll(Common.arabicFields(settings))
        add(Common.colourField)
        add(Common.brightnessField)
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
        val time by (if (settings[SECONDS]) feeds.second else feeds.minute).collectAsStateWithLifecycle()
        val arabic = Common.arabicDigits(settings)
        val parts = TimeText.parts(TimeText.fresh(time), settings[H24], settings[SECONDS], arabic)
        ClockFace(settings[STYLE], parts, Color(settings[Common.COLOUR]), arabic)
    }
}

/** The time in one style. Shared by the widget and the style strip's preview tiles. */
@Composable
fun ClockFace(style: String, parts: TimeParts, color: Color, arabicDigits: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val art = remember(style, parts) { ClockStyles.build(style, parts) }
    if (art != null) {
        ArtCanvas(art, color, modifier)
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
        )
    }
}
