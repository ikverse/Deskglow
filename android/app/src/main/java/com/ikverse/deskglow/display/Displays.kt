package com.ikverse.deskglow.display

import android.os.Bundle
import android.service.dreams.DreamService
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ikverse.deskglow.graph
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.store.Brightness
import com.ikverse.deskglow.store.BrightnessMode
import com.ikverse.deskglow.ui.DeskglowTheme
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The display itself, as both the screen saver and "Start now" show it. It always opens on the first
 * screen; two fingers swiping sideways move between screens. A tap shows an Exit button for a few
 * seconds, and a swipe shows which screen is open for the same time, so neither stays lit.
 */
@Composable
internal fun LiveDisplay(onExit: () -> Unit) {
    val graph = androidx.compose.ui.platform.LocalContext.current.graph
    val burnIn by graph.prefs.burnIn.collectAsStateWithLifecycle()
    var taps by remember { mutableIntStateOf(0) }
    var swipes by remember { mutableIntStateOf(0) }
    var showExit by remember { mutableStateOf(false) }
    var showDots by remember { mutableStateOf(false) }
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        showExit = true
        delay(3_000)
        showExit = false
    }
    LaunchedEffect(swipes) {
        if (swipes == 0) return@LaunchedEffect
        showDots = true
        delay(3_000)
        showDots = false
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
                    .twoFingerSwipe { direction ->
                        page = (shown + direction).coerceIn(0, pageCount - 1)
                        graph.prefs.setLastPage(orientation, page)
                        swipes++
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { taps++ },
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
                AnimatedVisibility(showExit, Modifier.align(Alignment.TopEnd).padding(16.dp), enter = fadeIn(), exit = fadeOut()) {
                    TextButton(onClick = onExit) { Text("Exit", color = Color(0xFF8C8C8C)) }
                }
            }
        }
    }
}

/** How far two fingers must travel sideways together before it counts as a swipe to another screen. */
private val SWIPE_DISTANCE = 64.dp

/**
 * Calls [onSwipe] with +1 (towards the next screen) or -1 when two fingers move sideways together.
 * One finger is left alone, so taps and the Exit button work as usual; once a second finger lands
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
    BrightnessMode.System -> null
    BrightnessMode.Dim -> 0.02f
    BrightnessMode.Custom -> (brightness.level / 100f).coerceIn(0.01f, 1f)
}

/**
 * Deskglow as Android's screen saver. Android starts it by itself when the phone is charging and
 * the screen would turn off (once the owner has chosen it in the screen saver settings), and ends it
 * when the phone is unplugged or touched. Nothing runs while it is not showing.
 */
class DeskglowDream : DreamService() {
    private val owner = ViewOwner()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive, so a touch reaches the display instead of ending it: it is ended by an Exit button,
        // the charger coming out, or the power button.
        isInteractive = true
        isFullscreen = true
        val brightness = graph.prefs.brightness.value
        // Android's own dim mode for screen savers, or a level of the owner's choosing.
        isScreenBright = brightness.mode != BrightnessMode.Dim
        if (brightness.mode == BrightnessMode.Custom) {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = windowBrightness(brightness) ?: screenBrightness } }
        }
        owner.create()
        setContentView(ComposeView(this).also { view ->
            owner.attach(view)
            view.setContent { DeskglowTheme { LiveDisplay(onExit = ::finish) } }
        })
        window?.let(::hideSystemBars)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        window?.let(::hideSystemBars) // again now the window is on screen: some phones only honour it then
        owner.resume()
    }

    override fun onDreamingStopped() {
        owner.pause()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        owner.destroy()
        super.onDetachedFromWindow()
    }
}

/**
 * "Start now": the display full screen without waiting for the charger, kept on until closed. A tap
 * shows an Exit button for a few seconds; Back also closes it.
 */
class DisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        windowBrightness(graph.prefs.brightness.value)?.let { level ->
            window.attributes = window.attributes.apply { screenBrightness = level }
        }
        hideSystemBars(window)
        setContent { DeskglowTheme { LiveDisplay(onExit = ::finish) } }
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
