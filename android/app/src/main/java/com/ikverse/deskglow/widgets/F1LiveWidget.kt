package com.ikverse.deskglow.widgets

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1Roster
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.LivePhase
import com.ikverse.deskglow.data.LiveSession
import com.ikverse.deskglow.data.LocalBroadcastDelay
import com.ikverse.deskglow.data.LocalF1Favourite
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.TrackFlag
import com.ikverse.deskglow.data.countdownText
import com.ikverse.deskglow.data.effectiveFavourite
import com.ikverse.deskglow.data.heldBack
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.IntKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object F1LiveWidget : WidgetType {
    /** "tower" (the classification), "glance" (the flag, big), "focus" (the followed car and its neighbours), "line" or "track" (the cars on a map). */
    val LAYOUT = TextKey("layout", "tower")

    // The track map's own choices.
    /** Which cars are named on the map: "me" (the followed car), "leader" (it and the leader), "top3", "all" or "none". */
    val TRACK_LABELS = TextKey("trackLabels", "me")
    /** Which cars are drawn bigger: "driver" (the followed car), "team" (both of the followed team's) or "none". */
    val TRACK_HIGHLIGHT = TextKey("trackHighlight", "driver")
    /** Fade every car but the highlighted ones. */
    val TRACK_DIM = FlagKey("trackDim", false)
    /** "small", "medium" or "large". */
    val CAR_SIZE = TextKey("carSize", "medium")
    /** "thin", "normal" or "bold". */
    val TRACK_WIDTH = TextKey("trackWidth", "normal")
    /** How far the map is turned clockwise, in degrees: "0", "90", "180" or "270". */
    val TRACK_TURN = TextKey("trackTurn", "0")
    /** What goes beside the map: "pit" (cars in the pits and out), "top3", "around" (the cars either side of the followed one) or "none". */
    val TRACK_SIDE = TextKey("trackSide", "pit")
    val START_LINE = FlagKey("startLine", true)
    val CORNERS = FlagKey("corners", false)
    /** The track drawn in the flag's colour while a flag is out. */
    val TRACK_FLAG = FlagKey("trackFlag", true)
    val ROWS = IntKey("rows", 10)
    /** "auto" (the car ahead in a race, the leader otherwise), "leader" for the gap to the leader, "ahead" for the gap to the car in front. */
    val GAP = TextKey("gap", "auto")
    val FAVOURITE = TextKey("favourite", "")
    val FAV_TEAM = TextKey("favTeam", "")
    val SHOW_FLAG = FlagKey("showFlag", true)
    /** A thin border in the flag's colour round the widget while a flag is out. */
    val BORDER = FlagKey("border", false)
    /** The followed team's colour in place of the accent colour. */
    val TEAM_ACCENT = FlagKey("teamAccent", false)
    val TYRES = FlagKey("showTyres", false)
    val GAINED = FlagKey("showGained", false)
    val NEWS = FlagKey("showNews", false)
    /** "code" (VER), "number" (1) or "surname" (VERSTAPPEN). */
    val NAMES = TextKey("names", "code")
    val ACCENT = ColourKey("accent", 0xFFE10600.toInt())

    override val id = "f1live"
    override val label = "F1 live session"
    override val blurb = "Live timing while a session runs, then its result until the next"
    override val width = 200
    override val height = 300
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = buildList {
        val layout = settings[LAYOUT]
        val track = layout == "track"
        add(LayoutField("Layout", LAYOUT, listOf("tower" to "Tower", "glance" to "Glance", "focus" to "Focus", "line" to "One line", "track" to "Track map")))
        if (layout == "tower") add(SliderField("Rows", ROWS, 3..22))
        if (layout == "tower") add(ChoiceField("Gap", GAP, listOf("auto" to "Automatic", "leader" to "To the leader", "ahead" to "To the car ahead")))
        if (track) {
            add(ChoiceField("Car labels", TRACK_LABELS, listOf("me" to "My driver", "leader" to "My driver and leader", "top3" to "Top 3", "all" to "Everyone", "none" to "None")))
            add(ChoiceField("Highlight", TRACK_HIGHLIGHT, listOf("driver" to "My driver", "team" to "My team", "none" to "None")))
            add(ToggleField("Dim other cars", TRACK_DIM))
            add(ChoiceField("Car size", CAR_SIZE, listOf("medium" to "Medium", "small" to "Small", "large" to "Large")))
            add(ChoiceField("Track width", TRACK_WIDTH, listOf("normal" to "Normal", "thin" to "Thin", "bold" to "Bold")))
            add(ChoiceField("Turn the map", TRACK_TURN, listOf("0" to "As drawn", "90" to "90°", "180" to "180°", "270" to "270°")))
            add(ChoiceField("Beside the map", TRACK_SIDE, listOf("pit" to "Pit and out", "top3" to "Top 3", "around" to "Around my driver", "none" to "Nothing")))
        }
        add(ChoiceField("Favourite driver", FAVOURITE, listOf("" to "My driver (from Home)", "none" to "None") + F1Roster.drivers.map { (code, name) -> code to "$code · $name" }))
        add(ChoiceField("Favourite team", FAV_TEAM, listOf("" to "My team (from Home)", "none" to "None") + F1Roster.teams))
        add(ToggleField("Team colour as accent", TEAM_ACCENT))
        add(ChoiceField("Names", NAMES, listOf("code" to "Codes", "number" to "Numbers", "surname" to "Surnames")))
        // Only the chips this layout acts on: the glance is the flag, so it cannot hide it; only the tower and the focus have a tyre to show.
        add(
            ShowField(
                "Show",
                buildList {
                    if (layout != "glance") add(SHOW_FLAG to "Flag and clock")
                    add(BORDER to "Flag border")
                    if (track) {
                        add(NEWS to "Latest news")
                        add(START_LINE to "Start line")
                        add(CORNERS to "Corner numbers")
                        add(TRACK_FLAG to "Track in flag colour")
                    } else {
                        if (layout == "tower" || layout == "focus") add(TYRES to "Tyres")
                        add(GAINED to "Places gained")
                        add(NEWS to "Latest news")
                    }
                },
            ),
        )
        add(Common.timeFormatField())
        add(ColourField("Accent colour", ACCENT))
        add(Common.colourField)
        add(Common.brightnessField)
    }

    override fun note(settings: Settings) =
        "Live timing from Formula 1's own live-timing feed, which is unofficial and may stop working; results between sessions from it or from OpenF1. " +
            (if (settings[LAYOUT] == "track") "On the map each car is placed from the last of the timing's mini-sectors it went through, so it is close but not exact; track outlines from MultiViewer. " else "") +
            "Connects only while a session runs; the broadcast delay set under F1 on the Home screen holds it back to match the TV. Not affiliated with Formula 1."

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val editing = LocalEditing.current
        // Held back to line up with the TV; the clock is set back with it, so countdowns and cars on the map match what is shown.
        val delaySeconds by LocalBroadcastDelay.current.collectAsStateWithLifecycle()
        val delayMs = if (editing) 0L else delaySeconds * 1000L
        val held = remember(feeds.f1Live, delayMs) { feeds.f1Live.heldBack(delayMs, feeds.f1History) }
        val state by held.collectAsStateWithLifecycle(feeds.f1Live.value)
        val lag = Duration.ofMillis(delayMs)
        val minute by feeds.minute.collectAsStateWithLifecycle()
        val app by LocalF1Favourite.current.collectAsStateWithLifecycle()
        val zone = ZoneId.systemDefault()
        val driver = effectiveFavourite(settings[FAVOURITE], app.driver)
        val team = effectiveFavourite(settings[FAV_TEAM], app.team)
        val h24 = Common.use24Hour(settings)
        // The editor and its previews have no session between sessions; a made-up one stands in so every layout can be seen.
        val shown: Pair<LiveSession, Boolean>? = when (val s = state) {
            F1LiveState.Waiting -> if (editing) F1LiveSample.race to true else null
            is F1LiveState.Result -> s.session to false
            is F1LiveState.Live -> s.session to true
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val (session, live) = shown ?: return@BoxWithConstraints
            val sample = state == F1LiveState.Waiting
            val onMap = settings[LAYOUT] == "track"
            // Seconds only matter where a clock is counting down on screen, or cars are moving round the map; everywhere else the minute tick is enough.
            val counting = live && session.running && (onMap || session.clockRunning && !session.isRace && session.remaining != null)
            val now = when {
                sample -> F1LiveSample.AT
                counting -> {
                    val second by feeds.second.collectAsStateWithLifecycle()
                    second.atZone(zone).toInstant() - lag
                }
                else -> minute.atZone(zone).toInstant() - lag
            }
            val followed = followedRow(session, driver, team)
            val noticed = if (live && !sample) {
                val tracker = remember { FollowTracker() }
                remember(session, followed?.code) { tracker.observe(session, followed?.code.orEmpty(), now) }
            } else null
            val calendar = if (!live || onMap) feeds.f1.collectAsStateWithLifecycle().value else null
            val next = if (live) null else calendar?.let { nextSession(it, minute.atZone(zone).toInstant()) }
            // The map's outline: the one fetched for this session, else the race-weekend widget's for this weekend, else the sample's in the editor.
            val track = if (!onMap) null else session.track
                ?: (calendar as? F1State.Ready)?.data?.track?.takeIf { live }
                ?: F1LiveSample.track.takeIf { sample }
            val colour = if (settings[TEAM_ACCENT]) followed?.teamColour else null
            val styled = if (colour != null) settings.with(ACCENT, colour.toInt()) else settings
            LiveFace(
                Board(
                    styled, session, live, session.phase(now), now, h24, driver, team, followed?.code.orEmpty(), next,
                    newsToShow(session, noticed, now, settings[NAMES]).takeIf { live }, track,
                ),
                constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
            )
        }
    }
}

/** "6:59", "1:02:03". */
internal fun clockText(left: Duration): String {
    val s = left.seconds.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * What the clock line says: "Lap 23/62" in a race (and "3 to go" near the end), "Q2 · 6:59 left" in
 * qualifying, "32:10 left" in practice.
 */
internal fun sessionProgress(session: LiveSession, now: Instant): String {
    if (session.isRace) {
        val lap = session.lap ?: return ""
        val toGo = session.lapsToGo
        return when {
            toGo == 0 -> "Final lap"
            toGo != null && toGo <= 3 -> "$toGo to go"
            session.totalLaps != null -> "Lap $lap/${session.totalLaps}"
            else -> "Lap $lap"
        }
    }
    val left = session.timeLeft(now)?.let { clockText(it) + " left" }.orEmpty()
    val part = session.part?.let { (if (session.name.startsWith("Sprint")) "SQ" else "Q") + it }
    return listOfNotNull(part, left.ifEmpty { null }).joinToString(" · ")
}

/** How far through a race it is, 0 to 1; null outside a race or before the lap count is known. */
internal fun raceFraction(session: LiveSession): Float? {
    val lap = session.lap ?: return null
    val total = session.totalLaps ?: return null
    return if (session.isRace) (lap.toFloat() / total).coerceIn(0f, 1f) else null
}

internal val Amber = Color(0xFFFFB000)
internal val FlagYellow = Color(0xFFFFD60A)
internal val FlagRed = Color(0xFFE10600)
internal val FlagGreen = Color(0xFF3FB950)
internal val Gain = Color(0xFF3FB950)
internal val Loss = Color(0xFFF2766B)

/** What the status bar says: [label] in [shade], [detail] beside it; [filled] when the shade is the bar's background, as for a flag. */
internal data class Status(val label: String, val detail: String, val shade: Color, val filled: Boolean)

internal fun timeText(at: Instant?, h24: Boolean): String =
    at?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US)).orEmpty()

/** Where [session] stands, in the words and colour of a flag. */
internal fun statusOf(session: LiveSession, phase: LivePhase, now: Instant, h24: Boolean): Status {
    val progress = sessionProgress(session, now)
    return when (phase) {
        LivePhase.PreStart -> {
            val minutes = session.start?.let { Duration.between(now, it).toMinutes() }
            val label = if (minutes != null && minutes in 0..59) "Starts in $minutes min" else if (session.start != null) "Starts ${timeText(session.start, h24)}" else "Starting soon"
            Status(label, "", Muted, false)
        }
        LivePhase.Delayed -> Status(
            "Delayed", session.restart?.let { "${session.restartLabel.ifEmpty { "Starts" }} ${timeText(it, h24)}" }.orEmpty(), Amber, true,
        )
        LivePhase.Break -> Status("Break", session.restart?.let { "${session.restartLabel.ifEmpty { "Resumes" }} ${timeText(it, h24)}" }.orEmpty(), Muted, false)
        LivePhase.Running -> if (session.flag == TrackFlag.Yellow) Status("Yellow flag", progress, FlagYellow, true) else Status("Green flag", progress, FlagGreen, false)
        LivePhase.SafetyCar -> Status("Safety car", progress, Amber, true)
        LivePhase.VirtualSafetyCar -> Status(if (session.flag == TrackFlag.VscEnding) "VSC ending" else "VSC", progress, Amber, true)
        LivePhase.Red -> Status("Red flag", session.restart?.let { "Resumes ${timeText(it, h24)}" } ?: progress, FlagRed, true)
        LivePhase.Finished -> Status("Finished", "Provisional", Muted, false)
        LivePhase.Final -> Status("Final", "", Muted, false)
    }
}

/** "Qualifying in 4 h 12 m", or after a race "Japanese GP in 6 d 2 h"; null when nothing is scheduled. */
internal fun nextSession(calendar: F1State, now: Instant): String? {
    val races = when (calendar) {
        is F1State.Ready -> calendar.data.races
        is F1State.Failed -> calendar.last?.races
        F1State.Loading -> null
    } ?: return null
    val race = races.firstOrNull { r -> r.sessions.any { it.start.isAfter(now) } } ?: return null
    val session = race.sessions.first { it.start.isAfter(now) }
    val sameWeekend = race.sessions.any { !it.start.isAfter(now) }
    val what = if (sameWeekend) shortKind(session.kind) else race.name
    return "$what in ${countdownText(now, session.start)}"
}
