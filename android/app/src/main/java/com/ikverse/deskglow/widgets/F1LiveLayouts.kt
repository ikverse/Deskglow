package com.ikverse.deskglow.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.ikverse.deskglow.data.LivePhase
import com.ikverse.deskglow.data.LiveRow
import com.ikverse.deskglow.data.LiveSession
import com.ikverse.deskglow.data.gapText
import com.ikverse.deskglow.data.standingColumns
import com.ikverse.deskglow.model.Settings
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What every layout draws from: the session, who is followed, what is next, and the words for the status. */
internal class Board(
    val settings: Settings,
    val session: LiveSession,
    /** False for a saved result. */
    val live: Boolean,
    val phase: LivePhase,
    val now: Instant,
    val h24: Boolean,
    /** The favourite driver and team (ids), and the code of the car followed: the driver's, else the team's better one. */
    val driver: String,
    val team: String,
    val followed: String,
    /** "Qualifying in 4 h", for a result. */
    val next: String?,
    /** The latest news in words, when there is some and it is fresh. */
    val news: String?,
) {
    val status: Status = if (session.signalLost) {
        Status("No signal", session.signalAt?.let { "${Duration.between(it, now).toMinutes().coerceAtLeast(1)} min ago" }.orEmpty(), Muted, false)
    } else statusOf(session, phase, now, h24)

    val colour = Color(settings[Common.COLOUR])
    val accent = Color(settings[F1LiveWidget.ACCENT])
    val names = settings[F1LiveWidget.NAMES]

    /** Whether racing has begun: before that the order is the grid, and there is nothing yet to have gained or run through. */
    val started: Boolean = phase != LivePhase.PreStart && phase != LivePhase.Delayed

    /** The car followed, or the leader when none is. */
    val me: LiveRow? = session.rows.firstOrNull { followed.isNotEmpty() && it.code == followed } ?: session.rows.firstOrNull()
}

@Composable
internal fun LiveFace(b: Board, w: Float, h: Float) {
    val unit = canvasUnit(w, F1LiveWidget.width)
    Box(Modifier.fillMaxSize().then(if (b.session.signalLost) Modifier.alpha(0.55f) else Modifier)) {
        when (b.settings[F1LiveWidget.LAYOUT]) {
            "glance" -> GlanceFace(b, unit)
            "focus" -> FocusFace(b, unit)
            "line" -> LineFace(b, unit)
            else -> TowerFace(b, w, h, unit)
        }
    }
}

/** What a fitted layout is measured afresh for: its settings and the shape of the session, not the text that changes from second to second. */
private fun fitKey(b: Board, vararg parts: Any?): List<Any?> =
    listOf(b.settings, b.phase, b.live, b.status.filled, b.session.isRace, b.session.type, b.session.signalLost) + parts

private val Purple = Color(0xFFB45CFF)
private val DropLine = Color(0xFFF2766B)

private fun tyreColour(letter: Char): Color = when (letter) {
    'S' -> Color(0xFFE53935)
    'M' -> Color(0xFFFFD60A)
    'H' -> Color(0xFFEDEDED)
    'I' -> Color(0xFF43A047)
    'W' -> Color(0xFF2979FF)
    else -> Muted
}

private fun tyreName(letter: Char): String = when (letter) {
    'S' -> "Soft"
    'M' -> "Medium"
    'H' -> "Hard"
    'I' -> "Inter"
    'W' -> "Wet"
    else -> ""
}

/** "1:32.274" or "+0.512": a time the way it reads on a widget, nothing for none. */
private fun seconds(value: Double): String = String.format(Locale.US, "%.1f", abs(value))

/** A coloured chip with [text] in black on [shade]: a state, as a flag shows it. */
@Composable
private fun StateChip(text: String, shade: Color, px: Float) {
    Text(
        text, color = Color.Black, fontSize = pxToSp(px), fontWeight = FontWeight.Bold, letterSpacing = 0.08.em, maxLines = 1, softWrap = false,
        modifier = Modifier.background(shade, RoundedCornerShape(pxToDp(px * 0.3f))).padding(horizontal = pxToDp(px * 0.5f), vertical = pxToDp(px * 0.1f)),
    )
}

@Composable
private fun TeamBar(colour: Long?, dim: Boolean, width: Float, height: Float) {
    Box(
        Modifier.width(pxToDp(width)).height(pxToDp(height))
            .background(Color(colour ?: 0xFF8C8C8C).let { if (dim) it.copy(alpha = 0.45f) else it }, RoundedCornerShape(pxToDp(width * 0.5f))),
    )
}

/** The tyre's letter in a ring of its compound's colour; dimmed when every car is on the same one, so a different choice stands out. */
@Composable
private fun TyreMark(letter: Char, muted: Boolean, px: Float) {
    val shade = tyreColour(letter).let { if (muted) it.copy(alpha = 0.45f) else it }
    Box(Modifier.size(pxToDp(px * 1.35f)).border(pxToDp((px * 0.14f).coerceAtLeast(1f)), shade, CircleShape), contentAlignment = Alignment.Center) {
        Text(letter.toString(), color = shade, fontSize = pxToSp(px * 0.75f), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Gained(gain: Int, px: Float) {
    if (gain == 0) return
    Text(
        (if (gain > 0) "▲" else "▼") + abs(gain), color = if (gain > 0) Gain else Loss, fontSize = pxToSp(px * 0.75f),
        maxLines = 1, softWrap = false,
    )
}

/** A thin bar under a line of text: how far through the race it is. */
@Composable
private fun ProgressLine(fraction: Float, shade: Color, height: Float) {
    Box(Modifier.fillMaxWidth().height(pxToDp(height)).background(Muted.copy(alpha = 0.25f), RoundedCornerShape(pxToDp(height)))) {
        Box(Modifier.fillMaxWidth(fraction).height(pxToDp(height)).background(shade, RoundedCornerShape(pxToDp(height))))
    }
}

/** The news, in amber, on one line. */
@Composable
private fun NewsLine(text: String, px: Float) {
    Text(text, color = Amber, fontSize = pxToSp(px), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
}

// ---- Tower ----

/** One tower row as it is drawn, so a row is redrawn only when something in it changes. */
@Immutable
private data class RowSpec(
    val position: String,
    val name: String,
    val team: Long?,
    /** 2 for the followed car, 1 for the followed team's other car. */
    val mark: Int,
    val dim: Boolean,
    val tyre: Char?,
    val tyreMuted: Boolean,
    /** The editor's stand-ins for a switched-on tyre or gain with nothing to show. */
    val tyreSlot: Boolean,
    val gainSlot: Boolean,
    val gain: Int,
    val fastest: Boolean,
    val tag: String?,
    val laps: String?,
    val gap: String,
)

@Composable
private fun TowerRow(spec: RowSpec, colour: Color, accent: Color, row: Float, text: Float, gapWidth: Float) {
    val main = (if (spec.mark == 2) accent else colour).let { if (spec.dim) it.copy(alpha = 0.45f) else it }
    val tint = when (spec.mark) {
        2 -> Modifier.background(accent.copy(alpha = 0.16f), RoundedCornerShape(pxToDp(text * 0.3f)))
        1 -> Modifier.background(accent.copy(alpha = 0.07f), RoundedCornerShape(pxToDp(text * 0.3f)))
        else -> Modifier
    }
    Row(Modifier.fillMaxWidth().height(pxToDp(row)).then(tint), verticalAlignment = Alignment.CenterVertically) {
        Text(
            spec.position, color = Muted, fontSize = pxToSp(text * 0.9f), textAlign = TextAlign.End,
            maxLines = 1, softWrap = false, modifier = Modifier.width(pxToDp(text * 1.3f)),
        )
        Spacer(Modifier.width(pxToDp(text * 0.45f)))
        TeamBar(spec.team, spec.dim, text * 0.2f, row * 0.62f)
        Spacer(Modifier.width(pxToDp(text * 0.45f)))
        Text(
            spec.name, color = main, fontSize = pxToSp(text), fontWeight = if (spec.mark == 2) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (spec.fastest) {
            Spacer(Modifier.width(pxToDp(text * 0.25f)))
            Box(Modifier.size(pxToDp(text * 0.4f)).background(Purple, CircleShape))
        }
        Spacer(Modifier.weight(1f))
        if (spec.gain != 0) {
            Gained(spec.gain, text)
            Spacer(Modifier.width(pxToDp(text * 0.3f)))
        } else if (spec.gainSlot) {
            EditorSlot("▲▼", text * 0.75f)
            Spacer(Modifier.width(pxToDp(text * 0.3f)))
        }
        spec.tyre?.let {
            TyreMark(it, spec.tyreMuted, text)
            Spacer(Modifier.width(pxToDp(text * 0.3f)))
        }
        if (spec.tyre == null && spec.tyreSlot) {
            EditorSlot("S", text * 0.75f, Modifier.size(pxToDp(text * 1.35f)))
            Spacer(Modifier.width(pxToDp(text * 0.3f)))
        }
        spec.laps?.let {
            Text(it, color = Muted, fontSize = pxToSp(text * 0.7f), maxLines = 1, softWrap = false)
            Spacer(Modifier.width(pxToDp(text * 0.4f)))
        }
        spec.tag?.let {
            Text(
                it, color = Muted, fontSize = pxToSp(text * 0.6f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em, maxLines = 1, softWrap = false,
                modifier = Modifier.background(Muted.copy(alpha = 0.18f), RoundedCornerShape(pxToDp(text * 0.2f))).padding(horizontal = pxToDp(text * 0.25f)),
            )
            Spacer(Modifier.width(pxToDp(text * 0.35f)))
        }
        Text(
            spec.gap, color = if (spec.mark == 2) accent else Muted, fontSize = pxToSp(text * 0.85f), textAlign = TextAlign.End,
            maxLines = 1, softWrap = false, modifier = Modifier.width(pxToDp(gapWidth)),
        )
    }
}

/**
 * The classification under a header: the session and where it stands, then the top rows (in two
 * columns in a wide box), the followed car underneath when it is further down, and the news or what
 * is next. The line the cut-off falls on, in qualifying, is drawn under its last car in.
 */
@Composable
private fun TowerFace(b: Board, w: Float, h: Float, unit: Float) {
    val settings = b.settings
    val session = b.session
    val live = b.live
    val showStatus = live && settings[F1LiveWidget.SHOW_FLAG]
    val editing = LocalEditing.current
    val flagShade = b.status.shade.takeIf { live && settings[F1LiveWidget.BORDER] && b.status.filled }
    // In the editor a switched-on border with no flag out is drawn faintly, so its place can be seen.
    val borderHint = editing && flagShade == null && settings[F1LiveWidget.BORDER]
    val statusSlot = editing && !showStatus && settings[F1LiveWidget.SHOW_FLAG]
    // Room round the edge, so the border has none of the rows against it.
    val inset = if (flagShade != null || borderHint) min(w, h) * 0.035f else 0f
    val wanted = session.rows.take(settings[F1LiveWidget.ROWS])
    val followedBelow = session.rows.drop(wanted.size).firstOrNull { b.followed.isNotEmpty() && it.code == b.followed }
    val columnGap = w * 0.06f
    val toAhead = when (settings[F1LiveWidget.GAP]) { "ahead" -> true; "leader" -> false; else -> session.isRace }
    val news = b.news.takeIf { settings[F1LiveWidget.NEWS] }
    val wantedFooter = if (!live) b.next else news
    val wantedNewsSlot = editing && live && settings[F1LiveWidget.NEWS] && news == null

    // In a small box the news goes first, then the followed car's own row, then rows from the bottom,
    // until a row is tall enough to read. Two columns wherever they make the rows taller.
    var footerOn = wantedFooter != null || wantedNewsSlot
    var count = wanted.size
    var extraOn = followedBelow != null
    fun rowFor(n: Int, two: Boolean): Float {
        val shownRows = wanted.take(n)
        val cut = session.cutoff?.takeIf { c -> shownRows.any { it.position == c } }
        val below = extraOn && session.rows.drop(n).any { b.followed.isNotEmpty() && it.code == b.followed }
        val slots = (if (two) (n + 1) / 2 else n) + 1.4f + (if (showStatus || statusSlot) 1.35f else 0f) + (if (below) 1.3f else 0f) +
            (if (footerOn) 1f else 0f) + (if (cut != null) 0.2f else 0f)
        val columnW = if (two) (w - columnGap) / 2 else w
        return min(min((h - inset * 2) / slots, (columnW - inset * 2) * 0.16f), Fitting.LARGEST_ROW * unit)
    }
    fun arrange(): Pair<Boolean, Float> {
        val one = rowFor(count, false)
        val two = if (count > 5) rowFor(count, true) else 0f
        return if (two > one * 1.05f) true to two else false to one
    }
    var (twoColumns, row) = arrange()
    while (row < Fitting.SMALLEST_ROW * unit) {
        when {
            footerOn -> footerOn = false
            extraOn -> extraOn = false
            count > 3 -> count--
            else -> break
        }
        arrange().let { twoColumns = it.first; row = it.second }
    }
    val top = wanted.take(count)
    val extra = session.rows.drop(count).firstOrNull { extraOn && b.followed.isNotEmpty() && it.code == b.followed }
    val footer = wantedFooter.takeIf { footerOn }
    val newsSlot = wantedNewsSlot && footerOn
    val (left, right) = if (twoColumns) standingColumns(top) else top to emptyList()
    val cutoff = session.cutoff?.takeIf { c -> top.any { it.position == c } }
    val text = row * 0.56f
    // The heading, the flag and the news grow more slowly than the rows.
    val label = Fitting.second(text, unit)

    fun gapOf(r: LiveRow) = gapText(if (toAhead) r.interval else r.gap)
    val gapWidth = text * 0.85f * 0.6f * ((top + listOfNotNull(extra)).maxOfOrNull { gapOf(it).length } ?: 0)
    val oneTyre = top.mapNotNull { it.tyre }.distinct().size <= 1
    val tyreHint = editing && settings[F1LiveWidget.TYRES] && top.none { it.tyre != null }
    val gainShown = settings[F1LiveWidget.GAINED] && session.isRace && b.started
    val gainHint = editing && settings[F1LiveWidget.GAINED] && top.none { gainShown && (it.gained ?: 0) != 0 }

    fun specOf(r: LiveRow) = RowSpec(
        position = r.position.toString(),
        name = driverName(r, b.names),
        team = r.teamColour,
        mark = when {
            b.followed.isNotEmpty() && r.code == b.followed -> 2
            teamMatches(b.team, r.team) -> 1
            else -> 0
        },
        dim = r.knockedOut || r.out,
        tyre = r.tyre.takeIf { settings[F1LiveWidget.TYRES] },
        tyreMuted = oneTyre,
        tyreSlot = tyreHint,
        gainSlot = gainHint && r.code == b.me?.code,
        gain =if (settings[F1LiveWidget.GAINED] && session.isRace && b.started) r.gained ?: 0 else 0,
        fastest = r.fastest && live,
        tag = when {
            r.out -> "OUT"
            r.inPit && live -> "PIT"
            r.penaltySeconds > 0 && live -> "+${r.penaltySeconds}s"
            else -> null
        },
        laps = r.laps?.takeIf { session.type == "Practice" }?.toString(),
        gap = gapOf(r),
    )

    @Composable
    fun Rows(rows: List<LiveRow>) {
        rows.forEach { r ->
            key(r.number) { TowerRow(specOf(r), b.colour, b.accent, row, text, gapWidth) }
            if (cutoff != null && r.position == cutoff) {
                Box(Modifier.fillMaxWidth().height(pxToDp(row * 0.2f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().height(pxToDp((row * 0.05f).coerceAtLeast(1f))).background(DropLine.copy(alpha = 0.8f)))
                }
            }
        }
    }

    val frame = if (flagShade != null) {
        Modifier.border(pxToDp((min(w, h) * 0.008f).coerceAtLeast(1.5f)), flagShade, RoundedCornerShape(pxToDp(inset * 1.5f))).padding(pxToDp(inset))
    } else if (borderHint) {
        Modifier.border(pxToDp((min(w, h) * 0.008f).coerceAtLeast(1.5f)), Muted.copy(alpha = 0.4f), RoundedCornerShape(pxToDp(inset * 1.5f))).padding(pxToDp(inset))
    } else Modifier
    Column(Modifier.fillMaxSize().then(frame), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth().height(pxToDp(row * 1.4f)), verticalAlignment = Alignment.CenterVertically) {
            if (live && b.phase != LivePhase.PreStart && b.phase != LivePhase.Delayed) {
                LiveBadge(b.accent, label * 0.7f)
                Spacer(Modifier.width(pxToDp(label * 0.4f)))
            }
            Text(
                session.name.replace("Qualifying", "Quali").uppercase(), color = b.colour, fontSize = pxToSp(label * 0.78f), fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.em, maxLines = 1, softWrap = false,
            )
            Spacer(Modifier.width(pxToDp(label * 0.5f)))
            Text(
                if (live) session.meeting else (if (b.phase == LivePhase.Finished) "Provisional · " else "Result · ") + session.meeting,
                color = Muted, fontSize = pxToSp(label * 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        if (showStatus) StatusBar(b, label, row, raceFraction(session)?.takeIf { b.started })
        else if (statusSlot) Box(Modifier.fillMaxWidth().height(pxToDp(row * 1.05f)), contentAlignment = Alignment.CenterStart) { EditorSlot("Flag and clock", label * 0.72f) }
        if (session.rows.isEmpty()) {
            Text("Waiting for the timing", color = Muted, fontSize = pxToSp(text * 0.8f), maxLines = 1, modifier = Modifier.padding(vertical = pxToDp(text * 0.5f)))
        } else if (twoColumns) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Rows(left) }
                Spacer(Modifier.width(pxToDp(columnGap)))
                Column(Modifier.weight(1f)) { Rows(right) }
            }
        } else {
            Rows(top)
        }
        if (extra != null) {
            Box(Modifier.fillMaxWidth().height(pxToDp(row * 0.3f)), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(pxToDp((row * 0.02f).coerceAtLeast(1f))).background(Color(0xFF2A2A2A)))
            }
            TowerRow(specOf(extra), b.colour, b.accent, row, text, gapWidth)
        }
        if (footer != null) {
            Box(Modifier.fillMaxWidth().height(pxToDp(row)), contentAlignment = Alignment.CenterStart) {
                if (live) NewsLine(footer, label * 0.78f)
                else Text("Next · $footer", color = Muted, fontSize = pxToSp(label * 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else if (newsSlot) {
            Box(Modifier.fillMaxWidth().height(pxToDp(row)), contentAlignment = Alignment.CenterStart) { EditorSlot("Latest news", label * 0.78f) }
        }
    }
}

/**
 * The state the session is in, across the width: a flag as a coloured bar (safety car, red, delayed),
 * otherwise plain words (the lap, the time left), with a thin line under it showing how far a race has got.
 */
@Composable
private fun StatusBar(b: Board, text: Float, row: Float, fraction: Float?) {
    val status = b.status
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(pxToDp(row * 1.05f)).then(
                if (status.filled) Modifier.background(status.shade, RoundedCornerShape(pxToDp(text * 0.3f))).padding(horizontal = pxToDp(text * 0.45f)) else Modifier,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.filled) {
                Text(
                    status.label.uppercase(), color = Color.Black, fontSize = pxToSp(text * 0.7f), fontWeight = FontWeight.Bold, letterSpacing = 0.08.em,
                    maxLines = 1, softWrap = false,
                )
                Spacer(Modifier.weight(1f))
                Text(status.detail, color = Color.Black.copy(alpha = 0.72f), fontSize = pxToSp(text * 0.7f), maxLines = 1, softWrap = false)
            } else {
                val calm = status.shade == FlagGreen
                Text(
                    listOf(if (calm) "" else status.label, status.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = Muted, fontSize = pxToSp(text * 0.72f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (fraction != null) ProgressLine(fraction, if (status.filled) status.shade else b.accent, max(1.5f, row * 0.05f))
    }
}

// ---- Glance ----

/** The parts of a glance that are left out, last first, when the box is too small for them all. */
private enum class GlancePart { Progress, Followed, Footer }

/**
 * The flag, big enough to read across a desk; with it where the session has got to, who leads, and the
 * followed car: under it in a tall box, beside it in a wide one.
 */
@Composable
private fun GlanceFace(b: Board, unit: Float) {
    val session = b.session
    val status = b.status
    val leader = session.rows.firstOrNull()
    val me = b.me?.takeIf { it !== leader }
    val footer = if (b.live) b.news else b.next?.let { "Next · $it" }
    val newsSlot = footer == null && b.live && b.settings[F1LiveWidget.NEWS] && LocalEditing.current
    val label = if (b.live) status.label else if (b.phase == LivePhase.Finished) "Finished" else "Result"
    val parts = listOfNotNull(
        GlancePart.Progress.takeIf { b.live || b.next != null },
        GlancePart.Followed.takeIf { me != null },
        GlancePart.Footer.takeIf { footer != null || newsSlot },
    )
    Fit(unit, fitKey(b, parts, label, leader != null), arrangements = 2, levels = parts.size) { f ->
        val s = f.scale
        val shown = parts.take(f.level)
        val big = s.main * 1.9f
        val fill = if (f.probing) Modifier else Modifier.fillMaxWidth()
        val gap = s.second * 0.4f * s.space

        @Composable
        fun Block(modifier: Modifier) {
            Box(
                modifier.then(
                    if (status.filled && b.live) Modifier.background(status.shade, RoundedCornerShape(pxToDp(big * 0.4f)))
                    else Modifier.border(pxToDp(max(1f, s.unit * 1.2f)), (if (b.live) status.shade else Muted).copy(alpha = 0.45f), RoundedCornerShape(pxToDp(big * 0.4f))),
                ).padding(horizontal = pxToDp(big * 0.6f), vertical = pxToDp(big * 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.uppercase(), color = if (status.filled && b.live) Color.Black else if (b.live) status.shade else b.colour,
                    fontSize = pxToSp(big), fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, maxLines = 1, softWrap = false,
                )
            }
        }

        @Composable
        fun Lines(modifier: Modifier) {
            Column(modifier) {
                if (GlancePart.Progress in shown) {
                    Column(fill.padding(vertical = pxToDp(gap))) {
                        Row(fill, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                session.name.replace("Qualifying", "Quali"), color = Muted, fontSize = pxToSp(s.second * 0.95f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = if (f.probing) Modifier else Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(pxToDp(s.second * 0.8f)))
                            Text(status.detail, color = b.colour, fontSize = pxToSp(s.second * 1.05f), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                        }
                        raceFraction(session)?.takeIf { b.live && b.started }?.let {
                            Spacer(Modifier.height(pxToDp(s.second * 0.3f)))
                            ProgressLine(it, if (status.filled) status.shade else b.accent, max(1.5f, s.second * 0.2f))
                        }
                    }
                }
                if (leader != null) {
                    val second = session.rows.getOrNull(1)
                    val trailing = if (session.isRace) second?.let { gapText(it.gap) }.orEmpty() else leader.gap
                    val note = when {
                        b.live -> "leads"
                        session.isRace -> "won"
                        session.type == "Qualifying" -> "pole"
                        else -> "fastest"
                    }
                    GlanceLine(driverName(leader, b.names), note, leader.teamColour, trailing, null, b, s, gap, f.probing)
                }
                if (GlancePart.Followed in shown && me != null) {
                    GlanceLine(
                        driverName(me, b.names), "P${me.position}", me.teamColour, if (session.isRace && b.live) gapText(me.interval) else "",
                        me.gained?.takeIf { session.isRace && b.started }, b, s, gap, f.probing, strong = true,
                    )
                }
                if (GlancePart.Footer in shown) {
                    Box(fill.padding(vertical = pxToDp(gap)), contentAlignment = Alignment.CenterStart) {
                        when {
                            footer == null -> EditorSlot("Latest news", s.second)
                            b.live -> NewsLine(f.sample(footer, 26), s.second)
                            else -> Text(f.sample(footer, 26), color = Muted, fontSize = pxToSp(s.second), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (f.arrangement == 0) {
            Column(fill) {
                Block(fill)
                Spacer(Modifier.height(pxToDp(gap)))
                Lines(fill)
            }
        } else {
            Row(fill, verticalAlignment = Alignment.CenterVertically) {
                Block(Modifier)
                Spacer(Modifier.width(pxToDp(s.main * 1.2f)))
                Lines(if (f.probing) Modifier else Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GlanceLine(
    name: String, note: String, team: Long?, trailing: String, gain: Int?, b: Board, s: Scale, gap: Float, probing: Boolean, strong: Boolean = false,
) {
    val nameSize = s.main * 1.45f
    Row((if (probing) Modifier else Modifier.fillMaxWidth()).padding(vertical = pxToDp(gap)), verticalAlignment = Alignment.CenterVertically) {
        TeamBar(team, false, s.main * 0.26f, nameSize * 0.95f)
        Spacer(Modifier.width(pxToDp(s.main * 0.55f)))
        Text(name, color = if (strong) b.accent else b.colour, fontSize = pxToSp(nameSize), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(pxToDp(s.second * 0.5f)))
        Text(
            note, color = if (strong) b.colour else Muted, fontSize = pxToSp(if (strong) s.main * 1.4f else s.second * 1.05f),
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false,
        )
        if (gain != null) {
            Spacer(Modifier.width(pxToDp(s.second * 0.4f)))
            Gained(gain, s.second * 1.3f)
        }
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(pxToDp(s.second * 0.8f)))
            if (!probing) Spacer(Modifier.weight(1f))
            Text(trailing, color = Muted, fontSize = pxToSp(s.second * 1.05f), maxLines = 1, softWrap = false)
        }
    }
}

// ---- Focus ----

/**
 * How much faster [chaser] went round on its last lap than [chased], in seconds: positive when the
 * gap between them is shrinking. Null when either has no last lap.
 */
internal fun closingRate(chaser: LiveRow, chased: LiveRow): Double? {
    val a = chaser.lastLapMs ?: return null
    val c = chased.lastLapMs ?: return null
    return (c - a) / 1000.0
}

/** The gap changing by [rate] a lap: "−0.3s a lap" when it shrinks, "+0.3s a lap" when it grows, "level" when neither. */
internal fun gapChangeText(rate: Double): String =
    if (abs(rate) < 0.05) "level" else (if (rate > 0) "−" else "+") + seconds(rate) + "s a lap"

/** The parts of the focus layout left out, last first, when the box is too small for them all. */
private enum class FocusPart { Tyre, News }

/**
 * The followed car at the centre, with the car ahead above it and the car behind below; in qualifying,
 * its time and where it stands against the cut-off. In a wide box the car sits on the left and the rest
 * beside it.
 */
@Composable
private fun FocusFace(b: Board, unit: Float) {
    val session = b.session
    val me = b.me
    if (me == null || b.phase == LivePhase.PreStart || b.phase == LivePhase.Delayed) {
        FocusWaiting(b, me, unit)
        return
    }
    val editing = LocalEditing.current
    val ahead = session.rows.firstOrNull { it.position == me.position - 1 }
    val behind = session.rows.firstOrNull { it.position == me.position + 1 }
    val raceLike = session.isRace
    val tyre = me.tyre?.let { t ->
        listOfNotNull(tyreName(t), me.tyreLaps?.let { "$it laps" }, if (raceLike && me.stops > 0) "${me.stops} stop" + (if (me.stops > 1) "s" else "") else null).joinToString(" · ")
    }
    val tyreSlot = tyre == null && b.settings[F1LiveWidget.TYRES] && editing
    val news = if (b.live) b.news else b.next?.let { "Next · $it" }
    val newsSlot = news == null && b.live && b.settings[F1LiveWidget.NEWS] && editing
    val parts = listOfNotNull(FocusPart.Tyre.takeIf { tyre != null || tyreSlot }, FocusPart.News.takeIf { news != null || newsSlot })
    Fit(unit, fitKey(b, parts, me.code, ahead != null, behind != null, session.cutoff), arrangements = 2, levels = parts.size) { f ->
        val s = f.scale
        val shown = parts.take(f.level)
        val fill = if (f.probing) Modifier else Modifier.fillMaxWidth()
        val big = s.main * 2.6f
        val gap = s.second * 0.35f * s.space

        // Where the session stands, small, at the top.
        @Composable
        fun Top() {
            Row(fill.padding(vertical = pxToDp(gap)), verticalAlignment = Alignment.CenterVertically) {
                if (b.status.filled && b.live) StateChip(b.status.label.uppercase(), b.status.shade, s.small * 0.85f) else Text(
                    if (b.live) b.status.label.ifEmpty { session.name } else "Result", color = Muted, fontSize = pxToSp(s.small * 0.95f), maxLines = 1, softWrap = false,
                )
                Spacer(Modifier.width(pxToDp(s.second * 0.8f)))
                if (!f.probing) Spacer(Modifier.weight(1f))
                Text(b.status.detail, color = b.colour, fontSize = pxToSp(s.second * 1.05f), maxLines = 1, softWrap = false)
            }
        }

        // The followed car.
        @Composable
        fun Car() {
            Row(
                fill.padding(vertical = pxToDp(gap)).background(b.accent.copy(alpha = 0.12f), RoundedCornerShape(pxToDp(s.main * 0.8f)))
                    .padding(horizontal = pxToDp(s.main * 0.8f), vertical = pxToDp(s.main * 0.4f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeamBar(me.teamColour, false, s.main * 0.3f, big * 0.95f)
                Spacer(Modifier.width(pxToDp(s.main * 0.7f)))
                Text("P${me.position}", color = b.colour, fontSize = pxToSp(big), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(pxToDp(s.main * 0.6f)))
                Column {
                    Text(driverName(me, b.names), color = b.accent, fontSize = pxToSp(s.main * 1.5f), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    val gain = me.gained?.takeIf { raceLike && b.started }
                    if (gain != null && gain != 0) Gained(gain, s.second * 1.4f)
                }
            }
        }

        // Its tyre, then the news.
        @Composable
        fun Extras() {
            if (FocusPart.Tyre in shown) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = pxToDp(gap))) {
                    if (tyre != null) {
                        TyreMark(me.tyre!!, false, s.second)
                        Spacer(Modifier.width(pxToDp(s.second * 0.5f)))
                        Text(tyre, color = Muted, fontSize = pxToSp(s.second), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else EditorSlot("Tyre", s.second)
                }
            }
            if (FocusPart.News in shown) {
                Box(fill.padding(vertical = pxToDp(gap))) {
                    when {
                        news == null -> EditorSlot("Latest news", s.second)
                        b.live -> NewsLine(f.sample(news, 26), s.second)
                        else -> Text(f.sample(news, 26), color = Muted, fontSize = pxToSp(s.second), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        @Composable
        fun Ahead() {
            if (raceLike && ahead != null) NeighbourLine(b, ahead, gapText(me.interval), closingRate(me, ahead)?.takeIf { b.live }, behind = false, s, gap, f.probing)
        }

        @Composable
        fun Behind() {
            if (raceLike && behind != null) NeighbourLine(b, behind, gapText(behind.interval), closingRate(behind, me)?.takeIf { b.live }, behind = true, s, gap, f.probing)
            if (!raceLike) QualifyingFocus(b, me, s, f)
        }

        if (f.arrangement == 0) {
            Column(fill) {
                Top()
                Ahead()
                Car()
                Behind()
                Extras()
            }
        } else {
            Row(fill, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Top()
                    Car()
                }
                Spacer(Modifier.width(pxToDp(s.main * 1.2f)))
                Column(if (f.probing) Modifier else Modifier.weight(1f)) {
                    Ahead()
                    Behind()
                    Extras()
                }
            }
        }
    }
}

/** A car beside the followed one: its place and name, the gap, and how the gap is changing; the change lit green when it favours the followed car, red when it does not. */
@Composable
private fun NeighbourLine(b: Board, row: LiveRow, gap: String, rate: Double?, behind: Boolean, s: Scale, space: Float, probing: Boolean) {
    // Ahead: the followed car closing is good. Behind: that car closing is not.
    val change = rate?.let(::gapChangeText) ?: if (b.live && row.catching) "closing" else ""
    val closing = (rate ?: if (row.catching) 1.0 else 0.0) > 0.05
    val shade = when {
        !closing -> Muted
        behind -> Loss
        else -> Gain
    }
    Row((if (probing) Modifier else Modifier.fillMaxWidth()).padding(vertical = pxToDp(space)), verticalAlignment = Alignment.CenterVertically) {
        TeamBar(row.teamColour, false, s.second * 0.22f, s.second * 1.4f)
        Spacer(Modifier.width(pxToDp(s.second * 0.6f)))
        Text(driverName(row, b.names), color = b.colour, fontSize = pxToSp(s.second * 1.2f), maxLines = 1, softWrap = false)
        Spacer(Modifier.width(pxToDp(s.second * 0.5f)))
        Text("P${row.position}", color = Muted, fontSize = pxToSp(s.second), maxLines = 1, softWrap = false)
        Spacer(Modifier.width(pxToDp(s.second * 0.8f)))
        if (!probing) Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(gap, color = b.colour, fontSize = pxToSp(s.second * 1.15f), maxLines = 1, softWrap = false)
            if (change.isNotEmpty()) Text(change, color = shade, fontSize = pxToSp(s.second * 0.8f), maxLines = 1, softWrap = false)
        }
    }
}

/** Qualifying and practice: the car's best lap, and its margin to the cut-off or its gap to the fastest. */
@Composable
private fun QualifyingFocus(b: Board, me: LiveRow, s: Scale, f: Fitted) {
    val session = b.session
    val unit = s.second
    Spacer(Modifier.height(pxToDp(unit * 0.4f * s.space)))
    if (me.bestLap.isNotEmpty()) Text(me.bestLap, color = b.colour, fontSize = pxToSp(unit * 1.5f), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    val margin = session.cutoffMargin(me)
    if (session.cutoff != null && margin != null) {
        val safe = margin >= 0
        Text(
            if (safe) "${String.format(Locale.US, "%.3f", margin)}s safe of the cut-off" else "In the drop zone · ${String.format(Locale.US, "%.3f", -margin)}s",
            color = if (safe) Gain else Loss, fontSize = pxToSp(unit * 1.05f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.background((if (safe) Gain else Loss).copy(alpha = 0.14f), RoundedCornerShape(pxToDp(unit * 0.4f))).padding(horizontal = pxToDp(unit * 0.6f), vertical = pxToDp(unit * 0.25f)),
        )
        val out = session.dropZone().take(6)
        if (out.isNotEmpty()) {
            Text(
                f.sample("Drop zone: " + out.joinToString(" ") { driverName(it, b.names) }, 24), color = Muted, fontSize = pxToSp(unit * 0.85f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = pxToDp(unit * 0.4f * s.space)),
            )
        }
    } else if (me.gap.isNotEmpty() && me.position > 1) {
        Text("${gapText(me.gap)} to the fastest", color = Muted, fontSize = pxToSp(unit), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    me.laps?.takeIf { session.type == "Practice" }?.let { Text("$it laps run", color = Muted, fontSize = pxToSp(unit), maxLines = 1, softWrap = false) }
}

/** Before the start, and while it is delayed: what is awaited, and where the followed car starts; side by side in a wide box. */
@Composable
private fun FocusWaiting(b: Board, me: LiveRow?, unit: Float) {
    val sky = when {
        b.session.raining -> "Raining" + (b.session.trackTemp?.let { " · track ${it.toInt()}°" } ?: "")
        b.session.rainRisk != null -> "Rain risk ${b.session.rainRisk}%"
        else -> null
    }
    Fit(unit, fitKey(b, me?.code, sky != null, b.status.detail.isNotEmpty()), arrangements = if (me != null || sky != null) 2 else 1, levels = if (sky != null) 1 else 0) { f ->
        val s = f.scale

        @Composable
        fun State() {
            Column {
                Text(b.session.name.replace("Qualifying", "Quali"), color = Muted, fontSize = pxToSp(s.second * 1.1f), maxLines = 1, softWrap = false)
                Text(b.status.label, color = b.status.shade.takeIf { b.status.filled } ?: b.colour, fontSize = pxToSp(s.main * 2.4f), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                if (b.status.detail.isNotEmpty()) Text(b.status.detail, color = b.colour, fontSize = pxToSp(s.second * 1.3f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        @Composable
        fun Driver() {
            Column {
                if (me != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TeamBar(me.teamColour, false, s.main * 0.3f, s.main * 1.5f)
                        Spacer(Modifier.width(pxToDp(s.main * 0.7f)))
                        Text(driverName(me, b.names), color = b.accent, fontSize = pxToSp(s.main * 1.6f), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        Spacer(Modifier.width(pxToDp(s.second * 0.6f)))
                        Text("starts P${me.grid ?: me.position}", color = Muted, fontSize = pxToSp(s.second * 1.1f), maxLines = 1, softWrap = false)
                    }
                }
                if (sky != null && f.level >= 1) {
                    Text(f.sample(sky, 24), color = Muted, fontSize = pxToSp(s.second), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = pxToDp(s.second * 0.5f * s.space)))
                }
            }
        }

        if (f.arrangement == 0) {
            Column {
                State()
                Spacer(Modifier.height(pxToDp(s.second * s.space)))
                Driver()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                State()
                Spacer(Modifier.width(pxToDp(s.main * 1.5f)))
                Driver()
            }
        }
    }
}

// ---- One line ----

/** Everything on one line: the state, where the session has got to, who leads, and the followed car. In a taller strip, on two lines. */
@Composable
private fun LineFace(b: Board, unit: Float) {
    val session = b.session
    val leader = session.rows.firstOrNull()
    val me = b.me?.takeIf { it !== leader }
    val short = when (b.phase) {
        LivePhase.PreStart -> "SOON"
        LivePhase.Delayed -> "DELAYED"
        LivePhase.Break -> "BREAK"
        LivePhase.Running -> if (b.status.filled) "YELLOW" else "LIVE"
        LivePhase.SafetyCar -> "SC"
        LivePhase.VirtualSafetyCar -> "VSC"
        LivePhase.Red -> "RED"
        LivePhase.Finished -> "FINISH"
        LivePhase.Final -> "FINAL"
    }
    Fit(unit, fitKey(b, short, me?.code, leader != null), arrangements = 2, levels = if (me != null) 1 else 0) { f ->
        val px = f.scale.main
        val twoLines = f.arrangement == 1

        @Composable
        fun RowScope.State() {
            if (session.signalLost) StateChip("NO SIGNAL", Muted, px * 0.8f) else StateChip(short, if (b.status.filled) b.status.shade else if (b.live && b.phase == LivePhase.Running) b.accent else Muted, px * 0.8f)
            if (b.status.detail.isNotEmpty()) {
                Spacer(Modifier.width(pxToDp(px * 0.5f)))
                Text(b.status.detail, color = b.colour, fontSize = pxToSp(px), maxLines = 1, softWrap = false)
            } else if (b.settings[F1LiveWidget.SHOW_FLAG]) {
                Spacer(Modifier.width(pxToDp(px * 0.5f)))
                EditorSlot("Flag and clock", px * 0.75f)
            }
        }

        @Composable
        fun RowScope.Who() {
            if (leader != null) {
                if (!twoLines) Text("  ·  ", color = Muted, fontSize = pxToSp(px), maxLines = 1, softWrap = false)
                Text(driverName(leader, b.names), color = b.colour, fontSize = pxToSp(px), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                val note = when {
                    b.live -> "leads"
                    session.isRace -> "won"
                    session.type == "Qualifying" -> "pole"
                    else -> "fastest"
                }
                Text(" $note", color = Muted, fontSize = pxToSp(px), maxLines = 1, softWrap = false)
            }
            if (me != null && f.level >= 1) {
                if (leader != null || !twoLines) Text("  ·  ", color = Muted, fontSize = pxToSp(px), maxLines = 1, softWrap = false)
                Text(
                    driverName(me, b.names) + " P${me.position}", color = b.accent, fontSize = pxToSp(px), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = if (f.probing) Modifier else Modifier.weight(1f, fill = false),
                )
                val gain = me.gained?.takeIf { session.isRace && b.started && it != 0 }
                if (gain != null) {
                    Spacer(Modifier.width(pxToDp(px * 0.3f)))
                    Gained(gain, px)
                } else if (b.settings[F1LiveWidget.GAINED]) {
                    Spacer(Modifier.width(pxToDp(px * 0.3f)))
                    EditorSlot("▲▼", px * 0.75f)
                }
            }
            if (b.live && b.settings[F1LiveWidget.NEWS]) {
                if (b.news != null) {
                    Text("  ·  ", color = Muted, fontSize = pxToSp(px), maxLines = 1, softWrap = false)
                    Text(b.news, color = Amber, fontSize = pxToSp(px), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                } else {
                    Spacer(Modifier.width(pxToDp(px * 0.5f)))
                    EditorSlot("Latest news", px * 0.75f)
                }
            }
        }

        if (twoLines) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { State() }
                Spacer(Modifier.height(pxToDp(px * 0.35f * f.scale.space)))
                Row(verticalAlignment = Alignment.CenterVertically) { Who() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                State()
                Who()
            }
        }
    }
}
