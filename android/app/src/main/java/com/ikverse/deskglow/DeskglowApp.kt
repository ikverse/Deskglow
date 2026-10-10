package com.ikverse.deskglow

import android.app.Application
import android.content.Context
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.F1LiveRepository
import com.ikverse.deskglow.data.F1LiveState
import com.ikverse.deskglow.data.F1LiveTimingFeed
import com.ikverse.deskglow.data.F1Repository
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.Feeds
import com.ikverse.deskglow.data.Http
import com.ikverse.deskglow.data.LocationFinder
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.data.NotificationState
import com.ikverse.deskglow.data.PrayerRepository
import com.ikverse.deskglow.data.PrayerState
import com.ikverse.deskglow.data.alarmUpdates
import com.ikverse.deskglow.data.currentAlarm
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
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.store.AppPrefs
import com.ikverse.deskglow.store.MAX_PAGES
import com.ikverse.deskglow.store.LayoutRepository
import com.ikverse.deskglow.store.SnapshotRepository
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
    /** Screens after the first, made on first use. The first screen keeps the files every earlier version wrote. */
    private val extraLayouts = HashMap<Pair<Orientation, Int>, LayoutRepository>()

    /** The layout of one screen (0 is the first) for an [orientation]. */
    fun layoutsFor(orientation: Orientation, page: Int = 0): LayoutRepository {
        if (page == 0) return if (orientation == Orientation.Landscape) landscapeLayouts else layouts
        return extraLayouts.getOrPut(orientation to page) {
            val name = (if (orientation == Orientation.Landscape) "layout-landscape-p" else "layout-p") + (page + 1) + ".json"
            LayoutRepository(File(app.filesDir, name), scope, Dispatchers.IO, orientation) { Layout(emptyList()) }
        }
    }

    /** Every screen of one orientation, in order. */
    fun pagesOf(orientation: Orientation): List<Layout> =
        (0 until prefs.pageCount(orientation).value).map { layoutsFor(orientation, it).layout.value }

    /** Replaces every screen of each orientation with the given ones; each orientation keeps the number it was given. */
    fun restorePages(portrait: List<Layout>, landscape: List<Layout>) {
        for ((orientation, given) in listOf(Orientation.Portrait to portrait, Orientation.Landscape to landscape)) {
            val pages = given.take(MAX_PAGES).ifEmpty { listOf(Layout(emptyList())) }
            val oldCount = prefs.pageCount(orientation).value
            pages.forEachIndexed { i, layout -> layoutsFor(orientation, i).update(LayoutRepository.tidied(layout, orientation)) }
            for (i in pages.size until oldCount) {
                layoutsFor(orientation, i).discard()
                extraLayouts.remove(orientation to i)
            }
            prefs.setPageCount(orientation, pages.size)
            prefs.setLastPage(orientation, prefs.lastPage(orientation).coerceAtMost(pages.size - 1))
        }
    }

    /** Adds a screen to one orientation, empty or a copy of its screen [copyOf]. Returns its number, or null at the limit. */
    fun addPage(orientation: Orientation, copyOf: Int? = null): Int? {
        val count = prefs.pageCount(orientation).value
        if (count >= MAX_PAGES) return null
        if (copyOf != null) layoutsFor(orientation, count).update(layoutsFor(orientation, copyOf).layout.value)
        prefs.setPageCount(orientation, count + 1)
        return count
    }

    /** Makes sure an orientation has at least [count] screens, adding empty ones. */
    fun ensurePages(orientation: Orientation, count: Int) {
        while (prefs.pageCount(orientation).value < count && addPage(orientation) != null) Unit
    }

    /** Removes screen [page] of one orientation; the ones after it each move up one. The last screen cannot be removed. */
    fun deletePage(orientation: Orientation, page: Int) {
        val count = prefs.pageCount(orientation).value
        if (count <= 1 || page !in 0 until count) return
        val last = prefs.lastPage(orientation)
        for (i in page until count - 1) layoutsFor(orientation, i).update(layoutsFor(orientation, i + 1).layout.value)
        layoutsFor(orientation, count - 1).discard()
        extraLayouts.remove(orientation to count - 1)
        prefs.setPageCount(orientation, count - 1)
        // The remembered screen follows its content up, and stays on a screen that exists.
        prefs.setLastPage(orientation, (if (last > page) last - 1 else last).coerceAtMost(count - 2))
    }

    /**
     * Moves screen [from] of one orientation to position [to]; the screens between the two each shift by one.
     * The remembered screen follows its content. Anything out of range, or a move to the same place, does nothing.
     */
    fun movePage(orientation: Orientation, from: Int, to: Int) {
        val count = prefs.pageCount(orientation).value
        if (from == to || from !in 0 until count || to !in 0 until count) return
        val layouts = (0 until count).map { layoutsFor(orientation, it).layout.value }.toMutableList()
        layouts.add(to, layouts.removeAt(from))
        layouts.forEachIndexed { i, layout -> layoutsFor(orientation, i).update(layout) }
        prefs.setLastPage(orientation, pageAfterMove(prefs.lastPage(orientation), from, to))
    }

    /** Named pairs of layouts the user saved, one file each. */
    val snapshots by lazy { SnapshotRepository(File(app.filesDir, "snapshots")) }
    private val locationFinder = LocationFinder(app)
    val weather = WeatherRepository(prefs, http, locate = locationFinder::locate)
    val prayer = PrayerRepository(prefs, http, locate = locationFinder::locate)
    val f1 = F1Repository(prefs, http)
    val f1Live = F1LiveRepository(prefs, F1LiveTimingFeed, http)
    val fontLibrary = FontLibrary(app, prefs, http)
    val fonts = FontResolver(app, fontLibrary)
    val feeds: Feeds = feeds ?: LiveFeeds(app, scope, weather, prayer, f1, f1Live)
}

/** Where the screen at [page] ends up when screen [from] is moved to [to]: the moved one lands on [to], those it passes shift by one. */
fun pageAfterMove(page: Int, from: Int, to: Int): Int = when {
    page == from -> to
    from < to && page in from + 1..to -> page - 1
    to < from && page in to until from -> page + 1
    else -> page
}

/**
 * The real feeds. Each starts when the first widget on screen reads it and stops [STOP_AFTER_MS]
 * after the last one goes, so switching between the editor and the home screen does not restart
 * everything, and a screen saver that has ended leaves nothing running.
 */
private class LiveFeeds(
    context: Context,
    private val scope: CoroutineScope,
    weatherRepository: WeatherRepository,
    private val prayerRepository: PrayerRepository,
    f1Repository: F1Repository,
    f1LiveRepository: F1LiveRepository,
) : Feeds {
    // The last value is kept after a feed stops, so a screen that comes back shows it at once rather
    // than an empty placeholder while the feed starts up again.
    private fun <T> Flow<T>.shared(initial: T): StateFlow<T> =
        stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), initial)

    override val minute = minuteTicks(context).shared(LocalDateTime.now())
    override val second = secondTicks().shared(LocalDateTime.now())
    override val battery = batteryUpdates(context).shared(currentBattery(context))
    override val batteryPower = batteryUpdates(context, pollCurrent = true).shared(currentBattery(context))
    override val notifications = notificationUpdates(context).shared(NotificationState.Apps(emptyList()))
    override val media = mediaUpdates(context).shared<MediaState>(MediaState.Idle)
    override val nextEvent = nextEventUpdates(context).shared<EventState>(EventState.None)
    override val weather = weatherRepository.updates().shared<WeatherState>(WeatherState.NoCity)
    override val alarm = alarmUpdates(context).shared(currentAlarm(context))
    override val f1 = f1Repository.updates().shared<F1State>(F1State.Loading)
    override val f1Live = f1LiveRepository.updates(f1).shared<F1LiveState>(F1LiveState.Waiting)

    private val prayers = HashMap<Pair<Int, Int>, StateFlow<PrayerState>>()

    override fun prayer(method: Int, school: Int): StateFlow<PrayerState> = synchronized(prayers) {
        prayers.getOrPut(method to school) { prayerRepository.updates(method, school).shared<PrayerState>(PrayerState.NoLocation) }
    }

    private companion object {
        const val STOP_AFTER_MS = 3_000L
    }
}
