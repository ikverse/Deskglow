package com.ikverse.deskglow.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation as DragAxis
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.display.WidgetBody
import com.ikverse.deskglow.layout.other
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.ui.AppButton
import com.ikverse.deskglow.ui.ButtonKind
import com.ikverse.deskglow.ui.Card
import com.ikverse.deskglow.ui.Chip
import com.ikverse.deskglow.ui.Glyph
import com.ikverse.deskglow.ui.GlyphIcon
import com.ikverse.deskglow.ui.IconAction
import com.ikverse.deskglow.ui.Palette
import com.ikverse.deskglow.ui.Rule
import com.ikverse.deskglow.ui.SectionLabel
import com.ikverse.deskglow.ui.Segmented
import com.ikverse.deskglow.ui.Type
import com.ikverse.deskglow.ui.pressable
import com.ikverse.deskglow.widgets.ChoiceField
import com.ikverse.deskglow.widgets.ColourField
import com.ikverse.deskglow.widgets.Field
import com.ikverse.deskglow.widgets.LayoutField
import com.ikverse.deskglow.widgets.ShowField
import com.ikverse.deskglow.widgets.SliderField
import com.ikverse.deskglow.widgets.StyleField
import com.ikverse.deskglow.widgets.ToggleField
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far a flick carries the sheet: the release speed (px/s) times this is where it would come to rest. */
private const val FLICK_REACH = 0.099f

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/**
 * The sheet along the bottom: the widget list and the selected widget's settings. It rests at one of
 * three heights (just its tabs, half the screen, nearly all of it). Drag it, or flick it and it lands
 * on the nearest stop in the direction thrown; a tap on the chevron folds or unfolds it. It sits below
 * the canvas, never over it: the canvas takes whatever the sheet leaves.
 */
@Composable
fun EditorSheet(state: EditorState, graph: AppGraph, screenHeight: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val inset = WindowInsets.navigationBars.getBottom(density)
    val screenPx = with(density) { screenHeight.toPx() }
    val peek = with(density) { 60.dp.toPx() } + inset
    val half = screenPx * 0.44f
    val full = (screenPx - with(density) { 200.dp.toPx() }).coerceAtLeast(half)
    fun heightOf(stop: SheetStop) = when (stop) { SheetStop.Peek -> peek; SheetStop.Half -> half; SheetStop.Full -> full }

    val height = remember { Animatable(heightOf(state.sheetStop)) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Springs, not timed tweens: a grab mid-flight carries on from where the sheet is, at the speed it has.
    val settle = spring<Float>(dampingRatio = 0.85f, stiffness = 400f)
    LaunchedEffect(state.sheetStop, peek, half, full) {
        if (!dragging) height.animateTo(heightOf(state.sheetStop), settle)
    }
    val dragState = rememberDraggableState { delta -> scope.launch { height.snapTo((height.value - delta).coerceIn(peek, full)) } }

    Column(
        modifier.fillMaxWidth().height(with(density) { height.value.toDp() }).testTag("sheet")
            .clip(SheetShape).background(Palette.Sheet).border(1.dp, Palette.Rule, SheetShape)
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier.draggable(
                dragState, DragAxis.Vertical,
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    dragging = false
                    val rest = height.value - velocity * FLICK_REACH
                    val stop = SheetStop.entries.minBy { abs(heightOf(it) - rest) }
                    state.sheetStop = stop
                    scope.launch { height.animateTo(heightOf(stop), settle, initialVelocity = -velocity) }
                },
            ),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Edge))
            }
            Tabs(state, foldable = true)
        }
        // The contents wait until the sheet is more than its tabs, so a folded sheet costs nothing.
        if (height.value > peek + 24) {
            SheetBody(state, graph, showPreview = state.sheetStop == SheetStop.Full, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The same sheet as a panel down the side, for landscape. The canvas sits beside it, so nothing is
 * hidden under it: it never tucks away and never folds.
 */
@Composable
fun EditorSidePanel(state: EditorState, graph: AppGraph, modifier: Modifier) {
    Column(modifier.testTag("sheet").background(Palette.Sheet)) {
        Tabs(state, foldable = false)
        SheetBody(state, graph, showPreview = false, modifier = Modifier.weight(1f))
    }
}

/** What is under the tabs: the same for the bottom sheet and the side panel. */
@Composable
private fun SheetBody(state: EditorState, graph: AppGraph, showPreview: Boolean, modifier: Modifier) {
    Rule()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        val selected = state.selected
        if (state.tab == SheetTab.Settings && selected != null) {
            if (showPreview) PreviewHeader(state, selected)
            SettingsTab(state, graph)
        } else WidgetsTab(state)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Tabs(state: EditorState, foldable: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Tab("Widgets", state.tab == SheetTab.Widgets || state.selected == null) {
            state.tab = SheetTab.Widgets
            if (foldable && state.sheetStop == SheetStop.Peek) state.sheetStop = SheetStop.Half
        }
        Tab("Settings", state.tab == SheetTab.Settings && state.selected != null, enabled = state.selected != null) {
            state.tab = SheetTab.Settings
            if (foldable && state.sheetStop == SheetStop.Peek) state.sheetStop = SheetStop.Half
        }
        Spacer(Modifier.weight(1f))
        if (foldable) {
            val open = state.sheetStop != SheetStop.Peek
            IconAction(
                if (open) Glyph.ChevronDown else Glyph.ChevronUp, if (open) "Fold settings" else "Unfold settings",
                { state.sheetStop = if (open) SheetStop.Peek else SheetStop.Half },
                Modifier.testTag("fold"), tint = Palette.Muted, size = 20.dp,
            )
        }
    }
}

@Composable
private fun Tab(text: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.selectable(selected = on, enabled = enabled, role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text, fontSize = Type.Body, fontWeight = FontWeight.Medium,
            color = when { on -> Palette.Ink; enabled -> Palette.Muted; else -> Palette.Muted.copy(alpha = 0.35f) },
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Box(Modifier.width(48.dp).height(2.dp).background(if (on) Palette.Select else Color.Transparent))
    }
}

/** A small live drawing of a widget, in the proportions of its box, fitted into whatever room it is given. */
@Composable
private fun FitWidget(item: WidgetItem, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = item.box.w.toFloat() / item.box.h
        val wide = maxWidth / maxHeight > aspect
        val h = if (wide) maxHeight else maxWidth / aspect
        val w = if (wide) maxHeight * aspect else maxWidth
        WidgetBody(item, Modifier.size(w, h))
    }
}

/** At full height the canvas is mostly covered, so the selected widget is drawn here as well. */
@Composable
private fun PreviewHeader(state: EditorState, item: WidgetItem) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).height(110.dp).clip(RoundedCornerShape(14.dp))
            .background(Color.Black).border(1.dp, Palette.Rule, RoundedCornerShape(14.dp)).padding(10.dp),
    ) { FitWidget(state.layout.find(item.id) ?: item, Modifier.fillMaxWidth().height(90.dp)) }
}

@Composable
private fun WidgetsTab(state: EditorState) {
    Spacer(Modifier.height(4.dp))
    state.layout.items.forEach { item ->
        val title = state.titleOf(item)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                    .border(1.dp, Palette.Rule, RoundedCornerShape(8.dp)).padding(3.dp),
                contentAlignment = Alignment.Center,
            ) { FitWidget(item, Modifier.fillMaxWidth().height(34.dp)) }
            Text(
                title, fontSize = Type.Body,
                color = when { item.id == state.selectedId -> Palette.Select; item.visible -> Palette.Ink; else -> Palette.Muted },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(enabled = item.visible) { state.selectAndOpen(item.id) }.padding(horizontal = 12.dp, vertical = 14.dp),
            )
            IconAction(
                if (item.visible) Glyph.Eye else Glyph.EyeOff, (if (item.visible) "Hide " else "Show ") + title,
                { state.setVisible(item.id, !item.visible) }, tint = if (item.visible) Palette.Ink else Palette.Muted,
            )
            IconAction(Glyph.Trash, "Delete $title", { state.delete(item.id) }, tint = Palette.Muted)
        }
        Rule()
    }
    Spacer(Modifier.height(14.dp))
    AppButton("Add widget", { state.pickerOpen = true }, Modifier.fillMaxWidth(), glyph = Glyph.Plus)
}

/** Asks before the other orientation's layout is replaced. */
@Composable
fun CopyDialog(state: EditorState) {
    val target = state.orientation.other.name.lowercase()
    AlertDialog(
        onDismissRequest = { state.copyConfirm = false },
        confirmButton = { TextButton(onClick = { state.copyToOther() }) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { state.copyConfirm = false }) { Text("Cancel") } },
        title = { Text("Copy to $target?") },
        text = { Text("Your $target layout is replaced by this one, rearranged to fit. You can undo it right after.") },
        containerColor = Palette.Sheet,
    )
}

/** Where a setting sits in the settings list. */
private enum class Section(val title: String) { Layout("Layout"), Content("Content"), Look("Look") }

private fun sectionOf(field: Field): Section = when (field) {
    is StyleField, is LayoutField -> Section.Layout
    is ColourField -> Section.Look
    is SliderField -> if (field.label in LOOK_LABELS) Section.Look else Section.Content
    is ChoiceField -> if (field.label in LOOK_LABELS) Section.Look else Section.Content
    is ToggleField, is ShowField -> Section.Content
}

private val LOOK_LABELS = setOf("Brightness", "Ring thickness", "Temperature size", "Alignment", "Icon style")

@Composable
private fun SettingsTab(state: EditorState, graph: AppGraph) {
    val item = state.selected ?: return
    val type = state.typeOf(item) ?: return
    val settings = state.settingsOf(item)
    Text(type.title(settings), fontSize = Type.Heading, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
    val groups = type.fields(settings).groupBy(::sectionOf)
    for (section in Section.entries) {
        val fields = groups[section] ?: continue
        SectionLabel(section.title, Modifier.padding(top = 18.dp, bottom = 6.dp))
        // Strips of pictures stand on their own; plain controls share one card.
        val (pictures, controls) = fields.partition { it is StyleField || it is LayoutField }
        for (field in pictures) FieldRow(field, item, settings, state, graph)
        if (controls.isNotEmpty()) {
            Card {
                controls.forEachIndexed { i, field ->
                    Column(Modifier.padding(horizontal = 14.dp)) { FieldRow(field, item, settings, state, graph) }
                    if (i < controls.lastIndex) Rule()
                }
            }
        }
    }
    type.note(settings)?.let { Text(it, fontSize = 12.5.sp, color = Palette.Muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 16.dp)) }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppButton("Hide widget", { state.setVisible(item.id, false) }, Modifier.weight(1f), glyph = Glyph.EyeOff)
        AppButton("Delete widget", { state.delete(item.id) }, Modifier.weight(1f), kind = ButtonKind.Danger, glyph = Glyph.Trash)
    }
}

/** Short choices are shown whole as a segmented control; long lists stay a menu. */
private const val SEGMENTED_MAX_OPTIONS = 4
private const val SEGMENTED_MAX_CHARS = 34

@Composable
private fun FieldRow(field: Field, item: WidgetItem, settings: Settings, state: EditorState, graph: AppGraph) {
    when (field) {
        is ToggleField -> {
            // The whole row is the switch, so a screen reader hears the label with it ("Show city, switch, on").
            val on = settings[field.key]
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).toggleable(value = on, role = Role.Switch) { state.set(field.key, it) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(field.label, fontSize = Type.Body, modifier = Modifier.weight(1f).padding(end = 12.dp))
                Switch(checked = on, onCheckedChange = null)
            }
        }
        is ShowField -> ShowRow(field, settings, state)
        is ChoiceField -> {
            val current = settings[field.key]
            if (field.options.size <= SEGMENTED_MAX_OPTIONS && field.options.sumOf { it.second.length } <= SEGMENTED_MAX_CHARS) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(field.label, fontSize = Type.Body)
                    Segmented(field.options, current, { state.set(field.key, it) }, Modifier.fillMaxWidth().testTag("segmented ${field.label}"))
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, fontSize = Type.Body, modifier = Modifier.weight(1f))
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).pressable { open = true }.padding(start = 8.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(field.options.firstOrNull { it.first == current }?.second ?: current, fontSize = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
                            Spacer(Modifier.width(4.dp))
                            GlyphIcon(Glyph.ChevronDown, tint = Palette.Muted, size = 16.dp, weight = 2.2f)
                        }
                        DropdownMenu(open, { open = false }, containerColor = Palette.Raised) {
                            field.options.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = if (value == current) Palette.Select else Palette.Ink) },
                                    onClick = { open = false; state.set(field.key, value) },
                                )
                            }
                        }
                    }
                }
            }
        }
        is LayoutField -> LayoutChoice(field, item, settings, state)
        is SliderField -> Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, fontSize = Type.Body, modifier = Modifier.weight(1f))
                Text("${settings[field.key]}${field.suffix}", fontSize = Type.Small, color = Palette.Muted, maxLines = 1)
            }
            Slider(
                value = settings[field.key].toFloat(),
                onValueChange = { state.set(field.key, it.roundToInt().coerceIn(field.range)) },
                valueRange = field.range.first.toFloat()..field.range.last.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is ColourField -> ColourRow(field, settings, state)
        is StyleField -> StyleStrip(field, settings, state, graph)
    }
}

/** Several on/off settings as chips side by side, each lit while its setting is on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowRow(field: ShowField, settings: Settings, state: EditorState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(field.label, fontSize = Type.Body)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in field.items) {
                val on = settings[key]
                Chip(label, { state.set(key, !on) }, selected = on)
            }
        }
    }
}

/**
 * A layout picked by how it looks: the widget itself drawn once per option, in the box it is in now,
 * with its other settings as they are. Tapping a tile applies it.
 */
@Composable
private fun LayoutChoice(field: LayoutField, item: WidgetItem, settings: Settings, state: EditorState) {
    val current = settings[field.key]
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        // A section already called "Layout" does not need the field to say so again.
        if (!field.label.equals(Section.Layout.title, ignoreCase = true)) {
            Row {
                Text(field.label, fontSize = Type.Body)
                Text(field.options.firstOrNull { it.first == current }?.second.orEmpty(), fontSize = Type.Body, color = Palette.Muted, modifier = Modifier.padding(start = 10.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp).testTag("layouts"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((value, label) in field.options) {
                val shown = item.copy(settings = settings.with(field.key, value))
                Tile(label, selected = value == current, enabled = true, onClick = { state.set(field.key, value) }, width = 132.dp, previewHeight = 74.dp) {
                    FitWidget(shown, Modifier.fillMaxWidth().height(66.dp))
                }
            }
        }
    }
}

private val SWATCHES = listOf(0xFFFFFFFF, 0xFF44B98A, 0xFF4FC3F7, 0xFFF5B942, 0xFFF2766B, 0xFFB48CF2, 0xFF9A9A9A).map { it.toInt() }

/** What a screen reader calls each of [SWATCHES], in the same order. */
private val SWATCH_NAMES = listOf("White", "Green", "Sky blue", "Amber", "Coral", "Violet", "Grey")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourRow(field: ColourField, settings: Settings, state: EditorState) {
    var custom by remember { mutableStateOf(false) }
    val current = settings[field.key]
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(field.label, fontSize = Type.Body)
        // Each swatch is 30 dp to look at and 48 dp to touch. They wrap onto a second line where the row is narrow.
        FlowRow(Modifier.padding(top = 4.dp)) {
            SWATCHES.forEachIndexed { index, colour ->
                SwatchButton(SWATCH_NAMES[index], selected = colour == current, onClick = { state.set(field.key, colour) }) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .border(if (colour == current) 2.dp else 1.dp, if (colour == current) Palette.Select else Palette.EdgeStrong, CircleShape)
                            .padding(3.dp).clip(CircleShape).background(Color(colour)),
                    )
                }
            }
            val isCustom = current !in SWATCHES
            SwatchButton("Custom colour", selected = isCustom, onClick = { custom = true }) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .border(if (isCustom) 2.dp else 1.dp, if (isCustom) Palette.Select else Palette.EdgeStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCustom) Box(Modifier.size(22.dp).clip(CircleShape).background(Color(current))) else GlyphIcon(Glyph.Plus, tint = Palette.Muted, size = 16.dp, weight = 2.2f)
                }
            }
        }
    }
    if (custom) ColourDialog(current, onDismiss = { custom = false }) {
        custom = false
        state.set(field.key, it)
    }
}

/** A 48 dp touch target around a swatch, named and marked selected for a screen reader. */
@Composable
private fun SwatchButton(name: String, selected: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Any colour, by hue, strength and brightness. */
@Composable
private fun ColourDialog(start: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.2f)) }
    val colour = Color.hsv(hue, saturation, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(colour.toArgb()) }) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Colour") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(colour))
                Text("Hue", fontSize = Type.Small, color = Palette.Muted, modifier = Modifier.padding(top = 12.dp))
                Slider(hue, { hue = it }, valueRange = 0f..360f)
                Text("Strength", fontSize = Type.Small, color = Palette.Muted)
                Slider(saturation, { saturation = it })
                Text("Brightness", fontSize = Type.Small, color = Palette.Muted)
                Slider(value, { value = it }, valueRange = 0.2f..1f)
            }
        },
        containerColor = Palette.Sheet,
    )
}
