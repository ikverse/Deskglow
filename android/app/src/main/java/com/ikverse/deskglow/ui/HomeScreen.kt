package com.ikverse.deskglow.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.BuildConfig
import com.ikverse.deskglow.data.hasCalendarAccess
import com.ikverse.deskglow.data.hasLocationAccess
import com.ikverse.deskglow.data.hasNotificationAccess
import com.ikverse.deskglow.display.DeskglowDream
import com.ikverse.deskglow.display.DisplayActivity
import com.ikverse.deskglow.display.DisplayContent
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.store.BrightnessMode

@Composable
fun HomeScreen(graph: AppGraph, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val portrait by graph.layouts.layout.collectAsStateWithLifecycle()
    val landscape by graph.landscapeLayouts.layout.collectAsStateWithLifecycle()
    val city by graph.prefs.city.collectAsStateWithLifecycle()
    val auto by graph.prefs.autoLocation.collectAsStateWithLifecycle()
    val detected by graph.prefs.detectedCity.collectAsStateWithLifecycle()
    val brightness by graph.prefs.brightness.collectAsStateWithLifecycle()
    val burnIn by graph.prefs.burnIn.collectAsStateWithLifecycle()
    // Permissions and the screen saver are changed in Android's settings, so look again on every return.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val autoStart = remember(resumes) { screenSaverStatus(context) }
    val permissions = remember(resumes) {
        listOf(
            "Notifications " + if (hasNotificationAccess(context)) "allowed" else "off",
            "Calendar " + if (hasCalendarAccess(context)) "allowed" else "off",
        ).joinToString(" · ")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Deskglow", fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 16.dp))
        // Both layouts side by side, in the same proportions as the screens they are for (1 : 2 in width).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LayoutPreview("Portrait", portrait, Orientation.Portrait, Modifier.weight(1f)) { go(Screen.Editor) }
            LayoutPreview("Landscape", landscape, Orientation.Landscape, Modifier.weight(2f)) { go(Screen.EditorLandscape) }
        }
        Box(Modifier.padding(top = 16.dp)) { Rule() }
        HomeRow("Edit portrait layout", "Phone upright: move, resize and style your widgets") { go(Screen.Editor) }
        HomeRow("Edit landscape layout", "Phone on its side, for a dock") { go(Screen.EditorLandscape) }
        HomeRow("Start now", "Show it full screen without waiting for the charger") {
            context.startActivity(Intent(context, DisplayActivity::class.java))
        }
        HomeRow("Start automatically when charging", autoStart) { go(Screen.AutoStart) }
        HomeRow("Permissions", permissions) { go(Screen.Permissions) }
        val located = remember(resumes) { hasLocationAccess(context) }
        val autoLocation = auto && located
        HomeRow("Weather city", if (autoLocation) "Auto: " + (detected ?: city)?.label.orEmpty().ifBlank { "finding…" } else city?.label ?: "Not set") { go(Screen.City) }
        HomeRow(
            "Brightness and burn-in",
            when (brightness.mode) {
                BrightnessMode.System -> "Follows the phone"
                BrightnessMode.Dim -> "Dim"
                BrightnessMode.Custom -> "${brightness.level}%"
            } + if (burnIn) " · burn-in protection on" else " · burn-in protection off",
        ) { go(Screen.Brightness) }
        HomeRow("About and licences", "Version ${BuildConfig.VERSION_NAME}") { go(Screen.About) }
    }
}

/** One layout drawn live, as small as a thumbnail, with its name under it. Tapping it opens its editor. */
@Composable
private fun LayoutPreview(caption: String, layout: Layout, orientation: Orientation, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(orientation.width.toFloat() / orientation.height)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Palette.Rule, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        ) {
            DisplayContent(layout, burnIn = false, orientation = orientation)
        }
        Text(caption, fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun HomeRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp)
                Text(detail, fontSize = 13.sp, color = Palette.Muted)
            }
            Text("›", fontSize = 22.sp, color = Palette.Muted)
        }
        Rule()
    }
}

/** A plain screen with a back link and a title. */
@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Palette.Select, fontSize = 15.sp) }
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        Rule()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/**
 * Whether Deskglow is the screen saver and set to start while charging. Android keeps these in
 * settings that some phones do not let apps read; then the status just invites a look.
 */
fun screenSaverStatus(context: Context): String = runCatching {
    val resolver = context.contentResolver
    val enabled = Settings.Secure.getInt(resolver, "screensaver_enabled", 0) == 1
    val ours = Settings.Secure.getString(resolver, "screensaver_components")
        ?.contains(DeskglowDream::class.java.name) == true
    val whileCharging = Settings.Secure.getInt(resolver, "screensaver_activate_on_sleep", 0) == 1
    when {
        enabled && ours && whileCharging -> "On: starts by itself while charging"
        enabled && ours -> "Chosen, but not set to start while charging"
        else -> "Off: tap to set up"
    }
}.getOrDefault("Tap to set up")
