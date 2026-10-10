package com.ikverse.deskglow.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.BuildConfig
import com.ikverse.deskglow.data.hasCalendarAccess
import com.ikverse.deskglow.data.hasLocationAccess
import com.ikverse.deskglow.data.hasNotificationAccess
import com.ikverse.deskglow.fonts.BundledFonts
import com.ikverse.deskglow.store.Brightness
import com.ikverse.deskglow.store.BrightnessMode
import com.ikverse.deskglow.store.City
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun Body(text: String) = Text(text, fontSize = Type.Body, lineHeight = 22.sp)

@Composable
private fun Small(text: String) = Text(text, fontSize = Type.Small, color = Palette.Muted, lineHeight = 19.sp)

/** A card whose content is spaced like a short group of paragraphs. */
@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) =
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }

@Composable
fun AutoStartScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val status = remember(resumes) { screenSaverStatus(context) }
    ScreenFrame("Start automatically when charging", onBack) {
        Section {
            Text(status, fontSize = Type.Body, fontWeight = FontWeight.Medium, color = if (status.startsWith("On")) Palette.Select else Palette.Ink)
            Body("Android can start Deskglow by itself whenever the phone is charging and the screen would turn off. It ends when you unplug or touch the phone, and uses nothing the rest of the time.")
            Body("1. Tap Open screen saver settings.\n2. Choose Deskglow, set When to start to While charging, and switch it on.")
        }
        AppButton("Open screen saver settings", { context.open(Intent(Settings.ACTION_DREAM_SETTINGS)) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
}

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val notifications = remember(resumes) { hasNotificationAccess(context) }
    var calendar by remember(resumes) { mutableStateOf(hasCalendarAccess(context)) }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendar = granted
        asked = true
    }
    ScreenFrame("Permissions", onBack) {
        Small("Both are optional. The clock, date and battery widgets need neither.")
        Section {
            Body("Notification access")
            Small("For the notification icons and Now playing. Deskglow only looks while its screen is showing, and nothing leaves the phone.")
            Text(if (notifications) "Allowed" else "Not allowed", fontSize = Type.Small, color = if (notifications) Palette.Select else Palette.Muted)
            AppButton(
                if (notifications) "Change in settings" else "Allow in settings",
                { context.open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, Modifier.fillMaxWidth(),
            )
        }
        Section {
            Body("Calendar")
            Small("For the Next event widget. Only read, never changed.")
            Text(if (calendar) "Allowed" else "Not allowed", fontSize = Type.Small, color = if (calendar) Palette.Select else Palette.Muted)
            if (!calendar) {
                AppButton(if (asked) "Allow in settings" else "Allow", {
                    if (asked) {
                        // Asked once and refused: Android will not ask again, so the app's own settings page is the way.
                        context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    } else {
                        request.launch(Manifest.permission.READ_CALENDAR)
                    }
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun CityScreen(graph: AppGraph, onBack: () -> Unit) {
    val current by graph.prefs.city.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<City>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() {
        if (query.isBlank() || searching) return
        searching = true
        message = null
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { graph.weather.search(query) } }
            searching = false
            found.onSuccess {
                results = it
                if (it.isEmpty()) message = "No place called \"$query\" was found."
            }.onFailure { message = "The search could not reach Open-Meteo. Check the connection and try again." }
        }
    }
    val context = LocalContext.current
    val auto by graph.prefs.autoLocation.collectAsStateWithLifecycle()
    val detected by graph.prefs.detectedCity.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    var allowed by remember(resumes) { mutableStateOf(hasLocationAccess(context)) }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        asked = true
    }
    val shown = if (auto && allowed) detected ?: current else current
    ScreenFrame("Weather city", onBack) {
        Section {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Body("Use my location")
                    Small("Finds your city by itself and keeps it up to date. Only its rough position is used.")
                }
                Switch(checked = auto, onCheckedChange = { on ->
                    graph.prefs.setAutoLocation(on)
                    if (on && !allowed && !asked) request.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                })
            }
            if (auto && !allowed) {
                Small("Location is not allowed, so the city chosen below is used.")
                AppButton(if (asked) "Allow in settings" else "Allow location", {
                    if (asked) {
                        // Asked once and refused: Android will not ask again, so the app's own settings page is the way.
                        context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    } else {
                        request.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                })
            }
        }
        Small(shown?.let { "Showing the weather for ${it.label}." } ?: "No city set yet. The weather widget stays empty until one is.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("City") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            AppButton(if (searching) "…" else "Search", ::search, kind = ButtonKind.Primary, enabled = !searching)
        }
        message?.let { Small(it) }
        results?.takeIf { it.isNotEmpty() }?.let { found ->
            Card {
                found.forEachIndexed { i, city ->
                    Column(Modifier.fillMaxWidth().pressable { graph.prefs.setCity(city); onBack() }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(city.name, fontSize = Type.Body)
                        Small(city.region)
                    }
                    if (i < found.lastIndex) Rule()
                }
            }
        }
        if (current != null) AppButton("Remove city", { graph.prefs.setCity(null) }, kind = ButtonKind.Danger)
        Small("Weather data by Open-Meteo.com. Only a position rounded to about a kilometre is sent, never your name or anything else.")
    }
}

@Composable
fun BrightnessScreen(graph: AppGraph, onBack: () -> Unit) {
    val brightness by graph.prefs.brightness.collectAsStateWithLifecycle()
    val burnIn by graph.prefs.burnIn.collectAsStateWithLifecycle()
    val coverOff by graph.prefs.coverOff.collectAsStateWithLifecycle()
    val blankInDark by graph.prefs.blankInDark.collectAsStateWithLifecycle()
    ScreenFrame("Brightness and burn-in", onBack) {
        Small("How bright the display is while it shows.")
        Card {
            val modes = listOf(
                BrightnessMode.Dim to "Dim (the screen saver's own low brightness)",
                BrightnessMode.System to "Follow the phone's brightness",
                BrightnessMode.Custom to "Choose a level",
                BrightnessMode.Auto to "Match the room's light",
            )
            modes.forEachIndexed { i, (mode, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { graph.prefs.setBrightness(brightness.copy(mode = mode)) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = brightness.mode == mode, onClick = { graph.prefs.setBrightness(brightness.copy(mode = mode)) })
                    Text(label, fontSize = Type.Body)
                }
                if (i < modes.lastIndex) Rule()
            }
            if (brightness.mode == BrightnessMode.Custom) {
                Rule()
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = brightness.level.toFloat(),
                        onValueChange = { graph.prefs.setBrightness(Brightness(BrightnessMode.Custom, it.toInt())) },
                        valueRange = 1f..100f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${brightness.level}%", color = Palette.Muted, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        Section {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Body("Burn-in protection")
                    Small("Moves the whole layout a few pixels every minute, so hours of the same white digits cannot mark the screen.")
                }
                Switch(checked = burnIn, onCheckedChange = graph.prefs::setBurnIn)
            }
        }
        Section {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Body("Turn off when covered")
                    Small("The screen goes off while the phone lies face down or is covered, and comes back on when it is clear. Needs a proximity sensor.")
                }
                Switch(checked = coverOff, onCheckedChange = graph.prefs::setCoverOff)
            }
        }
        Section {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Body("Blank in a dark room")
                    Small("With Match the room's light: after 10 minutes in the dark with no touch the screen fades to black. A touch, or the light coming on, brings it back.")
                }
                Switch(checked = blankInDark, onCheckedChange = graph.prefs::setBlankInDark)
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    ScreenFrame("About and licences", onBack) {
        Section {
            Body("Deskglow ${BuildConfig.VERSION_NAME}")
            Small("A charging and desk screen for your phone. Nothing runs while it is not showing.")
        }
        Section {
            Body("Weather")
            Small("Weather data by Open-Meteo.com, under the Creative Commons Attribution 4.0 licence.")
        }
        Section {
            Body("Fonts")
            Small("The fonts below ship with Deskglow under the SIL Open Font License 1.1. Fonts you pick from Google Fonts are open source too; each one's licence is listed at fonts.google.com. Tap a font to read its licence.")
        }
        Card {
            BundledFonts.all.forEachIndexed { i, font ->
                Column(Modifier.fillMaxWidth().pressable { open = if (open == font.id) null else font.id }.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(font.label, fontSize = Type.Body, modifier = Modifier.padding(vertical = 10.dp))
                    if (open == font.id) {
                        val text = remember(font.id) {
                            runCatching { context.assets.open("licences/${font.licenceFile}").bufferedReader().readText() }.getOrDefault("")
                        }
                        Text(text, fontSize = 11.sp, color = Palette.Muted, lineHeight = 15.sp, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                if (i < BundledFonts.all.lastIndex) Rule()
            }
        }
    }
}
