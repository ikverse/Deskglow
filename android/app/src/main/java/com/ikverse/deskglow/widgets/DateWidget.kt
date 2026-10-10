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
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey

object DateWidget : WidgetType {
    val FORMAT = TextKey("format", "short")
    /** "thin" (the phone's font, light) or a font id. */
    val FONT = TextKey("font", "thin")

    override val id = "date"
    override val label = "Date"
    override val blurb = "Day and date"
    override val width = 164
    override val height = 24
    override val defaults: Settings = Common.base(0xFFD8D8D8).with(Common.ALIGN, "center")

    override fun fields(settings: Settings): List<Field> = buildList {
        add(StyleField("Font", FONT, StyleKind.Date))
        add(ChoiceField("Format", FORMAT, if (settings[Common.ARABIC]) TimeText.DATE_FORMATS_ARABIC else TimeText.DATE_FORMATS))
        addAll(Common.arabicFields(settings))
        add(Common.alignField)
        add(Common.colourField)
        add(Common.brightnessField)
    }

    /** Arabic text needs a font with Arabic letters; a Latin-only one falls back to the phone's font. */
    override fun normalise(settings: Settings): Settings {
        if (!settings[Common.ARABIC]) return settings
        val bundled = BundledFonts.find(settings[FONT]) ?: return settings
        return if (bundled.arabic) settings else settings.with(FONT, "thin")
    }

    @Composable
    override fun Content(settings: Settings) {
        val minute by LocalFeeds.current.minute.collectAsStateWithLifecycle()
        DateFace(settings, settings[FONT], TimeText.fresh(minute).toLocalDate(), Modifier.fillMaxSize())
    }
}

/** The date in one font. Shared by the widget and the font strip's preview tiles. */
@Composable
fun DateFace(settings: Settings, font: String, date: java.time.LocalDate, modifier: Modifier) {
    val arabic = settings[Common.ARABIC]
    val text = TimeText.date(date, settings[DateWidget.FORMAT], arabic, Common.arabicDigits(settings))
    val fonts = LocalFonts.current
    val typeface = remember(font) { fonts.typeface(font) }
    FitText(
        text = text,
        typeface = typeface,
        color = Color(settings[Common.COLOUR]),
        modifier = modifier,
        // A fixed sample keeps the height steady from day to day, whatever letters the date has.
        sample = if (arabic) "الأربعاء أكتوبر" else "Wdgjy0,",
        align = alignFraction(settings[Common.ALIGN]),
    )
}
