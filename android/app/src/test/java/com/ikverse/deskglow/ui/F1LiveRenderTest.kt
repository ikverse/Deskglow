package com.ikverse.deskglow.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.FakeFeeds
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.LiveMessage
import com.ikverse.deskglow.data.LiveSession
import com.ikverse.deskglow.data.LiveTiming
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.TrackFlag
import com.ikverse.deskglow.display.DisplayContent
import com.ikverse.deskglow.fonts.FontResolver
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.Common
import com.ikverse.deskglow.widgets.F1LiveSample
import com.ikverse.deskglow.widgets.F1LiveWidget
import com.ikverse.deskglow.widgets.F1ScheduleWidget
import com.ikverse.deskglow.widgets.F1WeekendWidget
import com.ikverse.deskglow.widgets.LocalEditing
import com.ikverse.deskglow.widgets.timeText
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
 * Every layout of the live session widget in every state it can be in, and the calendar widgets while
 * the feed says a session is delayed or running late: drawn, saved under build/screens for a look
 * by eye, and checked for the words that matter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h848dp-xxhdpi")
class F1LiveRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val feeds = FakeFeeds()
    private val layouts = listOf("tower", "glance", "focus", "line")

    private fun show(layout: Layout, editing: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.setContent {
            CompositionLocalProvider(LocalFeeds provides feeds, LocalFonts provides FontResolver(context, null), LocalEditing provides editing) {
                DeskglowTheme { DisplayContent(layout, burnIn = true, orientation = com.ikverse.deskglow.model.Orientation.Portrait) }
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

    private fun settings(layout: String, vararg changes: Pair<String, Any>): Settings {
        var s = F1LiveWidget.defaults.with(F1LiveWidget.LAYOUT, layout).with(F1LiveWidget.FAVOURITE, "NOR").with(Common.TIME_FORMAT, "24")
        for ((key, value) in changes) {
            s = when (key) {
                "tyres" -> s.with(F1LiveWidget.TYRES, value as Boolean)
                "gained" -> s.with(F1LiveWidget.GAINED, value as Boolean)
                "news" -> s.with(F1LiveWidget.NEWS, value as Boolean)
                "names" -> s.with(F1LiveWidget.NAMES, value as String)
                "gap" -> s.with(F1LiveWidget.GAP, value as String)
                "favTeam" -> s.with(F1LiveWidget.FAV_TEAM, value as String).with(F1LiveWidget.FAVOURITE, "none")
                else -> s
            }
        }
        return s
    }

    /** The four layouts in one screen: a tall tower, the others stacked beside it. */
    private fun everyLayout(vararg changes: Pair<String, Any>) = Layout(
        listOf(
            WidgetItem("tower", F1LiveWidget.id, Box(8, 8, 188, 400), true, settings("tower", *changes)),
            WidgetItem("glance", F1LiveWidget.id, Box(204, 8, 188, 220), true, settings("glance", *changes)),
            WidgetItem("focus", F1LiveWidget.id, Box(204, 236, 188, 220), true, settings("focus", *changes)),
            WidgetItem("line", F1LiveWidget.id, Box(8, 416, 384, 36), true, settings("line", *changes)),
        ),
    )

    private val sprintDelayed: LiveSession by lazy {
        val all = JSONObject(javaClass.classLoader!!.getResource("f1live-sprint-delayed.json")!!.readText())
        val timing = LiveTiming()
        all.keys().forEach { timing.apply(LiveMessage(it, all.getJSONObject(it), full = true)) }
        timing.session()!!
    }

    @Test
    fun `every layout under the safety car, with tyres, places gained and the news on`() {
        at(F1LiveSample.AT)
        feeds.f1Live.value = F1LiveState.Live(F1LiveSample.race)
        show(everyLayout("tyres" to true, "gained" to true, "news" to true))
        save("f1-live-layouts-safety-car")
        // The tower, the big flag and the focus's chip say it in full; the line in short.
        assertEquals(3, compose.onAllNodesWithText("SAFETY CAR", ignoreCase = true).fetchSemanticsNodes().size)
        assertTrue(shows("SC"))
        assertTrue(shows("Lap 8/21"))
        // The sample's stopped car is in the news, named by code.
        assertTrue(shows("COL stopped on track"))
        // The followed car, P4, shows its place and a gain from the grid.
        assertTrue(shows("P4"))
        assertTrue(shows("▲"))
    }

    @Test
    fun `every layout in qualifying says where the followed car stands against the cut-off`() {
        at(F1LiveSample.AT)
        feeds.f1Live.value = F1LiveState.Live(F1LiveSample.qualifying)
        show(everyLayout())
        save("f1-live-layouts-qualifying")
        // Fifth, with eighth the last through: 0.548 quicker than the first car out.
        assertTrue(shows("0.548s safe of the cut-off"))
        assertTrue(shows("Drop zone"))
        assertTrue(shows("Q2"))
    }

    @Test
    fun `every layout in practice`() {
        at(F1LiveSample.AT)
        val practice = F1LiveSample.qualifying.copy(name = "Practice 1", type = "Practice", part = null, cutoff = null).let { s ->
            s.copy(rows = s.rows.mapIndexed { i, r -> r.copy(laps = 20 - i) })
        }
        feeds.f1Live.value = F1LiveState.Live(practice)
        show(everyLayout())
        save("f1-live-layouts-practice")
        assertTrue(shows("laps run"))
        assertTrue(shows("PRACTICE 1"))
    }

    @Test
    fun `every layout while the start is delayed says so, and when it will start`() {
        val now = Instant.parse("2026-10-10T09:10:00Z")
        at(now)
        feeds.f1Live.value = F1LiveState.Live(sprintDelayed)
        show(everyLayout())
        save("f1-live-layouts-delayed")
        val restart = timeText(sprintDelayed.restart, h24 = true)
        assertTrue(shows("DELAYED"))
        assertTrue("the new time, $restart, is shown", shows("Formation lap $restart"))
        // The grid is there to see: the tower's leader, and where the followed car starts.
        assertTrue(shows("VER"))
        assertTrue(shows("Rain risk 80%") || shows("Raining"))
    }

    @Test
    fun `every layout under a red flag says when it resumes`() {
        val resume = F1LiveSample.AT.plusSeconds(1200)
        at(F1LiveSample.AT)
        feeds.f1Live.value = F1LiveState.Live(F1LiveSample.race.copy(status = "Aborted", flag = TrackFlag.Red, restart = resume, restartLabel = "Resumes"))
        show(everyLayout())
        save("f1-live-layouts-red-flag")
        assertTrue(shows("RED FLAG"))
        assertTrue(shows("Resumes ${timeText(resume, h24 = true)}"))
    }

    @Test
    fun `every layout dims and says when the connection is lost`() {
        at(F1LiveSample.AT)
        feeds.f1Live.value = F1LiveState.Live(F1LiveSample.race.copy(signalLost = true, signalAt = F1LiveSample.AT.minusSeconds(190)))
        show(everyLayout())
        save("f1-live-layouts-no-signal")
        assertTrue(shows("No signal"))
        assertTrue(shows("3 min ago"))
        assertFalse(shows("SAFETY CAR"))
    }

    @Test
    fun `a result says it is one, and what is next`() {
        feeds.f1.value = F1State.Ready(F1Samples.data)
        at(Instant.parse("2026-10-09T12:00:00Z"))
        val final = F1LiveSample.qualifying.copy(status = "Finalised", finished = true, part = 3, cutoff = null, start = Instant.parse("2026-10-09T06:00:00Z"))
        feeds.f1Live.value = F1LiveState.Result(final)
        show(everyLayout())
        save("f1-live-layouts-result")
        assertTrue(shows("Result"))
        assertTrue(shows("Next · FP3 in"))
        // A result that is not yet final is marked.
        feeds.f1Live.value = F1LiveState.Result(final.copy(status = "Finished"))
        compose.waitForIdle()
        assertTrue(shows("Provisional"))
    }

    @Test
    fun `the smallest boxes keep the flag and the places, and leave out the rest`() {
        at(F1LiveSample.AT)
        feeds.f1Live.value = F1LiveState.Live(F1LiveSample.race)
        show(
            Layout(
                listOf(
                    WidgetItem("t", F1LiveWidget.id, Box(8, 8, 120, 80), true, settings("tower")),
                    WidgetItem("g", F1LiveWidget.id, Box(136, 8, 120, 80), true, settings("glance", "news" to true)),
                    WidgetItem("f", F1LiveWidget.id, Box(264, 8, 120, 80), true, settings("focus")),
                    WidgetItem("l", F1LiveWidget.id, Box(8, 96, 200, 24), true, settings("line")),
                    WidgetItem("l2", F1LiveWidget.id, Box(8, 128, 96, 20), true, settings("line")),
                ),
            ),
        )
        save("f1-live-layouts-small")
        assertTrue(shows("SAFETY CAR"))
    }

    @Test
    fun `names follow the setting in the tower and in the news`() {
        at(F1LiveSample.AT)
        feeds.f1Live.value = F1LiveState.Live(F1LiveSample.race)
        show(
            Layout(
                listOf(
                    WidgetItem("n", F1LiveWidget.id, Box(8, 8, 188, 300), true, settings("tower", "names" to "surname", "news" to true)),
                    WidgetItem("u", F1LiveWidget.id, Box(204, 8, 188, 300), true, settings("tower", "names" to "number")),
                ),
            ),
        )
        save("f1-live-layouts-names")
        assertTrue(shows("VERSTAPPEN"))
        assertTrue(shows("COLAPINTO stopped on track"))
    }

    @Test
    fun `the editor shows a made-up session where there is none, so every layout can be seen`() {
        show(everyLayout(), editing = true)
        save("f1-live-layouts-editor-sample")
        assertTrue(shows("SAFETY CAR"))
        assertTrue(shows("VER"))
    }

    @Test
    fun `the favourite team's other car is tinted, and the team stands in for a driver`() {
        at(F1LiveSample.AT)
        feeds.f1Live.value = F1LiveState.Live(F1LiveSample.race)
        show(
            Layout(
                listOf(
                    WidgetItem("t", F1LiveWidget.id, Box(8, 8, 188, 300), true, settings("tower", "favTeam" to "ferrari")),
                    WidgetItem("f", F1LiveWidget.id, Box(204, 8, 188, 220), true, settings("focus", "favTeam" to "ferrari")),
                ),
            ),
        )
        save("f1-live-layouts-team")
        // Ferrari's better car in the sample is third.
        assertTrue(shows("P3"))
    }

    // ---- the calendar widgets, while the feed knows more than the calendar ----

    private val sprintRunningLate = F1LiveSample.race.copy(
        key = 7, meeting = "United States GP", name = "Sprint", start = Instant.parse("2026-10-24T18:00:00Z"), rows = emptyList(), flag = TrackFlag.Clear,
    )

    private fun calendarWidgets(): Layout = Layout(
        listOf(
            WidgetItem("c", F1WeekendWidget.id, Box(8, 8, 396, 112), true, F1WeekendWidget.defaults.with(F1WeekendWidget.LAYOUT, "classic")),
            WidgetItem("h", F1WeekendWidget.id, Box(8, 128, 396, 112), true, F1WeekendWidget.defaults.with(F1WeekendWidget.LAYOUT, "hero").with(F1WeekendWidget.SHOW_TRACK, false)),
            WidgetItem("m", F1WeekendWidget.id, Box(8, 248, 396, 36), true, F1WeekendWidget.defaults.with(F1WeekendWidget.LAYOUT, "minimal")),
            WidgetItem("s", F1ScheduleWidget.id, Box(8, 292, 396, 232), true, F1ScheduleWidget.defaults.with(F1ScheduleWidget.LAYOUT, "list")),
        ),
    )

    @Test
    fun `the calendar widgets stay live past the calendar's slot while the feed says the sprint is on`() {
        feeds.f1.value = F1State.Ready(F1Samples.data)
        // The calendar gave the sprint an hour from 18:00; it started late and is still running at 19:20.
        at(Instant.parse("2026-10-24T19:20:00Z"))
        feeds.f1Live.value = F1LiveState.Live(sprintRunningLate)
        show(calendarWidgets())
        save("f1-calendar-widgets-running-late")
        assertTrue(shows("LIVE"))
        assertTrue(shows("Sprint"))
    }

    @Test
    fun `the calendar widgets say delayed, and red flag, from the feed`() {
        feeds.f1.value = F1State.Ready(F1Samples.data)
        at(Instant.parse("2026-10-24T18:20:00Z"))
        feeds.f1Live.value = F1LiveState.Live(
            sprintRunningLate.copy(status = "Inactive", flag = TrackFlag.Clear, delayed = true, restart = Instant.parse("2026-10-24T18:45:00Z"), restartLabel = "Formation lap"),
        )
        show(calendarWidgets())
        save("f1-calendar-widgets-delayed")
        // Three weekend layouts and the schedule's chip.
        assertTrue(compose.onAllNodesWithText("DELAYED", ignoreCase = true).fetchSemanticsNodes().size >= 3)
        assertTrue(shows("Formation lap " + timeText(Instant.parse("2026-10-24T18:45:00Z"), h24 = false)) || shows("Formation lap"))

        feeds.f1Live.value = F1LiveState.Live(sprintRunningLate.copy(status = "Aborted", flag = TrackFlag.Red))
        compose.waitForIdle()
        assertTrue(shows("RED FLAG"))
    }

    @Test
    fun `without the feed the calendar widgets are as they were`() {
        feeds.f1.value = F1State.Ready(F1Samples.data)
        at(Instant.parse("2026-10-24T18:20:00Z"))
        feeds.f1Live.value = F1LiveState.Waiting
        show(calendarWidgets())
        save("f1-calendar-widgets-calendar-only")
        assertTrue(shows("LIVE"))
        assertFalse(shows("DELAYED"))
    }
}
