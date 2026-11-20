package com.ikverse.deskglow.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The dimmest the display gets in a dark room, as the share of full brightness. */
internal const val DARKEST = 0.02f

/** The light level (lux) at and above which the display is at full brightness: daylight indoors. */
private const val BRIGHTEST_LUX = 10_000f

/**
 * The window brightness (0.02 to 1) for a room of [lux]. Eyes judge light on a log scale, so this
 * does too; the extra bend keeps a bedside room (a few lux to tens of lux) properly dim.
 */
internal fun brightnessForLux(lux: Float): Float {
    val position = (log10(1f + max(lux, 0f)) / log10(1f + BRIGHTEST_LUX)).coerceIn(0f, 1f)
    return DARKEST + (1f - DARKEST) * position.pow(1.5f)
}

/**
 * Turns the light sensor's readings into brightness levels worth applying: smoothed, so a passing
 * hand or shadow does not flicker the screen, and silent until the level has moved by [STEP].
 *
 * The sensor reports only when the light changes, so after the lights go off there may be a single
 * reading and then silence. Each reading moves the level only part of the way; [settle], called every
 * so often until [settled], carries it the rest of the way to the last reading.
 */
internal class RoomLight {
    private var smoothed: Float? = null
    private var applied: Float? = null
    private var target: Float? = null

    /** Whether the level has reached the last reading. */
    val settled: Boolean
        get() {
            val s = smoothed ?: return true
            val t = target ?: return true
            return abs(s - t) < SETTLED
        }

    /** The new brightness to apply for a reading, or null if it is not different enough yet. */
    fun onLux(lux: Float): Float? {
        target = log10(1f + max(lux, 0f))
        return step()
    }

    /** One more step towards the last reading, with no new one: the level to apply, or null if not different enough. */
    fun settle(): Float? = if (settled) null else step()

    private fun step(): Float? {
        val position = target ?: return null
        val now = smoothed?.let { it + SMOOTHING * (position - it) } ?: position
        smoothed = now
        val level = brightnessForLux(10f.pow(now) - 1f)
        val before = applied
        if (before != null && abs(level - before) < STEP) return null
        applied = level
        return level
    }

    private companion object {
        const val SMOOTHING = 0.3f
        const val STEP = 0.02f
        /** Closer than this (on the log scale of lux) to the last reading counts as there. */
        const val SETTLED = 0.01f
    }
}

/** Whether this phone has a light sensor at all. */
fun hasLightSensor(context: Context): Boolean =
    context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

/**
 * Brightness levels that follow the room's light. The sensor is only listened to while this is
 * collected, and it reports when the light changes, so a steady room costs nothing. Empty if the
 * phone has no light sensor.
 */
internal fun lightLevels(context: Context): Flow<Float> = callbackFlow {
    val manager = context.getSystemService(SensorManager::class.java)
    val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    if (manager == null || sensor == null) {
        close()
        return@callbackFlow
    }
    val room = RoomLight()
    val wake = Channel<Unit>(Channel.CONFLATED)
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            room.onLux(event.values[0])?.let { trySend(min(it, 1f)) }
            wake.trySend(Unit)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    // After a reading, the level is carried on to it every so often until it is there; nothing runs once it is.
    val settler = launch {
        for (ignored in wake) {
            while (!room.settled) {
                delay(SETTLE_EVERY_MS)
                room.settle()?.let { trySend(min(it, 1f)) }
            }
        }
    }
    // Reported at once rather than batched, so the first reading sets the screen as soon as the display shows.
    manager.registerListener(listener, sensor, SAMPLE_US, 0)
    awaitClose {
        settler.cancel()
        manager.unregisterListener(listener)
    }
}.conflate()

private const val SAMPLE_US = 500_000
private const val SETTLE_EVERY_MS = 500L
