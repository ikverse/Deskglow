package com.ikverse.deskglow.display

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.service.dreams.DreamService
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ikverse.deskglow.data.hasLightSensor
import com.ikverse.deskglow.data.lightLevels
import com.ikverse.deskglow.graph
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.store.Brightness
import com.ikverse.deskglow.store.BrightnessMode
import com.ikverse.deskglow.ui.DeskglowTheme
import com.ikverse.deskglow.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The display itself, as both the screen saver and "Start now" show it. It always opens on the first
 * screen; two fingers swiping sideways move between screens, and a swipe shows which screen is open
 * for a few seconds so the dots do not stay lit. Double-tapping closes it ([onExit]) and triple-tapping
 * closes it into the app ([onOpenApp]); a single tap does nothing.
 *
 * [onSwiping] is told when a swipe starts (true) and, a moment after it, when the screen can rest again
 * (false), so the phone can run its panel fast only while something is moving. With the setting on,
 * [dark] (the room is dark) for [blankAfterMs] without a touch fades the display to black; a touch or
 * the light coming back undoes it.
 */
@Composable
internal fun LiveDisplay(
    onExit: () -> Unit,
    onOpenApp: () -> Unit,
    dark: Boolean = false,
    onSwiping: (Boolean) -> Unit = {},
    blankAfterMs: Long = BLANK_AFTER_MS,
) {
    val graph = androidx.compose.ui.platform.LocalContext.current.graph
    val burnIn by graph.prefs.burnIn.collectAsStateWithLifecycle()
    val blankInDark by graph.prefs.blankInDark.collectAsStateWithLifecycle()
    var swipes by remember { mutableIntStateOf(0) }
    var showDots by remember { mutableStateOf(false) }
    var touches by remember { mutableIntStateOf(0) }
    var blank by remember { mutableStateOf(false) }
    LaunchedEffect(swipes) {
        if (swipes == 0) return@LaunchedEffect
        showDots = true
        delay(3_000)
        showDots = false
    }
    LaunchedEffect(swipes) {
        if (swipes == 0) return@LaunchedEffect
        onSwiping(true)
        delay(SWIPE_FAST_MS)
        onSwiping(false)
    }
    val goesBlank = dark && blankInDark
    LaunchedEffect(goesBlank, touches) {
        blank = false
        if (goesBlank) {
            delay(blankAfterMs)
            blank = true
        }
    }
    WidgetHost(graph) {
        // The window's own shape picks the canvas, so a phone on a dock shows the landscape layout
        // and turning it switches at once, to the screens and the last screen of that orientation.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val orientation = Orientation.of(constraints.maxWidth, constraints.maxHeight)
            val pageCount by graph.prefs.pageCount(orientation).collectAsStateWithLifecycle()
            var page by remember(orientation) { mutableIntStateOf(graph.prefs.lastPage(orientation)) }
            val shown = page.coerceIn(0, pageCount - 1)
            Box(
                Modifier.fillMaxSize()
                    // Only notes that the screen was touched, so a blanked display wakes; it takes nothing from the gestures below.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            touches++
                        }
                    }
                    .twoFingerSwipe { direction ->
                        page = (shown + direction).coerceIn(0, pageCount - 1)
                        graph.prefs.setLastPage(orientation, page)
                        swipes++
                    }
                    .doubleOrTripleTap(onDouble = onExit, onTriple = onOpenApp),
            ) {
                AnimatedContent(
                    targetState = shown,
                    transitionSpec = {
                        val forward = targetState > initialState
                        slideInHorizontally { if (forward) it else -it } togetherWith slideOutHorizontally { if (forward) -it else it }
                    },
                    label = "screen",
                ) { index ->
                    val layout by graph.layoutsFor(orientation, index).layout.collectAsStateWithLifecycle()
                    DisplayContent(layout, burnIn, orientation = orientation)
                }
                AnimatedVisibility(
                    showDots && pageCount > 1,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                    enter = fadeIn(), exit = fadeOut(),
                ) {
                    Row(Modifier.testTag("screen dots"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in 0 until pageCount) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == shown) Color(0xFF8C8C8C) else Color(0xFF3A3A3A)))
                        }
                    }
                }
                if (blankInDark) {
                    val veil by animateFloatAsState(if (blank) 1f else 0f, tween(if (blank) BLANK_FADE_MS else 150), label = "veil")
                    if (blank || veil > 0f) Box(Modifier.fillMaxSize().testTag("blanked").drawBehind { drawRect(Color.Black, alpha = veil) })
                }
            }
        }
    }
}

/** How long a room has to stay dark, untouched, before the display fades to black (when that is switched on). */
internal const val BLANK_AFTER_MS = 10 * 60_000L
private const val BLANK_FADE_MS = 2_000

/** How long the panel stays fast after a swipe starts: the slide is a third of a second, and the dots a little more. */
private const val SWIPE_FAST_MS = 1_000L

/** How far two fingers must travel sideways together before it counts as a swipe to another screen. */
private val SWIPE_DISTANCE = 64.dp

/**
 * Calls [onSwipe] with +1 (towards the next screen) or -1 when two fingers move sideways together.
 * One finger is left alone, so a double tap works as usual; once a second finger lands
 * the touch is taken from them so it cannot also count as a tap.
 */
private fun Modifier.twoFingerSwipe(onSwipe: (direction: Int) -> Unit): Modifier = pointerInput(Unit) {
    val distance = SWIPE_DISTANCE.toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var startX: Float? = null
        var swiped = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.filter { it.pressed }
            if (down.size >= 2) {
                event.changes.forEach { it.consume() }
                val x = down.map { it.position.x }.average().toFloat()
                val start = startX
                if (start == null) startX = x
                else if (!swiped && abs(x - start) > distance) {
                    swiped = true
                    onSwipe(if (x < start) 1 else -1)
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Calls [onDouble] after two quick taps, or [onTriple] after three. The second tap waits one
 * double-tap interval for a third, so a double tap lands a moment after the finger lifts.
 */
private fun Modifier.doubleOrTripleTap(onDouble: () -> Unit, onTriple: () -> Unit): Modifier = pointerInput(onDouble, onTriple) {
    val gap = viewConfiguration.doubleTapTimeoutMillis
    awaitEachGesture {
        var taps = 0
        awaitFirstDown()
        while (true) {
            waitForUpOrCancellation() ?: return@awaitEachGesture
            taps++
            if (taps == 3) {
                onTriple()
                return@awaitEachGesture
            }
            if (withTimeoutOrNull(gap) { awaitFirstDown() } == null) {
                if (taps == 2) onDouble()
                return@awaitEachGesture
            }
        }
    }
}

/**
 * Hides the status and navigation bars, and with them Samsung's gesture hint: a white bar that would
 * sit in one place for hours on a screen that stays lit, which is how an AMOLED panel gets marked.
 */
private fun hideSystemBars(window: Window) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** The window brightness a [Brightness] setting asks for, or null to leave it to the system. */
internal fun windowBrightness(brightness: Brightness): Float? = when (brightness.mode) {
    BrightnessMode.System, BrightnessMode.Auto -> null // Auto is set as the room's light is read
    BrightnessMode.Dim -> 0.02f
    BrightnessMode.Custom -> (brightness.level / 100f).coerceIn(0.01f, 1f)
}

/** Brings the Deskglow app to the front, reusing it if it is already open. */
private fun Context.openApp() {
    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
}

/** The brightness setting, except that Auto on a phone with no light sensor is Dim. */
private fun Context.chosenBrightness(): Brightness {
    val brightness = graph.prefs.brightness.value
    return if (brightness.mode == BrightnessMode.Auto && !hasLightSensor(this)) brightness.copy(mode = BrightnessMode.Dim) else brightness
}

/** The brightness level at or below which a room counts as dark: about a lux and a half, a bedroom with the lights off. */
private const val DARK_LEVEL = 0.05f

/**
 * Keeps [window]'s brightness matched to the room's light for as long as this is running, and tells
 * [onDark] whether the room is dark (false again once this stops). The same sensor reading serves both.
 */
private suspend fun followRoomLight(context: Context, window: Window, onDark: (Boolean) -> Unit = {}) {
    try {
        lightLevels(context).collect { level ->
            window.attributes = window.attributes.apply { screenBrightness = level }
            onDark(level <= DARK_LEVEL)
        }
    } finally {
        onDark(false)
    }
}

/**
 * Holds Android's proximity screen-off lock, so the screen goes off while the phone is covered or face
 * down and comes back when it is clear. Null if the owner has switched it off or the phone has no
 * proximity sensor. The timeout is only a backstop for a lock that is somehow never released.
 */
private fun Context.holdCoverOff(): PowerManager.WakeLock? {
    if (!graph.prefs.coverOff.value) return null
    val power = getSystemService(PowerManager::class.java) ?: return null
    if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return null
    return power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Deskglow:cover").apply {
        setReferenceCounted(false)
        acquire(12 * 60 * 60 * 1000L)
    }
}

private fun PowerManager.WakeLock?.letGo() {
    if (this?.isHeld == true) release()
}

/**
 * Deskglow as Android's screen saver. Android starts it by itself when the phone is charging and
 * the screen would turn off (once the owner has chosen it in the screen saver settings), and ends it
 * when the phone is unplugged or touched. Nothing runs while it is not showing.
 */
class DeskglowDream : DreamService() {
    private val owner = ViewOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lightJob: Job? = null
    private var roomDark by mutableStateOf(false)
    private var coverLock: PowerManager.WakeLock? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive, so a touch reaches the display instead of ending it: it is ended by a double tap,
        // the charger coming out, or the power button.
        isInteractive = true
        isFullscreen = true
        val brightness = chosenBrightness()
        // Android's own dim mode for screen savers, or a level of the owner's choosing.
        isScreenBright = brightness.mode != BrightnessMode.Dim
        if (brightness.mode == BrightnessMode.Custom) {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = windowBrightness(brightness) ?: screenBrightness } }
        }
        owner.create()
        setContentView(ComposeView(this).also { view ->
            owner.attach(view)
            view.setContent {
                DeskglowTheme {
                    LiveDisplay(
                        onExit = ::finish,
                        onOpenApp = { openApp(); finish() },
                        dark = roomDark,
                        onSwiping = { fast -> window?.setRefreshRate(this, fast) },
                    )
                }
            }
        })
        window?.let(::hideSystemBars)
        window?.setRefreshRate(this, fast = false)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        window?.let(::hideSystemBars) // again now the window is on screen: some phones only honour it then
        window?.setRefreshRate(this, fast = false)
        owner.resume()
        coverLock.letGo()
        coverLock = holdCoverOff()
        val window = window
        if (window != null && chosenBrightness().mode == BrightnessMode.Auto) {
            lightJob = scope.launch { followRoomLight(this@DeskglowDream, window) { roomDark = it } }
        }
    }

    override fun onDreamingStopped() {
        lightJob?.cancel()
        coverLock.letGo()
        coverLock = null
        owner.pause()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        coverLock.letGo()
        scope.cancel()
        owner.destroy()
        super.onDetachedFromWindow()
    }
}

/**
 * "Start now": the display full screen without waiting for the charger, kept on until closed. A double
 * tap closes it back to whatever was open before; a triple tap closes it into the app. Back closes it too.
 */
class DisplayActivity : ComponentActivity() {
    private var roomDark by mutableStateOf(false)
    private var coverLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val brightness = chosenBrightness()
        windowBrightness(brightness)?.let { level ->
            window.attributes = window.attributes.apply { screenBrightness = level }
        }
        if (brightness.mode == BrightnessMode.Auto) {
            lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { followRoomLight(this@DisplayActivity, window) { roomDark = it } } }
        }
        hideSystemBars(window)
        window.setRefreshRate(this, fast = false)
        setContent {
            DeskglowTheme {
                LiveDisplay(
                    onExit = ::finish,
                    onOpenApp = { openApp(); finish() },
                    dark = roomDark,
                    onSwiping = { fast -> window.setRefreshRate(this, fast) },
                )
            }
        }
    }

    // Held only while this is the screen being shown, so covering the phone in another app is never affected.
    override fun onResume() {
        super.onResume()
        coverLock.letGo()
        coverLock = holdCoverOff()
    }

    override fun onPause() {
        coverLock.letGo()
        coverLock = null
        super.onPause()
    }
}

/**
 * A screen saver is a service, not an activity, so it has none of the lifecycle that Compose needs to
 * know when it is on screen. This supplies one: created with the window, resumed while dreaming,
 * destroyed with the window. Widgets stop reading their feeds whenever it is not resumed.
 */
private class ViewOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun create() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun attach(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun pause() { registry.currentState = Lifecycle.State.CREATED }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
