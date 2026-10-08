package com.ikverse.deskglow

import android.app.Application
import android.content.Context
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.Feeds
import com.ikverse.deskglow.data.Http
import com.ikverse.deskglow.data.LocationFinder
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.data.NotificationState
import com.ikverse.deskglow.data.UrlConnectionHttp
import com.ikverse.deskglow.data.WeatherRepository
import com.ikverse.deskglow.data.WeatherState
import com.ikverse.deskglow.data.batteryUpdates
import com.ikverse.deskglow.data.currentBattery
import com.ikverse.deskglow.data.mediaUpdates
import com.ikverse.deskglow.data.minuteTicks
import com.ikverse.deskglow.data.nextEventUpdates
import com.ikverse.deskglow.data.notificationUpdates
import com.ikverse.deskglow.data.secondTicks
import com.ikverse.deskglow.fonts.FontLibrary
import com.ikverse.deskglow.fonts.FontResolver
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.store.AppPrefs
import com.ikverse.deskglow.store.LayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.time.LocalDateTime

class DeskglowApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as DeskglowApp).graph

/**
 * The app's long-lived parts, made on first use and shared by the home screen, the editor and the
 * screen saver. Tests pass their own [http] and [feeds] so nothing touches the network or the phone.
 */
class AppGraph(context: Context, http: Http = UrlConnectionHttp, feeds: Feeds? = null) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = AppPrefs(app)
    /** The portrait layout (the file every earlier version wrote, so nothing needs converting). */
    val layouts = LayoutRepository(File(app.filesDir, "layout.json"), scope, Dispatchers.IO)
    /** The landscape layout, read from disk the first time something asks for it. */
    val landscapeLayouts by lazy {
        LayoutRepository(File(app.filesDir, "layout-landscape.json"), scope, Dispatchers.IO, Orientation.Landscape)
    }
    fun layoutsFor(orientation: Orientation): LayoutRepository =
        if (orientation == Orientation.Landscape) landscapeLayouts else layouts
    val weather = WeatherRepository(prefs, http, locate = LocationFinder(app)::locate)
    val fontLibrary = FontLibrary(app, prefs, http)
    val fonts = FontResolver(app, fontLibrary)
    val feeds: Feeds = feeds ?: LiveFeeds(app, scope, weather)
}

/**
 * The real feeds. Each starts when the first widget on screen reads it and stops [STOP_AFTER_MS]
 * after the last one goes, so switching between the editor and the home screen does not restart
 * everything, and a screen saver that has ended leaves nothing running.
 */
private class LiveFeeds(context: Context, private val scope: CoroutineScope, weatherRepository: WeatherRepository) : Feeds {
    // The last value is kept after a feed stops, so a screen that comes back shows it at once rather
    // than an empty placeholder while the feed starts up again.
    private fun <T> Flow<T>.shared(initial: T): StateFlow<T> =
        stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), initial)

    override val minute = minuteTicks(context).shared(LocalDateTime.now())
    override val second = secondTicks().shared(LocalDateTime.now())
    override val battery = batteryUpdates(context).shared(currentBattery(context))
    override val notifications = notificationUpdates(context).shared(NotificationState.Apps(emptyList()))
    override val media = mediaUpdates(context).shared<MediaState>(MediaState.Idle)
    override val nextEvent = nextEventUpdates(context).shared<EventState>(EventState.None)
    override val weather = weatherRepository.updates().shared<WeatherState>(WeatherState.NoCity)

    private companion object {
        const val STOP_AFTER_MS = 3_000L
    }
}
