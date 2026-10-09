package com.ikverse.deskglow.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1Roster
import com.ikverse.deskglow.data.LiveRow
import com.ikverse.deskglow.data.LiveSession
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.TrackFlag
import com.ikverse.deskglow.data.standingColumns
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.IntKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.min

object F1LiveWidget : WidgetType {
    val ROWS = IntKey("rows", 10)
    /** "leader" for the gap to the leader, "ahead" for the gap to the car in front. */
    val GAP = TextKey("gap", "leader")
    val FAVOURITE = TextKey("favourite", "")
    val SHOW_FLAG = FlagKey("showFlag", true)
    val ACCENT = ColourKey("accent", 0xFFE10600.toInt())

    override val id = "f1live"
    override val label = "F1 live session"
    override val blurb = "Live timing while a session runs, then its result until the next"
    override val width = 200
    override val height = 300
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        SliderField("Rows", ROWS, 3..20),
        ChoiceField("Gap", GAP, listOf("leader" to "To the leader", "ahead" to "To the car ahead")),
        ChoiceField("Favourite driver", FAVOURITE, listOf("" to "None") + F1Roster.drivers.map { (code, name) -> code to "$code · $name" }),
        ToggleField("Show the flag and session clock", SHOW_FLAG),
        ColourField("Accent colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) =
        "Live timing from Formula 1's own live-timing feed, which is unofficial and may stop working; results between sessions from it or from OpenF1. " +
            "Connects only while a session runs. Not affiliated with Formula 1."

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val state by feeds.f1Live.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            val hint = min(h * 0.1f, w * 0.08f)
            when (val s = state) {
                F1LiveState.Waiting -> EditorHint("Shows the next F1 session live", hint)
                is F1LiveState.Result -> LiveFace(settings, s.session, live = false, now = Instant.EPOCH, w, h)
                is F1LiveState.Live -> {
                    // The session clock is counted down here between the feed's updates, so it reads the second tick.
                    val second by feeds.second.collectAsStateWithLifecycle()
                    LiveFace(settings, s.session, live = true, now = second.atZone(ZoneId.systemDefault()).toInstant(), w, h)
                }
            }
        }
    }
}

/** "6:59", "1:02:03". */
internal fun clockText(left: Duration): String {
    val s = left.seconds.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** What the clock line says: "Lap 23/62" in a race, "Q2 · 6:59 left" in qualifying, "32:10 left" in practice. */
internal fun sessionProgress(session: LiveSession, now: Instant): String {
    if (session.isRace) {
        val lap = session.lap ?: return ""
        return if (session.totalLaps != null) "Lap $lap/${session.totalLaps}" else "Lap $lap"
    }
    val left = session.timeLeft(now)?.let { clockText(it) + " left" }.orEmpty()
    val part = session.part?.let { (if (session.name.startsWith("Sprint")) "SQ" else "Q") + it }
    return listOfNotNull(part, left.ifEmpty { null }).joinToString(" · ")
}

private fun flagLabel(flag: TrackFlag): Pair<String, Color>? = when (flag) {
    TrackFlag.Clear -> null
    TrackFlag.Yellow -> "YELLOW" to Color(0xFFFFD60A)
    TrackFlag.SafetyCar -> "SAFETY CAR" to Color(0xFFFFB000)
    TrackFlag.VirtualSafetyCar -> "VSC" to Color(0xFFFFB000)
    TrackFlag.VscEnding -> "VSC ENDING" to Color(0xFFFFB000)
    TrackFlag.Red -> "RED FLAG" to Color(0xFFE10600)
}

/**
 * The session's name and state over its classification: while [live], the LIVE badge, the flag and
 * the clock; afterwards "Result". The top rows, in two columns in a wide box, and the favourite
 * underneath when it is further down.
 */
@Composable
private fun LiveFace(settings: Settings, session: LiveSession, live: Boolean, now: Instant, w: Float, h: Float) {
    val colour = Color(settings[Common.COLOUR])
    val accent = Color(settings[F1LiveWidget.ACCENT])
    val favourite = settings[F1LiveWidget.FAVOURITE]
    val toAhead = settings[F1LiveWidget.GAP] == "ahead"
    val showFlag = settings[F1LiveWidget.SHOW_FLAG]
    val top = session.rows.take(settings[F1LiveWidget.ROWS])
    val extra = session.rows.drop(top.size).firstOrNull { favourite.isNotEmpty() && it.code == favourite }

    val twoColumns = top.size > 5 && w >= h * 1.25f
    val (left, right) = if (twoColumns) standingColumns(top) else top to emptyList()
    val columnGap = w * 0.06f
    val columnW = if (twoColumns) (w - columnGap) / 2 else w
    val headerSlots = if (live && showFlag) 2.4f else 1.4f
    val slots = left.size + headerSlots + (if (extra != null) 1.3f else 0f)
    val row = min(h / slots, columnW * 0.16f)
    val text = row * 0.56f

    @Composable
    fun Line(r: LiveRow) {
        val fav = favourite.isNotEmpty() && r.code == favourite
        val dim = r.knockedOut || r.out
        val main = (if (fav) accent else colour).let { if (dim) it.copy(alpha = 0.45f) else it }
        Row(Modifier.fillMaxWidth().height(pxToDp(row)), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${r.position}", color = Muted, fontSize = pxToSp(text * 0.9f), textAlign = TextAlign.End,
                maxLines = 1, softWrap = false, modifier = Modifier.width(pxToDp(text * 1.3f)),
            )
            Spacer(Modifier.width(pxToDp(text * 0.45f)))
            Box(
                Modifier.width(pxToDp(text * 0.2f)).height(pxToDp(row * 0.62f))
                    .background(Color(r.teamColour ?: 0xFF8C8C8C).let { if (dim) it.copy(alpha = 0.45f) else it }, RoundedCornerShape(pxToDp(text * 0.1f))),
            )
            Spacer(Modifier.width(pxToDp(text * 0.45f)))
            Text(
                r.code, color = main, fontSize = pxToSp(text), fontWeight = if (fav) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            val tag = when {
                r.out -> "OUT"
                r.inPit && live -> "PIT"
                else -> null
            }
            if (tag != null) {
                Text(
                    tag, color = Muted, fontSize = pxToSp(text * 0.6f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em, maxLines = 1, softWrap = false,
                    modifier = Modifier.background(Muted.copy(alpha = 0.18f), RoundedCornerShape(pxToDp(text * 0.2f))).padding(horizontal = pxToDp(text * 0.25f)),
                )
                Spacer(Modifier.width(pxToDp(text * 0.35f)))
            }
            Text(
                if (toAhead) r.interval else r.gap, color = if (fav) accent else Muted, fontSize = pxToSp(text * 0.85f),
                maxLines = 1, softWrap = false,
            )
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth().height(pxToDp(row * 1.4f)), verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                LiveBadge(accent, text * 0.7f)
                Spacer(Modifier.width(pxToDp(text * 0.4f)))
            }
            Text(
                session.name.replace("Qualifying", "Quali").uppercase(), color = colour, fontSize = pxToSp(text * 0.78f), fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.em, maxLines = 1, softWrap = false,
            )
            Spacer(Modifier.width(pxToDp(text * 0.5f)))
            Text(
                if (live) session.meeting else "Result · ${session.meeting}", color = Muted, fontSize = pxToSp(text * 0.72f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        if (live && showFlag) {
            Row(Modifier.fillMaxWidth().height(pxToDp(row)), verticalAlignment = Alignment.CenterVertically) {
                flagLabel(session.flag)?.let { (label, shade) ->
                    Text(
                        label, color = Color.Black, fontSize = pxToSp(text * 0.62f), fontWeight = FontWeight.Bold, letterSpacing = 0.08.em, maxLines = 1, softWrap = false,
                        modifier = Modifier.background(shade, RoundedCornerShape(pxToDp(text * 0.2f))).padding(horizontal = pxToDp(text * 0.35f), vertical = pxToDp(text * 0.06f)),
                    )
                    Spacer(Modifier.width(pxToDp(text * 0.5f)))
                }
                Text(sessionProgress(session, now), color = Muted, fontSize = pxToSp(text * 0.72f), maxLines = 1, softWrap = false)
            }
        }
        if (twoColumns) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { left.forEach { Line(it) } }
                Spacer(Modifier.width(pxToDp(columnGap)))
                Column(Modifier.weight(1f)) { right.forEach { Line(it) } }
            }
        } else {
            top.forEach { Line(it) }
        }
        if (extra != null) {
            Box(Modifier.fillMaxWidth().height(pxToDp(row * 0.3f)), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(pxToDp((row * 0.02f).coerceAtLeast(1f))).background(Color(0xFF2A2A2A)))
            }
            Line(extra)
        }
    }
}
