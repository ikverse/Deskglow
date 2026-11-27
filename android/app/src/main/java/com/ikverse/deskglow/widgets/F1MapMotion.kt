package com.ikverse.deskglow.widgets

import kotlin.math.exp
import kotlin.math.floor

/**
 * The last smoothing of the cars on the track map. Where a car should be is worked out afresh on every frame
 * (played back between its timing points, or carried on past the last one), and nearly always moves on
 * smoothly by itself. It jumps only when the timing changes its mind: the next point comes in later or sooner
 * than the car was carried on to. This closes such a jump over a moment instead: a car ahead of where it
 * should be waits rather than going back, and one behind catches up. Only a car far off (one just out on
 * track, or after a gap in the feed) is moved there at once.
 */
internal class MapMotion {
    private class Shown(var fraction: Double, var atNanos: Long)

    private val shown = HashMap<String, Shown>()

    /** Where to draw the car [number] at [nowNanos], 0 to 1 round the lap, when it should be at [target]. */
    fun place(number: String, target: Float, nowNanos: Long): Float {
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
        // Never backwards: a car ahead of where it should be waits for it.
        val step = (off * (1 - exp(-dtMs / CLOSE_MS))).coerceAtLeast(0.0)
        was.fraction = (was.fraction + step).let { it - floor(it) }
        return was.fraction.toFloat()
    }

    /** Forgets the cars not in [numbers], so one back on track later starts where the timing puts it. */
    fun keepOnly(numbers: Set<String>) {
        shown.keys.retainAll(numbers)
    }

    companion object {
        /** About how long a car takes to close on where it should be: short, as it is nearly always there already. */
        const val CLOSE_MS = 180.0
        /** A car further than this share of a lap from where the timing puts it is moved there at once. */
        const val SNAP_LAP_SHARE = 0.15
        /** After a pause this long between frames (the screen off, say), the cars are put where the timing puts them. */
        const val SNAP_GAP_MS = 3_000.0
        /** The map is drawn on every frame while cars move: about 60 times a second. */
        const val FRAME_NANOS = 12_000_000L
    }
}
