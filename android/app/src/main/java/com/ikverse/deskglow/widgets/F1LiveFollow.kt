package com.ikverse.deskglow.widgets

import com.ikverse.deskglow.data.EVENT_FRESH
import com.ikverse.deskglow.data.EventKind
import com.ikverse.deskglow.data.F1Roster
import com.ikverse.deskglow.data.LiveEvent
import com.ikverse.deskglow.data.LiveRow
import com.ikverse.deskglow.data.LiveSession
import com.ikverse.deskglow.data.freshEvent
import java.time.Duration
import java.time.Instant

/** Whether the team the feed calls [name] is the team whose id is [teamId]: "red_bull" and "Red Bull Racing". */
internal fun teamMatches(teamId: String, name: String): Boolean {
    if (teamId.isEmpty() || name.isEmpty()) return false
    val id = teamId.filter(Char::isLetterOrDigit).lowercase()
    val written = name.filter(Char::isLetterOrDigit).lowercase()
    if (id == "rb") return "racingbulls" in written || "alphatauri" in written || written.endsWith("rb")
    return id in written
}

/** The car the widget follows: the favourite driver's, else the better placed of the favourite team's, else null. */
internal fun followedRow(session: LiveSession, driver: String, team: String): LiveRow? =
    session.rows.firstOrNull { driver.isNotEmpty() && it.code == driver }
        ?: session.rows.filter { teamMatches(team, it.team) }.minByOrNull { it.position }

/** How a driver is named in a row: "VER", the car number, or the surname in capitals. */
internal fun driverName(row: LiveRow, names: String): String = when (names) {
    "number" -> row.number
    "surname" -> surnameOf(row.code) ?: row.code
    else -> row.code
}

private fun surnameOf(code: String): String? =
    F1Roster.drivers.firstOrNull { it.first == code }?.second?.substringAfterLast(' ')?.uppercase()

private val TAG = Regex("""\[([A-Z]{3})]""")

/** News as it reads on the widget: each [CODE] written as the widget's setting names drivers. */
internal fun namedNews(text: String, names: String, rows: List<LiveRow>): String =
    TAG.replace(text) { m ->
        val code = m.groupValues[1]
        rows.firstOrNull { it.code == code }?.let { driverName(it, names) } ?: if (names == "surname") surnameOf(code) ?: code else code
    }

/**
 * Notices what happens to the followed car between one classification and the next: a place won or
 * lost, a pit stop, a new fastest lap. Race control's own news comes from the feed; this is the news
 * only the change shows. It keeps the last classification and nothing else.
 */
internal class FollowTracker {
    private var before: LiveSession? = null
    private var latest: LiveEvent? = null

    /** Looks at [session] against the last one seen; returns the newest news so far (the caller says whether it is still fresh). */
    fun observe(session: LiveSession, followed: String, now: Instant): LiveEvent? {
        val was = before
        before = session
        if (was == null || was.key != session.key || !session.isRace) {
            if (was?.key != session.key) latest = null
            return latest
        }
        val mine = session.rows.firstOrNull { it.code == followed }
        val mineBefore = was.rows.firstOrNull { it.code == followed }
        if (mine != null && mineBefore != null) {
            if (!mineBefore.inPit && mine.inPit) latest = LiveEvent(EventKind.Pit, "[$followed] pitting", now)
            else if (!mine.inPit && !mineBefore.inPit && mine.position != mineBefore.position) {
                // Whoever held the place this car now has: the car it passed, or the one that passed it.
                val other = was.rows.firstOrNull { it.position == mine.position }
                val rival = other?.let { o -> session.rows.firstOrNull { it.code == o.code } }
                latest = when {
                    other == null || other.code == followed || other.inPit || rival == null || rival.inPit || kotlin.math.abs(mine.position - mineBefore.position) > 1 ->
                        LiveEvent(EventKind.Position, "[$followed] ${if (mine.position < mineBefore.position) "up" else "down"} to P${mine.position}", now)
                    mine.position < mineBefore.position -> LiveEvent(EventKind.Position, "[$followed] passed [${other.code}] for P${mine.position}", now)
                    else -> LiveEvent(EventKind.Position, "[${other.code}] passed [$followed] for P${rival.position}", now)
                }
            }
        }
        val holder = session.rows.firstOrNull { it.fastest }
        if (holder != null && holder.code != was.rows.firstOrNull { it.fastest }?.code && was.rows.any { it.fastest }) {
            latest = LiveEvent(EventKind.Fastest, "Fastest lap [${holder.code}] ${holder.bestLap}", now)
        }
        return latest
    }
}

/** The news to show now, with names: the newer of race control's latest and what the tracker noticed, while fresh. */
internal fun newsToShow(session: LiveSession, noticed: LiveEvent?, now: Instant, names: String): String? {
    val fromFeed = session.freshEvent(now)
    val fromTracker = noticed?.takeIf { Duration.between(it.at, now) <= EVENT_FRESH }
    val pick = listOfNotNull(fromFeed, fromTracker).maxByOrNull { it.at } ?: return null
    return namedNews(pick.text, names, session.rows)
}
