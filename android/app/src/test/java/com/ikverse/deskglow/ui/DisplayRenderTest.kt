package com.ikverse.deskglow.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.data.NotificationState
import com.ikverse.deskglow.data.Weather
import com.ikverse.deskglow.data.WeatherState
import com.ikverse.deskglow.display.DisplayContent
import com.ikverse.deskglow.fonts.BundledFonts
import com.ikverse.deskglow.fonts.FontResolver
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.store.City
import com.ikverse.deskglow.widgets.ClockStyles
import com.ikverse.deskglow.widgets.ClockWidget
import com.ikverse.deskglow.widgets.Common
import com.ikverse.deskglow.widgets.DateWidget
import com.ikverse.deskglow.widgets.DefaultLayout
import com.ikverse.deskglow.widgets.LocalEditing
import com.ikverse.deskglow.widgets.WeatherWidget
import com.ikverse.deskglow.widgets.Widgets
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.data.AlarmState
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.Prayer
import com.ikverse.deskglow.data.PrayerDay
import com.ikverse.deskglow.data.PrayerState
import com.ikverse.deskglow.data.Sky
import com.ikverse.deskglow.widgets.AlarmWidget
import com.ikverse.deskglow.widgets.Bell
import com.ikverse.deskglow.widgets.DetailGlyph
import com.ikverse.deskglow.widgets.F1StandingsWidget
import com.ikverse.deskglow.widgets.F1WeekendWidget
import com.ikverse.deskglow.widgets.Glyph
import com.ikverse.deskglow.widgets.PrayerWidget
import com.ikverse.deskglow.widgets.WeatherIcon
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Draws the display in every state its data can be in, and every clock style and font in both
 * scripts, so a style or state that crashes is caught here rather than on the phone at 3 a.m. With
 * native graphics the frames are also saved under build/screens for a look by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h848dp-xxhdpi")
class DisplayRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val feeds = FakeFeeds()
    private val cairo = City("Cairo", "Cairo Governorate, Egypt", 30.06, 31.25)

    private fun show(layout: Layout, editing: Boolean = false, orientation: Orientation = Orientation.Portrait) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.setContent {
            CompositionLocalProvider(LocalFeeds provides feeds, LocalFonts provides FontResolver(context, null), LocalEditing provides editing) {
                DeskglowTheme { DisplayContent(layout, burnIn = true, orientation = orientation) }
            }
        }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        val dir = File("build/screens").apply { mkdirs() }
        runCatching {
            // Drawn straight from the window: Robolectric's native graphics produce real pixels this way.
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { System.err.println("Screen capture unavailable here: $it") }
    }

    @Test
    fun `the default layout with the Note 9's real readings`() {
        feeds.media.value = MediaState.Idle
        show(DefaultLayout.create())
        save("default")
    }

    @Test
    fun `every widget with live-looking data`() {
        feeds.weather.value = WeatherState.Ready(cairo, Weather(25.0, 2, false, 30.1, 18.0, 0))
        feeds.nextEvent.value = EventState.Next("Team call", LocalDateTime.of(2026, 10, 7, 20, 30), LocalDateTime.of(2026, 10, 7, 21, 0), false)
        feeds.media.value = MediaState.Track("Clair de Lune", "Debussy", 300_000, 120_000, 0, true, 1f)
        feeds.notifications.value = NotificationState.Apps(listOf(com.ikverse.deskglow.data.NotifiedApp("a", null), com.ikverse.deskglow.data.NotifiedApp("b", null)))
        show(DefaultLayout.create())
        save("live")
    }

    @Test
    fun `every widget with nothing to show, in the editor where hints appear`() {
        feeds.notifications.value = NotificationState.NoAccess
        feeds.media.value = MediaState.NoAccess
        feeds.nextEvent.value = EventState.NoAccess
        feeds.weather.value = WeatherState.Failed(cairo, null)
        show(DefaultLayout.create(), editing = true)
        save("empty-editing")
    }

    @Test
    @Config(qualifiers = "w848dp-h412dp-land-xxhdpi")
    fun `the landscape default layout draws on its own canvas`() {
        feeds.media.value = MediaState.Idle
        show(DefaultLayout.create(Orientation.Landscape), orientation = Orientation.Landscape)
        save("default-landscape")
    }

    @Test
    @Config(qualifiers = "w848dp-h412dp-land-xxhdpi")
    fun `every widget with live-looking data, on its side`() {
        feeds.weather.value = WeatherState.Ready(cairo, Weather(25.0, 2, false, 30.1, 18.0, 0))
        feeds.nextEvent.value = EventState.Next("Team call", LocalDateTime.of(2026, 10, 7, 20, 30), LocalDateTime.of(2026, 10, 7, 21, 0), false)
        feeds.media.value = MediaState.Track("Clair de Lune", "Debussy", 300_000, 120_000, 0, true, 1f)
        feeds.notifications.value = NotificationState.Apps(listOf(com.ikverse.deskglow.data.NotifiedApp("a", null), com.ikverse.deskglow.data.NotifiedApp("b", null)))
        show(DefaultLayout.create(Orientation.Landscape), orientation = Orientation.Landscape)
        save("live-landscape")
    }

    private fun clocks(arabic: Boolean) {
        val styles = ClockStyles.DRAWN.map { it.first } + listOf("thin", "bold") + BundledFonts.all.map { it.id }
        val items = styles.mapIndexed { i, style ->
            val settings = ClockWidget.defaults.with(ClockWidget.STYLE, style).with(Common.ARABIC, arabic).with(ClockWidget.SECONDS, i % 3 == 0)
            WidgetItem("c$i", ClockWidget.id, Box((i % 3) * 136 + 4, (i / 3) * 104 + 8, 132, if (style == "stacked") 96 else 56), true, settings)
        }
        show(Layout(items))
        save(if (arabic) "clocks-arabic" else "clocks")
    }

    @Test
    fun `every clock style and font, in Western numerals`() = clocks(arabic = false)

    @Test
    fun `every clock style and font, in Arabic numerals`() = clocks(arabic = true)

    private fun dates(arabic: Boolean) {
        val fonts = listOf("thin") + BundledFonts.all.map { it.id }
        val items = fonts.mapIndexed { i, font ->
            val settings = DateWidget.defaults.with(DateWidget.FONT, font).with(Common.ARABIC, arabic)
                .with(DateWidget.FORMAT, listOf("short", "long", "numeric")[i % 3])
            WidgetItem("d$i", DateWidget.id, Box(8, i * 52 + 8, 396, 36), true, settings)
        }
        show(Layout(items))
        save(if (arabic) "dates-arabic" else "dates")
    }

    @Test
    fun `every date font and format, in English`() = dates(arabic = false)

    @Test
    fun `every date font and format, in Arabic`() = dates(arabic = true)

    private fun weatherLayouts(outline: Boolean) {
        feeds.weather.value = WeatherState.Ready(cairo, WeatherWidget.SAMPLE)
        // Each layout twice: at the size a new widget gets, and wide.
        val items = WeatherWidget.LAYOUTS.flatMapIndexed { i, (layout, _) ->
            val settings = WeatherWidget.defaults.with(WeatherWidget.LAYOUT, layout)
                .with(WeatherWidget.ICON_STYLE, if (outline) "outline" else "filled")
                .with(WeatherWidget.SHOW_FEELS, true).with(WeatherWidget.SHOW_HUMIDITY, i % 2 == 0)
                .with(WeatherWidget.SHOW_WIND, true).with(WeatherWidget.SHOW_RAIN, i % 2 == 1)
                .with(Common.ALIGN, listOf("left", "center", "right", "left")[i])
            listOf(
                WidgetItem("w$i", WeatherWidget.id, Box(8, i * 200 + 8, 396, 112), true, settings),
                WidgetItem("s$i", WeatherWidget.id, Box(8, i * 200 + 128, 176, 64), true, settings),
            )
        }
        show(Layout(items))
        save(if (outline) "weather-outline" else "weather-filled")
    }

    @Test
    fun `every weather layout with filled icons`() = weatherLayouts(outline = false)

    @Test
    fun `every weather layout with outline icons`() = weatherLayouts(outline = true)

    @Test
    fun `every sky draws as a filled and as an outline icon, day and night`() {
        compose.setContent {
            Column(Modifier.background(Color.Black)) {
                for (outline in listOf(false, true)) for (day in listOf(true, false)) {
                    Row {
                        Sky.entries.forEach { WeatherIcon(it, day, Color(0xFFF5B942), Modifier.size(48.dp), outline, Color.White) }
                    }
                }
                Row {
                    Glyph.entries.forEach { DetailGlyph(it, Color.White, Modifier.size(32.dp)) }
                    Bell(Color(0xFFF5B942), Modifier.size(32.dp))
                }
            }
        }
        compose.waitForIdle()
        save("weather-icons")
    }

    private val tonight = listOf(
        PrayerDay(LocalDate.of(2026, 10, 7), mapOf(Prayer.Fajr to LocalTime.of(4, 26), Prayer.Dhuhr to LocalTime.of(11, 41), Prayer.Asr to LocalTime.of(15, 1), Prayer.Maghrib to LocalTime.of(17, 32), Prayer.Isha to LocalTime.of(20, 50))),
        PrayerDay(LocalDate.of(2026, 10, 8), mapOf(Prayer.Fajr to LocalTime.of(4, 27), Prayer.Dhuhr to LocalTime.of(11, 40), Prayer.Asr to LocalTime.of(15, 0), Prayer.Maghrib to LocalTime.of(17, 31), Prayer.Isha to LocalTime.of(18, 49))),
    )

    private fun newWidgets(): Layout = Layout(listOf(
        WidgetItem("p1", PrayerWidget.id, Box(8, 8, 396, 104), true, PrayerWidget.defaults),
        WidgetItem("p2", PrayerWidget.id, Box(8, 120, 396, 104), true, PrayerWidget.defaults.with(Common.ARABIC, true).with(Common.ALIGN, "right")),
        WidgetItem("a1", AlarmWidget.id, Box(8, 232, 260, 72), true, AlarmWidget.defaults),
        WidgetItem("f1", F1WeekendWidget.id, Box(8, 312, 396, 120), true, F1WeekendWidget.defaults.with(F1WeekendWidget.FAVOURITE, "VER")),
        WidgetItem("s1", F1StandingsWidget.id, Box(8, 440, 196, 240), true, F1StandingsWidget.defaults.with(F1StandingsWidget.FAV_DRIVER, "HAM").with(F1StandingsWidget.ROWS, 4)),
        WidgetItem("s2", F1StandingsWidget.id, Box(212, 440, 196, 240), true,
            F1StandingsWidget.defaults.with(F1StandingsWidget.TABLE, "constructors").with(F1StandingsWidget.VALUE, "gap").with(F1StandingsWidget.FAV_TEAM, "ferrari")),
    ))

    @Test
    fun `prayer times, the next alarm and F1 with live-looking data`() {
        feeds.prayers.value = PrayerState.Ready(cairo, tonight)
        feeds.alarm.value = AlarmState(LocalDateTime.of(2026, 10, 8, 6, 30))
        feeds.f1.value = F1State.Ready(F1Samples.data)
        show(newWidgets())
        save("new-widgets")
    }

    @Test
    fun `the F1 weekend counting down, and live`() {
        fun at(utc: LocalDateTime) = LocalDateTime.ofInstant(utc.toInstant(ZoneOffset.UTC), ZoneId.systemDefault())
        feeds.f1.value = F1State.Ready(F1Samples.data)
        val counting = at(LocalDateTime.of(2026, 10, 9, 5, 20, 15))
        feeds.minute.value = counting
        feeds.second.value = counting
        show(Layout(listOf(WidgetItem("f1", F1WeekendWidget.id, Box(8, 8, 396, 120), true, F1WeekendWidget.defaults))))
        save("f1-countdown")
        val live = at(LocalDateTime.of(2026, 10, 10, 6, 30))
        feeds.minute.value = live
        feeds.second.value = live
        compose.waitForIdle()
        save("f1-live")
    }

    @Test
    fun `the F1 track beside a wide weekend and below a tall one, and the standings in two columns`() {
        fun at(utc: LocalDateTime) = LocalDateTime.ofInstant(utc.toInstant(ZoneOffset.UTC), ZoneId.systemDefault())
        feeds.f1.value = F1State.Ready(F1Samples.data.copy(track = F1Samples.marinaBay))
        // Seven hours before Japan's first practice.
        val now = at(LocalDateTime.of(2026, 10, 8, 19, 30))
        feeds.minute.value = now
        feeds.second.value = now
        val withTrack = F1WeekendWidget.defaults.with(F1WeekendWidget.SHOW_TRACK, true)
        show(
            Layout(listOf(
                WidgetItem("wide", F1WeekendWidget.id, Box(8, 8, 396, 120), true, withTrack),
                WidgetItem("tall", F1WeekendWidget.id, Box(8, 144, 260, 340), true, withTrack),
                WidgetItem("table", F1StandingsWidget.id, Box(8, 500, 396, 180), true,
                    F1StandingsWidget.defaults.with(F1StandingsWidget.ROWS, 10).with(F1StandingsWidget.FAV_DRIVER, "HAM")),
            )),
        )
        save("f1-track-and-two-columns")
    }

    @Test
    fun `the new widgets with nothing to show, in the editor where hints appear`() {
        show(newWidgets(), editing = true)
        save("new-widgets-empty")
    }

    @Test
    fun `a widget from a newer version is skipped, not a crash`() {
        show(Layout(DefaultLayout.create().items + WidgetItem("w99", "crypto", Box(0, 0, 100, 40))))
        assert(Widgets.find("crypto") == null)
    }
}
