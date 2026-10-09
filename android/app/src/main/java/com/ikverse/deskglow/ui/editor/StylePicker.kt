package com.ikverse.deskglow.ui.editor

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.ikverse.deskglow.ui.pressable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.WeatherState
import com.ikverse.deskglow.display.WidgetTextStyle
import com.ikverse.deskglow.fonts.BundledFonts
import com.ikverse.deskglow.fonts.CatalogFont
import com.ikverse.deskglow.fonts.FontCategory
import com.ikverse.deskglow.fonts.FontIds
import com.ikverse.deskglow.fonts.GoogleFonts
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.fonts.PickedFont
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import com.ikverse.deskglow.ui.Palette
import com.ikverse.deskglow.ui.Rule
import com.ikverse.deskglow.widgets.ClockFace
import com.ikverse.deskglow.widgets.ClockStyles
import com.ikverse.deskglow.widgets.ClockWidget
import com.ikverse.deskglow.widgets.Common
import com.ikverse.deskglow.widgets.DateFace
import com.ikverse.deskglow.widgets.DateWidget
import com.ikverse.deskglow.widgets.FitText
import com.ikverse.deskglow.widgets.StyleField
import com.ikverse.deskglow.widgets.StyleKind
import com.ikverse.deskglow.widgets.TimeText
import com.ikverse.deskglow.widgets.WeatherWidget
import kotlinx.coroutines.launch

/** One tile in a strip: a style or font id, its name, and whether it can be chosen right now. */
data class StyleOption(val id: String, val label: String, val enabled: Boolean = true, val note: String? = null)

/**
 * What a strip offers: for the clock, the drawn styles and the phone's thin and bold, then the
 * bundled fonts, then fonts picked from the library; for the date, the phone's thin font, then the
 * same fonts. In Arabic mode only fonts with Arabic letters are offered, and seven-segment (which
 * cannot form Arabic numerals) is shown but greyed out.
 */
fun styleOptions(kind: StyleKind, settings: Settings, picked: List<PickedFont>): List<StyleOption> {
    val arabic = settings[Common.ARABIC]
    val arabicDigits = Common.arabicDigits(settings)
    val builtIn = when (kind) {
        StyleKind.Clock -> listOf(
            StyleOption("squared", "Squared"), StyleOption(FontIds.THIN, "Thin"), StyleOption(FontIds.BOLD, "Bold"),
        ) + ClockStyles.DRAWN.filter { it.first != "squared" }.map { (id, label) ->
            if (arabicDigits && !ClockStyles.supportsArabic(id)) StyleOption(id, label, enabled = false, note = "Western only") else StyleOption(id, label)
        }
        StyleKind.Date -> listOf(StyleOption(FontIds.THIN, "Thin (default)"))
        StyleKind.Weather -> return WeatherWidget.LAYOUTS.map { (id, label) -> StyleOption(id, label) }
    }
    val bundled = BundledFonts.all.filter { !arabic || it.arabic }.map { StyleOption(it.id, it.label) }
    val library = picked.filter { if (arabic) it.arabic else it.latin }.map { StyleOption(it.id, it.family) }
    return builtIn + bundled + library
}

private fun keyOf(kind: StyleKind): TextKey = when (kind) {
    StyleKind.Clock -> ClockWidget.STYLE
    StyleKind.Date -> DateWidget.FONT
    StyleKind.Weather -> WeatherWidget.LAYOUT
}

/** The sideways strip of preview tiles, ending in "More fonts". */
@Composable
fun StyleStrip(field: StyleField, settings: Settings, state: EditorState, graph: AppGraph) {
    val picked by graph.fontLibrary.picked.collectAsStateWithLifecycle()
    val options = styleOptions(field.kind, settings, picked)
    val current = settings[field.key]
    val start = (options.indexOfFirst { it.id == current } - 1).coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row {
            Text(field.label, fontSize = 15.sp)
            Text(options.firstOrNull { it.id == current }?.label ?: LocalFonts.current.label(current), fontSize = 15.sp, color = Palette.Muted, modifier = Modifier.padding(start = 10.dp))
        }
        LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 10.dp), modifier = Modifier.testTag("strip")) {
            items(options, key = { it.id }) { option ->
                Tile(option.label + (option.note?.let { " · $it" } ?: ""), selected = option.id == current, enabled = option.enabled, onClick = { state.set(field.key, option.id) }) {
                    Preview(field.kind, settings, option.id)
                }
            }
            if (field.kind != StyleKind.Weather) item(key = "more") {
                Tile("More fonts", selected = false, enabled = true, onClick = { state.moreFonts = field.kind }) {
                    Text("Aa +", color = Palette.Select, fontSize = 22.sp)
                }
            }
        }
    }
}

/** One choice in a strip: a drawing of it over its name. [width] and [previewHeight] let a strip of whole widgets be larger than one of type samples. */
@Composable
internal fun Tile(
    label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    width: Dp = 104.dp, previewHeight: Dp = 56.dp, preview: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(width).alpha(if (enabled) 1f else 0.35f).clip(shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Palette.Select else Palette.Edge, shape)
            .pressable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(previewHeight).clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(4.dp), contentAlignment = Alignment.Center) { preview() }
        // Two lines, so "Seven-segment · Western only" is read in full; anything longer ends in "…" rather than being cut mid-letter.
        Text(
            label, fontSize = 11.5.sp, color = if (selected) Palette.Select else Palette.Muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** The widget's own text (the time, or today's date) drawn in one style, in the widget's colour. */
@Composable
private fun Preview(kind: StyleKind, settings: Settings, id: String) {
    val minute by LocalFeeds.current.minute.collectAsStateWithLifecycle()
    val now = TimeText.fresh(minute)
    val colour = Color(settings[Common.COLOUR])
    when (kind) {
        StyleKind.Clock -> {
            val arabic = Common.arabicDigits(settings)
            ClockFace(id, TimeText.parts(now, settings[ClockWidget.H24], settings[ClockWidget.SECONDS], arabic), colour, arabic)
        }
        StyleKind.Date -> DateFace(settings, id, now.toLocalDate(), Modifier.fillMaxSize())
        StyleKind.Weather -> {
            // The widget's own layout with the owner's current settings, on today's weather (or a sample before there is any).
            val state by LocalFeeds.current.weather.collectAsStateWithLifecycle()
            val (name, weather) = when (val s = state) {
                is WeatherState.Ready -> s.city.name to s.weather
                is WeatherState.Failed -> s.city.name to (s.last ?: WeatherWidget.SAMPLE)
                else -> "Cairo" to WeatherWidget.SAMPLE
            }
            CompositionLocalProvider(LocalTextStyle provides WidgetTextStyle) {
                WeatherWidget.WeatherBody(settings.with(WeatherWidget.LAYOUT, id), name, weather)
            }
        }
    }
}

/**
 * "More fonts": the Google Fonts library, most popular first, 20 more each time the end comes into
 * view. Each preview downloads only the characters it shows; picking one downloads the whole font
 * once and adds it to the strip.
 */
@Composable
fun MoreFontsSheet(kind: StyleKind, state: EditorState, graph: AppGraph, modifier: Modifier) {
    val item = state.selected ?: return
    val settings = state.settingsOf(item)
    val arabic = settings[Common.ARABIC]
    var catalog by remember { mutableStateOf<List<CatalogFont>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<FontCategory?>(null) }
    var shown by remember { mutableIntStateOf(PAGE) }
    var downloading by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()

    LaunchedEffect(attempt) {
        failed = false
        runCatching { graph.fontLibrary.catalog() }.onSuccess { catalog = it }.onFailure { failed = true }
    }
    val fonts = catalog.orEmpty().filter { font ->
        (if (arabic) font.arabic else font.latin) &&
            (category == null || font.category == category) &&
            (query.isBlank() || font.family.contains(query.trim(), ignoreCase = true))
    }
    LaunchedEffect(grid, fonts.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= shown - 4 && shown < fonts.size) shown += PAGE
        }
    }

    Column(modifier.background(Palette.Page).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (arabic) "More Arabic fonts" else "More fonts", fontSize = 17.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.moreFonts = null }) { Text("Cancel", color = Palette.Select, fontSize = 15.sp) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it; shown = PAGE }, singleLine = true,
            placeholder = { Text("Search by name") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        // Scrolls sideways, so six buttons never run off a narrow screen or a large font.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            (listOf<FontCategory?>(null) + FontCategory.entries).forEach { c ->
                TextButton(onClick = { category = c; shown = PAGE }) {
                    Text(c?.label ?: "All", fontSize = 13.sp, color = if (category == c) Palette.Ink else Palette.Muted)
                }
            }
        }
        Rule()
        when {
            failed && catalog == null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Couldn't reach Google Fonts.", fontSize = 15.sp)
                Text("Check the connection and try again. Your fonts already on the phone are still in the strip.", fontSize = 13.sp, color = Palette.Muted)
                TextButton(onClick = { attempt++ }) { Text("Try again", color = Palette.Select) }
            }
            catalog == null -> Text("Loading fonts…", color = Palette.Muted, modifier = Modifier.padding(20.dp))
            fonts.isEmpty() -> Text("No fonts match.", color = Palette.Muted, modifier = Modifier.padding(20.dp))
            else -> LazyVerticalGrid(GridCells.Adaptive(180.dp), state = grid, modifier = Modifier.fillMaxSize()) {
                items(fonts.take(shown), key = { it.family }) { font ->
                    FontCell(font, kind, settings, graph, busy = downloading == font.family) {
                        if (downloading != null) return@FontCell
                        downloading = font.family
                        scope.launch {
                            val weight = GoogleFonts.chooseWeight(font.weights, targetWeight(kind))
                            runCatching { graph.fontLibrary.pick(font, weight) }
                                .onSuccess { picked ->
                                    state.set(keyOf(kind), picked.id)
                                    state.moreFonts = null
                                }
                                .onFailure { state.toast = Toast("Couldn't download ${font.family}. Check the connection.") }
                            downloading = null
                        }
                    }
                }
            }
        }
    }
}

private const val PAGE = 20

private fun targetWeight(kind: StyleKind) = if (kind == StyleKind.Clock) 500 else 400

/** The characters a preview needs: every digit (so the clock can change without a new download), or today's date. */
private fun previewText(kind: StyleKind, settings: Settings, text: String): String =
    if (kind == StyleKind.Clock) {
        if (Common.arabicDigits(settings)) "٠١٢٣٤٥٦٧٨٩:" else "0123456789:"
    } else text.toSet().joinToString("")

@Composable
private fun FontCell(font: CatalogFont, kind: StyleKind, settings: Settings, graph: AppGraph, busy: Boolean, onPick: () -> Unit) {
    val minute by LocalFeeds.current.minute.collectAsStateWithLifecycle()
    val now = TimeText.fresh(minute)
    val arabicDigits = Common.arabicDigits(settings)
    val shownText = when (kind) {
        StyleKind.Clock -> TimeText.parts(now, settings[ClockWidget.H24], false, arabicDigits).text
        StyleKind.Date -> TimeText.date(now.toLocalDate(), settings[DateWidget.FORMAT], settings[Common.ARABIC], arabicDigits)
        StyleKind.Weather -> ""
    }
    val needs = previewText(kind, settings, shownText)
    var typeface by remember(font.family) { mutableStateOf<Typeface?>(null) }
    var failed by remember(font.family) { mutableStateOf(false) }
    LaunchedEffect(font.family, needs) {
        val weight = GoogleFonts.chooseWeight(font.weights, targetWeight(kind))
        runCatching { graph.fontLibrary.preview(font.family, weight, needs) }
            .onSuccess { typeface = graph.fonts.preview(it) }
            .onFailure { failed = true }
    }
    Column(
        Modifier.clickable(enabled = !busy, onClick = onPick).padding(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(64.dp).background(Color.Black).padding(6.dp), contentAlignment = Alignment.Center) {
            val face = typeface
            when {
                busy -> Text("Downloading…", fontSize = 12.sp, color = Palette.Muted)
                face != null -> FitText(
                    shownText, face, Color(settings[Common.COLOUR]), Modifier.fillMaxSize(),
                    sample = if (kind == StyleKind.Clock) needs else shownText, stableDigits = kind == StyleKind.Clock && !arabicDigits,
                )
                failed -> Text("Preview unavailable", fontSize = 12.sp, color = Palette.Muted)
                else -> Text("…", color = Palette.Muted)
            }
        }
        Text(font.family, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(font.category.label, fontSize = 12.sp, color = Palette.Muted)
    }
}
