package com.ikverse.deskglow.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime

/**
 * The time on every new minute. Uses the system's own minute tick rather than a timer, so it costs
 * nothing between minutes and follows a clock or time-zone change straight away.
 */
fun minuteTicks(context: Context): Flow<LocalDateTime> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(LocalDateTime.now())
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend(LocalDateTime.now())
    awaitClose { context.unregisterReceiver(receiver) }
}

/** The time on every new second, lined up with the second boundary so the display never lags. */
fun secondTicks(): Flow<LocalDateTime> = flow {
    while (true) {
        val now = LocalDateTime.now()
        emit(now)
        delay(1000L - now.nano / 1_000_000)
    }
}
