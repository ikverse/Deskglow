package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.F1Data
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1Race
import com.ikverse.deskglow.data.F1Result
import com.ikverse.deskglow.data.F1Roster
import com.ikverse.deskglow.data.F1Session
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.F1Track
import com.ikverse.deskglow.data.LivePhase
import com.ikverse.deskglow.data.LocalF1Favourite
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.SessionTop
import com.ikverse.deskglow.data.WeekendView
import com.ikverse.deskglow.data.countdownText
import com.ikverse.deskglow.data.effectiveFavourite
import com.ikverse.deskglow.data.favouriteColour
import com.ikverse.deskglow.data.sessionTop
import com.ikverse.deskglow.data.weekendViewWithFeed
import com.ikverse.deskglow.data.feedStatus
import com.ikverse.deskglow.data.teamColour
import com.ikverse.deskglow.data.weekendView
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

object F1WeekendWidget : WidgetType {
    /** "classic", "hero", "countdown", "watermark" or "minimal". */
    val LAYOUT = TextKey("layout", "classic")
    /** Only Classic and Countdown let the track be turned off; Hero and Watermark are built round it, One line has no room. */
    val SHOW_TRACK = FlagKey("showTrack", false)
    val FAVOURITE = TextKey("favourite", "")
    val ACCENT = ColourKey("accent", 0xFFE10600.toInt())
    /** "countdown", or "top3" for the last session's top 3 until an hour before the next. */
    val BETWEEN = TextKey("between", "countdown")
    /** "next" session, or the "race" only. */
    val COUNT_TO = TextKey("countTo", "next")
    /** The session's start time, in the phone's zone, beside its countdown. */
    val SHOW_START = FlagKey("showStart", false)
    /** The followed team's colour in place of the accent colour. */
    val TEAM_ACCENT = FlagKey("teamAccent", false)

    private val LAYOUTS = listOf(
        "classic" to "Classic", "hero" to "Hero · big track", "countdown" to "Countdown blocks",
        "watermark" to "Track behind", "minimal" to "One line",
    )
    private val TRACK_OPTIONAL = setOf("classic", "countdown")

    override val id = "f1weekend"
    override val label = "F1 race weekend"
    override val blurb = "Countdown and podium"
    override val width = 372
    override val height = 112
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = buildList {
        add(LayoutField("Layout", LAYOUT, LAYOUTS))
        if (settings[LAYOUT] in TRACK_OPTIONAL) add(ToggleField("Show the track", SHOW_TRACK))
        add(ChoiceField("Between sessions", BETWEEN, listOf("countdown" to "Countdown", "top3" to "Latest session's top 3")))
        add(ChoiceField("Count down to", COUNT_TO, listOf("next" to "Next session", "race" to "Race only")))
        add(ToggleField("Show the start time", SHOW_START))
        if (settings[SHOW_START]) add(Common.timeFormatField())
        add(ChoiceField("Favourite driver", FAVOURITE, listOf("" to "My driver (from Home)", "none" to "None") + F1Roster.drivers.map { (code, name) -> code to "$code · $name" }))
        add(ToggleField("Team colour as accent", TEAM_ACCENT))
        add(ColourField("Accent colour", ACCENT))
        add(Common.colourField)
        add(Common.brightnessField)
    }

    override fun note(settings: Settings) =
        "F1 data from the Jolpica F1 API (api.jolpi.ca), track outlines from OpenF1 and MultiViewer, times in your phone's time zone. " +
            (if (settings[BETWEEN] == "top3") "Session results from Formula 1's own live-timing feed, which is unofficial and may stop working, or from OpenF1; it connects only while a session runs. " else "") +
            "Not affiliated with Formula 1."

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val state by feeds.f1.collectAsStateWithLifecycle()
        val minute by feeds.minute.collectAsStateWithLifecycle()
        val app by LocalF1Favourite.current.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val data = when (val s = state) {
                F1State.Loading -> return@BoxWithConstraints EditorHint("Loading the F1 calendar…", h * 0.15f)
                is F1State.Failed -> s.last ?: return@BoxWithConstraints EditorHint("No F1 data yet", h * 0.15f)
                is F1State.Ready -> s.data
            }
            val roughNow = minute.atZone(ZoneId.systemDefault()).toInstant()
            // Seconds only matter in the last hour before a session, so only then is the second tick read.
            val view = weekendView(data, roughNow)
            val fine = view is WeekendView.Upcoming && view.next != null && Duration.between(roughNow, view.next.start) < Duration.ofMinutes(61)
            val now = if (fine) {
                val second by feeds.second.collectAsStateWithLifecycle()
                second.atZone(ZoneId.systemDefault()).toInstant()
            } else roughNow
            // The live-timing feed says whether a session is on, delayed or stopped, and has the results; it is asked for only a few topics, and only while a session runs.
            val pulse by feeds.f1Pulse.collectAsStateWithLifecycle()
            val latest = if (settings[BETWEEN] == "top3") (pulse as? F1LiveState.Result)?.session else null
            val counted = weekendView(data, now).let { v ->
                if (settings[COUNT_TO] == "race" && v is WeekendView.Upcoming) v.copy(next = v.race.race.takeIf { it.start.isAfter(now) }) else v
            }
            val shown = weekendViewWithFeed(counted, data.races, feedStatus(data.races, pulse, now))
            val favourite = effectiveFavourite(settings[FAVOURITE], app.driver)
            val styled = settings.with(FAVOURITE, favourite).let { s ->
                if (settings[TEAM_ACCENT]) favouriteColour(data, favourite, "")?.let { s.with(ACCENT, it) } ?: s else s
            }
            WeekendFace(styled, data, shown, sessionTop(data.races, latest, now), now)
        }
    }
}

/** The widget's colours and the favourite driver, passed down together. */
private class Look(val colour: Color, val accent: Color, val favourite: String, val start: ((F1Session) -> String)? = null, val note: String? = null)

/** The badge for a session on: "LIVE", or what the feed says it is. */
private fun badgeOf(view: WeekendView.Upcoming, look: Look): Pair<String, Color> = when (view.feed?.phase) {
    LivePhase.Delayed -> "DELAYED" to Amber
    LivePhase.Red -> "RED FLAG" to FlagRed
    LivePhase.SafetyCar -> "SAFETY CAR" to Amber
    LivePhase.VirtualSafetyCar -> "VSC" to Amber
    else -> "LIVE" to look.accent
}

/** "Sat 16:00": when [session] starts, in the phone's time zone. */
private fun startLabel(session: F1Session, h24: Boolean): String =
    session.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (h24) "EEE HH:mm" else "EEE h:mm a", Locale.US))

/** " · SAT 16:00" for a caption, or nothing when the start time is not asked for. */
private fun startPart(look: Look, session: F1Session): String = look.start?.let { " · " + it(session).uppercase() }.orEmpty()

/** One place on a podium as drawn, from a race result or from a session's timing. */
private class Place(val position: Int, val code: String, val team: Color)

private fun placesOf(result: F1Result) = result.podium.map { Place(it.position, it.id, Color(teamColour(it.teamId))) }

private fun placesOf(top: SessionTop) = top.rows.map { Place(it.position, it.code, Color(it.teamColour ?: 0xFF8C8C8C)) }

/** Whether the session after [top]'s is in the same weekend. */
private fun sameWeekend(top: SessionTop) = top.nextRace == top.race

/** "Next · Race · in 18 h 40 m", or after a race "Next · Japanese GP · in 6 d 2 h"; null at the end of the season. */
private fun nextLine(top: SessionTop, now: Instant): String? {
    val next = top.next ?: return null
    val what = if (sameWeekend(top)) shortKind(next.kind) else top.nextRace?.name ?: return null
    return "Next · $what · in ${countdownText(now, next.start)}"
}

/** "1 NOR  2 PIA  3 VER". */
private fun placesText(places: List<Place>) = places.joinToString("  ") { "${it.position} ${it.code}" }

/**
 * The chosen layout, with the circuit drawn in the widget's own colour where the layout has one and its
 * outline has arrived. [sessionTop], when given, is shown in place of the countdown until a race's podium is in.
 */
@Composable
private fun WeekendFace(settings: Settings, data: F1Data, view: WeekendView, sessionTop: SessionTop?, now: Instant) {
    val h24 = Common.use24Hour(settings)
    val startText: ((F1Session) -> String)? = if (settings[F1WeekendWidget.SHOW_START]) ({ session: F1Session -> startLabel(session, h24) }) else null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        val w = constraints.maxWidth.toFloat()
        // A delayed session says when it is to start, as race control has announced it.
        val note = (view as? WeekendView.Upcoming)?.feed?.takeIf { it.phase == LivePhase.Delayed }?.live?.let { l ->
            l.restart?.let { "${l.restartLabel.ifEmpty { "Starts" }} ${timeText(it, h24)}" }
        }
        val look = Look(Color(settings[Common.COLOUR]), Color(settings[F1WeekendWidget.ACCENT]), settings[F1WeekendWidget.FAVOURITE], startText, note)
        // A race's podium, once it has arrived, wins over its top 3 from timing.
        val top = sessionTop?.takeIf { view !is WeekendView.AfterRace }
        if (view == WeekendView.Empty && top == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Text("No races scheduled", color = Muted, fontSize = pxToSp(min(h * 0.2f, w * 0.05f)), maxLines = 1)
            }
            return@BoxWithConstraints
        }
        val layout = settings[F1WeekendWidget.LAYOUT]
        val wantsTrack = when (layout) {
            "hero", "watermark" -> true
            "minimal" -> false
            else -> settings[F1WeekendWidget.SHOW_TRACK]
        }
        val track = data.track?.takeIf { wantsTrack }
        when (layout) {
            "hero" -> BesideTrack(track, look.colour, w, h, lead = true, share = 0.45f) { cw, ch -> HeroContent(view, top, now, look, cw, ch) }
            "countdown" -> BesideTrack(track, look.colour, w, h, lead = false, share = 0.3f) { cw, ch -> CountdownContent(view, top, data, now, look, cw, ch) }
            "watermark" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (track != null) TrackOutline(track, look.colour.copy(alpha = 0.16f), Modifier.fillMaxSize(), strokeShare = 0.05f)
                ClassicContent(view, top, data, now, look, w, h, centred = true, spread = true)
            }
            "minimal" -> MinimalContent(view, top, now, look, w, h)
            else -> BesideTrack(track, look.colour, w, h, lead = false, share = 0.32f) { cw, ch -> ClassicContent(view, top, data, now, look, cw, ch, centred = false, spread = track == null) }
        }
    }
}

/**
 * [content] with the circuit right beside it: in a box wider than it is tall, before it ([lead]) or
 * after it, taking at most [share] of the width; in a taller one, above or below it. The words take
 * only the room they need, so the track sits a short gap from them rather than at the far edge.
 * [content] is told the width and height left for it.
 */
@Composable
private fun BesideTrack(track: F1Track?, colour: Color, w: Float, h: Float, lead: Boolean, share: Float, content: @Composable (Float, Float) -> Unit) {
    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { content(w, h) }
        return
    }
    if (w >= h) {
        val gap = h * 0.14f
        val trackW = min(h * 0.9f * track.aspect, w * share)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (lead) {
                TrackOutline(track, colour, Modifier.width(pxToDp(trackW)).fillMaxHeight())
                Spacer(Modifier.width(pxToDp(gap)))
            }
            Box(Modifier.weight(1f, fill = false)) { content(w - trackW - gap, h) }
            if (!lead) {
                Spacer(Modifier.width(pxToDp(gap)))
                TrackOutline(track, colour, Modifier.width(pxToDp(trackW)).fillMaxHeight())
            }
        }
    } else {
        val gap = w * 0.06f
        val trackH = min(w / track.aspect, h * (share + 0.13f))
        val trackBox = Modifier.width(pxToDp(trackH * track.aspect)).height(pxToDp(trackH))
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            if (lead) {
                TrackOutline(track, colour, trackBox)
                Spacer(Modifier.height(pxToDp(gap)))
            }
            Box(Modifier.weight(1f, fill = false)) { content(w, h - trackH - gap) }
            if (!lead) {
                Spacer(Modifier.height(pxToDp(gap)))
                TrackOutline(track, colour, trackBox)
            }
        }
    }
}

/**
 * The weekend and its countdown or session running, the last session's top 3, or the last podium.
 * [centred] for the Watermark layout; [spread] lays the podium across the full width instead of close together.
 */
@Composable
private fun ClassicContent(view: WeekendView, top: SessionTop?, data: F1Data, now: Instant, look: Look, w: Float, h: Float, centred: Boolean, spread: Boolean) {
    val small = min(h * 0.15f, w * 0.042f)
    val big = min(h * 0.34f, w * 0.09f)
    Column(horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start) {
        if (top != null) {
            PodiumBlock(top.race.name, shortKind(top.session.kind), placesOf(top), nextLine(top, now), look, small, big, spread)
            return@Column
        }
        when (view) {
            WeekendView.Empty -> {}
            is WeekendView.AfterRace -> Podium(view.result, view.next, data, now, look, small, big, spread)
            is WeekendView.Upcoming -> {
                Header(view.race.name, view.race.place, look.accent, look.colour, small)
                Spacer(Modifier.height(pxToDp(h * 0.05f)))
                SessionLine(view, now, look, small, big)
            }
        }
    }
}

/** "● LIVE Qualifying", or "FP2  3 h 12 m". */
@Composable
private fun SessionLine(view: WeekendView.Upcoming, now: Instant, look: Look, small: Float, big: Float) {
    val live = view.live
    val next = view.next
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (live != null) {
            val (badge, shade) = badgeOf(view, look)
            LiveBadge(shade, small, badge)
            Spacer(Modifier.width(pxToDp(small * 0.6f)))
            Text(live.kind, color = look.colour, fontSize = pxToSp(big), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            look.note?.let {
                Spacer(Modifier.width(pxToDp(small * 0.6f)))
                Text(it, color = Muted, fontSize = pxToSp(small), maxLines = 1, softWrap = false)
            }
        } else if (next != null) {
            Text(next.kind, color = Muted, fontSize = pxToSp(big * 0.62f), maxLines = 1, softWrap = false)
            Spacer(Modifier.width(pxToDp(big * 0.3f)))
            Text(countdownText(now, next.start), color = look.colour, fontSize = pxToSp(big), fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
            look.start?.let { start ->
                Spacer(Modifier.width(pxToDp(big * 0.3f)))
                Text(start(next), color = Muted, fontSize = pxToSp(big * 0.45f), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Name and place over the session next and a large countdown; after a session or a race, its top 3 as a short table. */
@Composable
private fun HeroContent(view: WeekendView, top: SessionTop?, now: Instant, look: Look, w: Float, h: Float) {
    val small = min(h * 0.12f, w * 0.06f)
    val big = min(h * 0.34f, w * 0.19f)

    @Composable
    fun Table(title: String, subtitle: String, places: List<Place>, after: String?) {
        Text(title.uppercase(), color = look.colour, fontSize = pxToSp(small * 1.15f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Muted, fontSize = pxToSp(small * 0.95f), maxLines = 1)
        Spacer(Modifier.height(pxToDp(h * 0.04f)))
        places.forEach { PodiumEntry(it, look, big * 0.5f) }
        if (after != null) {
            Spacer(Modifier.height(pxToDp(h * 0.03f)))
            Text(after, color = Muted, fontSize = pxToSp(small * 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    Column {
        if (top != null) {
            Table(top.race.name, shortKind(top.session.kind), placesOf(top), nextLine(top, now))
            return@Column
        }
        when (view) {
            WeekendView.Empty -> {}
            is WeekendView.Upcoming -> {
                Text(view.race.name.uppercase(), color = look.colour, fontSize = pxToSp(small * 1.15f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                if (view.race.place.isNotEmpty()) Text(view.race.place, color = Muted, fontSize = pxToSp(small * 0.95f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(pxToDp(h * 0.07f)))
                val live = view.live
                val next = view.next
                if (live != null) {
                    val (badge, shade) = badgeOf(view, look)
                    LiveBadge(shade, small, badge)
                    Text(live.kind, color = look.colour, fontSize = pxToSp(big * 0.8f), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                    look.note?.let { Text(it, color = Muted, fontSize = pxToSp(small * 0.95f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else if (next != null) {
                    Text((next.kind + startPart(look, next)).uppercase(), color = look.accent, fontSize = pxToSp(small * 0.9f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.em, maxLines = 1, softWrap = false)
                    Text(countdownText(now, next.start), color = look.colour, fontSize = pxToSp(big), fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
                }
            }
            is WeekendView.AfterRace -> Table(
                view.result.raceName, "Result", placesOf(view.result),
                view.next?.let { next -> "Next · ${next.name} · in ${countdownText(now, next.first.start)}" },
            )
        }
    }
}

/**
 * Large blocks counting down to the next session, or after a race to the next weekend's first one;
 * after a session or a race, its top 3 on a line underneath.
 */
@Composable
private fun CountdownContent(view: WeekendView, top: SessionTop?, data: F1Data, now: Instant, look: Look, w: Float, h: Float) {
    val small = min(h * 0.13f, w * 0.04f)
    val big = min(h * 0.36f, w * 0.11f)

    /** [race]'s name over the blocks to [next], and [line] beneath. */
    @Composable
    fun BlocksWithLine(race: F1Race, next: F1Session, line: String) {
        Header(race.name, race.place, look.accent, look.colour, small)
        Spacer(Modifier.height(pxToDp(h * 0.05f)))
        Text(("${next.kind} in" + startPart(look, next)).uppercase(), color = Muted, fontSize = pxToSp(small * 0.85f), fontWeight = FontWeight.Medium, letterSpacing = 0.1.em, maxLines = 1, softWrap = false)
        CountdownBlocks(now, next.start, look, small, big)
        Spacer(Modifier.height(pxToDp(small * 0.6f)))
        Text(line, color = Muted, fontSize = pxToSp(small * 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    Column {
        if (top != null) {
            val nextRace = top.nextRace
            val next = top.next
            val places = placesOf(top)
            if (nextRace == null || next == null) {
                PodiumBlock(top.race.name, shortKind(top.session.kind), places, null, look, small, big, spread = false)
            } else {
                val what = if (sameWeekend(top)) shortKind(top.session.kind) else top.race.name
                BlocksWithLine(nextRace, next, "$what · ${placesText(places)}")
            }
            return@Column
        }
        when (view) {
            WeekendView.Empty -> {}
            is WeekendView.Upcoming -> {
                Header(view.race.name, view.race.place, look.accent, look.colour, small)
                Spacer(Modifier.height(pxToDp(h * 0.05f)))
                val live = view.live
                val next = view.next
                if (live != null) {
                    SessionLine(view, now, look, small, big)
                } else if (next != null) {
                    Text(("${next.kind} in" + startPart(look, next)).uppercase(), color = Muted, fontSize = pxToSp(small * 0.85f), fontWeight = FontWeight.Medium, letterSpacing = 0.1.em, maxLines = 1, softWrap = false)
                    CountdownBlocks(now, next.start, look, small, big)
                }
            }
            is WeekendView.AfterRace -> {
                val next = view.next
                if (next == null) {
                    Podium(view.result, null, data, now, look, small, big, spread = false)
                } else {
                    BlocksWithLine(next, next.first, "${view.result.raceName} · ${placesText(placesOf(view.result))}")
                }
            }
        }
    }
}

@Composable
private fun CountdownBlocks(now: Instant, then: Instant, look: Look, small: Float, big: Float) {
    Row(verticalAlignment = Alignment.Bottom) {
        countdownParts(now, then).forEachIndexed { i, (value, unit) ->
            if (i > 0) Spacer(Modifier.width(pxToDp(big * 0.4f)))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, color = look.colour, fontSize = pxToSp(big), fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
                Text(unit, color = Muted, fontSize = pxToSp(small * 0.75f), fontWeight = FontWeight.Medium, letterSpacing = 0.12.em, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** "2 DAYS 04 HRS 12 MIN" as its parts; "04 HRS 12 MIN" within a day; "12 MIN 34 SEC" in the last hour. */
internal fun countdownParts(now: Instant, then: Instant): List<Pair<String, String>> {
    val s = Duration.between(now, then).seconds.coerceAtLeast(0)
    fun two(v: Long) = String.format(Locale.US, "%02d", v)
    return when {
        s >= 86_400 -> listOf("${s / 86_400}" to (if (s < 2 * 86_400) "DAY" else "DAYS"), two(s % 86_400 / 3600) to "HRS", two(s % 3600 / 60) to "MIN")
        s >= 3600 -> listOf(two(s / 3600) to "HRS", two(s % 3600 / 60) to "MIN")
        else -> listOf(two(s / 60) to "MIN", two(s % 60) to "SEC")
    }
}

/**
 * Everything on one line: "Singapore GP · Quali in 4 h 12 m", "● LIVE Quali · Singapore GP", the podium,
 * or "Quali · 1 NOR 2 PIA 3 VER · Race in 18 h 40 m".
 */
@Composable
private fun MinimalContent(view: WeekendView, top: SessionTop?, now: Instant, look: Look, w: Float, h: Float) {
    val px = min(h * 0.4f, w * 0.042f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun Part(text: String, colour: Color, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) =
                Text(text, color = colour, fontSize = pxToSp(px), fontWeight = weight, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = modifier)

            // A name that does not fit gives way to what follows it, so the countdown is never the part cut off.
            val yields = Modifier.weight(1f, fill = false)

            @Composable
            fun Places(places: List<Place>) = places.forEach { place ->
                Part("  ${place.position} ", Muted)
                Part(place.code, if (place.code == look.favourite) look.accent else look.colour, FontWeight.Medium)
            }

            if (top != null) {
                // After a race the next weekend is too far off to be worth the room, as with the podium.
                val inWeekend = sameWeekend(top)
                Part(if (inWeekend) shortKind(top.session.kind) else top.race.name, look.colour, FontWeight.SemiBold, yields)
                Part("  · ", Muted)
                Places(placesOf(top))
                val next = top.next
                if (inWeekend && next != null) Part("  ·  ${shortKind(next.kind)} in ${countdownText(now, next.start)}", Muted)
                return@Row
            }
            when (view) {
                WeekendView.Empty -> {}
                is WeekendView.Upcoming -> {
                    val live = view.live
                    val next = view.next
                    if (live != null) {
                        val (badge, shade) = badgeOf(view, look)
                        LiveBadge(shade, px * 0.75f, badge)
                        Spacer(Modifier.width(pxToDp(px * 0.45f)))
                        Part(shortKind(live.kind), look.colour, FontWeight.Medium)
                        look.note?.let { Part("  ·  $it", Muted) }
                        Part("  ·  ${view.race.name}", Muted, modifier = yields)
                    } else if (next != null) {
                        Part(view.race.name, look.colour, FontWeight.SemiBold, yields)
                        Part("  ·  ${shortKind(next.kind)} in ", Muted)
                        Part(countdownText(now, next.start), look.colour)
                    } else {
                        Part(view.race.name, look.colour, FontWeight.SemiBold, yields)
                    }
                }
                is WeekendView.AfterRace -> {
                    Part(view.result.raceName, look.colour, FontWeight.SemiBold, yields)
                    Part("  · ", Muted)
                    Places(placesOf(view.result))
                }
            }
        }
    }
}

/** "Qualifying" to "Quali", so session names fit beside times and countdowns. */
internal fun shortKind(kind: String) = kind.replace("Qualifying", "Quali")

/** The circuit as one closed line in [colour], as large as [modifier]'s box allows and centred in it. */
@Composable
private fun TrackOutline(track: F1Track, colour: Color, modifier: Modifier, strokeShare: Float = 0.035f) {
    Canvas(modifier) {
        val stroke = (min(size.width, size.height) * strokeShare).coerceAtLeast(1.5f)
        val spanW = if (track.aspect >= 1f) 1f else track.aspect
        val spanH = if (track.aspect >= 1f) 1f / track.aspect else 1f
        val scale = min((size.width - 2 * stroke) / spanW, (size.height - 2 * stroke) / spanH)
        if (scale <= 0f) return@Canvas
        val dx = (size.width - spanW * scale) / 2
        val dy = (size.height - spanH * scale) / 2
        val path = Path()
        track.points.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(dx + x * scale, dy + y * scale) else path.lineTo(dx + x * scale, dy + y * scale)
        }
        path.close()
        drawPath(path, colour, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A short upright bar in the accent colour, then the race and where it is. */
@Composable
internal fun Header(race: String, place: String, accent: Color, colour: Color, px: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(pxToDp(px * 0.28f)).height(pxToDp(px * 1.1f)).background(accent, RoundedCornerShape(pxToDp(px * 0.14f))))
        Spacer(Modifier.width(pxToDp(px * 0.5f)))
        Text(race.uppercase(), color = colour, fontSize = pxToSp(px * 1.05f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        if (place.isNotEmpty()) {
            Spacer(Modifier.width(pxToDp(px * 0.6f)))
            Text(place, color = Muted, fontSize = pxToSp(px), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
internal fun LiveBadge(accent: Color, px: Float, label: String = "LIVE") {
    Row(
        Modifier.background(accent, RoundedCornerShape(pxToDp(px * 0.3f))).padding(horizontal = pxToDp(px * 0.55f), vertical = pxToDp(px * 0.2f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(pxToDp(px * 0.5f)).background(Color.White, CircleShape))
        Spacer(Modifier.width(pxToDp(px * 0.35f)))
        Text(label, color = Color.White, fontSize = pxToSp(px * 1.05f), fontWeight = FontWeight.Bold, letterSpacing = 0.08.em, maxLines = 1, softWrap = false)
    }
}

/** One podium place: position, team-colour bar, driver code, the favourite lit. */
@Composable
private fun PodiumEntry(place: Place, look: Look, px: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${place.position}", color = Muted, fontSize = pxToSp(px * 0.73f), fontWeight = FontWeight.Light, maxLines = 1)
        Spacer(Modifier.width(pxToDp(px * 0.22f)))
        Box(Modifier.width(pxToDp(px * 0.12f)).height(pxToDp(px * 0.95f)).background(place.team, RoundedCornerShape(pxToDp(px * 0.06f))))
        Spacer(Modifier.width(pxToDp(px * 0.22f)))
        Text(
            place.code, color = if (place.code == look.favourite) look.accent else look.colour,
            fontSize = pxToSp(px), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
        )
    }
}

/**
 * The top three of the last race, each with a team-colour bar; the favourite lit; the next race below.
 * [spread] lays the three across the full width; otherwise they sit close together.
 */
@Composable
private fun Podium(result: F1Result, next: F1Race?, data: F1Data, now: Instant, look: Look, small: Float, big: Float, spread: Boolean) {
    val after = when {
        next != null -> "Next · ${next.name} · in ${countdownText(now, next.first.start)}"
        data.races.any { it.round == result.round } -> "Season complete"
        else -> null
    }
    PodiumBlock(result.raceName, "Result", placesOf(result), after, look, small, big, spread)
}

/** [title] and [subtitle] over three [places] in a row, and [after] beneath them. */
@Composable
private fun PodiumBlock(title: String, subtitle: String, places: List<Place>, after: String?, look: Look, small: Float, big: Float, spread: Boolean) {
    Header(title, subtitle, look.accent, look.colour, small)
    Spacer(Modifier.height(pxToDp(small * 0.5f)))
    Row(
        if (spread) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(pxToDp(big * 0.6f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        places.forEach { PodiumEntry(it, look, big * 0.82f) }
    }
    if (after != null) {
        Spacer(Modifier.height(pxToDp(small * 0.5f)))
        Text(after, color = Muted, fontSize = pxToSp(small), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
