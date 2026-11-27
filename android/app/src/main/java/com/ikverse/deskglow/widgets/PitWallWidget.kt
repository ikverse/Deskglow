package com.ikverse.deskglow.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.F1Data
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.LocalBroadcastDelay
import com.ikverse.deskglow.data.LocalF1Favourite
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.countdownText
import com.ikverse.deskglow.data.heldBack
import com.ikverse.deskglow.data.raceOf
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.IntKey
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What every PitWall screen draws with: [u] is pixels to a canvas unit, so sizes are the same in the editor as on the display. */
internal class PitLook(
    val u: Float,
    val colourArgb: Int,
    val accentArgb: Int,
    /** False in the editor, whose samples stand still. */
    val animate: Boolean,
    val h24: Boolean,
    /** The favourite driver (a code) and team (an id), or empty when none is to be lit. */
    val driver: String,
    val team: String,
    val rows: Int,
    val landscape: Boolean,
    /** PitWall has turned to this screen from another since it came up, so the screen may make an entrance. */
    val entered: Boolean,
    val settings: Settings,
) {
    val colour = Color(colourArgb)
    val accent = Color(accentArgb)
}

/**
 * Formula 1 on the whole screen, changing with the calendar: a session live on the circuit's map with
 * the order and race control; the weekend between its sessions; the race to come and the tables
 * between weekends; and between seasons, the season gone by and the days to the next.
 */
object PitWallWidget : WidgetType {
    val ACCENT = ColourKey("accent", 0xFFE10600.toInt())
    val HIGHLIGHT = FlagKey("highlight", true)
    val ROWS = IntKey("rows", 10)
    /** How far everything is kept in from the edges, in canvas units, clear of a screen's rounded corners. */
    val PADDING = IntKey("padding", 16)
    val SHOW_REVIEW = FlagKey("showReview", true)
    val SHOW_HONOUR = FlagKey("showHonour", true)
    val SHOW_CALENDAR = FlagKey("showCalendar", true)
    /** Which screen the editor shows: "auto" (whatever the calendar says), "live", "weekend", "between" or "offseason". The display always follows the calendar. */
    val PREVIEW = TextKey("preview", "auto")

    override val id = "f1pitwall"
    override val label = "F1 PitWall"
    override val blurb = "The whole screen: live sessions on the track map, the next race, the season"
    override val width = Orientation.Landscape.width
    override val height = Orientation.Landscape.height
    override val fullCanvas = true
    override val smallText = false
    override val mainGrows = false
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = buildList {
        add(ChoiceField("Preview in the editor", PREVIEW, listOf("auto" to "Automatic", "live" to "Live session", "weekend" to "Race weekend", "between" to "Between races", "offseason" to "Off-season")))
        add(SliderField("Rows in the order", ROWS, 5..12))
        add(ToggleField("Highlight my driver and team", HIGHLIGHT))
        add(ShowField("Off-season", listOf(SHOW_REVIEW to "Season in review", SHOW_HONOUR to "Past champions", SHOW_CALENDAR to "Next calendar")))
        add(SliderField("Edge padding", PADDING, 0..40))
        add(Common.timeFormatField())
        add(ColourField("Accent colour", ACCENT))
        add(Common.colourField)
        add(Common.brightnessField)
    }

    override fun note(settings: Settings) =
        "Fills the screen, so it has a screen to itself. Live timing from Formula 1's own live-timing feed, which is unofficial and may stop working; " +
            "on the map each car is placed from the last of the timing's mini-sectors it went through, so it is close but not exact. " +
            "Calendar, results and past seasons from Jolpica; track outlines from MultiViewer. " +
            "The favourite driver and team, and the broadcast delay, are set under F1 on the Home screen. Not affiliated with Formula 1."

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val editing = LocalEditing.current
        // Held back to line up with the TV; the clock is set back with it, so countdowns and cars on the map match what is shown.
        val delaySeconds by LocalBroadcastDelay.current.collectAsStateWithLifecycle()
        val delayMs = if (editing) 0L else delaySeconds * 1000L
        val held = remember(feeds.f1Live, delayMs) { feeds.f1Live.heldBack(delayMs, feeds.f1History) }
        val liveState by held.collectAsStateWithLifecycle(feeds.f1Live.value)
        val lag = Duration.ofMillis(delayMs)
        val calendar by feeds.f1.collectAsStateWithLifecycle()
        val seasons by feeds.f1Seasons.collectAsStateWithLifecycle()
        val minute by feeds.minute.collectAsStateWithLifecycle()
        val app by LocalF1Favourite.current.collectAsStateWithLifecycle()
        val zone = ZoneId.systemDefault()
        val h24 = Common.use24Hour(settings)
        val data: F1Data? = when (val c = calendar) {
            is F1State.Ready -> c.data
            is F1State.Failed -> c.last
            F1State.Loading -> null
        }
        val byMinute = minute.atZone(zone).toInstant() - lag
        val real = PitWallScene(pitWallMode(data, liveState, byMinute, seasons?.next.orEmpty()), data, seasons)
        // The editor can show any screen, made up, whatever the calendar says; and has a made-up session when there is nothing at all to show.
        val scene = if (!editing) real else PitWallSample.of(settings[PREVIEW]) ?: real.takeIf { it.mode != PitWallMode.Empty } ?: PitWallSample.live
        val mode = scene.mode
        // Seconds only where something on screen moves by the second: cars on the map, or a countdown in its last hour.
        val soon: Instant? = when (mode) {
            is PitWallMode.Weekend -> mode.next?.start
            is PitWallMode.Between -> mode.next.sessions.firstOrNull { it.start.isAfter(byMinute) }?.start
            else -> null
        }
        val counting = scene.sampleAt == null && when (mode) {
            is PitWallMode.Live -> mode.session.running
            else -> soon != null && Duration.between(byMinute, soon) < Duration.ofHours(1)
        }
        val now = scene.sampleAt ?: if (counting) {
            val second by feeds.second.collectAsStateWithLifecycle()
            second.atZone(zone).toInstant() - lag
        } else byMinute

        val kind = kindOf(scene)
        val first = remember { kind }
        val switched = remember { booleanArrayOf(false) }
        if (kind != first) switched[0] = true
        // The last scene of each kind, so the screen fading out goes on showing what it showed.
        val scenes = remember { HashMap<String, PitWallScene>() }
        scenes[kind] = scene
        val sizes = LocalTextSizes.current

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val landscape = w >= h
            val unit = w / (if (landscape) Orientation.Landscape.width else Orientation.Portrait.width)
            val look = PitLook(
                unit * sizes.main, settings[Common.COLOUR], settings[ACCENT], !editing, h24,
                if (settings[HIGHLIGHT]) app.driver else "", if (settings[HIGHLIGHT]) app.team else "",
                settings[ROWS].coerceIn(5, 12), landscape, switched[0], settings,
            )
            // One screen fades into the next. The live session and its weekend are one screen: the cars leave, the map stays.
            AnimatedContent(kind, Modifier.fillMaxSize(), transitionSpec = { pitFade(look.animate, 500) }, label = "PitWall screen") { shownKind ->
                val shown = scenes[shownKind] ?: scene
                Box(Modifier.fillMaxSize().padding(pxToDp(unit * settings[PADDING].coerceIn(0, 40)))) {
                    val sample = shown.sampleAt != null
                    when (val m = shown.mode) {
                        is PitWallMode.Live -> PitWallLiveFace(look, m.session, true, now, trackFor(m.session, shown.data, live = true, sample), null)
                        is PitWallMode.Weekend -> {
                            val result = m.result
                            val next = m.next?.let { "${shortKind(it.kind)} in ${countdownText(now, it.start)}" }
                            if (result != null) PitWallLiveFace(look, result, false, now, trackFor(result, shown.data, live = false, sample), next)
                            else if (shown.data != null) PitWallBetweenFace(look, m.race, shown.data, null, now, shown.data.track)
                        }
                        is PitWallMode.Between -> if (shown.data != null) PitWallBetweenFace(look, m.next, shown.data, m.last, now, shown.data.track)
                        is PitWallMode.OffSeason ->
                            PitWallOffSeasonFace(look, m.season, m.first, shown.seasons, shown.data, now, now.epochSecond / 60)
                        PitWallMode.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Waiting for the F1 calendar", color = Muted, fontSize = pxToSp(unit * 13f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    /** Which of PitWall's screens [scene] is drawn as; a change of this is what fades over. */
    private fun kindOf(scene: PitWallScene): String = when (val m = scene.mode) {
        is PitWallMode.Live -> "track"
        is PitWallMode.Weekend -> if (m.result != null) "track" else "between"
        is PitWallMode.Between -> "between"
        is PitWallMode.OffSeason -> "offseason"
        PitWallMode.Empty -> "empty"
    }

    /**
     * The map's outline: the one fetched for this session, else the calendar's for this weekend (for a
     * result, only while that is still this result's circuit), else the sample's for a made-up scene.
     */
    private fun trackFor(session: com.ikverse.deskglow.data.LiveSession, data: F1Data?, live: Boolean, sample: Boolean) =
        session.track
            ?: data?.let { d -> d.track?.takeIf { t -> live || raceOf(d.races, session)?.let(t::isFor) == true } }
            ?: F1LiveSample.track.takeIf { sample }
}
