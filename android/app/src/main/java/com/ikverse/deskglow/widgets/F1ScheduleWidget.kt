package com.ikverse.deskglow.widgets

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.F1Race
import com.ikverse.deskglow.data.F1Session
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.countdownText
import com.ikverse.deskglow.data.trackRace
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

object F1ScheduleWidget : WidgetType {
    /** "list", "days", "timeline" or "strip". */
    val LAYOUT = TextKey("layout", "list")
    val CLOCK = TextKey("clock", "phone")
    val SHOW_DATES = FlagKey("showDates", false)
    val SHOW_COUNTDOWN = FlagKey("showCountdown", true)
    val DIM_PAST = FlagKey("dimPast", true)
    val ACCENT = ColourKey("accent", 0xFFE10600.toInt())

    override val id = "f1schedule"
    override val label = "F1 schedule"
    override val blurb = "The race weekend's sessions, with the one on now or next lit"
    override val width = 220
    override val height = 200
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ChoiceField("Layout", LAYOUT, listOf("list" to "List", "days" to "Days", "timeline" to "Timeline", "strip" to "Strip")),
        ChoiceField("Times", CLOCK, listOf("phone" to "Phone setting", "12" to "12-hour", "24" to "24-hour")),
        ToggleField("Show dates", SHOW_DATES),
        ToggleField("Show the countdown", SHOW_COUNTDOWN),
        ToggleField("Dim finished sessions", DIM_PAST),
        ColourField("Accent colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) =
        "F1 calendar from the Jolpica F1 API (api.jolpi.ca), times in your phone's time zone. Not affiliated with Formula 1."

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val state by feeds.f1.collectAsStateWithLifecycle()
        val minute by feeds.minute.collectAsStateWithLifecycle()
        val phone24 = DateFormat.is24HourFormat(LocalContext.current)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            val hint = min(h * 0.1f, w * 0.08f)
            val data = when (val s = state) {
                F1State.Loading -> return@BoxWithConstraints EditorHint("Loading the F1 calendar…", hint)
                is F1State.Failed -> s.last ?: return@BoxWithConstraints EditorHint("No F1 data yet", hint)
                is F1State.Ready -> s.data
            }
            val roughNow = minute.atZone(ZoneId.systemDefault()).toInstant()
            val race = trackRace(data, roughNow) ?: return@BoxWithConstraints Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Text("No races scheduled", color = Muted, fontSize = pxToSp(hint * 1.4f), maxLines = 1)
            }
            // The countdown shows seconds only in the last hour, so only then is the second tick read.
            val next = race.sessions.firstOrNull { it.start.isAfter(roughNow) }
            val fine = settings[SHOW_COUNTDOWN] && next != null && Duration.between(roughNow, next.start) < Duration.ofMinutes(61)
            val now = if (fine) {
                val second by feeds.second.collectAsStateWithLifecycle()
                second.atZone(ZoneId.systemDefault()).toInstant()
            } else roughNow
            val h24 = when (settings[CLOCK]) { "12" -> false; "24" -> true; else -> phone24 }
            val plan = Plan(race, now, settings, h24)
            when (settings[LAYOUT]) {
                "days" -> DaysLayout(plan, w, h)
                "timeline" -> TimelineLayout(plan, w, h)
                "strip" -> StripLayout(plan, w, h)
                else -> ListLayout(plan, w, h)
            }
        }
    }
}

/** One weekend at one moment: which session is running or next, how each is shaded, and how times read. */
private class Plan(val race: F1Race, val now: Instant, settings: Settings, h24: Boolean) {
    val colour = Color(settings[Common.COLOUR])
    val accent = Color(settings[F1ScheduleWidget.ACCENT])
    val dates = settings[F1ScheduleWidget.SHOW_DATES]
    private val dim = settings[F1ScheduleWidget.DIM_PAST]
    val sessions = race.sessions
    private val live = sessions.firstOrNull { it.liveAt(now) }
    private val next = sessions.firstOrNull { it.start.isAfter(now) }
    /** The session running, or else the next to start; lit in the accent colour. */
    val current = live ?: next
    /** "LIVE", "in 3 h 12 m", or null when the countdown is off or the weekend is over. */
    val chip: String? = when {
        !settings[F1ScheduleWidget.SHOW_COUNTDOWN] -> null
        live != null -> "LIVE"
        next != null -> "in " + countdownText(now, next.start)
        else -> null
    }
    private val zone = ZoneId.systemDefault()
    private val timeFormat = DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US)

    fun local(session: F1Session): ZonedDateTime = session.start.atZone(zone)
    fun time(session: F1Session): String = local(session).format(timeFormat)
    fun past(session: F1Session) = !session.end.isAfter(now)

    fun shade(session: F1Session): Color = when {
        session == current -> accent
        past(session) -> if (dim) Muted.copy(alpha = 0.4f) else Muted
        else -> colour
    }

    /** "FRI", or with dates on "FRI 9 OCT" ([long]) or "FRI 9". */
    fun day(session: F1Session, long: Boolean = true): String =
        local(session).format(DateTimeFormatter.ofPattern(if (!dates) "EEE" else if (long) "EEE d MMM" else "EEE d", Locale.UK)).uppercase()
}

/**
 * The race and where it is, with the countdown chip at the far end. A long name in a narrow box
 * loses its place first and then shrinks, rather than pushing the chip off the edge; the widths are
 * rough, in units of the text size.
 */
@Composable
private fun HeaderRow(plan: Plan, px: Float, w: Float, withChip: Boolean = true) {
    val chip = plan.chip.takeIf { withChip }
    val name = 0.8f + plan.race.name.length * 0.74f
    val place = if (plan.race.place.isEmpty()) 0f else 0.6f + plan.race.place.length * 0.56f
    val chipUnits = if (chip == null) 0f else 0.5f + (chip.length * 0.62f + 1.1f) * 0.85f
    val room = w * 0.86f
    val showPlace = px * (name + place + chipUnits) <= room
    val size = if (px * (name + chipUnits) <= room) px else room / (name + chipUnits)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Header(plan.race.name, if (showPlace) plan.race.place else "", plan.accent, plan.colour, size) }
        if (chip != null) {
            Spacer(Modifier.width(pxToDp(size * 0.5f)))
            Chip(chip, plan.accent, size * 0.85f)
        }
    }
}

@Composable
private fun Chip(text: String, accent: Color, px: Float) {
    Text(
        text, color = accent, fontSize = pxToSp(px), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
        modifier = Modifier.background(accent.copy(alpha = 0.18f), RoundedCornerShape(pxToDp(px * 0.4f))).padding(horizontal = pxToDp(px * 0.5f), vertical = pxToDp(px * 0.12f)),
    )
}

/** A row per session: the day (on the first of each day), the session, the chip beside the one on or next, the time. */
@Composable
private fun ListLayout(plan: Plan, w: Float, h: Float) {
    val row = min(h / (plan.sessions.size + 1.5f), w * 0.13f)
    val text = row * 0.5f
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().height(pxToDp(row * 1.3f)), contentAlignment = Alignment.CenterStart) { HeaderRow(plan, text * 0.85f, w, withChip = false) }
        plan.sessions.forEachIndexed { i, session ->
            val newDay = i == 0 || plan.local(plan.sessions[i - 1]).toLocalDate() != plan.local(session).toLocalDate()
            val shade = plan.shade(session)
            val isCurrent = session == plan.current
            Row(Modifier.fillMaxWidth().height(pxToDp(row)), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (newDay) plan.day(session) else "", color = Muted, fontSize = pxToSp(text * 0.8f), fontWeight = FontWeight.Medium,
                    letterSpacing = 0.08.em, maxLines = 1, softWrap = false, modifier = Modifier.width(pxToDp(text * if (plan.dates) 5.4f else 2.6f)),
                )
                Text(
                    shortKind(session.kind), color = shade, fontSize = pxToSp(text), fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                val chip = plan.chip
                if (isCurrent && chip != null) {
                    Chip(chip, plan.accent, text * 0.72f)
                    Spacer(Modifier.width(pxToDp(text * 0.5f)))
                }
                Text(plan.time(session), color = shade, fontSize = pxToSp(text * 0.95f), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A column per day, its sessions and times underneath. */
@Composable
private fun DaysLayout(plan: Plan, w: Float, h: Float) {
    val days = plan.sessions.groupBy { plan.local(it).toLocalDate() }
    val most = days.values.maxOf { it.size }
    val px = min(h / (3.2f + most * 2.9f + if (plan.dates) 1f else 0f), w / days.size * 0.12f)
    val dayFormat = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        HeaderRow(plan, px, w)
        Spacer(Modifier.height(pxToDp(px * 0.9f)))
        Row(Modifier.fillMaxWidth()) {
            days.forEach { (date, sessions) ->
                Column(Modifier.weight(1f)) {
                    Text(
                        date.format(dayFormat).uppercase(), color = if (plan.current in sessions) plan.accent else Muted,
                        fontSize = pxToSp(px * 0.9f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.em, maxLines = 1, softWrap = false,
                    )
                    if (plan.dates) Text(date.format(dateFormat).uppercase(), color = Muted, fontSize = pxToSp(px * 0.75f), maxLines = 1, softWrap = false)
                    Spacer(Modifier.height(pxToDp(px * 0.4f)))
                    Box(Modifier.fillMaxWidth(0.85f).height(pxToDp((px * 0.06f).coerceAtLeast(1f))).background(Muted.copy(alpha = 0.3f)))
                    Spacer(Modifier.height(pxToDp(px * 0.5f)))
                    sessions.forEach { session ->
                        val shade = plan.shade(session)
                        Text(shortKind(session.kind), color = shade, fontSize = pxToSp(px * 0.85f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(plan.time(session), color = shade, fontSize = pxToSp(px), maxLines = 1, softWrap = false)
                        Spacer(Modifier.height(pxToDp(px * 0.5f)))
                    }
                }
            }
        }
    }
}

/**
 * The weekend as a line from first session to last, a dot for each, filled in the accent up to now:
 * the session's name above its dot, its day and time below.
 */
@Composable
private fun TimelineLayout(plan: Plan, w: Float, h: Float) {
    val count = plan.sessions.size
    val px = min(h * 0.12f, w / count * 0.17f)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        HeaderRow(plan, px, w)
        Spacer(Modifier.height(pxToDp(px * 1.0f)))
        Row(Modifier.fillMaxWidth()) {
            plan.sessions.forEach { session ->
                Text(
                    shortKind(session.kind), color = plan.shade(session), fontSize = pxToSp(px * 0.85f), fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(pxToDp(px * 0.3f)))
        val progress = timelineProgress(plan.sessions, plan.now)
        Canvas(Modifier.fillMaxWidth().height(pxToDp(px * 1.4f))) {
            val cell = size.width / count
            val y = size.height / 2
            fun x(i: Float) = (i + 0.5f) * cell
            val line = (px * 0.14f).coerceAtLeast(1.5f)
            val radius = px * 0.3f
            drawLine(Muted.copy(alpha = 0.35f), Offset(x(0f), y), Offset(x(count - 1f), y), line, StrokeCap.Round)
            if (progress > 0f) drawLine(plan.accent, Offset(x(0f), y), Offset(x(progress), y), line, StrokeCap.Round)
            plan.sessions.forEachIndexed { i, session ->
                val centre = Offset(x(i.toFloat()), y)
                when {
                    session == plan.current -> {
                        drawCircle(plan.accent.copy(alpha = 0.3f), radius * 2f, centre)
                        drawCircle(plan.accent, radius * 1.2f, centre)
                    }
                    plan.past(session) -> drawCircle(plan.accent, radius, centre)
                    else -> drawCircle(Muted, radius, centre)
                }
            }
        }
        Spacer(Modifier.height(pxToDp(px * 0.3f)))
        Row(Modifier.fillMaxWidth()) {
            plan.sessions.forEach { session ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(plan.day(session, long = false), color = Muted, fontSize = pxToSp(px * 0.7f), fontWeight = FontWeight.Medium, letterSpacing = 0.08.em, maxLines = 1, softWrap = false)
                    Text(plan.time(session), color = plan.shade(session), fontSize = pxToSp(px * 0.85f), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * How far along the timeline now is, in sessions: 0 at the first session's start, 1 at the second's,
 * and between them in proportion to the time; 0 before the weekend and the last index after it.
 */
internal fun timelineProgress(sessions: List<F1Session>, now: Instant): Float {
    val started = sessions.indexOfLast { !it.start.isAfter(now) }
    if (started < 0) return 0f
    if (started == sessions.lastIndex) return started.toFloat()
    val from = sessions[started].start
    val span = Duration.between(from, sessions[started + 1].start).toMillis().coerceAtLeast(1)
    return started + Duration.between(from, now).toMillis().toFloat() / span
}

/** Roughly how wide a session's label and time are, in units of the text size. */
private fun stripItemWidth(plan: Plan, session: F1Session) =
    maxOf("${shortKind(session.kind)} · ${plan.day(session, long = false)}".length * 0.55f, plan.time(session).length * 0.5f)

/**
 * Every session, "FP1 · FRI" over its time: spread along one line when they fit, otherwise set
 * close together and running onto as many lines as the box has room for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StripLayout(plan: Plan, w: Float, h: Float) {
    val gap = 1.4f
    val oneLine = plan.sessions.sumOf { stripItemWidth(plan, it).toDouble() }.toFloat() + (plan.sessions.size - 1) * gap
    val single = w >= h && min(h * 0.2f, w * 0.042f) * oneLine <= w
    var px = if (single) min(h * 0.2f, w * 0.042f) else min(h * 0.2f, w * 0.06f)
    if (!single) repeat(2) {
        val rows = Math.ceil((oneLine * px / w).toDouble()).toInt().coerceAtLeast(1)
        px = min(px, h / (3.2f + 2.5f * rows))
    }
    val items: @Composable () -> Unit = {
        plan.sessions.forEach { session ->
            val shade = plan.shade(session)
            Column {
                Text(
                    "${shortKind(session.kind)} · ${plan.day(session, long = false)}".uppercase(), color = shade,
                    fontSize = pxToSp(px * 0.78f), fontWeight = FontWeight.Medium, letterSpacing = 0.05.em, maxLines = 1, softWrap = false,
                )
                Text(plan.time(session), color = shade, fontSize = pxToSp(px * 0.95f), maxLines = 1, softWrap = false)
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        HeaderRow(plan, px, w)
        Spacer(Modifier.height(pxToDp(px * 0.8f)))
        if (single) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { items() }
        } else {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(pxToDp(px * gap)),
                verticalArrangement = Arrangement.spacedBy(pxToDp(px * 0.6f)),
            ) { items() }
        }
    }
}
