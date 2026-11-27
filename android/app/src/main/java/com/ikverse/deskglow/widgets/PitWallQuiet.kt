package com.ikverse.deskglow.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.ikverse.deskglow.data.F1Data
import com.ikverse.deskglow.data.F1Entry
import com.ikverse.deskglow.data.F1Race
import com.ikverse.deskglow.data.F1Result
import com.ikverse.deskglow.data.F1Seasons
import com.ikverse.deskglow.data.F1Session
import com.ikverse.deskglow.data.F1Track
import com.ikverse.deskglow.data.SeasonRound
import com.ikverse.deskglow.data.countdownText
import com.ikverse.deskglow.data.teamColour
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of a PitWall table: [lead] (a place or a year), a bar in a team's colour, [name], and [trail] at the far end. */
internal class PitRow(val key: String, val lead: String, val bar: Color?, val name: String, val trail: String, val lit: Boolean = false, val trailColour: Color? = null)

private fun pointsText(points: Double): String = if (points % 1.0 == 0.0) points.toInt().toString() else points.toString()

private fun teamShade(teamId: String) = Color(teamColour(teamId))

private fun driverRows(entries: List<F1Entry>, favourite: String) =
    entries.map { PitRow(it.id, "${it.position}", teamShade(it.teamId), it.id, pointsText(it.points), lit = favourite.isNotEmpty() && it.id == favourite) }

private fun teamRows(entries: List<F1Entry>, favourite: String) =
    entries.map { PitRow(it.id, "${it.position}", teamShade(it.id), it.name, pointsText(it.points), lit = favourite.isNotEmpty() && it.id == favourite) }

/** A small grey heading over a part of the screen. */
@Composable
private fun Label(text: String, look: PitLook) {
    Text(text, color = Muted, fontSize = pxToSp(look.u * 11f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(pxToDp(look.u * 18f)))
}

/**
 * [title] over as many of [rows] as the height holds. A row whose place changes (the standings after
 * a race) slides to its new one.
 */
@Composable
internal fun PitTable(title: String, rows: List<PitRow>, look: PitLook, modifier: Modifier, leadWidth: Float = 1.6f) {
    val u = look.u
    val row = u * 24f
    BoxWithConstraints(modifier) {
        val count = ((constraints.maxHeight - u * 18f) / row).toInt().coerceAtLeast(0)
        Column(Modifier.fillMaxSize()) {
            Label(title, look)
            LazyColumn(Modifier.fillMaxWidth().height(pxToDp(row * count)), userScrollEnabled = false) {
                items(rows.take(count), key = { it.key }) { r ->
                    PitLine(
                        r, look, row, leadWidth,
                        Modifier.animateItem(fadeInSpec = pitSpec(look.animate, 300), placementSpec = pitPlacement(look.animate), fadeOutSpec = pitSpec(look.animate, 300)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PitLine(r: PitRow, look: PitLook, row: Float, leadWidth: Float, modifier: Modifier = Modifier) {
    val px = row * 0.56f
    Row(modifier.fillMaxWidth().height(pxToDp(row)), verticalAlignment = Alignment.CenterVertically) {
        if (r.lead.isNotEmpty()) {
            Text(r.lead, color = Muted, fontSize = pxToSp(px * 0.85f), maxLines = 1, softWrap = false, textAlign = TextAlign.End, modifier = Modifier.width(pxToDp(px * leadWidth)))
            Spacer(Modifier.width(pxToDp(px * 0.5f)))
        }
        if (r.bar != null) {
            Box(Modifier.width(pxToDp(px * 0.22f)).height(pxToDp(px)).background(r.bar, RoundedCornerShape(pxToDp(px * 0.11f))))
            Spacer(Modifier.width(pxToDp(px * 0.5f)))
        }
        Text(
            r.name, color = if (r.lit) look.accent else look.colour, fontSize = pxToSp(px), fontWeight = if (r.lit) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(pxToDp(px * 0.5f)))
        Text(r.trail, color = r.trailColour ?: Muted, fontSize = pxToSp(px * 0.85f), maxLines = 1, softWrap = false)
    }
}

private fun kindWords(kind: String) = when (kind) {
    "FP1" -> "first practice"
    "FP2" -> "second practice"
    "FP3" -> "third practice"
    "Sprint Quali" -> "sprint qualifying"
    else -> kind.lowercase(Locale.US)
}

private fun kindName(kind: String) = when (kind) {
    "FP1" -> "Practice 1"
    "FP2" -> "Practice 2"
    "FP3" -> "Practice 3"
    else -> kind
}

private fun whenText(at: Instant, h24: Boolean): String =
    at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (h24) "EEE HH:mm" else "EEE h:mm a", Locale.US))

private fun dayText(at: Instant): String = at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))

/** The race to come: its name and place, the time to its next session counting down, and when each session is. */
@Composable
private fun NextRace(race: F1Race, now: Instant, look: PitLook, sessions: Int) {
    val u = look.u
    val coming = race.sessions.firstOrNull { it.start.isAfter(now) }
    Label("Next race · round ${race.round}", look)
    Text(race.name, color = look.colour, fontSize = pxToSp(u * 21f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (race.place.isNotEmpty()) Text(race.place, color = Muted, fontSize = pxToSp(u * 12f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (coming != null) {
        Spacer(Modifier.height(pxToDp(u * 8f)))
        RollingText(countdownText(now, coming.start), look.colour, u * 38f, look.animate, weight = FontWeight.SemiBold)
        Text("until ${kindWords(coming.kind)}", color = Muted, fontSize = pxToSp(u * 12f), maxLines = 1)
    }
    Spacer(Modifier.height(pxToDp(u * 8f)))
    // The sessions still to come first: on a short screen the ones already run are the ones left out.
    val listed: List<F1Session> = race.sessions.filter { it.end.isAfter(now) }.ifEmpty { race.sessions }.takeLast(sessions)
    listed.forEach { s ->
        PitLine(PitRow(s.kind, "", null, kindName(s.kind), whenText(s.start, look.h24), lit = s.kind == "Race", trailColour = look.colour), look, u * 22f, 0f)
    }
}

/** "PIA · VER · SAI", each in its team's colour. */
@Composable
private fun LastRace(last: F1Result, look: PitLook) {
    Label("Last race · ${last.raceName}", look)
    Text(
        buildAnnotatedString {
            last.podium.forEachIndexed { i, e ->
                if (i > 0) withStyle(SpanStyle(color = Muted)) { append("  ·  ") }
                withStyle(SpanStyle(color = teamShade(e.teamId))) { append(e.id) }
            }
        },
        fontSize = pxToSp(look.u * 13f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * PitWall between race weekends: the race to come and its countdown, and the drivers' and
 * constructors' tables as far down as the screen reaches. On its side these are three columns, with
 * the circuit's outline under the countdown; upright they are stacked.
 */
@Composable
internal fun PitWallBetweenFace(look: PitLook, race: F1Race, data: F1Data, last: F1Result?, now: Instant, track: F1Track?) {
    val u = look.u
    val drivers = driverRows(data.drivers, look.driver)
    val teams = teamRows(data.constructors, look.team)
    if (look.landscape) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                NextRace(race, now, look, sessions = 5)
                if (track != null) {
                    DrawnOutline(track, look.colour.copy(alpha = 0.35f), look.animate && look.entered, Modifier.fillMaxWidth().weight(1f).padding(vertical = pxToDp(u * 6f)))
                } else Spacer(Modifier.weight(1f))
                if (last != null) LastRace(last, look)
            }
            Spacer(Modifier.width(pxToDp(u * 24f)))
            PitTable("Drivers", drivers, look, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(pxToDp(u * 24f)))
            PitTable("Constructors", teams, look, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            NextRace(race, now, look, sessions = 3)
            Spacer(Modifier.height(pxToDp(u * 10f)))
            PitTable("Drivers", drivers, look, Modifier.fillMaxWidth().weight(1.5f))
            PitTable("Constructors", teams, look, Modifier.fillMaxWidth().weight(1f))
            if (last != null) LastRace(last, look)
        }
    }
}

/** The days to the new season's first session, large, and which race that is. */
@Composable
private fun LightsOut(season: Int, first: F1Race?, now: Instant, look: PitLook) {
    val u = look.u
    Label("Lights out · ${season + 1}", look)
    if (first == null) {
        Text("Calendar to come", color = Muted, fontSize = pxToSp(u * 16f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val days = Duration.between(now, first.first.start).toDays().coerceAtLeast(0)
    Row(verticalAlignment = Alignment.Bottom) {
        RollingText("$days", look.colour, u * 40f, look.animate, weight = FontWeight.SemiBold)
        Text(if (days == 1L) " day" else " days", color = Muted, fontSize = pxToSp(u * 16f), maxLines = 1, modifier = Modifier.padding(bottom = pxToDp(u * 6f)))
    }
    Text(
        buildAnnotatedString {
            append(first.name)
            withStyle(SpanStyle(color = Muted)) { append("  ·  ${dayText(first.race.start)}") }
        },
        color = look.colour, fontSize = pxToSp(u * 13f), maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** The finished season's champions: the driver large in the team's colour, the constructors' champion under it. */
@Composable
private fun Champions(season: Int, driver: F1Entry?, team: F1Entry?, look: PitLook) {
    val u = look.u
    Label("$season champions", look)
    if (driver == null && team == null) {
        Text("Results to come", color = Muted, fontSize = pxToSp(u * 16f), maxLines = 1)
        return
    }
    if (driver != null) {
        Text(driver.name.ifEmpty { driver.id }, color = teamShade(driver.teamId), fontSize = pxToSp(u * 21f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (team != null) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = teamShade(team.id))) { append(team.name) }
                append("  ·  constructors")
            },
            color = Muted, fontSize = pxToSp(u * 12f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One race of the season gone by: who won, who was on pole, and the podium. A new race fades in each minute. */
@Composable
private fun Review(rounds: List<SeasonRound>, minute: Long, look: PitLook) {
    if (rounds.isEmpty()) return
    val u = look.u
    val round = rounds[Math.floorMod(minute, rounds.size.toLong()).toInt()]
    val last = rounds.maxOf { it.round }
    Box(
        Modifier.fillMaxWidth().border(pxToDp((u * 0.6f).coerceAtLeast(1f)), WidgetPalette.Rule, RoundedCornerShape(pxToDp(u * 8f)))
            .padding(horizontal = pxToDp(u * 10f), vertical = pxToDp(u * 7f)),
    ) {
        AnimatedContent(round, transitionSpec = { pitFade(look.animate, 600) }, label = "season in review") { r ->
            Column(Modifier.fillMaxWidth()) {
                Text("Season in review · round ${r.round} of $last", color = Muted, fontSize = pxToSp(u * 11f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildAnnotatedString {
                        append(r.name)
                        r.winner?.let { w ->
                            withStyle(SpanStyle(color = Muted)) { append("  ·  ") }
                            withStyle(SpanStyle(color = teamShade(w.teamId), fontWeight = FontWeight.SemiBold)) { append(w.id) }
                            withStyle(SpanStyle(color = Muted)) { append(" won") }
                        }
                    },
                    color = look.colour, fontSize = pxToSp(u * 13f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val pole = r.pole?.let { "Pole ${it.id}" }
                val podium = r.podium.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "podium ") { it.id }
                Text(listOfNotNull(pole, podium).joinToString("  ·  "), color = Muted, fontSize = pxToSp(u * 11.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * PitWall between seasons: the days to the first race, the champions and the final table of the
 * season gone by, the champions of the years before it, the new calendar once it is out, and the
 * finished season race by race. [minute] counts minutes, and picks the race looked back on.
 */
@Composable
internal fun PitWallOffSeasonFace(look: PitLook, season: Int, first: F1Race?, seasons: F1Seasons?, data: F1Data?, now: Instant, minute: Long) {
    val u = look.u
    // Until the look back is fetched, the tables as they stood are the season's own, while the calendar is still that season's.
    val drivers = seasons?.takeIf { it.season == season }?.drivers?.takeIf { it.isNotEmpty() } ?: data?.drivers?.takeIf { data.races.isNotEmpty() }.orEmpty()
    val constructors = seasons?.takeIf { it.season == season }?.constructors?.takeIf { it.isNotEmpty() } ?: data?.constructors?.takeIf { data.races.isNotEmpty() }.orEmpty()
    val settings = look.settings
    val honour = seasons?.honour.orEmpty().takeIf { settings[PitWallWidget.SHOW_HONOUR] }.orEmpty().map { c ->
        PitRow(
            "${c.year}", "${c.year}", c.driver?.let { teamShade(it.teamId) }, c.driver?.name?.substringAfterLast(' ').orEmpty(), c.team?.name.orEmpty(),
            trailColour = c.team?.let { teamShade(it.id) },
        )
    }
    val calendar = seasons?.next.orEmpty().takeIf { settings[PitWallWidget.SHOW_CALENDAR] }.orEmpty().filter { it.race.end.isAfter(now) }.map { r ->
        PitRow("${r.round}", "${r.round}", null, r.name.removeSuffix(" GP"), dayText(r.race.start))
    }
    val rounds = seasons?.takeIf { it.season == season }?.rounds.orEmpty().takeIf { settings[PitWallWidget.SHOW_REVIEW] }.orEmpty()
    val table = driverRows(drivers, look.driver)
    val gap = pxToDp(u * 10f)
    if (look.landscape) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LightsOut(season, first, now, look)
                Spacer(Modifier.height(gap))
                if (calendar.isNotEmpty()) PitTable("${season + 1} calendar", calendar, look, Modifier.fillMaxWidth().weight(1f)) else Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(pxToDp(u * 24f)))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Champions(season, drivers.firstOrNull(), constructors.firstOrNull(), look)
                Spacer(Modifier.height(gap))
                PitTable("Final standings", table, look, Modifier.fillMaxWidth().weight(1f))
            }
            Spacer(Modifier.width(pxToDp(u * 24f)))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (honour.isNotEmpty()) PitTable("Past champions", honour, look, Modifier.fillMaxWidth().weight(1f), leadWidth = 2.6f) else Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(gap))
                Review(rounds, minute, look)
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            LightsOut(season, first, now, look)
            Spacer(Modifier.height(gap))
            Champions(season, drivers.firstOrNull(), constructors.firstOrNull(), look)
            Spacer(Modifier.height(gap))
            PitTable("Final standings", table, look, Modifier.fillMaxWidth().weight(1.2f))
            if (honour.isNotEmpty()) PitTable("Past champions", honour, look, Modifier.fillMaxWidth().weight(1.2f), leadWidth = 2.6f)
            if (calendar.isNotEmpty()) PitTable("${season + 1} calendar", calendar, look, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(gap))
            Review(rounds, minute, look)
        }
    }
}
