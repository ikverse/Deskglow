package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.IntKey
import com.ikverse.deskglow.model.Settings
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object EventWidget : WidgetType {
    val SHOW_HEADING = FlagKey("showHeading", true)
    val SHOW_TIME = FlagKey("showTime", true)
    /** "in 25 min" once the event is under an hour away, and "ends in 40 min" while it is on. */
    val SHOW_SOON = FlagKey("showSoon", false)
    /** A thin line filling as the event under way runs its course. */
    val SHOW_PROGRESS = FlagKey("showProgress", false)
    /** How many events: one in the usual layout, more as an agenda. */
    val COUNT = IntKey("count", 1)
    val SHOW_COLOUR = FlagKey("showColour", false)
    val SHOW_LOCATION = FlagKey("showLocation", false)

    override val id = "event"
    override val label = "Next event"
    override val blurb = "Your next calendar item"
    override val width = 176
    override val height = 64
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        SliderField("Events shown", COUNT, 1..5),
        ShowField(
            "Show",
            listOf(
                SHOW_HEADING to "NEXT heading", SHOW_TIME to "Time", SHOW_SOON to "Minutes away", SHOW_PROGRESS to "Progress while on",
                SHOW_COLOUR to "Calendar colour", SHOW_LOCATION to "Location",
            ),
        ),
        Common.timeFormatField(),
        Common.alignField,
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "Needs calendar access (Home › Permissions)."

    @Composable
    override fun Content(settings: Settings) {
        val state by LocalFeeds.current.nextEvent.collectAsStateWithLifecycle()
        val minute by LocalFeeds.current.minute.collectAsStateWithLifecycle()
        val h24 = Common.use24Hour(settings)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            val event = when (val s = state) {
                EventState.NoAccess -> return@BoxWithConstraints EditorHint("Allow calendar access", h * 0.22f)
                EventState.None -> null
                is EventState.Next -> s
            }
            val align = settings[Common.ALIGN]
            val upcoming = if (event == null) emptyList() else (listOf(event) + event.later.filter { it.end.isAfter(minute) }).take(settings[COUNT])
            if (upcoming.size > 1) return@BoxWithConstraints Agenda(upcoming, minute, h24, settings, w)
            val bar = if (settings[SHOW_COLOUR] && event?.colour != null) Color(event.colour) else null
            val running = event != null && !event.allDay && event.start <= minute && minute < event.end
            // The title is what grows; the heading and the time with it, more slowly.
            Fit(canvasUnit(w, width), listOf(settings, event == null, running, h24), align = alignFraction(align)) { f ->
                val s = f.scale
                val titleSize = s.main * 1.6f
                val barWidth = max(2f, titleSize * 0.14f)
                val frame = if (bar != null) {
                    Modifier.drawBehind { drawRoundRect(bar, Offset(0f, size.height * 0.08f), Size(barWidth, size.height * 0.84f), CornerRadius(barWidth / 2)) }.padding(start = pxToDp(barWidth * 2.6f))
                } else Modifier
                Column(frame, verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                    val heading = if (event != null && !event.allDay && event.start <= minute) "NOW" else "NEXT"
                    if (settings[SHOW_HEADING]) Text(heading, color = Muted, fontSize = pxToSp(s.small * 0.95f), letterSpacing = 0.08.em, maxLines = 1, textAlign = textAlign(align))
                    Text(
                        f.sample(event?.title ?: "No upcoming events", 20),
                        color = if (event == null) Muted else Color(settings[Common.COLOUR]),
                        fontSize = pxToSp(titleSize), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align),
                    )
                    if (event != null && settings[SHOW_TIME]) {
                        Text(
                            f.sample(whenText(event, minute, h24, settings[SHOW_SOON]) + (event.location.takeIf { settings[SHOW_LOCATION] && it.isNotBlank() }?.let { " · $it" } ?: ""), 30),
                            color = Muted, fontSize = pxToSp(s.second * 1.05f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align),
                        )
                    }
                    if (event != null && settings[SHOW_TIME] && settings[SHOW_LOCATION] && event.location.isBlank()) {
                        EditorSlot("Location", s.small * 0.95f)
                    }
                    if (event != null && settings[SHOW_PROGRESS] && !running) {
                        EditorSlot("Progress bar", s.small * 0.95f)
                    }
                    if (event != null && settings[SHOW_PROGRESS] && running) {
                        val total = Duration.between(event.start, event.end).toMinutes().coerceAtLeast(1)
                        val done = (Duration.between(event.start, minute).toMinutes().toFloat() / total).coerceIn(0f, 1f)
                        val accent = Color(settings[Common.COLOUR])
                        Spacer(Modifier.height(pxToDp(s.second * 0.4f * s.space)))
                        Canvas(Modifier.width(pxToDp(titleSize * 5f)).height(pxToDp((s.second * 0.25f).coerceAtLeast(3f)))) {
                            val y = size.height / 2
                            // The rounded ends reach half the bar's height past each end, so the line starts and stops that far in.
                            drawLine(Color(0xFF262626), Offset(y, y), Offset(size.width - y, y), size.height, StrokeCap.Round)
                            if (done > 0f) drawLine(accent.copy(alpha = 0.85f), Offset(y, y), Offset(y + (size.width - 2 * y) * done, y), size.height, StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }

    /**
     * "8:30 PM · Today", "All day · Tomorrow", "9:00 AM · Fri 9 Oct". With [soon], "in 25 min" in the hour
     * before the event and "ends in 40 min" in the last hour of one under way.
     */
    fun whenText(event: EventState.Next, now: LocalDateTime, h24: Boolean, soon: Boolean = false): String {
        if (soon && !event.allDay) {
            val until = Duration.between(now, event.start).toMinutes()
            val left = Duration.between(now, event.end).toMinutes()
            if (until in 1..59) return "in $until min"
            if (until <= 0 && left in 1..59) return "ends in $left min"
        }
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), event.start.toLocalDate())
        val day = when {
            days <= 0L -> "Today"
            days == 1L -> "Tomorrow"
            else -> event.start.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        }
        if (event.allDay) return "All day · $day"
        val time = event.start.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US))
        return "$time · $day"
    }
}

/** Several events, one to a row: its colour (if asked), when it starts, its title and (if asked) where. */
@Composable
private fun Agenda(events: List<EventState.Next>, now: LocalDateTime, h24: Boolean, settings: Settings, w: Float) {
    val heading = settings[EventWidget.SHOW_HEADING]
    val align = settings[Common.ALIGN]
    val colour = Color(settings[Common.COLOUR])
    // As large as the box allows; in a small box the later events are left off first (the first two always stay).
    Fit(canvasUnit(w, EventWidget.width), listOf(settings, events.size, h24), levels = events.size - 2, align = alignFraction(align)) { f ->
        val text = f.scale.main * 1.15f
        Column(if (f.probing) Modifier else Modifier.fillMaxWidth()) {
            if (heading) {
                Text(
                    "UP NEXT", color = Muted, fontSize = pxToSp(f.scale.small * 0.85f), letterSpacing = 0.08.em, maxLines = 1, textAlign = textAlign(align),
                    modifier = (if (f.probing) Modifier else Modifier.fillMaxWidth()).padding(bottom = pxToDp(text * 0.3f * f.scale.space)),
                )
            }
            events.take(2 + f.level).forEach { event ->
                Row(
                    (if (f.probing) Modifier else Modifier.fillMaxWidth()).padding(vertical = pxToDp(text * 0.25f * f.scale.space)),
                    horizontalArrangement = arrangementOf(align), verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (settings[EventWidget.SHOW_COLOUR]) {
                        Box(Modifier.width(pxToDp(text * 0.2f)).height(pxToDp(text * 1.1f)).background(Color(event.colour ?: 0xFF8C8C8C.toInt()), RoundedCornerShape(pxToDp(text * 0.1f))))
                        Spacer(Modifier.width(pxToDp(text * 0.4f)))
                    }
                    Text(agendaTime(event, now, h24), color = Muted, fontSize = pxToSp(text * 0.85f), maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(pxToDp(text * 0.5f)))
                    val flexible = if (f.probing) Modifier else Modifier.weight(1f, fill = false)
                    Text(f.sample(event.title, 16), color = colour, fontSize = pxToSp(text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = flexible)
                    val where = event.location.takeIf { settings[EventWidget.SHOW_LOCATION] && it.isNotBlank() }
                    if (where != null) {
                        Spacer(Modifier.width(pxToDp(text * 0.4f)))
                        Text(f.sample(where, 12), color = Muted, fontSize = pxToSp(text * 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = flexible)
                    }
                }
            }
        }
    }
}

/** "8:30 PM" today, "Fri 9:00 AM" another day, "All day" for a whole day. */
fun agendaTime(event: EventState.Next, now: LocalDateTime, h24: Boolean): String {
    if (event.allDay) return "All day"
    val time = DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US)
    val sameDay = event.start.toLocalDate() == now.toLocalDate()
    return if (sameDay) event.start.format(time) else event.start.format(DateTimeFormatter.ofPattern("EEE", Locale.UK)) + " " + event.start.format(time)
}
