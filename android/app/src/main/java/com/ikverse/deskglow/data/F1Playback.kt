package com.ikverse.deskglow.data

import java.time.Duration
import java.time.Instant
import kotlin.math.floor

/**
 * A car through the [passed]th of a lap's [of] timing points (mini-sectors) at [at]: 1 is the first after
 * the line, and [of] is the line itself, a lap done.
 */
data class Crossing(val passed: Int, val of: Int, val at: Instant)

/**
 * Where each timing point is round the lap, as a share of a real lap's time: [at] has one entry for each
 * point from 0 (the line, a lap begun) to [of] (the line again, 1). Learned from the cars' own crossings,
 * so a car drawn between two points is drawn where the timing really puts it.
 */
class TimingPoints(val of: Int, val at: FloatArray) {
    /** The point [passed] (1 to [of]) as a share of the lap's time, 0 for the line. */
    fun of(passed: Int): Float = if (passed >= of) 0f else at[passed.coerceIn(0, of)]
}

/** How many laps a timing point needs before its learned place is trusted over the outline's guess. */
private const val LEARN_FROM = 3

/** A lap this much slower than the typical one (a pit stop, the safety car) says little about where the points are. */
private const val SLOW_LAP = 1.25

/**
 * Where the [of] timing points are round the lap. A point's place is the middle of where it fell, as a share
 * of each whole lap the cars ran through it at a normal pace; until a point has been seen on [LEARN_FROM]
 * such laps, the points are taken as evenly spread along the [track]'s length, and placed in time by its real lap.
 */
fun timingPoints(crossings: Map<String, List<Crossing>>, of: Int, track: F1Track?): TimingPoints {
    val guess = FloatArray(of + 1) { k -> if (k == of) 1f else track?.timeAtDistance(k.toFloat() / of) ?: (k.toFloat() / of) }
    if (of <= 1) return TimingPoints(of, guess)
    class Lap(val ms: Long, val shares: List<Pair<Int, Float>>)
    val laps = ArrayList<Lap>()
    for (list in crossings.values) {
        var start: Crossing? = null
        val inside = ArrayList<Crossing>()
        for (c in list) {
            if (c.of != of) { start = null; inside.clear(); continue }
            if (c.passed == of) {
                val from = start
                if (from != null && inside.isNotEmpty()) {
                    val ms = Duration.between(from.at, c.at).toMillis()
                    if (ms > 0) laps += Lap(ms, inside.map { it.passed to Duration.between(from.at, it.at).toMillis().toFloat() / ms })
                }
                start = c
                inside.clear()
            } else if (start != null) {
                if (inside.isNotEmpty() && c.passed <= inside.last().passed) { start = null; inside.clear() } else inside += c
            }
        }
    }
    if (laps.isEmpty()) return TimingPoints(of, guess)
    val typical = laps.map { it.ms }.sorted()[laps.size / 2]
    val samples = HashMap<Int, MutableList<Float>>()
    laps.filter { it.ms <= typical * SLOW_LAP }.forEach { lap -> lap.shares.forEach { (k, share) -> samples.getOrPut(k) { ArrayList() } += share } }
    val at = guess.copyOf()
    for ((k, list) in samples) if (k in 1 until of && list.size >= LEARN_FROM) at[k] = list.sorted()[list.size / 2]
    // Each point after the one before it: a learned one beside a guessed one could otherwise cross over.
    for (k in 1 until of) at[k] = at[k].coerceIn(at[k - 1] + 1e-4f, 1f - (of - k) * 1e-4f)
    return TimingPoints(of, at)
}

/** [to] as a share of the lap's time at or after [from], going forward round the line. */
private fun forward(from: Float, to: Float): Float = if (to > from) to else to + 1f

private fun wrapped(v: Float) = v - floor(v)

/**
 * How far through a lap in time (0 to 1) the car whose timing-point [crossings] these are was at [at]. Between
 * two crossings, the answer is exact: the car is drawn going from one point to the next in just the time it
 * took. Past its last crossing (the next is not in yet), it is carried on at the pace of its last stretch, but
 * never to the next point, which it has not been timed through. Null before its first crossing.
 */
fun playbackShare(crossings: List<Crossing>, points: TimingPoints, at: Instant, lapMs: Long): Float? {
    val i = crossings.indexOfLast { !it.at.isAfter(at) }
    if (i < 0) return null
    val a = crossings[i]
    val from = points.of(a.passed)
    val b = crossings.getOrNull(i + 1)
    if (b != null) {
        val to = if (b.passed == a.passed) from + 1f else forward(from, points.of(b.passed))
        val span = Duration.between(a.at, b.at).toMillis().toFloat()
        val f = if (span > 0f) (Duration.between(a.at, at).toMillis() / span).coerceIn(0f, 1f) else 1f
        return wrapped(from + (to - from) * f)
    }
    // Ahead of the timing: on at the last stretch's pace, up to just short of the next point.
    val before = crossings.getOrNull(i - 1)
    val perMs = if (before != null && Duration.between(before.at, a.at).toMillis() > 0) {
        val prev = points.of(before.passed)
        (from - prev).let { if (it <= 0f) it + 1f else it } / Duration.between(before.at, a.at).toMillis()
    } else 1f / lapMs.coerceAtLeast(1)
    val next = points.of(if (a.passed >= points.of) 1 else a.passed + 1).let { forward(from, it) }
    val reach = from + (next - from) * NEXT_REACH
    val on = from + perMs.coerceIn(0f, 1f / 20_000f) * Duration.between(a.at, at).toMillis().coerceAtLeast(0)
    return wrapped(minOf(on, reach))
}

/** How far towards a point a car not yet timed through it is drawn at most. */
private const val NEXT_REACH = 0.95f

/** How many crossings are kept for each car: a little over two laps' worth. */
internal fun crossingsKept(of: Int) = 2 * of + 4

/**
 * In which marshal sector of [track] the car whose [crossings] these are last was timed: for naming the car
 * behind a yellow flag. Null when the track has no sectors or the car no crossings.
 */
fun sectorOf(track: F1Track, crossings: List<Crossing>, points: TimingPoints): Int? {
    val last = crossings.lastOrNull() ?: return null
    if (track.sectors.isEmpty()) return null
    val d = track.distanceAtTime(points.of(last.passed))
    val ordered = track.sectors.sortedBy { it.at }
    return (ordered.lastOrNull { it.at <= d } ?: ordered.last()).number
}
