package com.ikverse.deskglow.widgets

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.ikverse.deskglow.data.Crossing
import com.ikverse.deskglow.data.F1Track
import com.ikverse.deskglow.data.sectorOf
import com.ikverse.deskglow.data.timingPoints
import com.ikverse.deskglow.data.LivePhase
import com.ikverse.deskglow.data.LiveRow
import com.ikverse.deskglow.data.LiveSession
import com.ikverse.deskglow.data.gapText
import com.ikverse.deskglow.model.Settings
import java.time.Instant
import kotlin.math.min

/**
 * PitWall while a session is on, and between a weekend's sessions (then [live] is false and [session]
 * is the last one's result: the map without cars, its order, and what is [next] in place of race
 * control). On its side: the session, the lap and the flag over a large map, with the order and race
 * control's last words beside it. Upright: the same, stacked.
 */
@Composable
internal fun PitWallLiveFace(
    look: PitLook, session: LiveSession, live: Boolean, now: Instant, track: F1Track?, next: String?,
    crossings: Map<String, List<Crossing>> = session.crossings,
) {
    val u = look.u
    val followed = followedRow(session, look.driver, look.team)?.code.orEmpty()
    val phase = session.phase(now)
    val board = Board(mapSettings(look), session, live, phase, now, look.h24, look.driver, look.team, followed, next, null, track, crossings)
    val dimmed = if (session.signalLost) Modifier.alpha(0.55f) else Modifier
    if (look.landscape) {
        Row(Modifier.fillMaxSize().then(dimmed)) {
            Column(Modifier.weight(1.65f).fillMaxHeight()) {
                LiveHeader(board, look)
                LiveMap(board, look, Modifier.fillMaxWidth().weight(1f))
            }
            Spacer(Modifier.width(pxToDp(u * 20f)))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    Order(board, look, min(constraints.maxHeight.toFloat() / look.rows, u * 26f))
                }
                Spacer(Modifier.height(pxToDp(u * 8f)))
                Notes(board, look)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().then(dimmed)) {
            LiveHeader(board, look)
            LiveMap(board, look, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(pxToDp(u * 8f)))
            Order(board, look, u * 24f)
            Spacer(Modifier.height(pxToDp(u * 8f)))
            Notes(board, look)
        }
    }
}

/**
 * A session on by the calendar whose live timing has not come through yet: the weekend, the session and the
 * circuit, and that it is connecting, rather than the session before's result as if nothing were on.
 */
@Composable
internal fun PitWallConnectingFace(look: PitLook, meeting: String, session: String, track: F1Track?) {
    val u = look.u
    Column(Modifier.fillMaxSize()) {
        WeekendTitle(meeting, session, look)
        Text(
            "Connecting to live timing…", color = look.colour, fontSize = pxToSp(u * 22f), fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.height(pxToDp(u * 36f)).wrapContentHeight(),
        )
        if (track != null) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = pxToDp(u * 12f))) {
                DrawnOutline(track, look.colour.copy(alpha = 0.28f), false, Modifier.fillMaxSize(), strokeShare = 0.03f)
            }
        }
    }
}

/** "Qatar GP · Practice 2": the place in white, "GP" in the accent colour (F1 red unless changed), the session after it in grey. */
@Composable
private fun WeekendTitle(meeting: String, session: String, look: PitLook) {
    val u = look.u
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = pxToSp(u * 18f))) {
                // The place in white, and "GP" after it in the accent colour; a name without it is all white.
                val name = meeting.removeSuffix(" GP")
                withStyle(SpanStyle(color = look.colour)) { append(name) }
                if (name != meeting) withStyle(SpanStyle(color = look.accent)) { append(" GP") }
            }
            if (session.isNotEmpty()) withStyle(SpanStyle(color = Muted, fontSize = pxToSp(u * 15f))) { append(if (meeting.isEmpty()) session else " · $session") }
        },
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** The track map's choices, which it reads as the live-session widget's settings: every car named on a wide screen, the followed one and the leader on a tall one. */
private fun mapSettings(look: PitLook): Settings = Settings()
    .with(Common.COLOUR, look.colourArgb)
    .with(F1LiveWidget.ACCENT, look.accentArgb)
    .with(F1LiveWidget.TRACK_LABELS, "teams")
    .with(F1LiveWidget.TRACK_HIGHLIGHT, "driver")
    .with(F1LiveWidget.START_LINE, true)
    .with(F1LiveWidget.CORNERS, false)
    // The flag's colour is drawn under the map's line by FlagTrack, which runs it round the circuit as it comes out.
    .with(F1LiveWidget.TRACK_FLAG, false)
    .with(F1LiveWidget.NAMES, "code")

/** The weekend and the session, then the lap (or the clock) large with the flag in a pill beside it. */
@Composable
private fun LiveHeader(b: Board, look: PitLook) {
    val u = look.u
    val session = b.session
    val progress = when {
        !b.live -> if (b.phase == LivePhase.Finished) "Provisional" else "Result"
        b.started -> sessionProgress(session, b.now)
        else -> ""
    }
    // "Lap 23/62": the lap large, the distance small beside it.
    val lead = if (b.live && session.isRace && '/' in progress) progress.substringBefore('/') else progress
    val rest = if (lead != progress) "/ " + progress.substringAfter('/') else ""
    Column(Modifier.fillMaxWidth()) {
        WeekendTitle(session.meeting, session.name, look)
        Row(Modifier.fillMaxWidth().height(pxToDp(u * 36f)), verticalAlignment = Alignment.CenterVertically) {
            if (lead.isNotEmpty()) {
                // The lap rolls over as it changes; a clock counting down each second just ticks, as rolling it would
                // redraw the screen for a quarter of every second, between the frames the cars on the map need.
                RollingText(lead, look.colour, u * 26f, look.animate && session.isRace, weight = FontWeight.SemiBold)
                if (rest.isNotEmpty()) {
                    Spacer(Modifier.width(pxToDp(u * 6f)))
                    Text(rest, color = Muted, fontSize = pxToSp(u * 14f), maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.width(pxToDp(u * 12f)))
            }
            StatusPill(b.status.label, b.status.shade, u * 12f, look.animate)
            val detail = b.status.detail.takeIf { it.isNotEmpty() && it != progress && b.live }
            if (detail != null) {
                Spacer(Modifier.width(pxToDp(u * 8f)))
                Text(detail, color = Muted, fontSize = pxToSp(u * 12f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The map, turned on its side when that fills the space better, over the flag's colour on the circuit. */
@Composable
private fun LiveMap(b: Board, look: PitLook, modifier: Modifier) {
    val u = look.u
    BoxWithConstraints(modifier) {
        val wide = constraints.maxWidth >= constraints.maxHeight
        // A plain ring stands in only while a live session's outline is on its way, so the cars can still be seen going round.
        val track = remember(b.track, b.live, wide) {
            (b.track ?: F1Track.ring().takeIf { b.live })?.let { t -> if ((t.aspect >= 1f) != wide && (t.aspect > 1.25f || t.aspect < 0.8f)) turned(t, 90) else t }
        }
        if (track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No circuit map for this session", color = Muted, fontSize = pxToSp(u * 12f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            return@BoxWithConstraints
        }
        // With the sectors known, a yellow lights only the sectors it is out in; without them, the whole track as before.
        val yellows = if (b.live && track.sectors.isNotEmpty()) b.session.sectorFlags else emptyMap()
        val sectorYellow = yellows.isNotEmpty() && b.phase == LivePhase.Running
        FlagTrack(
            track, b.status.shade.takeIf { b.live && b.status.filled && !sectorYellow }, look.colour.copy(alpha = 0.85f),
            sectors = track.sectors, lit = yellows, marks = look.settings[PitWallWidget.SECTOR_MARKS],
            pulse = b.phase == LivePhase.Red || b.phase == LivePhase.SafetyCar, finish = b.session.finished, enter = look.entered,
            animate = look.animate,
            // Drawn into a layer of its own, redrawn only as a flag changes, not on every frame the cars move.
            modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        )
        TrackMap(b, track, Modifier.fillMaxSize(), u * if (look.landscape) 18f else 16f)
    }
}

/** The order: as many rows as asked for, each [row] pixels tall. A car that changes place slides to its new row. */
@Composable
private fun Order(b: Board, look: PitLook, row: Float) {
    val session = b.session
    val shown = session.rows.take(look.rows)
    val tyres = shown.any { it.tyre != null }
    LazyColumn(Modifier.fillMaxWidth().height(pxToDp(row * look.rows)), userScrollEnabled = false) {
        items(shown, key = { it.number.ifEmpty { it.code } }) { r ->
            OrderRow(
                r, b, look, row, tyres,
                Modifier.animateItem(fadeInSpec = pitSpec(look.animate, 300), placementSpec = pitPlacement(look.animate), fadeOutSpec = pitSpec(look.animate, 300)),
            )
        }
    }
}

@Composable
private fun OrderRow(r: LiveRow, b: Board, look: PitLook, row: Float, tyres: Boolean, modifier: Modifier) {
    val px = row * 0.56f
    val me = b.followed.isNotEmpty() && r.code == b.followed
    val gone = r.out || r.knockedOut
    // What stands where the gap does: 2 out, 1 in the pits, 0 the gap itself. Only a change of kind fades over; the gap just ticks.
    val kind = if (r.out) 2 else if (r.inPit && b.live) 1 else 0
    Row(
        modifier.fillMaxWidth().height(pxToDp(row)).then(rowPulse(r.position, r.fastest && b.session.isRace && b.live, look.animate)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${r.position}", color = Muted, fontSize = pxToSp(px * 0.85f), maxLines = 1, softWrap = false, textAlign = TextAlign.End,
            modifier = Modifier.width(pxToDp(px * 1.5f)),
        )
        Spacer(Modifier.width(pxToDp(px * 0.5f)))
        TeamBar(r.teamColour, gone, px * 0.22f, px)
        Spacer(Modifier.width(pxToDp(px * 0.5f)))
        Text(
            r.code, color = (if (me) look.accent else look.colour).let { if (gone) it.copy(alpha = 0.5f) else it }, fontSize = pxToSp(px),
            fontWeight = if (me) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, softWrap = false,
        )
        Spacer(Modifier.weight(1f))
        AnimatedContent(kind, transitionSpec = { pitFade(look.animate, 250) }, label = "gap") { k ->
            Text(
                when (k) { 2 -> "OUT"; 1 -> "PIT"; else -> gapText(r.gap) }, color = if (k == 1) Amber else Muted, fontSize = pxToSp(px * 0.85f),
                maxLines = 1, softWrap = false,
            )
        }
        if (tyres) {
            Spacer(Modifier.width(pxToDp(px * 0.6f)))
            Box(Modifier.width(pxToDp(px * 1.2f)), contentAlignment = Alignment.Center) {
                // A new set fades in over the old one.
                AnimatedContent(r.tyre, transitionSpec = { pitFade(look.animate, 300) }, label = "tyre") { t -> if (t != null) TyreMark(t, false, px * 0.8f) }
            }
        }
    }
}

private val SECTOR_IN = Regex("""sector (\d+)""")

/** Race control's last three messages, newest first, drivers by their codes; between sessions, what comes next instead. */
@Composable
private fun Notes(b: Board, look: PitLook) {
    val u = look.u
    val line = u * 22f
    Column(Modifier.fillMaxWidth().height(pxToDp(u * 20f + line * 3))) {
        if (!b.live) {
            Text("Next", color = Muted, fontSize = pxToSp(u * 13f), maxLines = 1)
            Text(b.next ?: "Nothing scheduled", color = look.colour, fontSize = pxToSp(u * 16f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@Column
        }
        Text("Race control", color = Muted, fontSize = pxToSp(u * 13f), maxLines = 1)
        val events = b.session.events.takeLast(3).reversed().distinctBy { it.at to it.text }
        val track = b.track
        // A yellow race control gave no cause for: the car stopped in that sector, if one is.
        val points = remember(b.crossings, track) {
            val of = b.crossings.values.firstNotNullOfOrNull { it.lastOrNull()?.of } ?: 0
            if (of > 1) timingPoints(b.crossings, of, track) else null
        }
        fun withCause(text: String): String {
            if ('[' in text || track == null || points == null) return text
            val sector = SECTOR_IN.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return text
            val stopped = b.session.rows.firstOrNull { r -> r.out && !r.retired && b.crossings[r.number]?.let { sectorOf(track, it, points) } == sector }
            return stopped?.let { "$text · [${it.code}]" } ?: text
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), userScrollEnabled = false) {
            items(events, key = { "${it.at.toEpochMilli()} ${it.text}" }) { e ->
                Text(
                    namedNews(withCause(e.text), "code", b.session.rows), color = if (e === events.first()) look.colour else Muted, fontSize = pxToSp(u * 16f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(pxToDp(line))
                        .animateItem(fadeInSpec = pitSpec(look.animate, 350), placementSpec = pitPlacement(look.animate), fadeOutSpec = pitSpec(look.animate, 350)),
                )
            }
        }
    }
}
