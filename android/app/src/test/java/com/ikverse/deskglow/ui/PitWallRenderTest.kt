package com.ikverse.deskglow.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.data.EventKind
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.LiveEvent
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.TrackFlag
import com.ikverse.deskglow.display.DisplayContent
import com.ikverse.deskglow.fonts.FontResolver
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.Common
import com.ikverse.deskglow.widgets.F1LiveSample
import com.ikverse.deskglow.widgets.LocalEditing
import com.ikverse.deskglow.widgets.PitWallWidget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * PitWall's four screens, upright and on its side: drawn, saved under build/screens for a look by
 * eye, and checked for the words that matter. The made-up scenes are the editor's; the last tests
 * are the display with the calendar and the feed as they would be, and what moves when they change.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h848dp-xxhdpi")
class PitWallRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val feeds = FakeFeeds()

    // The tests' clock does not run on in real time, so PitWall is not held back behind it.
    @org.junit.Before fun noDelay() { PitWallWidget.leastDelayS = 0 }
    @org.junit.After fun usualDelay() { PitWallWidget.leastDelayS = PitWallWidget.MIN_DELAY_S }

    private fun settings(preview: String = "auto") = PitWallWidget.defaults.with(PitWallWidget.PREVIEW, preview).with(Common.TIME_FORMAT, "24")

    private fun show(orientation: Orientation, settings: Settings, editing: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val layout = Layout(listOf(WidgetItem("w1", PitWallWidget.id, Box(0, 0, orientation.width, orientation.height), true, settings)))
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
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { System.err.println("Screen capture unavailable here: $it") }
    }

    private fun at(instant: Instant) {
        val local = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        feeds.minute.value = local
        feeds.second.value = local
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    private fun live(orientation: Orientation) {
        show(orientation, settings("live"), editing = true)
        save("pitwall-live-${orientation.name.lowercase()}")
        assertTrue(shows("Singapore GP · Sprint"))
        assertTrue(shows("Lap 8"))
        assertTrue(shows("Safety car"))
        assertTrue(shows("Race control"))
        // Race control names drivers by their codes, never by car number.
        assertTrue(shows("COL stopped on track"))
        assertFalse(shows("Car "))
        // The order: the leader and the car behind, with its gap.
        assertTrue(shows("VER"))
        assertTrue(shows("RUS"))
        assertTrue(shows("+1.4"))
    }

    private fun weekend(orientation: Orientation) {
        show(orientation, settings("weekend"), editing = true)
        save("pitwall-weekend-${orientation.name.lowercase()}")
        assertTrue(shows("Singapore GP · Qualifying"))
        assertTrue(shows("Result"))
        assertTrue(shows("Final"))
        assertTrue(shows("Race in "))
        assertFalse(shows("Race control"))
    }

    private fun between(orientation: Orientation) {
        show(orientation, settings("between"), editing = true)
        save("pitwall-between-${orientation.name.lowercase()}")
        assertTrue(shows("Next race · round 24"))
        assertTrue(shows("Abu Dhabi GP"))
        assertTrue(shows("6 d 4 h"))
        assertTrue(shows("until first practice"))
        assertTrue(shows("Drivers"))
        assertTrue(shows("Constructors"))
        assertTrue(shows("McLaren"))
        assertTrue(shows("Last race · Qatar GP"))
    }

    private fun offSeason(orientation: Orientation) {
        show(orientation, settings("offseason"), editing = true)
        save("pitwall-offseason-${orientation.name.lowercase()}")
        assertTrue(shows("Lights out · 2027"))
        assertTrue(shows("Australian GP"))
        assertTrue(shows("2026 champions"))
        assertTrue(shows("Lando Norris"))
        assertTrue(shows("Final standings"))
        assertTrue(shows("Past champions"))
        assertTrue(shows("2027 calendar"))
        assertTrue(shows("Season in review"))
    }

    @Test fun `live, upright`() = live(Orientation.Portrait)
    @Test @Config(qualifiers = "w848dp-h412dp-land-xxhdpi") fun `live, on its side`() = live(Orientation.Landscape)
    @Test fun `a weekend between sessions, upright`() = weekend(Orientation.Portrait)
    @Test @Config(qualifiers = "w848dp-h412dp-land-xxhdpi") fun `a weekend between sessions, on its side`() = weekend(Orientation.Landscape)
    @Test fun `between weekends, upright`() = between(Orientation.Portrait)
    @Test @Config(qualifiers = "w848dp-h412dp-land-xxhdpi") fun `between weekends, on its side`() = between(Orientation.Landscape)
    @Test fun `the off-season, upright`() = offSeason(Orientation.Portrait)
    @Test @Config(qualifiers = "w848dp-h412dp-land-xxhdpi") fun `the off-season, on its side`() = offSeason(Orientation.Landscape)

    @Test
    fun `the off-season leaves out what is switched off`() {
        show(Orientation.Portrait, settings("offseason").with(PitWallWidget.SHOW_HONOUR, false).with(PitWallWidget.SHOW_REVIEW, false).with(PitWallWidget.SHOW_CALENDAR, false), editing = true)
        assertTrue(shows("2026 champions"))
        assertFalse(shows("Past champions"))
        assertFalse(shows("Season in review"))
        assertFalse(shows("2027 calendar"))
    }

    @Test
    fun `on the display it follows the calendar, and nothing shows until there is one`() {
        at(Instant.parse("2026-10-06T00:00:00Z"))
        show(Orientation.Portrait, settings(), editing = false)
        assertTrue(shows("Waiting for the F1 calendar"))
        feeds.f1.value = F1State.Ready(F1Samples.data)
        compose.waitForIdle()
        assertTrue(shows("Japanese GP"))
        assertTrue(shows("until first practice"))
        // The editor's preview choice is the editor's alone.
        assertFalse(shows("Abu Dhabi GP"))
    }

    @Test
    fun `the tables move when the order changes, and then everything is still`() {
        at(Instant.parse("2026-10-06T00:00:00Z"))
        feeds.f1.value = F1State.Ready(F1Samples.data)
        show(Orientation.Portrait, settings(), editing = false)
        val data = F1Samples.data
        val swapped = data.drivers.toMutableList().apply {
            val first = this[0]
            this[0] = this[1].copy(position = 1)
            this[1] = first.copy(position = 2)
        }
        feeds.f1.value = F1State.Ready(data.copy(drivers = swapped))
        // Coming to rest at all is the point: an animation that looped would never let this return.
        compose.waitForIdle()
        assertTrue(shows("PIA"))
        assertTrue(shows("NOR"))
    }

    @Test
    @Config(qualifiers = "w848dp-h412dp-land-xxhdpi")
    fun `a yellow lights its sector on the track, and race control says who it is for`() {
        compose.mainClock.autoAdvance = false
        at(F1LiveSample.AT.plusSeconds(3))
        val track = F1LiveSample.track.copy(sectors = (1..16).map { com.ikverse.deskglow.data.F1Sector(it, (it - 1) / 16f) })
        val session = F1LiveSample.race.copy(
            track = track, flag = TrackFlag.Yellow, sectorFlags = mapOf(5 to "DOUBLE YELLOW", 6 to "YELLOW"),
            events = F1LiveSample.race.events + LiveEvent(EventKind.Flag, "Double yellow · sector 5 · [COL]", F1LiveSample.AT),
        )
        feeds.f1Live.value = F1LiveState.Live(session)
        show(Orientation.Landscape, settings(), editing = false)
        compose.mainClock.advanceTimeBy(2_000)
        save("pitwall-live-sector-yellow")
        assertTrue(shows("Yellow flag"))
        assertTrue(shows("Double yellow · sector 5 · COL"))
        assertFalse(shows("[COL]"))
    }

    @Test
    fun `a live session moves through a pass, a flag and race control's news`() {
        // The cars move on every frame while they run, so the test's clock is held still and stepped by hand.
        compose.mainClock.autoAdvance = false
        at(F1LiveSample.AT.plusSeconds(3))
        val session = F1LiveSample.race.copy(track = F1LiveSample.track)
        feeds.f1Live.value = F1LiveState.Live(session)
        show(Orientation.Portrait, settings(), editing = false)
        compose.mainClock.advanceTimeBy(500)
        assertTrue(shows("Safety car"))
        assertTrue(shows("COL stopped on track"))

        // The first two change places, the red flag comes out, and race control says why.
        val rows = session.rows.toMutableList().apply {
            val leader = this[0]
            this[0] = this[1].copy(position = 1)
            this[1] = leader.copy(position = 2)
        }
        feeds.f1Live.value = F1LiveState.Live(
            session.copy(rows = rows, status = "Aborted", flag = TrackFlag.Red, events = session.events + LiveEvent(EventKind.Red, "Red flag", F1LiveSample.AT)),
        )
        compose.mainClock.advanceTimeBy(4_000)
        save("pitwall-live-red-flag")
        assertTrue(shows("Red flag"))
        assertFalse(shows("Safety car"))
        assertTrue(shows("COL stopped on track"))
        assertTrue(shows("RUS"))
    }
}
