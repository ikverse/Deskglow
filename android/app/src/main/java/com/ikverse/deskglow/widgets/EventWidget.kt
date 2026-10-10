package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.min

object EventWidget : WidgetType {
    val SHOW_HEADING = FlagKey("showHeading", true)
    val SHOW_TIME = FlagKey("showTime", true)
    /** "in 25 min" once the event is under an hour away, and "ends in 40 min" while it is on. */
    val SHOW_SOON = FlagKey("showSoon", false)
    /** A thin line filling as the event under way runs its course. */
    val SHOW_PROGRESS = FlagKey("showProgress", false)

    override val id = "event"
    override val label = "Next event"
    override val blurb = "Your next calendar item"
    override val width = 176
    override val height = 64
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ShowField("Show", listOf(SHOW_HEADING to "NEXT heading", SHOW_TIME to "Time", SHOW_SOON to "Minutes away", SHOW_PROGRESS to "Progress while on")),
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
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                val heading = if (event != null && !event.allDay && event.start <= minute) "NOW" else "NEXT"
                if (settings[SHOW_HEADING]) Text(heading, color = Muted, fontSize = pxToSp(min(h * 0.17f, w * 0.06f)), letterSpacing = 0.08.em, maxLines = 1, textAlign = textAlign(align))
                Text(
                    event?.title ?: "No upcoming events",
                    color = if (event == null) Muted else Color(settings[Common.COLOUR]),
                    fontSize = pxToSp(min(h * 0.32f, w * 0.11f)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align),
                )
                if (event != null && settings[SHOW_TIME]) {
                    Text(
                        whenText(event, minute, h24, settings[SHOW_SOON]),
                        color = Muted, fontSize = pxToSp(min(h * 0.21f, w * 0.075f)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align),
                    )
                }
                if (event != null && settings[SHOW_PROGRESS] && !event.allDay && event.start <= minute && minute < event.end) {
                    val total = Duration.between(event.start, event.end).toMinutes().coerceAtLeast(1)
                    val done = (Duration.between(event.start, minute).toMinutes().toFloat() / total).coerceIn(0f, 1f)
                    val accent = Color(settings[Common.COLOUR])
                    Spacer(Modifier.height(pxToDp(h * 0.05f)))
                    Canvas(Modifier.width(pxToDp(min(w * 0.7f, h * 2.6f))).height(pxToDp((h * 0.05f).coerceAtLeast(3f)))) {
                        val y = size.height / 2
                        drawLine(Color(0xFF262626), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                        if (done > 0f) drawLine(accent.copy(alpha = 0.85f), Offset(0f, y), Offset(size.width * done, y), size.height, StrokeCap.Round)
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
