package com.ikverse.deskglow.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.data.AlarmState
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.LiveSession
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.data.NotificationState
import com.ikverse.deskglow.data.Prayer
import com.ikverse.deskglow.data.PrayerDay
import com.ikverse.deskglow.data.PrayerState
import com.ikverse.deskglow.data.Sky
import com.ikverse.deskglow.data.TrackFlag
import com.ikverse.deskglow.data.Weather
import com.ikverse.deskglow.data.WeatherState
import com.ikverse.deskglow.data.parseLiveSession
import com.ikverse.deskglow.display.DisplayContent
import com.ikverse.deskglow.fonts.BundledFonts
import com.ikverse.deskglow.fonts.FontResolver
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.store.City
import com.ikverse.deskglow.widgets.AlarmWidget
import com.ikverse.deskglow.widgets.Bell
import com.ikverse.deskglow.widgets.ClockStyles
import com.ikverse.deskglow.widgets.ClockWidget
import com.ikverse.deskglow.widgets.Common
import com.ikverse.deskglow.widgets.DateWidget
import com.ikverse.deskglow.widgets.DefaultLayout
import com.ikverse.deskglow.widgets.DetailGlyph
import com.ikverse.deskglow.widgets.F1LiveWidget
import com.ikverse.deskglow.widgets.F1ScheduleWidget
import com.ikverse.deskglow.widgets.F1StandingsWidget
import com.ikverse.deskglow.widgets.F1WeekendWidget
import com.ikverse.deskglow.widgets.Glyph
import com.ikverse.deskglow.widgets.LocalEditing
import com.ikverse.deskglow.widgets.PrayerWidget
import com.ikverse.deskglow.widgets.WeatherIcon
import com.ikverse.deskglow.widgets.WeatherWidget
import com.ikverse.deskglow.widgets.Widgets
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
    private val favourite = kotlinx.coroutines.flow.MutableStateFlow(com.ikverse.deskglow.data.F1Favourite())
    private val cairo = City("Cairo", "Cairo Governorate, Egypt", 30.06, 31.25)

    private fun show(layout: Layout, editing: Boolean = false, orientation: Orientation = Orientation.Portrait) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.setContent {
            CompositionLocalProvider(LocalFeeds provides feeds, LocalFonts provides FontResolver(context, null), LocalEditing provides editing, com.ikverse.deskglow.data.LocalF1Favourite provides favourite) {
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
        val items = WeatherWidget.LAYOUTS.filter { it.first != "hours" }.flatMapIndexed { i, (layout, _) ->
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

    private fun atUtc(utc: LocalDateTime) = LocalDateTime.ofInstant(utc.toInstant(ZoneOffset.UTC), ZoneId.systemDefault())

    private fun at(utc: LocalDateTime) {
        val local = atUtc(utc)
        feeds.minute.value = local
        feeds.second.value = local
    }

    private fun weekend(layout: String, track: Boolean = true, between: String = "countdown") =
        F1WeekendWidget.defaults.with(F1WeekendWidget.LAYOUT, layout).with(F1WeekendWidget.SHOW_TRACK, track).with(F1WeekendWidget.FAVOURITE, "VER")
            .with(F1WeekendWidget.BETWEEN, between)

    private val weekendLayouts = listOf("classic", "hero", "countdown", "watermark", "minimal")

    private fun weekendStack(height: Int = 120, between: String = "countdown"): Layout =
        Layout(weekendLayouts.mapIndexed { i, layout -> WidgetItem("w$i", F1WeekendWidget.id, Box(8, 8 + i * (height + 8), 396, height), true, weekend(layout, between = between)) })

    /** A finished Japanese session as the timing feed saves it, Piastri, Verstappen and Russell on top. */
    private fun japanResult(name: String, type: String, startUtc: LocalDateTime) = LiveSession(
        1, "Japanese GP", name, type, startUtc.toInstant(ZoneOffset.UTC), "Finalised", true,
        listOf("PIA" to 0xFFFF8000, "VER" to 0xFF4781D7, "RUS" to 0xFF00D7B6, "NOR" to 0xFFFF8000).mapIndexed { i, (code, colour) ->
            com.ikverse.deskglow.data.LiveRow(i + 1, "${i + 1}", code, colour, "", "")
        },
    )

    @Test
    fun `every race weekend layout with the last session's top 3, then a race's before its podium is in`() {
        feeds.f1.value = F1State.Ready(F1Samples.data.copy(track = F1Samples.marinaBay))
        // Saturday afternoon in Japan: qualifying is done and the race is tomorrow.
        feeds.f1Live.value = F1LiveState.Result(japanResult("Qualifying", "Qualifying", LocalDateTime.of(2026, 10, 10, 6, 0)))
        at(LocalDateTime.of(2026, 10, 10, 12, 0))
        show(weekendStack(between = "top3"))
        save("f1-weekend-layouts-session-top3")
        // Sunday after the race, with the podium source still on Singapore's result.
        feeds.f1Live.value = F1LiveState.Result(japanResult("Race", "Race", LocalDateTime.of(2026, 10, 11, 5, 0)))
        at(LocalDateTime.of(2026, 10, 11, 8, 0))
        compose.waitForIdle()
        save("f1-weekend-layouts-race-top3")
    }

    @Test
    fun `every race weekend layout counting down`() {
        feeds.f1.value = F1State.Ready(F1Samples.data.copy(track = F1Samples.marinaBay))
        at(LocalDateTime.of(2026, 10, 8, 19, 30))
        show(weekendStack())
        save("f1-weekend-layouts-countdown")
    }

    @Test
    fun `every race weekend layout in the last hour, then during a session`() {
        feeds.f1.value = F1State.Ready(F1Samples.data.copy(track = F1Samples.marinaBay))
        at(LocalDateTime.of(2026, 10, 9, 5, 20, 15))
        show(weekendStack())
        save("f1-weekend-layouts-last-hour")
        at(LocalDateTime.of(2026, 10, 10, 6, 30))
        compose.waitForIdle()
        save("f1-weekend-layouts-live")
    }

    @Test
    fun `every race weekend layout showing the last podium`() {
        feeds.f1.value = F1State.Ready(F1Samples.data.copy(track = F1Samples.marinaBay))
        at(LocalDateTime.of(2026, 10, 6, 12, 0))
        show(weekendStack())
        save("f1-weekend-layouts-podium")
    }

    @Test
    fun `every race weekend layout in a tall box and without the track`() {
        feeds.f1.value = F1State.Ready(F1Samples.data.copy(track = F1Samples.marinaBay))
        at(LocalDateTime.of(2026, 10, 8, 19, 30))
        show(Layout(listOf(
            WidgetItem("hero", F1WeekendWidget.id, Box(8, 8, 196, 240), true, weekend("hero")),
            WidgetItem("count", F1WeekendWidget.id, Box(212, 8, 196, 240), true, weekend("countdown")),
            WidgetItem("mark", F1WeekendWidget.id, Box(8, 256, 196, 240), true, weekend("watermark")),
            WidgetItem("class", F1WeekendWidget.id, Box(212, 256, 196, 240), true, weekend("classic")),
            WidgetItem("bare", F1WeekendWidget.id, Box(8, 504, 396, 120), true, weekend("hero", track = false)),
            WidgetItem("none", F1WeekendWidget.id, Box(8, 632, 396, 120), true, weekend("countdown", track = false)),
        )))
        save("f1-weekend-layouts-tall")
    }

    private fun schedule(layout: String, vararg changes: Pair<FlagKey, Boolean>) =
        changes.fold(F1ScheduleWidget.defaults.with(F1ScheduleWidget.LAYOUT, layout)) { s, (key, value) -> s.with(key, value) }

    @Test
    fun `every schedule layout on a sprint weekend, with the sprint running`() {
        feeds.f1.value = F1State.Ready(F1Samples.data)
        at(LocalDateTime.of(2026, 10, 24, 18, 30))
        show(Layout(listOf(
            WidgetItem("list", F1ScheduleWidget.id, Box(8, 8, 196, 232), true, schedule("list")),
            WidgetItem("strip", F1ScheduleWidget.id, Box(212, 8, 196, 232), true, schedule("strip")),
            WidgetItem("days", F1ScheduleWidget.id, Box(8, 248, 396, 180), true, schedule("days")),
            WidgetItem("line", F1ScheduleWidget.id, Box(8, 436, 396, 140), true, schedule("timeline")),
            WidgetItem("wide", F1ScheduleWidget.id, Box(8, 584, 396, 100), true, schedule("strip")),
        )))
        save("f1-schedule-layouts-live")
    }

    @Test
    fun `every schedule layout counting down, with dates, 24-hour times and nothing dimmed`() {
        feeds.f1.value = F1State.Ready(F1Samples.data)
        at(LocalDateTime.of(2026, 10, 9, 5, 20, 15))
        val extras = arrayOf(F1ScheduleWidget.SHOW_DATES to true, F1ScheduleWidget.DIM_PAST to false)
        show(Layout(listOf(
            WidgetItem("list", F1ScheduleWidget.id, Box(8, 8, 260, 200), true, schedule("list", *extras).with(F1ScheduleWidget.CLOCK, "24")),
            WidgetItem("days", F1ScheduleWidget.id, Box(8, 216, 396, 170), true, schedule("days", *extras)),
            WidgetItem("line", F1ScheduleWidget.id, Box(8, 394, 396, 140), true, schedule("timeline", *extras)),
            WidgetItem("strip", F1ScheduleWidget.id, Box(8, 542, 396, 100), true, schedule("strip", *extras)),
            WidgetItem("off", F1ScheduleWidget.id, Box(8, 650, 396, 140), true, schedule("timeline", F1ScheduleWidget.SHOW_COUNTDOWN to false)),
        )))
        save("f1-schedule-layouts-countdown")
    }

    @Test
    fun `the schedule is for the next weekend after a race`() {
        feeds.f1.value = F1State.Ready(F1Samples.data)
        at(LocalDateTime.of(2026, 10, 6, 12, 0))
        show(Layout(listOf(WidgetItem("s", F1ScheduleWidget.id, Box(8, 8, 196, 232), true, F1ScheduleWidget.defaults))))
        save("f1-schedule-after-race")
    }

    /** Singapore Sprint Qualifying as the feed held it after the flag. */
    private val sprintQuali: LiveSession by lazy {
        val topics = org.json.JSONObject(javaClass.classLoader!!.getResource("f1live-sprint-quali.json")!!.readText())
        parseLiveSession(topics.keys().asSequence().associateWith { topics.getJSONObject(it) })!!
    }

    private fun liveStack(): Layout = Layout(listOf(
        WidgetItem("tall", F1LiveWidget.id, Box(8, 8, 196, 360), true, F1LiveWidget.defaults.with(F1LiveWidget.FAVOURITE, "HAM")),
        WidgetItem("ahead", F1LiveWidget.id, Box(212, 8, 196, 220), true, F1LiveWidget.defaults.with(F1LiveWidget.ROWS, 6).with(F1LiveWidget.GAP, "ahead")),
        WidgetItem("wide", F1LiveWidget.id, Box(8, 384, 396, 220), true, F1LiveWidget.defaults.with(F1LiveWidget.FAVOURITE, "NOR")),
    ))

    @Test
    fun `the live session widget in qualifying under a red flag, then with its result`() {
        val now = LocalDateTime.of(2026, 10, 9, 12, 35)
        at(now)
        val clockAt = now.toInstant(ZoneOffset.UTC)
        feeds.f1Live.value = F1LiveState.Live(
            sprintQuali.copy(
                status = "Aborted", finished = false, flag = TrackFlag.Red, part = 2,
                remaining = java.time.Duration.ofSeconds(419), clockAt = clockAt, clockRunning = false,
            ),
        )
        show(liveStack())
        save("f1-live-session-red-flag")
        feeds.f1Live.value = F1LiveState.Result(sprintQuali)
        compose.waitForIdle()
        save("f1-live-session-result")
    }

    @Test
    fun `the live session widget during a race under the safety car`() {
        at(LocalDateTime.of(2026, 10, 11, 12, 50))
        val race = sprintQuali.copy(
            key = 11388, name = "Race", type = "Race", status = "Started", finished = false, flag = TrackFlag.SafetyCar, part = null, lap = 23, totalLaps = 62,
            rows = sprintQuali.rows.mapIndexed { i, r ->
                val gap = if (i == 0) "Leader" else String.format(java.util.Locale.US, "+%.3f", i * 1.873)
                r.copy(gap = gap, interval = if (i == 0) gap else "+1.873", knockedOut = false, inPit = i == 4, out = i == 21)
            },
        )
        feeds.f1Live.value = F1LiveState.Live(race)
        show(liveStack())
        save("f1-live-session-race")
    }

    @Test
    fun `the live session widget before it has seen any session, in the editor`() {
        show(Layout(listOf(WidgetItem("w", F1LiveWidget.id, Box(8, 8, 196, 300), true, F1LiveWidget.defaults))), editing = true)
        save("f1-live-session-waiting")
    }

    @Test
    fun `the new widgets with nothing to show, in the editor where hints appear`() {
        show(newWidgets(), editing = true)
        save("new-widgets-empty")
    }

    @Test
    fun `the clock, date and notifications set against either edge`() {
        val w = com.ikverse.deskglow.widgets.Common.ALIGN
        feeds.notifications.value = com.ikverse.deskglow.data.NotificationState.Apps(
            listOf(com.ikverse.deskglow.data.NotifiedApp("a", null), com.ikverse.deskglow.data.NotifiedApp("b", null)),
        )
        val items = listOf("left", "center", "right").flatMapIndexed { i, align ->
            listOf(
                WidgetItem("c$i", com.ikverse.deskglow.widgets.ClockWidget.id, Box(8, 8 + i * 130, 396, 64), true, com.ikverse.deskglow.widgets.ClockWidget.defaults.with(w, align)),
                WidgetItem("d$i", com.ikverse.deskglow.widgets.DateWidget.id, Box(8, 80 + i * 130, 396, 24), true, com.ikverse.deskglow.widgets.DateWidget.defaults.with(w, align)),
                WidgetItem("n$i", com.ikverse.deskglow.widgets.NotificationsWidget.id, Box(8, 108 + i * 130, 396, 28), true, com.ikverse.deskglow.widgets.NotificationsWidget.defaults.with(w, align)),
            )
        }
        show(Layout(items))
        save("alignment")
    }

    @Test
    fun `the clock with seconds as a line, a leading zero and AM or PM, and the date in capitals or Hijri`() {
        val clock = com.ikverse.deskglow.widgets.ClockWidget
        val date = com.ikverse.deskglow.widgets.DateWidget
        val items = listOf(
            WidgetItem("c1", clock.id, Box(8, 8, 396, 72), true, clock.defaults.with(clock.SECONDS_MODE, "line")),
            WidgetItem("c2", clock.id, Box(8, 96, 396, 72), true, clock.defaults.with(clock.SECONDS_MODE, "digits").with(clock.AMPM, true).with(clock.LEADING_ZERO, true)),
            WidgetItem("c3", clock.id, Box(8, 184, 396, 72), true, clock.defaults.with(clock.AMPM, true).with(clock.SECONDS_MODE, "line").with(com.ikverse.deskglow.widgets.Common.ALIGN, "left")),
            WidgetItem("d1", date.id, Box(8, 272, 396, 28), true, date.defaults.with(date.CASE, "upper")),
            WidgetItem("d2", date.id, Box(8, 312, 396, 28), true, date.defaults.with(date.FORMAT, "hijri")),
            WidgetItem("d3", date.id, Box(8, 352, 396, 28), true, date.defaults.with(date.FORMAT, "day").with(date.CASE, "upper")),
        )
        show(Layout(items))
        save("clock-and-date-options")
    }

    @Test
    fun `the ring, stat, notifications, weather, event and now playing in their new looks`() {
        val common = com.ikverse.deskglow.widgets.Common.ALIGN
        feeds.battery.value = com.ikverse.deskglow.data.BatteryState(42, com.ikverse.deskglow.data.ChargeStatus.Charging, true, 4100, 335, 500, 5_400_000L)
        feeds.batteryPower.value = feeds.battery.value
        feeds.notifications.value = com.ikverse.deskglow.data.NotificationState.Apps(
            listOf(com.ikverse.deskglow.data.NotifiedApp("a", null), com.ikverse.deskglow.data.NotifiedApp("b", null), com.ikverse.deskglow.data.NotifiedApp("c", null)),
        )
        feeds.weather.value = WeatherState.Ready(cairo, WeatherWidget.SAMPLE)
        feeds.nextEvent.value = com.ikverse.deskglow.data.EventState.Next("Team call", LocalDateTime.of(2026, 10, 7, 20, 30), LocalDateTime.of(2026, 10, 7, 21, 30), false)
        feeds.media.value = MediaState.Track("Clair de Lune", "Debussy", 225_000, 83_000, android.os.SystemClock.elapsedRealtime(), false, 1f)
        val ring = com.ikverse.deskglow.widgets.RingWidget
        val stat = com.ikverse.deskglow.widgets.StatWidget
        val notifs = com.ikverse.deskglow.widgets.NotificationsWidget
        val media = com.ikverse.deskglow.widgets.MediaWidget
        val event = com.ikverse.deskglow.widgets.EventWidget
        val items = listOf(
            WidgetItem("r1", ring.id, Box(8, 8, 120, 120), true, ring.defaults.with(ring.LAYOUT, "circle").with(ring.UNDER, "time")),
            WidgetItem("r2", ring.id, Box(140, 8, 120, 120), true, ring.defaults.with(ring.LAYOUT, "segments").with(ring.UNDER, "power").with(ring.LEVEL_COLOUR, true)),
            WidgetItem("r3", ring.id, Box(272, 8, 120, 120), true, ring.defaults.with(ring.UNDER, "temp").with(ring.LEVEL_COLOUR, true)),
            WidgetItem("s1", stat.id, Box(8, 140, 190, 40), true, stat.defaults.with(stat.LABEL_MODE, "beside")),
            WidgetItem("s2", stat.id, Box(210, 140, 190, 40), true, stat.defaults.with(stat.METRIC, "time").with(stat.LABEL_MODE, "under")),
            WidgetItem("n1", notifs.id, Box(8, 192, 396, 30), true, notifs.defaults.with(notifs.STYLE, "pill")),
            WidgetItem("n2", notifs.id, Box(8, 232, 396, 30), true, notifs.defaults.with(notifs.STYLE, "dots").with(common, "right")),
            WidgetItem("w1", WeatherWidget.id, Box(8, 272, 196, 64), true, WeatherWidget.defaults.with(WeatherWidget.TINT, true).with(WeatherWidget.ICON_STYLE, "none")),
            WidgetItem("w2", WeatherWidget.id, Box(212, 272, 196, 64), true, WeatherWidget.defaults.with(WeatherWidget.TINT, true).with(WeatherWidget.ICON_STYLE, "outline")),
            WidgetItem("e1", event.id, Box(8, 348, 396, 70), true, event.defaults.with(event.SHOW_SOON, true)),
            WidgetItem("m1", media.id, Box(8, 430, 396, 52), true, media.defaults.with(media.PROGRESS_MODE, "times")),
            WidgetItem("m2", media.id, Box(8, 494, 396, 52), true, media.defaults.with(media.PROGRESS_MODE, "thin").with(common, "right")),
        )
        feeds.minute.value = LocalDateTime.of(2026, 10, 7, 20, 5)
        feeds.second.value = LocalDateTime.of(2026, 10, 7, 20, 5, 9)
        show(Layout(items))
        save("widget-looks")
    }

    @Test
    fun `prayer times as a sun arc and the alarm as a ring, or as sleep`() {
        feeds.prayers.value = PrayerState.Ready(cairo, tonight)
        feeds.alarm.value = AlarmState(LocalDateTime.of(2026, 10, 8, 6, 30))
        feeds.minute.value = LocalDateTime.of(2026, 10, 7, 20, 5)
        feeds.second.value = LocalDateTime.of(2026, 10, 7, 20, 5, 9)
        val prayer = PrayerWidget
        val alarm = AlarmWidget
        val items = listOf(
            WidgetItem("p1", prayer.id, Box(8, 8, 396, 130), true, prayer.defaults.with(prayer.VIEW, "arc")),
            WidgetItem("p2", prayer.id, Box(8, 150, 396, 130), true, prayer.defaults.with(prayer.VIEW, "arc").with(com.ikverse.deskglow.widgets.Common.ARABIC, true)),
            WidgetItem("p3", prayer.id, Box(8, 292, 196, 130), true, prayer.defaults.with(prayer.VIEW, "arc")),
            WidgetItem("a1", alarm.id, Box(8, 434, 260, 72), true, alarm.defaults.with(alarm.TIME_LEFT, "ring")),
            WidgetItem("a2", alarm.id, Box(8, 518, 260, 72), true, alarm.defaults.with(alarm.PHRASE, "sleep")),
            WidgetItem("a3", alarm.id, Box(8, 602, 260, 72), true, alarm.defaults.with(alarm.WITHIN, "6")),
        )
        show(Layout(items), editing = true)
        save("prayer-arc-and-alarm-looks")
    }

    @Test
    fun `the F1 widgets in their new options`() {
        feeds.f1.value = F1State.Ready(F1Samples.data.copy(track = F1Samples.marinaBay))
        at(LocalDateTime.of(2026, 10, 10, 8, 30))
        val raceOnly = weekend("classic").with(F1WeekendWidget.COUNT_TO, "race").with(F1WeekendWidget.SHOW_START, true).with(com.ikverse.deskglow.widgets.Common.TIME_FORMAT, "24")
        feeds.f1Live.value = F1LiveState.Live(
            sprintQuali.copy(status = "Aborted", finished = false, flag = TrackFlag.Red, part = 2, remaining = java.time.Duration.ofSeconds(419), clockAt = LocalDateTime.of(2026, 10, 10, 8, 30).toInstant(ZoneOffset.UTC)),
        )
        val items = listOf(
            WidgetItem("k1", F1WeekendWidget.id, Box(8, 8, 396, 112), true, raceOnly),
            WidgetItem("k2", F1WeekendWidget.id, Box(8, 128, 196, 150), true, raceOnly.with(F1WeekendWidget.LAYOUT, "hero").with(F1WeekendWidget.SHOW_TRACK, false)),
            WidgetItem("k3", F1WeekendWidget.id, Box(212, 128, 196, 150), true, raceOnly.with(F1WeekendWidget.LAYOUT, "countdown").with(F1WeekendWidget.SHOW_TRACK, false)),
            WidgetItem("c1", F1ScheduleWidget.id, Box(8, 290, 196, 110), true, F1ScheduleWidget.defaults.with(F1ScheduleWidget.LAYOUT, "list").with(F1ScheduleWidget.SESSIONS, "main")),
            WidgetItem("l1", F1LiveWidget.id, Box(212, 290, 196, 260), true,
                F1LiveWidget.defaults.with(F1LiveWidget.BORDER, true).with(F1LiveWidget.NAMES, "surname").with(F1LiveWidget.ROWS, 6)),
            WidgetItem("t1", F1StandingsWidget.id, Box(8, 412, 196, 150), true, F1StandingsWidget.defaults.with(F1StandingsWidget.LAYOUT, "bars").with(F1StandingsWidget.ROWS, 5)),
            WidgetItem("t2", F1StandingsWidget.id, Box(8, 572, 196, 150), true, F1StandingsWidget.defaults.with(F1StandingsWidget.VALUE, "ahead").with(F1StandingsWidget.ROWS, 5)),
            WidgetItem("t3", F1StandingsWidget.id, Box(212, 572, 196, 150), true,
                F1StandingsWidget.defaults.with(F1StandingsWidget.TABLE, "constructors").with(F1StandingsWidget.LAYOUT, "bars").with(F1StandingsWidget.VALUE, "gap")),
        )
        show(Layout(items))
        save("f1-new-options")
    }

    @Test
    fun `the weather forecast layout, the sun details, and the sun and moon widget`() {
        val city = cairo
        val noon = LocalDateTime.of(2026, 10, 10, 14, 5)
        feeds.minute.value = noon
        feeds.second.value = noon
        val offset = java.time.ZonedDateTime.of(noon, java.time.ZoneId.systemDefault()).offset.totalSeconds
        val hours = (0 until 24).map { i ->
            com.ikverse.deskglow.data.HourForecast(LocalDateTime.of(2026, 10, 10, 0, 0).plusHours(i.toLong()), 17.0 + 8 * Math.sin((i - 8) / 24.0 * 2 * Math.PI), listOf(0, 2, 3, 61, 3, 2)[i % 6], i in 6..17)
        }
        feeds.weather.value = WeatherState.Ready(
            city,
            Weather(
                25.0, 2, true, 30.1, 18.0, 0, feelsLikeC = 26.0, humidityPercent = 48, windKmh = 14.0, rainChancePercent = 10, hours = hours,
                sunrise = LocalDateTime.of(2026, 10, 10, 5, 42), sunset = LocalDateTime.of(2026, 10, 10, 17, 30), uvIndex = 6.4, utcOffsetSeconds = offset,
            ),
        )
        val sun = com.ikverse.deskglow.widgets.SunMoonWidget
        val items = listOf(
            WidgetItem("h1", WeatherWidget.id, Box(8, 8, 396, 80), true, WeatherWidget.defaults.with(WeatherWidget.LAYOUT, "hours")),
            WidgetItem("h2", WeatherWidget.id, Box(8, 100, 396, 112), true, WeatherWidget.defaults.with(WeatherWidget.LAYOUT, "hours").with(WeatherWidget.UNITS, "f")),
            WidgetItem("h3", WeatherWidget.id, Box(8, 224, 196, 80), true, WeatherWidget.defaults.with(WeatherWidget.LAYOUT, "hours")),
            WidgetItem("d1", WeatherWidget.id, Box(8, 316, 396, 70), true,
                WeatherWidget.defaults.with(WeatherWidget.LAYOUT, "compact").with(WeatherWidget.SHOW_SUNRISE, true).with(WeatherWidget.SHOW_SUNSET, true).with(WeatherWidget.SHOW_UV, true)),
            WidgetItem("d2", WeatherWidget.id, Box(212, 224, 196, 80), true,
                WeatherWidget.defaults.with(WeatherWidget.SHOW_SUNRISE, true).with(WeatherWidget.SHOW_SUNSET, true).with(WeatherWidget.SHOW_UV, true)),
            WidgetItem("s1", sun.id, Box(8, 400, 240, 88), true, sun.defaults),
            WidgetItem("s2", sun.id, Box(8, 500, 396, 120), true, sun.defaults.with(sun.SHOW_MOON, false)),
        )
        show(Layout(items))
        save("weather-forecast-and-sun-moon")
        // And at night, before dawn.
        val night = LocalDateTime.of(2026, 10, 10, 3, 30)
        feeds.minute.value = night
        feeds.second.value = night
        compose.waitForIdle()
        save("sun-moon-night")
    }

    @Test
    fun `prayer times with sunrise and the Hijri date, in a row and on the arc`() {
        feeds.prayers.value = PrayerState.Ready(cairo, tonight.map { it.copy(sunrise = LocalTime.of(5, 52)) })
        feeds.minute.value = LocalDateTime.of(2026, 10, 7, 12, 30)
        feeds.second.value = LocalDateTime.of(2026, 10, 7, 12, 30, 5)
        val prayer = PrayerWidget
        val shown = prayer.defaults.with(prayer.SHOW_SUNRISE, true).with(prayer.SHOW_HIJRI, true)
        val items = listOf(
            WidgetItem("p1", prayer.id, Box(8, 8, 396, 110), true, shown),
            WidgetItem("p2", prayer.id, Box(8, 130, 396, 130), true, shown.with(prayer.VIEW, "arc")),
            WidgetItem("p3", prayer.id, Box(8, 272, 396, 110), true, shown.with(com.ikverse.deskglow.widgets.Common.ARABIC, true).with(com.ikverse.deskglow.widgets.Common.ALIGN, "right")),
            WidgetItem("p4", prayer.id, Box(8, 394, 396, 60), true, shown.with(prayer.VIEW, "next")),
        )
        show(Layout(items))
        save("prayer-sunrise-and-hijri")
    }

    @Test
    fun `notification counts beside the icons, and the stat showing the charger, the health and the cycles`() {
        feeds.notifications.value = com.ikverse.deskglow.data.NotificationState.Apps(
            listOf(com.ikverse.deskglow.data.NotifiedApp("a", null, 3), com.ikverse.deskglow.data.NotifiedApp("b", null, 1), com.ikverse.deskglow.data.NotifiedApp("c", null, 12)),
        )
        feeds.battery.value = com.ikverse.deskglow.data.BatteryState(80, com.ikverse.deskglow.data.ChargeStatus.Charging, true, 4100, 335, 500, 5_400_000L, plugSource = 4, healthCode = 2, cycles = 212)
        val notifs = com.ikverse.deskglow.widgets.NotificationsWidget
        val stat = com.ikverse.deskglow.widgets.StatWidget
        val items = listOf(
            WidgetItem("n1", notifs.id, Box(8, 8, 396, 40), true, notifs.defaults.with(notifs.COUNTS, true)),
            WidgetItem("n2", notifs.id, Box(8, 60, 396, 40), true, notifs.defaults.with(notifs.COUNTS, true).with(notifs.STYLE, "pill")),
            WidgetItem("s1", stat.id, Box(8, 120, 120, 56), true, stat.defaults.with(stat.METRIC, "charger")),
            WidgetItem("s2", stat.id, Box(140, 120, 120, 56), true, stat.defaults.with(stat.METRIC, "health")),
            WidgetItem("s3", stat.id, Box(272, 120, 120, 56), true, stat.defaults.with(stat.METRIC, "cycles")),
        )
        show(Layout(items))
        save("notification-counts-and-battery-readings")
    }

    @Test
    fun `the next event as an agenda, with calendar colours and places`() {
        feeds.minute.value = LocalDateTime.of(2026, 10, 7, 20, 5)
        fun at(h: Int, m: Int = 0, d: Int = 7) = LocalDateTime.of(2026, 10, d, h, m)
        feeds.nextEvent.value = com.ikverse.deskglow.data.EventState.Next(
            "Team call", at(20, 30), at(21, 30), false, 0xFF4285F4.toInt(), "Zoom",
            listOf(
                com.ikverse.deskglow.data.EventState.Next("Dinner with Sara", at(22), at(23), false, 0xFF0B8043.toInt(), "Zamalek"),
                com.ikverse.deskglow.data.EventState.Next("Dentist", at(9, 0, 8), at(10, 0, 8), false, 0xFFD50000.toInt(), "Maadi"),
                com.ikverse.deskglow.data.EventState.Next("A holiday with a long name", at(0, 0, 9), at(0, 0, 10), true, null, ""),
            ),
        )
        val event = com.ikverse.deskglow.widgets.EventWidget
        val items = listOf(
            WidgetItem("e1", event.id, Box(8, 8, 396, 130), true, event.defaults.with(event.COUNT, 4).with(event.SHOW_COLOUR, true).with(event.SHOW_LOCATION, true)),
            WidgetItem("e2", event.id, Box(8, 150, 196, 110), true, event.defaults.with(event.COUNT, 3).with(event.SHOW_HEADING, false)),
            WidgetItem("e3", event.id, Box(212, 150, 196, 110), true, event.defaults.with(event.COUNT, 2).with(event.SHOW_COLOUR, true).with(com.ikverse.deskglow.widgets.Common.ALIGN, "right")),
            WidgetItem("e4", event.id, Box(8, 272, 220, 64), true, event.defaults.with(event.SHOW_COLOUR, true).with(event.SHOW_LOCATION, true)),
        )
        show(Layout(items))
        save("event-agenda")
    }

    @Test
    fun `now playing with the cover, or the note when there is none`() {
        val bitmap = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
            val canvas = android.graphics.Canvas(b)
            canvas.drawColor(android.graphics.Color.rgb(40, 90, 160))
            canvas.drawCircle(60f, 60f, 36f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(250, 190, 60) })
        }
        val art = bitmap.asImageBitmap()
        val media = com.ikverse.deskglow.widgets.MediaWidget
        val track = MediaState.Track("Clair de Lune", "Debussy", 225_000, 83_000, android.os.SystemClock.elapsedRealtime(), false, 1f, art)
        feeds.media.value = track
        val items = listOf(
            WidgetItem("m1", media.id, Box(8, 8, 396, 64), true, media.defaults.with(media.PICTURE, "art")),
            WidgetItem("m2", media.id, Box(8, 84, 396, 64), true, media.defaults.with(media.PICTURE, "art").with(com.ikverse.deskglow.widgets.Common.ALIGN, "right")),
            WidgetItem("m3", media.id, Box(8, 160, 396, 64), true, media.defaults.with(media.PICTURE, "note")),
        )
        show(Layout(items))
        save("now-playing-art")
        feeds.media.value = track.copy(art = null)
        compose.waitForIdle()
        save("now-playing-no-art")
    }

    @Test
    fun `the F1 widgets follow the driver and team set on Home, in their team colour when asked`() {
        feeds.f1.value = F1State.Ready(F1Samples.data.copy(track = F1Samples.marinaBay))
        at(LocalDateTime.of(2026, 10, 6, 12, 0))
        favourite.value = com.ikverse.deskglow.data.F1Favourite(driver = "LEC", team = "ferrari")
        val items = listOf(
            WidgetItem("a", F1WeekendWidget.id, Box(8, 8, 396, 100), true, F1WeekendWidget.defaults.with(F1WeekendWidget.LAYOUT, "classic")),
            WidgetItem("b", F1WeekendWidget.id, Box(8, 120, 396, 100), true, F1WeekendWidget.defaults.with(F1WeekendWidget.TEAM_ACCENT, true)),
            WidgetItem("c", F1WeekendWidget.id, Box(8, 232, 396, 100), true, F1WeekendWidget.defaults.with(F1WeekendWidget.FAVOURITE, "none")),
            WidgetItem("d", F1StandingsWidget.id, Box(8, 344, 196, 170), true, F1StandingsWidget.defaults.with(F1StandingsWidget.ROWS, 6)),
            WidgetItem("e", F1StandingsWidget.id, Box(212, 344, 196, 170), true,
                F1StandingsWidget.defaults.with(F1StandingsWidget.TABLE, "constructors").with(F1StandingsWidget.TEAM_ACCENT, true)),
        )
        show(Layout(items))
        save("f1-home-favourite")
    }

    @Test
    fun `a widget from a newer version is skipped, not a crash`() {
        show(Layout(DefaultLayout.create().items + WidgetItem("w99", "crypto", Box(0, 0, 100, 40))))
        assert(Widgets.find("crypto") == null)
    }
}
