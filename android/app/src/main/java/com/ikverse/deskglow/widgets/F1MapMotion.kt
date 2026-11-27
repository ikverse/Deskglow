package com.ikverse.deskglow.widgets

import kotlin.math.exp
import kotlin.math.floor

/**
 * Smooth movement for the cars on the track map. The timing only says which mini-sector each car
 * went through last, so where a car should be ([place]'s target) jumps on each new mini-sector and
 * waits at the edge of the next one until the feed says it got there. Drawn as it is, a car stutters.
 * Instead each car keeps where it was last drawn and goes on from there at its own pace, closing on
 * the target over a fraction of a second: ahead of it, a car slows down rather than going back;
 * behind it, a car speeds up rather than jumping. Only a car far from where the timing puts it (one
 * just out on track, or after a gap in the feed) is moved there at once.
 */
internal class MapMotion {
    private class Shown(var fraction: Double, var atNanos: Long)

    private val shown = HashMap<String, Shown>()

    /**
     * Where to draw the car [number] at [nowNanos], 0 to 1 round the lap: [target] is where the timing
     * puts it now, and [lapMs] how long it takes over a lap.
     */
    fun place(number: String, target: Float, lapMs: Long, nowNanos: Long): Float {
        val goal = target.toDouble() - floor(target.toDouble())
        val was = shown[number]
        if (was == null) {
            shown[number] = Shown(goal, nowNanos)
            return goal.toFloat()
        }
        val dtMs = ((nowNanos - was.atNanos) / 1e6).coerceAtLeast(0.0)
        was.atNanos = nowNanos
        // How far the target is from the car as drawn, the short way round the lap.
        val off = (goal - was.fraction).let { it - floor(it + 0.5) }
        if (kotlin.math.abs(off) > SNAP_LAP_SHARE || dtMs > SNAP_GAP_MS) {
            was.fraction = goal
            return goal.toFloat()
        }
        val pace = if (lapMs > 0) dtMs / lapMs else 0.0
        val close = off * (1 - exp(-dtMs / CLOSE_MS))
        // Never backwards: a car ahead of the timing waits for it.
        val step = (pace + close).coerceAtLeast(0.0)
        was.fraction = (was.fraction + step).let { it - floor(it) }
        return was.fraction.toFloat()
    }

    /** Forgets the cars not in [numbers], so one back on track later starts where the timing puts it. */
    fun keepOnly(numbers: Set<String>) {
        shown.keys.retainAll(numbers)
    }

    companion object {
        /** About how long a car takes to close on where the timing puts it. */
        const val CLOSE_MS = 600.0
        /** A car further than this share of a lap from where the timing puts it is moved there at once. */
        const val SNAP_LAP_SHARE = 0.15
        /** After a pause this long between frames (the screen off, say), the cars are put where the timing puts them. */
        const val SNAP_GAP_MS = 3_000.0
        /** The map is drawn at most this often while cars move: about 30 times a second. */
        const val FRAME_NANOS = 30_000_000L
    }
}
