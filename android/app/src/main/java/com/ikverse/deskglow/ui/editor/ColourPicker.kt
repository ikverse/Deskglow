package com.ikverse.deskglow.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.model.formatHexColour
import com.ikverse.deskglow.model.parseHexColour
import com.ikverse.deskglow.ui.Glyph
import com.ikverse.deskglow.ui.GlyphIcon
import com.ikverse.deskglow.ui.Palette
import com.ikverse.deskglow.ui.SectionLabel
import com.ikverse.deskglow.ui.Type
import com.ikverse.deskglow.ui.pressable
import com.ikverse.deskglow.widgets.ColourField

/** The quick colours in every colour row, and what a screen reader calls each. */
internal val SWATCHES = listOf(0xFFFFFFFF, 0xFF44B98A, 0xFF4FC3F7, 0xFFF5B942, 0xFFF2766B, 0xFFB48CF2, 0xFF9A9A9A).map { it.toInt() }
internal val SWATCH_NAMES = listOf("White", "Green", "Sky blue", "Amber", "Coral", "Violet", "Grey")

/** The colour pad never goes darker than this: the display is black, and a darker colour is lost on it. */
private const val LEAST_VALUE = 0.2f

/** A colour setting: seven quick swatches and a tile that opens the full picker, lit when the colour is one of its own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColourRow(field: ColourField, item: WidgetItem, settings: Settings, state: EditorState, graph: AppGraph) {
    var open by remember { mutableStateOf(false) }
    val current = settings[field.key]
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(field.label, fontSize = Type.Body)
        // Each swatch is 30 dp to look at and 48 dp to touch. They wrap onto a second line where the row is narrow.
        FlowRow(Modifier.padding(top = 4.dp)) {
            SWATCHES.forEachIndexed { index, colour ->
                SwatchButton(SWATCH_NAMES[index], selected = colour == current, onClick = { state.set(field.key, colour) }) {
                    Swatch(colour, selected = colour == current)
                }
            }
            val isCustom = current !in SWATCHES
            SwatchButton("Custom colour", selected = isCustom, onClick = { open = true }) {
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
    if (open) ColourPicker(field, item, settings, state, graph) { open = false }
}

@Composable
private fun Swatch(colour: Int, selected: Boolean, size: Int = 32) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Select else Palette.EdgeStrong, CircleShape)
            .padding(3.dp).clip(CircleShape).background(Color(colour)),
    )
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

/**
 * The full picker, over the editor. Every pick is applied to the widget at once, so the canvas and
 * the preview at the top show it on the real widget; Cancel puts back what the widget had. It offers
 * colours that go with the widget's other colour, pairs that set both at once, a hue strip and a
 * pad, the colour as text, and the last colours mixed here.
 */
@Composable
private fun ColourPicker(field: ColourField, item: WidgetItem, settings: Settings, state: EditorState, graph: AppGraph, onClose: () -> Unit) {
    val type = state.typeOf(item)
    // The widget's other colour, if it has one: the accent for the colour, the colour for the accent.
    val partner: ColourKey? = type?.fields(settings)?.filterIsInstance<ColourField>()?.firstOrNull { it.key != field.key }?.key
    val start = remember { settings[field.key] }
    val partnerStart = remember { partner?.let { settings[it] } }
    val live = state.layout.find(item.id) ?: item
    val liveSettings = state.settingsOf(live)
    val current = liveSettings[field.key]
    val partnerColour = partner?.let { liveSettings[it] }
    var hsv by remember { mutableStateOf(Hsv.of(start).let { it.copy(value = maxOf(it.value, LEAST_VALUE)) }) }
    // What is typed in the hex field, kept apart from the colour so a half-typed value is not snapped back.
    var hex by remember { mutableStateOf(formatHexColour(start)) }
    val recents by graph.prefs.recentColours.collectAsStateWithLifecycle()

    fun apply(argb: Int, fromPad: Boolean = false) {
        if (!fromPad) hsv = Hsv.of(argb).let { it.copy(value = maxOf(it.value, LEAST_VALUE)) }
        hex = formatHexColour(argb)
        state.set(field.key, argb)
    }

    fun cancel() {
        state.set(field.key, start)
        if (partner != null && partnerStart != null) state.set(partner, partnerStart)
        onClose()
    }

    fun done() {
        if (current !in SWATCHES) graph.prefs.addRecentColour(current)
        onClose()
    }

    Dialog(onDismissRequest = ::cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp).clip(shape).background(Palette.Sheet).border(1.dp, Palette.Rule, shape)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("colour picker"),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, fontSize = Type.Heading, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = ::cancel) { Text("Cancel", color = Palette.Muted, fontSize = Type.Body) }
                TextButton(onClick = ::done) { Text("Done", color = Palette.Select, fontSize = Type.Body, fontWeight = FontWeight.Medium) }
            }

            // The widget itself, as the colour changes under it.
            Box(
                Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black).border(1.dp, Palette.Rule, RoundedCornerShape(14.dp)).padding(10.dp),
            ) { FitWidget(live, Modifier.fillMaxWidth().height(76.dp)) }

            // Colours that sit well beside the widget's other colour, or, with no other, beside this one.
            val suggestions = ColourHarmony.suggestions(partnerColour ?: current)
            SectionLabel(
                when {
                    partner == null -> "Goes with it"
                    partner.name == "accent" -> "Goes with the accent"
                    else -> "Goes with the colour"
                },
                Modifier.padding(top = 18.dp, bottom = 8.dp),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("pairings"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s ->
                    PairTile(s.name, partnerColour ?: current, s.argb, selected = s.argb == current, description = "${s.name}: ${formatHexColour(s.argb)}") { apply(s.argb) }
                }
            }

            // Pairs chosen by hand that set the colour and the accent together; only where the widget has both.
            if (partner != null) {
                SectionLabel("Duos", Modifier.padding(top = 18.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("duos"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColourHarmony.DUOS.forEach { duo ->
                        val mine = if (field.key.name == "accent") duo.accent else duo.colour
                        val theirs = if (field.key.name == "accent") duo.colour else duo.accent
                        PairTile(duo.name, duo.colour, duo.accent, selected = mine == current && theirs == partnerColour, description = "Duo ${duo.name}") {
                            state.set(partner, theirs)
                            apply(mine)
                        }
                    }
                }
            }

            // Any colour: the hue along the strip, how strong and how bright on the pad.
            SectionLabel("Mix", Modifier.padding(top = 18.dp, bottom = 8.dp))
            SaturationValuePad(hsv, Modifier.fillMaxWidth().height(150.dp)) { s, v ->
                hsv = hsv.copy(saturation = s, value = v)
                apply(hsv.toArgb(), fromPad = true)
            }
            Spacer(Modifier.height(10.dp))
            HueStrip(hsv.hue, Modifier.fillMaxWidth().height(28.dp)) { h ->
                hsv = hsv.copy(hue = h)
                apply(hsv.toArgb(), fromPad = true)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(current, selected = false, size = 40)
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = hex,
                    onValueChange = { typed ->
                        hex = typed.take(7)
                        parseHexColour(hex.trim())?.takeIf { hex.trim().removePrefix("#").length == 6 }?.let { apply(it) }
                    },
                    singleLine = true,
                    label = { Text("Hex") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f).testTag("hex"),
                )
            }

            // The last colours mixed here, newest first.
            if (recents.isNotEmpty()) {
                SectionLabel("Recent", Modifier.padding(top = 18.dp, bottom = 2.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    recents.forEach { colour ->
                        SwatchButton("Recent ${formatHexColour(colour)}", selected = colour == current, onClick = { apply(colour) }) {
                            Swatch(colour, selected = colour == current)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Two colours as one tile, the first fading into the second, with a name beneath. */
@Composable
private fun PairTile(name: String, first: Int, second: Int, selected: Boolean, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(92.dp).clip(shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Palette.Select else Palette.Edge, shape)
            .pressable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(5.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp))
                    .background(Brush.horizontalGradient(0f to Color(first), 0.35f to Color(first), 0.65f to Color(second), 1f to Color(second))),
            )
        }
        Text(name, fontSize = 11.sp, letterSpacing = 0.sp, color = if (selected) Palette.Select else Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }
}

/** Every hue along a strip, with a ring where the current one is. Drag or tap to pick. */
@Composable
private fun HueStrip(hue: Float, modifier: Modifier, onHue: (Float) -> Unit) {
    val hues = remember { (0..12).map { Color(ColourHarmony.argbOf(it * 30f, 1f, 1f)) } }
    Canvas(
        modifier.clip(RoundedCornerShape(14.dp)).semantics { contentDescription = "Hue" }.testTag("hue")
            .pointerInput(Unit) {
                detectTapGestures { onHue((it.x / size.width).coerceIn(0f, 1f) * 360f) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> change.consume(); onHue((change.position.x / size.width).coerceIn(0f, 1f) * 360f) }
            },
    ) {
        drawRect(Brush.horizontalGradient(hues))
        val x = hue / 360f * size.width
        val r = size.height * 0.42f
        drawCircle(Color.Black, r + 2.dp.toPx(), Offset(x, size.height / 2))
        drawCircle(Color(ColourHarmony.argbOf(hue, 1f, 1f)), r, Offset(x, size.height / 2))
        drawCircle(Color.White, r, Offset(x, size.height / 2), style = Stroke(2.dp.toPx()))
    }
}

/** The current hue from white (left) to full strength (right) and from bright (top) to dim (bottom), with a ring at the current mix. */
@Composable
private fun SaturationValuePad(hsv: Hsv, modifier: Modifier, onPick: (saturation: Float, value: Float) -> Unit) {
    val pure = Color(ColourHarmony.argbOf(hsv.hue, 1f, 1f))
    fun pick(p: Offset, w: Float, h: Float) {
        val s = (p.x / w).coerceIn(0f, 1f)
        val v = 1f - (p.y / h).coerceIn(0f, 1f) * (1f - LEAST_VALUE)
        onPick(s, v)
    }
    Canvas(
        modifier.clip(RoundedCornerShape(14.dp)).semantics { contentDescription = "Strength and brightness" }.testTag("pad")
            .pointerInput(Unit) {
                detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> change.consume(); pick(change.position, size.width.toFloat(), size.height.toFloat()) }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
        // The bottom edge is the least brightness the pad allows, not black.
        val floor = Color.Black.copy(alpha = 1f - LEAST_VALUE)
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, floor)))
        val x = hsv.saturation * size.width
        val y = (1f - hsv.value) / (1f - LEAST_VALUE) * size.height
        val r = 10.dp.toPx()
        drawCircle(Color.Black, r + 2.dp.toPx(), Offset(x, y))
        drawCircle(Color(hsv.toArgb()), r, Offset(x, y))
        drawCircle(Color.White, r, Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}
