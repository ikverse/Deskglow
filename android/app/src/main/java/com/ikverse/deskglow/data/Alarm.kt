package com.ikverse.deskglow.data

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The next alarm the phone will ring, from any clock app, or null when none is set. */
data class AlarmState(val next: LocalDateTime?)

/**
 * The next alarm, as Android reports it to every app with no permission needed. Android says when
 * it changes (set, cancelled, snoozed, rung), so this does no work in between.
 */
fun alarmUpdates(context: Context): Flow<AlarmState> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(currentAlarm(context))
        }
    }
    val filter = IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend(currentAlarm(context))
    awaitClose { context.unregisterReceiver(receiver) }
}

fun currentAlarm(context: Context): AlarmState {
    val at = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime ?: return AlarmState(null)
    return AlarmState(LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()))
}
