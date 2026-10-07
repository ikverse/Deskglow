package com.ikverse.deskglow.data

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Whether the owner has given Deskglow notification access (needed for notification icons and now playing). */
fun hasNotificationAccess(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

fun watcherComponent(context: Context) = ComponentName(context, NotificationWatcher::class.java)

/**
 * Android keeps a notification listener connected for as long as access is granted, and would wake
 * the app on every notification. Deskglow only needs to know while its screen is showing, so the
 * listener disconnects itself whenever nobody is watching and is asked back when the screen appears.
 */
object NotificationHub {
    private const val TAG = "NotificationHub"
    private const val ICON_PX = 64
    private const val MAX_APPS = 12

    internal val apps = MutableStateFlow<List<NotifiedApp>>(emptyList())
    private var watchers = 0
    private var service = WeakReference<NotificationWatcher>(null)

    internal val wanted: Boolean get() = watchers > 0

    internal fun startWatching(context: Context) {
        watchers++
        val connected = service.get()
        if (connected != null) refresh(connected) else runCatching {
            NotificationListenerService.requestRebind(watcherComponent(context))
        }.onFailure { Log.w(TAG, "Could not ask for the notification listener", it) }
    }

    internal fun stopWatching() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers == 0) {
            icons.clear()
            service.get()?.let { runCatching { it.requestUnbind() } }
        }
    }

    internal fun connected(watcher: NotificationWatcher) {
        service = WeakReference(watcher)
        if (wanted) refresh(watcher) else runCatching { watcher.requestUnbind() }
    }

    internal fun disconnected(watcher: NotificationWatcher) {
        if (service.get() === watcher) service = WeakReference(null)
    }

    /** Icons already drawn, by app, so a busy chat does not redraw every icon on each message. */
    private val icons = HashMap<String, NotifiedApp>()

    internal fun refresh(watcher: NotificationWatcher) {
        val active = runCatching { watcher.activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        apps.value = notifiedApps(watcher.packageName, active).take(MAX_APPS).map { sbn ->
            icons.getOrPut(sbn.packageName) {
                val icon = runCatching {
                    sbn.notification.smallIcon?.loadDrawable(watcher)?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap()
                }.getOrNull()
                NotifiedApp(sbn.packageName, icon)
            }
        }
    }

    /**
     * One notification per app, newest first: the ones a person would want to see. Ongoing ones
     * (music, downloads, "USB debugging") and Deskglow's own are left out, as the always-on display does.
     */
    internal fun notifiedApps(ownPackage: String, active: List<StatusBarNotification>): List<StatusBarNotification> =
        active
            .filter { !it.isOngoing && it.packageName != ownPackage }
            .sortedByDescending { it.postTime }
            .distinctBy { it.packageName }
}

/** The notification listener Android talks to. All it does is tell [NotificationHub] when things change. */
class NotificationWatcher : NotificationListenerService() {
    override fun onListenerConnected() = NotificationHub.connected(this)
    override fun onListenerDisconnected() = NotificationHub.disconnected(this)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (NotificationHub.wanted) NotificationHub.refresh(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (NotificationHub.wanted) NotificationHub.refresh(this)
    }
}

fun notificationUpdates(context: Context): Flow<NotificationState> = callbackFlow {
    if (!hasNotificationAccess(context)) {
        send(NotificationState.NoAccess)
        awaitClose {}
        return@callbackFlow
    }
    NotificationHub.startWatching(context)
    val job = launch { NotificationHub.apps.collectLatest { send(NotificationState.Apps(it)) } }
    awaitClose {
        job.cancel()
        NotificationHub.stopWatching()
    }
}
