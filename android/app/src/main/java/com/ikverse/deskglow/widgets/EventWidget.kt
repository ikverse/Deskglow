package com.ikverse.deskglow.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.min

object EventWidget : WidgetType {
    val SHOW_HEADING = FlagKey("showHeading", true)
    val SHOW_TIME = FlagKey("showTime", true)

    override val id = "event"
    override val label = "Next event"
    override val blurb = "Your next calendar item"
    override val width = 176
    override val height = 64
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ShowField("Show", listOf(SHOW_HEADING to "NEXT heading", SHOW_TIME to "Time")),
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
                        whenText(event, minute, h24),
                        color = Muted, fontSize = pxToSp(min(h * 0.21f, w * 0.075f)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align),
                    )
                }
            }
        }
    }

    /** "8:30 PM · Today", "All day · Tomorrow", "9:00 AM · Fri 9 Oct". */
    fun whenText(event: EventState.Next, now: LocalDateTime, h24: Boolean): String {
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
