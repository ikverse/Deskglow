package com.ikverse.deskglow.widgets

import android.text.format.DateFormat
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.F1Data
import com.ikverse.deskglow.data.F1Race
import com.ikverse.deskglow.data.F1Result
import com.ikverse.deskglow.data.F1Roster
import com.ikverse.deskglow.data.F1Session
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.WeekendView
import com.ikverse.deskglow.data.countdownText
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
    val SHOW_SCHEDULE = FlagKey("showSchedule", true)
    val FAVOURITE = TextKey("favourite", "")
    val CLOCK = TextKey("clock", "phone")
    val ACCENT = ColourKey("accent", 0xFFE10600.toInt())

    override val id = "f1weekend"
    override val label = "F1 race weekend"
    override val blurb = "Countdown to the next session, and the weekend's schedule"
    override val width = 372
    override val height = 112
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ToggleField("Show the weekend's schedule", SHOW_SCHEDULE),
        ChoiceField("Favourite driver", FAVOURITE, listOf("" to "None") + F1Roster.drivers.map { (code, name) -> code to "$code · $name" }),
        ChoiceField("Times", CLOCK, listOf("phone" to "Phone setting", "12" to "12-hour", "24" to "24-hour")),
        ColourField("Accent colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "F1 data from the Jolpica F1 API (api.jolpi.ca), in your phone's time zone. Not affiliated with Formula 1."

    @Composable
    override fun Content(settings: Settings) {
        val feeds = LocalFeeds.current
        val state by feeds.f1.collectAsStateWithLifecycle()
        val minute by feeds.minute.collectAsStateWithLifecycle()
        val phone24 = DateFormat.is24HourFormat(LocalContext.current)
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
            val h24 = when (settings[CLOCK]) { "12" -> false; "24" -> true; else -> phone24 }
            WeekendFace(settings, data, weekendView(data, now), now, h24)
        }
    }
}

@Composable
private fun WeekendFace(settings: Settings, data: F1Data, view: WeekendView, now: Instant, h24: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        val w = constraints.maxWidth.toFloat()
        val colour = Color(settings[Common.COLOUR])
        val accent = Color(settings[F1WeekendWidget.ACCENT])
        val small = min(h * 0.13f, w * 0.04f)
        val big = min(h * 0.3f, w * 0.085f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            when (view) {
                WeekendView.Empty -> Text("No races scheduled", color = Muted, fontSize = pxToSp(small * 1.3f))
                is WeekendView.AfterRace -> Podium(view.result, view.next, data, now, settings[F1WeekendWidget.FAVOURITE], colour, accent, small, big)
                is WeekendView.Upcoming -> {
                    Header(view.race.name, view.race.place, accent, colour, small)
                    Spacer(Modifier.height(pxToDp(h * 0.04f)))
                    val live = view.live
                    val next = view.next
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (live != null) {
                            LiveBadge(accent, small)
                            Spacer(Modifier.width(pxToDp(small * 0.6f)))
                            Text(live.kind, color = colour, fontSize = pxToSp(big), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                        } else if (next != null) {
                            Text(next.kind, color = Muted, fontSize = pxToSp(big * 0.62f), maxLines = 1, softWrap = false)
                            Spacer(Modifier.width(pxToDp(big * 0.3f)))
                            Text(countdownText(now, next.start), color = colour, fontSize = pxToSp(big), fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
                        }
                    }
                    if (settings[F1WeekendWidget.SHOW_SCHEDULE]) {
                        Spacer(Modifier.height(pxToDp(h * 0.06f)))
                        Schedule(view.race.sessions, view.live ?: view.next, now, h24, accent, small)
                    }
                }
            }
        }
    }
}

/** A short upright bar in the accent colour, then the race and where it is. */
@Composable
private fun Header(race: String, place: String, accent: Color, colour: Color, px: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(pxToDp(px * 0.28f)).height(pxToDp(px * 1.1f)).background(accent, RoundedCornerShape(pxToDp(px * 0.14f))))
        Spacer(Modifier.width(pxToDp(px * 0.5f)))
        Text(race.uppercase(), color = colour, fontSize = pxToSp(px * 1.05f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em, maxLines = 1, softWrap = false)
        if (place.isNotEmpty()) {
            Spacer(Modifier.width(pxToDp(px * 0.6f)))
            Text(place, color = Muted, fontSize = pxToSp(px), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LiveBadge(accent: Color, px: Float) {
    Row(
        Modifier.background(accent, RoundedCornerShape(pxToDp(px * 0.3f))).padding(horizontal = pxToDp(px * 0.55f), vertical = pxToDp(px * 0.2f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(pxToDp(px * 0.5f)).background(Color.White, CircleShape))
        Spacer(Modifier.width(pxToDp(px * 0.35f)))
        Text("LIVE", color = Color.White, fontSize = pxToSp(px * 1.05f), fontWeight = FontWeight.Bold, letterSpacing = 0.08.em, maxLines = 1, softWrap = false)
    }
}

/** Each session: its name and day ("FP1 · FRI") over its time. Past ones dim; the one running or next is lit. */
@Composable
private fun Schedule(sessions: List<F1Session>, current: F1Session?, now: Instant, h24: Boolean, accent: Color, px: Float) {
    val zone = ZoneId.systemDefault()
    val day = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    val time = DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        sessions.forEach { session ->
            val shade = when {
                session == current -> accent
                !session.end.isAfter(now) -> Muted.copy(alpha = 0.4f)
                else -> Muted
            }
            val local = session.start.atZone(zone)
            Column {
                Text(
                    "${session.kind.replace("Qualifying", "Quali")} · ${local.format(day)}".uppercase(), color = shade,
                    fontSize = pxToSp(px * 0.78f), fontWeight = FontWeight.Medium, letterSpacing = 0.05.em, maxLines = 1, softWrap = false,
                )
                Text(local.format(time), color = shade, fontSize = pxToSp(px * 0.95f), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The top three of the last race, each with a team-colour bar; the favourite lit; the next race below. */
@Composable
private fun Podium(result: F1Result, next: F1Race?, data: F1Data, now: Instant, favourite: String, colour: Color, accent: Color, small: Float, big: Float) {
    val race = data.races.firstOrNull { it.round == result.round }
    Header(result.raceName, "Result", accent, colour, small)
    Spacer(Modifier.height(pxToDp(small * 0.5f)))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        result.podium.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${entry.position}", color = Muted, fontSize = pxToSp(big * 0.6f), fontWeight = FontWeight.Light, maxLines = 1)
                Spacer(Modifier.width(pxToDp(big * 0.18f)))
                Box(Modifier.width(pxToDp(big * 0.1f)).height(pxToDp(big * 0.78f)).background(Color(teamColour(entry.teamId)), RoundedCornerShape(pxToDp(big * 0.05f))))
                Spacer(Modifier.width(pxToDp(big * 0.18f)))
                Text(
                    entry.id, color = if (entry.id == favourite) accent else colour,
                    fontSize = pxToSp(big * 0.82f), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                )
            }
        }
    }
    if (next != null) {
        Spacer(Modifier.height(pxToDp(small * 0.5f)))
        Text("Next · ${next.name} · in ${countdownText(now, next.first.start)}", color = Muted, fontSize = pxToSp(small), maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else if (race != null) {
        Spacer(Modifier.height(pxToDp(small * 0.5f)))
        Text("Season complete", color = Muted, fontSize = pxToSp(small), maxLines = 1)
    }
}
