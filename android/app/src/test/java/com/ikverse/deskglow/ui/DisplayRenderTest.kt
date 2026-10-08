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
        val items = WeatherWidget.LAYOUTS.mapIndexed { i, (layout, _) ->
            val settings = WeatherWidget.defaults.with(WeatherWidget.LAYOUT, layout)
                .with(WeatherWidget.ICON_STYLE, if (outline) "outline" else "filled")
                .with(WeatherWidget.SHOW_FEELS, true).with(WeatherWidget.SHOW_HUMIDITY, i % 2 == 0)
                .with(WeatherWidget.SHOW_WIND, true).with(WeatherWidget.SHOW_RAIN, i % 2 == 1)
                .with(Common.ALIGN, listOf("left", "center", "right", "left")[i])
            WidgetItem("w$i", WeatherWidget.id, Box(8, i * 100 + 8, 396, 92), true, settings)
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
            androidx.compose.foundation.layout.Column {
                for (outline in listOf(false, true)) for (day in listOf(true, false)) {
                    androidx.compose.foundation.layout.Row {
                        com.ikverse.deskglow.data.Sky.entries.forEach {
                            com.ikverse.deskglow.widgets.WeatherIcon(it, day, androidx.compose.ui.graphics.Color.White, androidx.compose.ui.Modifier.size(40.dp), outline)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        save("weather-icons")
    }

    @Test
    fun `a widget from a newer version is skipped, not a crash`() {
        show(Layout(DefaultLayout.create().items + WidgetItem("w99", "crypto", Box(0, 0, 100, 40))))
        assert(Widgets.find("crypto") == null)
    }
}
