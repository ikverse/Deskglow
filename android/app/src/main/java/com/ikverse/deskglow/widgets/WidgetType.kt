package com.ikverse.deskglow.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.ikverse.deskglow.data.Weather
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

    /** A saved widget's settings brought up to date: a setting that was renamed or merged is carried into its new form. Run before the defaults are filled in. */
    fun migrate(settings: Settings): Settings = settings

    /** [saved] migrated, with every setting it lacks at its default. */
    fun resolve(saved: Settings): Settings = migrate(saved).withDefaults(defaults)

    /** Settings after a change, made consistent (e.g. a style that cannot show Arabic numerals is swapped). */
    fun normalise(settings: Settings): Settings = settings

    /** Draws the widget filling whatever box it is given. */
    @Composable
    fun Content(settings: Settings)
}

object Widgets {
    val all: List<WidgetType> = listOf(
        ClockWidget, DateWidget, NotificationsWidget, RingWidget, StatWidget, WeatherWidget, SunMoonWidget, EventWidget, MediaWidget,
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
/** A choice shown as small drawings of the widget itself, one per option ([options] are value to label), so a layout is picked by how it looks. */
data class LayoutField(override val label: String, val key: TextKey, val options: List<Pair<String, String>>) : Field
/** Several on/off settings as one row of chips, each lit when its setting is on. [items] are key to chip text. */
data class ShowField(override val label: String, val items: List<Pair<FlagKey, String>>) : Field
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

    /** "phone" follows the phone's 12 or 24-hour setting. */
    val TIME_FORMAT = TextKey("timeFormat", "phone")

    fun timeFormatField(key: TextKey = TIME_FORMAT) =
        ChoiceField("Time format", key, listOf("phone" to "Phone", "12" to "12-hour", "24" to "24-hour"))

    /** Whether times read on a 24-hour clock under the choice stored at [key]. */
    @Composable
    fun use24Hour(settings: Settings, key: TextKey = TIME_FORMAT): Boolean {
        val phone = DateFormat.is24HourFormat(LocalContext.current)
        return when (settings[key]) { "12" -> false; "24" -> true; else -> phone }
    }

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


/** A one-line hint, shown only in the editor and previews (on the display itself the widget stays empty). */
@Composable
internal fun EditorHint(text: String, size: Float) {
    if (!LocalEditing.current) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = pxToSp(size), maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

internal fun horizontal(align: String) = when (align) { "center" -> Alignment.CenterHorizontally; "right" -> Alignment.End; else -> Alignment.Start }
internal fun textAlign(align: String) = when (align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start }
/** Where text or art sits in spare room: 0 against the left edge, 1 against the right. */
internal fun alignFraction(align: String) = when (align) { "left" -> 0f; "right" -> 1f; else -> 0.5f }
internal fun arrangementOf(align: String) = when (align) { "center" -> Arrangement.Center; "right" -> Arrangement.End; else -> Arrangement.Start }

/** Text sized in pixels, so it fills a widget the same way whatever the phone's font-size setting. */
@Composable
fun pxToSp(px: Float): TextUnit = with(LocalDensity.current) { px.toSp() }

@Composable
fun pxToDp(px: Float): Dp = with(LocalDensity.current) { px.toDp() }
