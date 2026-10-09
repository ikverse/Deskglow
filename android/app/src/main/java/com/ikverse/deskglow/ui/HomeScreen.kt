package com.ikverse.deskglow.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.data.hasCalendarAccess
import com.ikverse.deskglow.data.hasLocationAccess
import com.ikverse.deskglow.data.hasNotificationAccess
import com.ikverse.deskglow.display.DeskglowDream
import com.ikverse.deskglow.display.DisplayActivity
import com.ikverse.deskglow.display.DisplayContent
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.store.BrightnessMode

/**
 * [go] opens a screen; [edit] opens a layout's editor on one of its screens (the one being shown in
 * the preview).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    graph: AppGraph,
    go: (Screen) -> Unit,
    edit: (Orientation, Int) -> Unit = { orientation, _ -> go(if (orientation == Orientation.Portrait) Screen.Editor else Screen.EditorLandscape) },
) {
    val context = LocalContext.current
    val portraitScreens by graph.prefs.pageCount(Orientation.Portrait).collectAsStateWithLifecycle()
    val landscapeScreens by graph.prefs.pageCount(Orientation.Landscape).collectAsStateWithLifecycle()
    val city by graph.prefs.city.collectAsStateWithLifecycle()
    val auto by graph.prefs.autoLocation.collectAsStateWithLifecycle()
    val detected by graph.prefs.detectedCity.collectAsStateWithLifecycle()
    val brightness by graph.prefs.brightness.collectAsStateWithLifecycle()
    val burnIn by graph.prefs.burnIn.collectAsStateWithLifecycle()
    val saved by graph.snapshots.snapshots.collectAsStateWithLifecycle()
    // Permissions and the screen saver are changed in Android's settings, so look again on every return.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val autoStart = remember(resumes) { screenSaverStatus(context) }
    val permissions = remember(resumes) {
        val off = listOfNotNull(
            "Notifications".takeUnless { hasNotificationAccess(context) },
            "Calendar".takeUnless { hasCalendarAccess(context) },
        )
        if (off.isEmpty()) "All allowed" else off.joinToString(" and ") + " off"
    }
    val located = remember(resumes) { hasLocationAccess(context) }
    val shownCity = if (auto && located) detected ?: city else city

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Desk", fontSize = Type.Title, fontWeight = FontWeight.Medium, letterSpacing = 0.04.em)
            Text("glow", fontSize = Type.Title, fontWeight = FontWeight.Medium, letterSpacing = 0.04.em, color = Palette.Select)
        }

        PreviewCard(graph, edit)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                "Start Deskglow", { context.startActivity(Intent(context, DisplayActivity::class.java)) },
                Modifier.fillMaxWidth().height(56.dp), kind = ButtonKind.Primary, glyph = Glyph.Play,
            )
            Text(
                "Full screen without the charger. Also on the home-screen widget, the app-icon shortcut and the Quick Settings tile.",
                fontSize = Type.Small, color = Palette.Muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val charging = autoStart.startsWith("On")
            Chip(if (charging) "Starts when charging" else "Auto-start off", { go(Screen.AutoStart) }, dot = charging)
            Chip(
                "Brightness " + when (brightness.mode) {
                    BrightnessMode.System -> "follows phone"
                    BrightnessMode.Dim -> "dim"
                    BrightnessMode.Custom -> "${brightness.level}%"
                } + if (burnIn) "" else " · burn-in off",
                { go(Screen.Brightness) },
            )
            Chip(shownCity?.label?.substringBefore(',') ?: "Set weather city", { go(Screen.City) })
        }

        Card {
            CardRow(Glyph.Phone, "Portrait layout", screensLabel(portraitScreens), { edit(Orientation.Portrait, 0) })
            CardRow(Glyph.PhoneSideways, "Landscape layout", screensLabel(landscapeScreens), { edit(Orientation.Landscape, 0) })
            CardRow(
                Glyph.Archive, "Saved layouts",
                if (saved.isEmpty()) "Back up both layouts and restore them later" else "${saved.size} saved · back up to a file",
                { go(Screen.Snapshots) }, last = true,
            )
        }
        Card {
            CardRow(Glyph.Shield, "Permissions", permissions, { go(Screen.Permissions) })
            CardRow(Glyph.Info, "About and licences", null, { go(Screen.About) }, last = true)
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun screensLabel(count: Int) = if (count == 1) "1 screen" else "$count screens"

/**
 * Every screen of both layouts, drawn live, to swipe through. Tapping one, or Edit layout, opens the
 * editor on that screen.
 */
@Composable
private fun PreviewCard(graph: AppGraph, edit: (Orientation, Int) -> Unit) {
    val portraitScreens by graph.prefs.pageCount(Orientation.Portrait).collectAsStateWithLifecycle()
    val landscapeScreens by graph.prefs.pageCount(Orientation.Landscape).collectAsStateWithLifecycle()
    val screens = remember(portraitScreens, landscapeScreens) {
        (0 until portraitScreens).map { Orientation.Portrait to it } + (0 until landscapeScreens).map { Orientation.Landscape to it }
    }
    val pager = rememberPagerState { screens.size }
    val (orientation, index) = screens[pager.currentPage.coerceIn(0, screens.lastIndex)]
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalPager(pager, Modifier.fillMaxWidth().height(330.dp).testTag("preview pager"), pageSpacing = 12.dp) { page ->
                val (pageOrientation, pageIndex) = screens[page]
                val layout by graph.layoutsFor(pageOrientation, pageIndex).layout.collectAsStateWithLifecycle()
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val aspect = pageOrientation.width.toFloat() / pageOrientation.height
                    val wide = maxWidth / maxHeight > aspect
                    val density = LocalDensity.current
                    val fitHeight = if (wide) maxHeight else maxWidth / aspect
                    val fitWidth = if (wide) maxHeight * aspect else maxWidth
                    Box(
                        Modifier
                            .size(fitWidth, fitHeight)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Palette.Rule, RoundedCornerShape(12.dp))
                            .pressable(role = androidx.compose.ui.semantics.Role.Button) { edit(pageOrientation, pageIndex) },
                    ) {
                        DisplayContent(layout, burnIn = false, orientation = pageOrientation)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${orientation.name} · screen ${index + 1} of ${if (orientation == Orientation.Portrait) portraitScreens else landscapeScreens}",
                        fontSize = Type.Small, color = Palette.Muted,
                    )
                    Row(Modifier.testTag("preview dots"), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (i in screens.indices) {
                            val current = i == pager.currentPage
                            Box(
                                Modifier.height(4.dp).width(if (current) 18.dp else 6.dp).clip(RoundedCornerShape(2.dp))
                                    .background(if (current) Palette.Select else Palette.Edge),
                            )
                        }
                    }
                }
                AppButton("Edit layout", { edit(orientation, index) })
            }
        }
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
