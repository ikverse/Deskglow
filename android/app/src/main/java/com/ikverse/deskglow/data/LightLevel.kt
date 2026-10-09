package com.ikverse.deskglow.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
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
 */
internal class RoomLight {
    private var smoothed: Float? = null
    private var applied: Float? = null

    /** The new brightness to apply for a reading, or null if it is not different enough yet. */
    fun onLux(lux: Float): Float? {
        val position = log10(1f + max(lux, 0f))
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
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            room.onLux(event.values[0])?.let { trySend(min(it, 1f)) }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SAMPLE_US, REPORT_LATENCY_US)
    awaitClose { manager.unregisterListener(listener) }
}.conflate()

private const val SAMPLE_US = 500_000
private const val REPORT_LATENCY_US = 2_000_000
