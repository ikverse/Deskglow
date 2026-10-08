package com.ikverse.deskglow.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ikverse.deskglow.display.WidgetHost
import com.ikverse.deskglow.graph
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.ui.editor.EditorScreen

enum class Screen { Home, Editor, EditorLandscape, AutoStart, Permissions, City, Brightness, Snapshots, About }

/** The editors draw edge to edge; every other screen stays inside the system bars. */
private val Screen.isEditor: Boolean get() = this == Screen.Editor || this == Screen.EditorLandscape

/** A strong ease-out: starts fast, so the new screen is already moving the moment it is asked for. */
private val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark whatever the phone is set to, so the bars are always the dark kind (light icons,
        // no light scrim). Plain enableEdgeToEdge() follows the phone: dark icons on a near-black page in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { DeskglowTheme { App() } }
    }
}

@Composable
fun App() {
    val graph = LocalContext.current.graph
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home && !screen.isEditor) { screen = Screen.Home }
    WidgetHost(graph) {
        Box(Modifier.fillMaxSize().background(Palette.Page)) {
            // Screens change with a short fade and nothing else: opacity only, no sliding, scaling or
            // resizing, so the change costs the graphics card almost nothing. The new screen is quick to
            // arrive and the old one quicker to leave. With the system's animations off it is instant.
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn(tween(160, easing = EaseOut)) togetherWith fadeOut(tween(100, easing = EaseOut)))
                        .using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                label = "screen",
            ) { current ->
                Box(Modifier.fillMaxSize().then(if (current.isEditor) Modifier else Modifier.safeDrawingPadding())) {
                    when (current) {
                        Screen.Home -> HomeScreen(graph) { screen = it }
                        Screen.Editor -> EditorScreen(graph, Orientation.Portrait) { screen = Screen.Home }
                        Screen.EditorLandscape -> EditorScreen(graph, Orientation.Landscape) { screen = Screen.Home }
                        Screen.AutoStart -> AutoStartScreen { screen = Screen.Home }
                        Screen.Permissions -> PermissionsScreen { screen = Screen.Home }
                        Screen.City -> CityScreen(graph) { screen = Screen.Home }
                        Screen.Brightness -> BrightnessScreen(graph) { screen = Screen.Home }
                        Screen.Snapshots -> SnapshotsScreen(graph) { screen = Screen.Home }
                        Screen.About -> AboutScreen { screen = Screen.Home }
                    }
                }
            }
        }
    }
}
