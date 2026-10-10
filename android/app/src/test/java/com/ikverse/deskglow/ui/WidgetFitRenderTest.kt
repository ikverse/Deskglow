package com.ikverse.deskglow.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.data.AlarmState
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.data.Prayer
import com.ikverse.deskglow.data.PrayerDay
import com.ikverse.deskglow.data.PrayerState
import com.ikverse.deskglow.data.Weather
import com.ikverse.deskglow.data.WeatherState
import com.ikverse.deskglow.display.DisplayContent
import com.ikverse.deskglow.fonts.FontResolver
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.store.City
import com.ikverse.deskglow.widgets.AlarmWidget
import com.ikverse.deskglow.widgets.EventWidget
import com.ikverse.deskglow.widgets.F1LiveSample
import com.ikverse.deskglow.widgets.F1LiveWidget
import com.ikverse.deskglow.widgets.F1ScheduleWidget
import com.ikverse.deskglow.widgets.F1StandingsWidget
import com.ikverse.deskglow.widgets.F1WeekendWidget
import com.ikverse.deskglow.widgets.MediaWidget
import com.ikverse.deskglow.widgets.PrayerWidget
import com.ikverse.deskglow.widgets.StatWidget
import com.ikverse.deskglow.widgets.SunMoonWidget
import com.ikverse.deskglow.widgets.WeatherWidget
import com.ikverse.deskglow.widgets.WidgetType
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every widget that sizes itself to its box, drawn alone in a wide box, a tall one and a thin strip:
 * nothing it draws may reach outside the box, and in the wide and tall boxes what it draws must reach most
 * of the way across the box on whichever side runs out first. Frames are saved under build/screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h848dp-xxhdpi")
class WidgetFitRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val feeds = FakeFeeds()
    private val favourite = kotlinx.coroutines.flow.MutableStateFlow(com.ikverse.deskglow.data.F1Favourite())
    private val cairo = City("Cairo", "Cairo Governorate, Egypt", 30.06, 31.25)

    private val shapes = mapOf(
        "wide" to Box(6, 100, 400, 110),
        "tall" to Box(6, 100, 170, 330),
        "strip" to Box(6, 100, 400, 40),
    )

    @Before
    fun data() {
        val now = LocalDateTime.of(2026, 10, 7, 20, 5)
        feeds.minute.value = now
        feeds.second.value = now
        feeds.weather.value = WeatherState.Ready(
            cairo,
            Weather(
                25.0, 2, false, 30.1, 18.0, 0, feelsLikeC = 26.0, humidityPercent = 48, windKmh = 14.0, rainChancePercent = 10,
                sunrise = LocalDateTime.of(2026, 10, 7, 5, 54), sunset = LocalDateTime.of(2026, 10, 7, 17, 31),
            ),
        )
        feeds.alarm.value = AlarmState(LocalDateTime.of(2026, 10, 8, 6, 30))
        feeds.media.value = MediaState.Track("Clair de Lune", "Debussy", 300_000, 120_000, 0, true, 1f)
        val later = EventState.Next("Dentist", LocalDateTime.of(2026, 10, 8, 9, 0), LocalDateTime.of(2026, 10, 8, 10, 0), false)
        feeds.nextEvent.value = EventState.Next("Team call", LocalDateTime.of(2026, 10, 7, 20, 30), LocalDateTime.of(2026, 10, 7, 21, 0), false, later = listOf(later))
        feeds.prayers.value = PrayerState.Ready(
            cairo,
            listOf(
                PrayerDay(LocalDate.of(2026, 10, 7), mapOf(Prayer.Fajr to LocalTime.of(4, 26), Prayer.Dhuhr to LocalTime.of(11, 40), Prayer.Asr to LocalTime.of(15, 1), Prayer.Maghrib to LocalTime.of(17, 32), Prayer.Isha to LocalTime.of(18, 50))),
                PrayerDay(LocalDate.of(2026, 10, 8), mapOf(Prayer.Fajr to LocalTime.of(4, 27), Prayer.Dhuhr to LocalTime.of(11, 40), Prayer.Asr to LocalTime.of(15, 0), Prayer.Maghrib to LocalTime.of(17, 31), Prayer.Isha to LocalTime.of(18, 49))),
            ),
        )
        feeds.f1.value = F1State.Ready(F1Samples.data)
    }

    private val shown = mutableStateOf<WidgetItem?>(null)

    /** Draws [item] alone; the content is set once per test and the widget swapped in it after that. */
    private fun show(item: WidgetItem) {
        val first = shown.value == null
        shown.value = item
        if (first) {
            val context = ApplicationProvider.getApplicationContext<android.app.Application>()
            compose.setContent {
                CompositionLocalProvider(LocalFeeds provides feeds, LocalFonts provides FontResolver(context, null), com.ikverse.deskglow.data.LocalF1Favourite provides favourite) {
                    DeskglowTheme { DisplayContent(Layout(listOfNotNull(shown.value)), burnIn = false, orientation = Orientation.Portrait) }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The window as drawn, saved under build/screens/fit as [name]. */
    private fun capture(name: String): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screens/fit").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** [box] in pixels, as the display draws it: the 412 by 848 canvas scaled to fit the window and centred. */
    private fun pixels(box: Box): Rect {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val unit = min(root.width / 412f, root.height / 848f)
        val left = root.left + (root.width - 412f * unit) / 2
        val top = root.top + (root.height - 848f * unit) / 2
        return Rect(left + box.x * unit, top + box.y * unit, left + box.right * unit, top + box.bottom * unit)
    }

    /** Where anything at all is drawn: every pixel that is not (near) black. Null on a blank screen. */
    private fun drawn(bitmap: Bitmap): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 60) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    bottom = y
                }
            }
        }
        return if (right < 0) null else Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    private fun check(name: String, type: WidgetType, settings: Settings, shapesToCheck: Collection<String> = shapes.keys) {
        for (shape in shapesToCheck) {
            val box = shapes.getValue(shape)
            show(WidgetItem("w", type.id, box, true, settings))
            val area = pixels(box)
            val ink = drawn(capture("$name-$shape")) ?: error("$name in the $shape box drew nothing")
            // Nothing spills out: everything drawn is inside the box (a pixel or two for rounding).
            val slack = 2f
            if (ink.left < area.left - slack || ink.top < area.top - slack || ink.right > area.right + slack || ink.bottom > area.bottom + slack) {
                problems += "$name in the $shape box draws at $ink, outside the box $area"
            }
            val fill = max(ink.width / area.width, ink.height / area.height)
            println("FIT $name $shape fill=${"%.2f".format(fill)}")
            // Ink, not layout: a line of text inks only from the top of its capitals to the foot of its descenders,
            // so a few lines filling a box's height measure about four fifths of it.
            if (shape != "strip" && fill < 0.75f) problems += "$name in the $shape box fills only ${"%.2f".format(fill)} of it"
        }
    }

    private val problems = mutableListOf<String>()

    private fun noProblems() = assertTrue(problems.joinToString("\n"), problems.isEmpty())

    @Test
    fun `the clock-side widgets fill their boxes`() {
        check("alarm", AlarmWidget, AlarmWidget.defaults)
        check("stat", StatWidget, StatWidget.defaults)
        check("media", MediaWidget, MediaWidget.defaults)
        check("event", EventWidget, EventWidget.defaults)
        check("agenda", EventWidget, EventWidget.defaults.with(EventWidget.COUNT, 3), listOf("wide", "tall"))
        noProblems()
    }

    @Test
    fun `the weather, sun and prayer widgets fill their boxes`() {
        for (layout in listOf("side", "stacked", "compact", "big", "hours")) {
            check("weather-$layout", WeatherWidget, WeatherWidget.defaults.with(WeatherWidget.LAYOUT, layout))
        }
        check("sunmoon", SunMoonWidget, SunMoonWidget.defaults)
        for (view in listOf("next", "row", "arc")) {
            check("prayer-$view", PrayerWidget, PrayerWidget.defaults.with(PrayerWidget.VIEW, view), if (view == "next") shapes.keys else listOf("wide", "tall"))
        }
        noProblems()
    }

    @Test
    fun `the F1 calendar widgets fill their boxes`() {
        for (layout in listOf("classic", "hero", "countdown", "minimal")) {
            check("weekend-$layout", F1WeekendWidget, F1WeekendWidget.defaults.with(F1WeekendWidget.LAYOUT, layout), if (layout == "minimal") shapes.keys else listOf("wide", "tall"))
        }
        for (layout in listOf("list", "days", "timeline", "strip")) {
            check("schedule-$layout", F1ScheduleWidget, F1ScheduleWidget.defaults.with(F1ScheduleWidget.LAYOUT, layout), listOf("wide", "tall"))
        }
        check("standings", F1StandingsWidget, F1StandingsWidget.defaults, listOf("wide", "tall"))
        noProblems()
    }

    @Test
    fun `the F1 live layouts fill their boxes`() {
        val at = LocalDateTime.ofInstant(F1LiveSample.AT, ZoneId.systemDefault())
        feeds.minute.value = at
        feeds.second.value = at
        feeds.f1Live.value = F1LiveState.Live(F1LiveSample.race)
        for (layout in listOf("tower", "glance", "focus", "line")) {
            val settings = F1LiveWidget.defaults.with(F1LiveWidget.LAYOUT, layout).with(F1LiveWidget.FAVOURITE, "NOR")
            check("live-$layout", F1LiveWidget, settings, if (layout == "line") shapes.keys else listOf("wide", "tall"))
        }
        noProblems()
    }
}
