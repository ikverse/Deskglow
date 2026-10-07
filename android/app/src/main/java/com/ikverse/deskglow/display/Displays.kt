package com.ikverse.deskglow.display

import android.os.Bundle
import android.service.dreams.DreamService
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** The display itself, as both the screen saver and "Start now" show it. */
@Composable
private fun LiveDisplay() {
    val graph = androidx.compose.ui.platform.LocalContext.current.graph
    val burnIn by graph.prefs.burnIn.collectAsStateWithLifecycle()
    WidgetHost(graph) {
        // The window's own shape picks the canvas, so a phone on a dock shows the landscape layout
        // and turning it switches at once.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val orientation = Orientation.of(constraints.maxWidth, constraints.maxHeight)
            val layout by graph.layoutsFor(orientation).layout.collectAsStateWithLifecycle()
            DisplayContent(layout, burnIn, orientation = orientation)
        }
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
        isInteractive = false
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
            view.setContent { DeskglowTheme { LiveDisplay() } }
        })
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            DeskglowTheme {
                var taps by remember { mutableIntStateOf(0) }
                var showExit by remember { mutableIntStateOf(0) }
                LaunchedEffect(taps) {
                    if (taps == 0) return@LaunchedEffect
                    showExit = 1
                    delay(3_000)
                    showExit = 0
                }
                Box(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { taps++ },
                ) {
                    LiveDisplay()
                    AnimatedVisibility(showExit == 1, Modifier.align(Alignment.TopEnd).padding(16.dp), enter = fadeIn(), exit = fadeOut()) {
                        TextButton(onClick = ::finish) { Text("Exit", color = Color(0xFF8C8C8C)) }
                    }
                }
            }
        }
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
