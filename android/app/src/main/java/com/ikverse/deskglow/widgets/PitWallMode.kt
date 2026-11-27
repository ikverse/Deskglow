package com.ikverse.deskglow.widgets

import com.ikverse.deskglow.data.F1Data
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1Race
import com.ikverse.deskglow.data.F1Result
import com.ikverse.deskglow.data.F1Session
import com.ikverse.deskglow.data.LIVE_LEAD
import com.ikverse.deskglow.data.LiveSession
import com.ikverse.deskglow.data.RESULT_HOLD
import com.ikverse.deskglow.data.SEASON_LEAD
import com.ikverse.deskglow.data.endedBy
import com.ikverse.deskglow.data.liveWindowSession
import com.ikverse.deskglow.data.raceOf
import com.ikverse.deskglow.data.seasonToReview
import java.time.Instant

/** Which of its screens PitWall is showing. */
sealed interface PitWallMode {
    /** A session is on: the cars on the map, the order, the flag. */
    data class Live(val session: LiveSession) : PitWallMode

    /**
     * A race weekend between its sessions: the last session's [result] (when it is this weekend's) and what is
     * [next]. [on] is a session the calendar says is on now whose live timing has not come through yet.
     */
    data class Weekend(val race: F1Race, val result: LiveSession?, val next: F1Session?, val on: F1Session? = null) : PitWallMode

    /** Between race weekends: the race to come and the tables; [last] is the last race's podium. */
    data class Between(val next: F1Race, val last: F1Result?) : PitWallMode

    /** Between seasons: looking back on [season]; [first] is the next season's opening race, once known. */
    data class OffSeason(val season: Int, val first: F1Race?) : PitWallMode

    /** Nothing to go on yet. */
    data object Empty : PitWallMode
}

/**
 * What PitWall shows at [now]. Live while the feed is following a session (from five minutes before
 * it); the weekend's own screen from its first session until [RESULT_HOLD] after its race; the look
 * back between seasons, until [SEASON_LEAD] before the new season's first session; otherwise the race
 * to come. [nextSeason] is next year's calendar, for when the calendar in [data] is still last year's.
 */
fun pitWallMode(data: F1Data?, live: F1LiveState, now: Instant, nextSeason: List<F1Race> = emptyList()): PitWallMode {
    val session = (live as? F1LiveState.Live)?.session
    data ?: return session?.takeIf { !it.signalLost }?.let { PitWallMode.Live(it) } ?: PitWallMode.Empty
    val races = (data.races + nextSeason.filter { n -> data.races.none { it.first.start == n.first.start } }).sortedBy { it.first.start }
    // A connection lost and never back, past the session's window, says nothing about now.
    if (session != null && !(session.signalLost && liveWindowSession(races, now) == null)) return PitWallMode.Live(session)
    val result = (live as? F1LiveState.Result)?.session
    races.firstOrNull { !now.isBefore(it.first.start.minus(LIVE_LEAD)) && now.isBefore(it.race.end.plus(RESULT_HOLD)) }?.let { race ->
        val own = result?.takeIf { raceOf(races, it) == race }
        // On by the calendar, and not already over by its own result: the feed is still to say so.
        val on = race.sessions.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) && !endedBy(own, it) }
        return PitWallMode.Weekend(race, own, race.sessions.firstOrNull { it.start.isAfter(now) }, on)
    }
    val next = races.firstOrNull { it.race.end.isAfter(now) }
    val season = seasonToReview(data.races, now)
    if (next != null && (season == null || !now.isBefore(next.first.start.minus(SEASON_LEAD)))) {
        return PitWallMode.Between(next, data.lastResult.takeIf { season == null })
    }
    return if (season != null) PitWallMode.OffSeason(season, next) else PitWallMode.Empty
}
