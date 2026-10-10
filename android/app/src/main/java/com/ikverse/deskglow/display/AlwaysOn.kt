package com.ikverse.deskglow.display

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ikverse.deskglow.R
import com.ikverse.deskglow.store.alwaysOnEnabled
import com.ikverse.deskglow.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/*
 * Always on. Android gives no app the real always-on display, so this does what other apps do: a small
 * service hears the screen turn off, and brings the display back up over the lock screen. The phone
 * stays locked; the screen is really on, so this costs more battery than the system's own.
 */

/** What to do when the screen has turned off. */
internal enum class OffAction { Nothing, Show, WakeToLockScreen }

/** What is known at the moment the screen turned off, and again once the short wait is over. */
internal data class ScreenOff(
    val enabled: Boolean,
    /** A Deskglow screen (this one, the screen saver or Start now) was on screen: the power button was pressed. */
    val wasShowing: Boolean,
    /** The display itself switched the screen off because the phone is covered. */
    val coverHeld: Boolean,
    /** Another screen saver is running, or has only just ended. */
    val dreaming: Boolean,
    val inCall: Boolean,
    /** The screen is on again after the wait. */
    val screenBackOn: Boolean,
)

internal fun decideOnScreenOff(f: ScreenOff): OffAction = when {
    !f.enabled || f.screenBackOn || f.coverHeld || f.inCall -> OffAction.Nothing
    // Power pressed on a Deskglow screen: go to the lock screen, as the system's own always-on display does.
    f.wasShowing -> OffAction.WakeToLockScreen
    f.dreaming -> OffAction.Nothing
    else -> OffAction.Show
}

/**
 * A safety net against the screen flipping on and off in a loop: after [max] acts within [windowMs]
 * it refuses for [pauseMs]. Ordinary use, a few presses of the power button, never reaches it.
 */
internal class LoopGuard(private val max: Int = 6, private val windowMs: Long = 10_000, private val pauseMs: Long = 60_000) {
    private val times = ArrayDeque<Long>()
    private var pausedUntil = Long.MIN_VALUE

    fun admit(now: Long): Boolean {
        if (now < pausedUntil) return false
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        if (times.size >= max) {
            pausedUntil = now + pauseMs
            times.clear()
            return false
        }
        times.addLast(now)
        return true
    }
}

/** How long the phone has to stay covered before the always-on screen goes off. */
internal const val COVER_DELAY_MS = 5_000L

/**
 * Turns [covered] into calls of [setOff]: true once the phone has stayed covered for [delayMs], false the
 * moment it is clear. [firstDelayMs] is used instead for the first cover, since a phone that is already
 * in a pocket when the display starts should not light up first.
 */
internal suspend fun applyCoverDelay(covered: Flow<Boolean>, delayMs: Long, firstDelayMs: Long, setOff: (Boolean) -> Unit) {
    var first = true
    covered.distinctUntilChanged().collectLatest { isCovered ->
        if (isCovered) {
            delay(if (first) firstDelayMs else delayMs)
            first = false
            setOff(true)
        } else {
            setOff(false)
        }
    }
}

/** Whether the proximity sensor is covered, as it changes. Ends at once on a phone without one. */
internal fun proximityCovered(context: Context): Flow<Boolean> = callbackFlow {
    val manager = context.getSystemService(SensorManager::class.java)
    val sensor = manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    if (manager == null || sensor == null) {
        close()
        return@callbackFlow
    }
    val near = minOf(sensor.maximumRange, 5f)
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            trySend(event.values[0] < near)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    awaitClose { manager.unregisterListener(listener) }
}

/**
 * Which Deskglow screens are up, so the service can tell a press of the power button (a Deskglow
 * screen was showing when the screen went off) from the screen simply timing out.
 */
internal object DeskglowPresence {
    /** Android may stop an activity just before or after the screen-off broadcast arrives, so an end this recent still counts. */
    private const val GRACE_MS = 1_500L

    private val showing = HashSet<Any>()
    private var lastEnd = Long.MIN_VALUE
    private var alwaysOn: WeakReference<Activity>? = null

    /** The always-on screen has switched the screen off itself because the phone is covered. */
    @Volatile var coverHeld = false

    @Synchronized fun started(owner: Any) { showing.add(owner) }

    @Synchronized fun stopped(owner: Any) {
        if (showing.remove(owner)) lastEnd = SystemClock.elapsedRealtime()
    }

    @Synchronized fun wasShowing(now: Long): Boolean =
        showing.isNotEmpty() || alwaysOn?.get() != null || now - lastEnd <= GRACE_MS

    @Synchronized fun registerAlwaysOn(activity: Activity) { alwaysOn = WeakReference(activity) }

    @Synchronized fun unregisterAlwaysOn(activity: Activity) {
        if (alwaysOn?.get() === activity) alwaysOn = null
    }

    @Synchronized fun closeAlwaysOn() {
        alwaysOn?.get()?.let { if (!it.isFinishing) it.finish() }
    }
}

internal const val EXTRA_FROM_OFF = "from_off"
private const val CHANNEL = "always_on"
private const val NOTIFICATION_ID = 1
private const val WAIT_MS = 400L

/** Starts the service (or just keeps it going if it already runs). Quietly does nothing if Android refuses. */
fun startAlwaysOn(context: Context) {
    try {
        ContextCompat.startForegroundService(context, Intent(context, AlwaysOnService::class.java))
    } catch (_: RuntimeException) {
        // Not allowed to start from the background just now; it starts again the next time the app is opened or the phone boots.
    }
}

fun stopAlwaysOn(context: Context) {
    context.stopService(Intent(context, AlwaysOnService::class.java))
}

/** What the always-on display still needs, for the settings screen: empty when it can work. */
fun alwaysOnMissing(context: Context): List<String> = buildList {
    if (!Settings.canDrawOverlays(context)) add("Display over other apps")
    if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) add("Unrestricted battery use")
    if (!notificationsAllowed(context)) add("Notifications")
}

fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Waits for the screen to turn off and then shows [AlwaysOnActivity] over the lock screen, unless a
 * Deskglow screen was already up (the power button: it wakes to the lock screen instead), a screen saver
 * is running, or a call is going. It holds nothing awake while it waits.
 */
class AlwaysOnService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val guard = LoopGuard()
    private var pending: Job? = null
    private var dreaming = false
    private var dreamEnded = Long.MIN_VALUE

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff()
                Intent.ACTION_SCREEN_ON -> pending?.cancel()
                Intent.ACTION_USER_PRESENT -> DeskglowPresence.closeAlwaysOn()
                Intent.ACTION_DREAMING_STARTED -> {
                    dreaming = true
                    DeskglowPresence.closeAlwaysOn()
                }
                Intent.ACTION_DREAMING_STOPPED -> {
                    dreaming = false
                    dreamEnded = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Started while the screen is already off (after an update, say): show the display now.
        if (!getSystemService(PowerManager::class.java).isInteractive) screenOff()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!alwaysOnEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        return START_STICKY
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Always on", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_start)
            .setContentTitle("Deskglow always on")
            .setContentText("Shows the display whenever the screen turns off.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun screenOff() {
        pending?.cancel()
        val power = getSystemService(PowerManager::class.java)
        val at = SystemClock.elapsedRealtime()
        // Taken now: a Deskglow screen may stop a moment after this, and that must not look like a timeout.
        val wasShowing = DeskglowPresence.wasShowing(at)
        pending = scope.launch {
            delay(WAIT_MS)
            val now = SystemClock.elapsedRealtime()
            val audio = getSystemService(AudioManager::class.java)
            val action = decideOnScreenOff(
                ScreenOff(
                    enabled = alwaysOnEnabled(),
                    wasShowing = wasShowing,
                    coverHeld = DeskglowPresence.coverHeld,
                    dreaming = dreaming || now - dreamEnded <= 1_500L,
                    inCall = audio.mode != AudioManager.MODE_NORMAL,
                    screenBackOn = power.isInteractive,
                ),
            )
            if (action == OffAction.Nothing || !guard.admit(now)) return@launch
            when (action) {
                OffAction.Show -> startActivity(
                    Intent(this@AlwaysOnService, AlwaysOnActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(EXTRA_FROM_OFF, true),
                )
                OffAction.WakeToLockScreen -> {
                    DeskglowPresence.closeAlwaysOn()
                    @Suppress("DEPRECATION")
                    power.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                        "Deskglow:wake",
                    ).acquire(2_000)
                }
                OffAction.Nothing -> Unit
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Starts the service again after a reboot or an update of the app, when always on is switched on. */
class AlwaysOnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.alwaysOnEnabled()) {
            startAlwaysOn(context)
        }
    }
}
