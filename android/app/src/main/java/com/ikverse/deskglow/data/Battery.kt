package com.ikverse.deskglow.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The battery. Level, status, voltage and temperature come from Android's own battery broadcast,
 * which only fires when something changes. The current (and so the power) is not in that broadcast,
 * so with [pollCurrent] it alone is also read every [CURRENT_POLL_MS] while the battery is on screen.
 * Without it nothing runs between broadcasts, which is all a widget that shows level or status needs.
 */
fun batteryUpdates(context: Context, pollCurrent: Boolean = false): Flow<BatteryState> = callbackFlow {
    val manager = context.getSystemService(BatteryManager::class.java)
    var latest: Intent? = null

    fun publish() {
        val intent = latest ?: return
        val raw = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        val timeToFull = manager?.computeChargeTimeRemaining() ?: -1L
        trySend(batteryState(intent, raw, timeToFull))
    }

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            latest = intent
            publish()
        }
    }
    // The battery broadcast is sticky: registering hands back the current state straight away.
    latest = ContextCompat.registerReceiver(
        context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    publish()
    val poll = if (pollCurrent) launch {
        while (isActive) {
            delay(CURRENT_POLL_MS)
            publish()
        }
    } else null
    awaitClose {
        poll?.cancel()
        context.unregisterReceiver(receiver)
    }
}

private const val CURRENT_POLL_MS = 5_000L

/** The battery right now, read without listening: Android hands back its last battery broadcast. */
fun currentBattery(context: Context): BatteryState {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return BatteryState()
    val manager = context.getSystemService(BatteryManager::class.java)
    return batteryState(
        intent,
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE,
        manager?.computeChargeTimeRemaining() ?: -1L,
    )
}

internal fun batteryState(intent: Intent, rawCurrent: Int, timeToFullMs: Long): BatteryState {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
    val status = chargeStatus(intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN))
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    return BatteryState(
        level = if (level < 0) 0 else level * 100 / scale,
        status = status,
        plugged = plugged,
        voltageMv = normaliseVoltage(intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)),
        temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
        currentMa = normaliseCurrent(rawCurrent),
        timeToFullMs = timeToFullMs.takeIf { it > 0 && status == ChargeStatus.Charging },
    )
}

internal fun chargeStatus(code: Int): ChargeStatus = when (code) {
    BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.Charging
    BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.Full
    BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.Discharging
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NotCharging
    else -> ChargeStatus.Unknown
}

/** A few phones report volts rather than millivolts. */
internal fun normaliseVoltage(raw: Int): Int = if (raw in 1..99) raw * 1000 else raw

/**
 * Android documents the current in microamps, but Samsung (and others) report milliamps. No phone
 * charges or drains at 10 A, so anything that large must be microamps. Returned as mA, positive.
 * Null where the phone does not report a current at all.
 */
internal fun normaliseCurrent(raw: Int): Int? = when {
    raw == Int.MIN_VALUE -> null
    abs(raw) >= 10_000 -> abs(raw) / 1000
    else -> abs(raw)
}
