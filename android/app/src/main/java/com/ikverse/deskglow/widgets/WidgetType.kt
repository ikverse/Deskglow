package com.ikverse.deskglow.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.IntKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey

/**
 * One kind of widget. Each lives in its own file and holds everything about itself: its look, its
 * settings and the data it reads. Adding a widget means writing one of these and listing it in
 * [Widgets.all]; nothing else in the app needs to change.
 */
interface WidgetType {
    /** Stored in saved layouts, so never renamed. */
    val id: String
    val label: String
    /** One line for the add-widget picker. */
    val blurb: String
    /** Its size when first added, in canvas units. */
    val width: Int
    val height: Int
    val defaults: Settings

    /** The controls in its settings sheet. May depend on the current settings (Arabic adds a numerals choice). */
    fun fields(settings: Settings): List<Field>

    /** Its name in the widget list, e.g. "Stat · Temperature". */
    fun title(settings: Settings): String = label

    /** A line under its settings, e.g. where its data comes from. */
    fun note(settings: Settings): String? = null

    /** Settings after a change, made consistent (e.g. a style that cannot show Arabic numerals is swapped). */
    fun normalise(settings: Settings): Settings = settings

    /** Draws the widget filling whatever box it is given. */
    @Composable
    fun Content(settings: Settings)
}

object Widgets {
    val all: List<WidgetType> = listOf(
        ClockWidget, DateWidget, NotificationsWidget, RingWidget, StatWidget, WeatherWidget, EventWidget, MediaWidget,
        PrayerWidget, AlarmWidget, F1WeekendWidget, F1ScheduleWidget, F1LiveWidget, F1StandingsWidget,
    )

    fun find(id: String): WidgetType? = all.firstOrNull { it.id == id }
}

/** A control in a widget's settings sheet. */
sealed interface Field {
    val label: String
}

data class ToggleField(override val label: String, val key: FlagKey) : Field
data class ChoiceField(override val label: String, val key: TextKey, val options: List<Pair<String, String>>) : Field
data class SliderField(override val label: String, val key: IntKey, val range: IntRange, val suffix: String = "") : Field
data class ColourField(override val label: String, val key: ColourKey) : Field
/** The sideways strip of preview tiles (clock styles, date fonts), ending in "More fonts". */
data class StyleField(override val label: String, val key: TextKey, val kind: StyleKind) : Field

enum class StyleKind { Clock, Date, Weather }

/** Settings every widget has. */
object Common {
    val COLOUR = ColourKey("color", 0xFFFFFFFF.toInt())
    val OPACITY = IntKey("opacity", 100)
    val ALIGN = TextKey("align", "left")
    val ARABIC = FlagKey("arabic", false)
    /** "arabic" for ٠١٢٣, "western" for 0123. Only used while [ARABIC] is on. */
    val NUMERALS = TextKey("numerals", "arabic")

    val colourField = ColourField("Colour", COLOUR)
    val brightnessField = SliderField("Brightness", OPACITY, 20..100, "%")
    val alignField = ChoiceField("Alignment", ALIGN, listOf("left" to "Left", "center" to "Centre", "right" to "Right"))

    fun base(colour: Long = 0xFFFFFFFF) = Settings().with(COLOUR, colour.toInt()).with(OPACITY, 100)

    fun arabicDigits(settings: Settings) = settings[ARABIC] && settings[NUMERALS] == "arabic"

    fun arabicFields(settings: Settings): List<Field> = buildList {
        add(ToggleField("عربي / Arabic", ARABIC))
        if (settings[ARABIC]) add(ChoiceField("Numbers", NUMERALS, listOf("arabic" to "٠١٢٣  Arabic", "western" to "0123  Western")))
    }
}

/** True in the editor and in previews, where a widget may show a hint ("Allow calendar access") instead of nothing. */
val LocalEditing = staticCompositionLocalOf { false }

val Muted = Color(0xFF8C8C8C)
