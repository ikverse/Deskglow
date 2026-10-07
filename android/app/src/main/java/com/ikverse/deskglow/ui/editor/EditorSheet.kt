package com.ikverse.deskglow.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.ui.Palette
import com.ikverse.deskglow.ui.Rule
import com.ikverse.deskglow.widgets.ChoiceField
import com.ikverse.deskglow.widgets.ColourField
import com.ikverse.deskglow.widgets.Field
import com.ikverse.deskglow.widgets.SliderField
import com.ikverse.deskglow.widgets.StyleField
import com.ikverse.deskglow.widgets.ToggleField
import kotlin.math.roundToInt

/**
 * The sheet along the bottom: the widget list (show, hide, delete, add) and the selected widget's
 * settings. It tucks away while a widget is being dragged, so nothing near the bottom is ever hidden
 * under it, and folds down to its tabs with the chevron.
 */
@Composable
fun EditorSheet(state: EditorState, graph: AppGraph, modifier: Modifier) {
    AnimatedVisibility(
        visible = state.dragId == null,
        modifier = modifier,
        enter = slideInVertically { it / 3 } + fadeIn(),
        // Gone at once when a drag begins: the stage needs every frame, and the sheet is the heaviest thing on screen.
        exit = ExitTransition.None,
    ) {
        BoxWithConstraints {
            val openHeight = maxHeight * 0.44f
            Column(
                Modifier.fillMaxWidth().testTag("sheet").background(Palette.Sheet).navigationBarsPadding()
                    .then(if (state.sheetOpen) Modifier.height(openHeight) else Modifier),
            ) {
                SheetContent(state, graph, foldable = true)
            }
        }
    }
}

/**
 * The same sheet as a panel down the side, for landscape. The canvas sits beside it, so nothing is
 * hidden under it: it never tucks away while a widget is dragged, and it never folds.
 */
@Composable
fun EditorSidePanel(state: EditorState, graph: AppGraph, modifier: Modifier) {
    Column(modifier.testTag("sheet").background(Palette.Sheet)) {
        SheetContent(state, graph, foldable = false)
    }
}

/** The tabs and what is under them: the same for the bottom sheet and the side panel. */
@Composable
private fun ColumnScope.SheetContent(state: EditorState, graph: AppGraph, foldable: Boolean) {
    Rule()
    Tabs(state, foldable)
    Rule()
    if (state.sheetOpen || !foldable) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            val selected = state.selected
            if (state.tab == SheetTab.Settings && selected != null) SettingsTab(state, graph) else WidgetsTab(state)
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun Tabs(state: EditorState, foldable: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Tab("Widgets", state.tab == SheetTab.Widgets) { state.tab = SheetTab.Widgets; state.sheetOpen = true }
        Tab("Settings", state.tab == SheetTab.Settings && state.selected != null, enabled = state.selected != null) {
            state.tab = SheetTab.Settings
            state.sheetOpen = true
        }
        Spacer(Modifier.weight(1f))
        if (foldable) {
            Box(
                Modifier.size(44.dp)
                    .clickable(role = Role.Button) { state.sheetOpen = !state.sheetOpen }
                    .semantics { contentDescription = if (state.sheetOpen) "Fold settings" else "Unfold settings" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(14.dp).rotate(if (state.sheetOpen) 0f else 180f)) {
                    val w = 2.dp.toPx()
                    drawLine(Palette.Muted, Offset(0f, size.height * 0.3f), Offset(size.width / 2, size.height * 0.75f), w, StrokeCap.Round)
                    drawLine(Palette.Muted, Offset(size.width / 2, size.height * 0.75f), Offset(size.width, size.height * 0.3f), w, StrokeCap.Round)
                }
            }
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
            text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = when { on -> Palette.Ink; enabled -> Palette.Muted; else -> Palette.Muted.copy(alpha = 0.35f) },
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Box(Modifier.width(56.dp).height(2.dp).background(if (on) Palette.Select else Color.Transparent))
    }
}

@Composable
private fun WidgetsTab(state: EditorState) {
    state.layout.items.forEach { item ->
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = item.visible,
                onCheckedChange = { state.setVisible(item.id, it) },
                modifier = Modifier.semantics { contentDescription = "Show ${state.titleOf(item)}" },
            )
            Text(
                state.titleOf(item), fontSize = 15.sp,
                color = when { item.id == state.selectedId -> Palette.Select; item.visible -> Palette.Ink; else -> Palette.Muted },
                modifier = Modifier.weight(1f).clickable(enabled = item.visible) { state.select(item.id) }.padding(horizontal = 12.dp, vertical = 14.dp),
            )
            TextButton(
                onClick = { state.delete(item.id) },
                modifier = Modifier.semantics { contentDescription = "Delete ${state.titleOf(item)}" },
            ) { Text("×", fontSize = 20.sp, color = Palette.Muted) }
        }
        Rule()
    }
    TextButton(onClick = { state.pickerOpen = true }) { Text("+ Add widget", color = Palette.Select, fontSize = 15.sp) }
}

@Composable
private fun SettingsTab(state: EditorState, graph: AppGraph) {
    val item = state.selected ?: return
    val type = state.typeOf(item) ?: return
    val settings = state.settingsOf(item)
    Text(type.title(settings), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 14.dp))
    Rule()
    type.fields(settings).forEach { field ->
        FieldRow(field, settings, state, graph)
        Rule()
    }
    type.note(settings)?.let { Text(it, fontSize = 12.5.sp, color = Palette.Muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { state.setVisible(item.id, false) }) { Text("Hide", color = Palette.Select, fontSize = 15.sp) }
        TextButton(onClick = { state.delete(item.id) }) { Text("Delete", color = Palette.Danger, fontSize = 15.sp) }
    }
}

@Composable
private fun FieldRow(field: Field, settings: Settings, state: EditorState, graph: AppGraph) {
    when (field) {
        is ToggleField -> {
            // The whole row is the switch, so a screen reader hears the label with it ("Show city, switch, on").
            val on = settings[field.key]
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value = on, role = Role.Switch) { state.set(field.key, it) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(field.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = null)
            }
        }
        is ChoiceField -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            var open by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { open = true }) {
                    Text((field.options.firstOrNull { it.first == settings[field.key] }?.second ?: settings[field.key]) + "  ▾", fontSize = 15.sp, color = Palette.Ink)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    field.options.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            open = false
                            state.set(field.key, value)
                        })
                    }
                }
            }
        }
        is SliderField -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Slider(
                value = settings[field.key].toFloat(),
                onValueChange = { state.set(field.key, it.roundToInt().coerceIn(field.range)) },
                valueRange = field.range.first.toFloat()..field.range.last.toFloat(),
                modifier = Modifier.width(150.dp),
            )
            Text("${settings[field.key]}${field.suffix}", fontSize = 13.sp, color = Palette.Muted, maxLines = 1, modifier = Modifier.widthIn(min = 46.dp).padding(start = 8.dp))
        }
        is ColourField -> ColourRow(field, settings, state)
        is StyleField -> StyleStrip(field, settings, state, graph)
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
        Text(field.label, fontSize = 15.sp)
        // Each swatch is 28 dp to look at and 48 dp to touch. They wrap onto a second line where the row is narrow.
        FlowRow(Modifier.padding(top = 4.dp)) {
            SWATCHES.forEachIndexed { index, colour ->
                SwatchButton(SWATCH_NAMES[index], selected = colour == current, onClick = { state.set(field.key, colour) }) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .border(if (colour == current) 2.dp else 1.dp, if (colour == current) Palette.Select else Palette.EdgeStrong, CircleShape)
                            .padding(3.dp).clip(CircleShape).background(Color(colour)),
                    )
                }
            }
            val isCustom = current !in SWATCHES
            SwatchButton("Custom colour", selected = isCustom, onClick = { custom = true }) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .border(if (isCustom) 2.dp else 1.dp, if (isCustom) Palette.Select else Palette.EdgeStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCustom) Box(Modifier.size(20.dp).clip(CircleShape).background(Color(current))) else Text("+", color = Palette.Muted, fontSize = 16.sp)
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
                Text("Hue", fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(top = 12.dp))
                Slider(hue, { hue = it }, valueRange = 0f..360f)
                Text("Strength", fontSize = 13.sp, color = Palette.Muted)
                Slider(saturation, { saturation = it })
                Text("Brightness", fontSize = 13.sp, color = Palette.Muted)
                Slider(value, { value = it }, valueRange = 0.2f..1f)
            }
        },
        containerColor = Palette.Sheet,
    )
}
