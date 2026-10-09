package com.ikverse.deskglow.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The app's type scale: five sizes, so every screen reads as one. */
object Type {
    val Title = 24.sp
    val Heading = 17.sp
    val Body = 15.sp
    val Small = 13.sp
    val Label = 12.sp
}

/** A strong ease-out: starts fast, so a press or a panel is already moving the moment it is asked for. */
val EaseOutStrong = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * A tap target that shrinks a little under the finger, so the press is felt before it is let go.
 * Android's "Remove animations" setting makes the shrink instant (the animation clock stops).
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, tween(100, easing = EaseOutStrong), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
}

/**
 * A soft halo round whatever it is put on: stacked, fading rounded rectangles, since a real blur is not
 * available on older phones. Used only for the active thing, so it keeps meaning something.
 */
fun Modifier.glow(radius: Dp = 14.dp, corner: Dp = 16.dp, colour: Color = Palette.Select): Modifier = drawBehind {
    val steps = 6
    for (i in steps downTo 1) {
        val grow = radius.toPx() * i / steps
        drawRoundRect(
            colour.copy(alpha = 0.05f),
            topLeft = Offset(-grow, -grow),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(corner.toPx() + grow),
        )
    }
}

/** A 44 dp icon button. [description] is what a screen reader says; the icon itself has no name. */
@Composable
fun IconAction(
    glyph: Glyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.Ink,
    enabled: Boolean = true,
    size: Dp = 22.dp,
) {
    Box(
        modifier.size(44.dp).pressable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, tint = if (enabled) tint else Palette.Muted.copy(alpha = 0.35f), size = size)
    }
}

enum class ButtonKind { Primary, Secondary, Danger }

/** A real button: amber and glowing for the one main action, outlined for the rest, red for deleting. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    glyph: Glyph? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    val (fill, ink, edge) = when (kind) {
        ButtonKind.Primary -> Triple(Palette.Select, Palette.OnAccent, Palette.Select)
        ButtonKind.Secondary -> Triple(Palette.Raised, Palette.Ink, Palette.Edge)
        ButtonKind.Danger -> Triple(Palette.Raised, Palette.Danger, Palette.Danger.copy(alpha = 0.35f))
    }
    Row(
        modifier
            .then(if (kind == ButtonKind.Primary && enabled) Modifier.glow(corner = 14.dp) else Modifier)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (enabled) fill else Palette.Raised)
            .border(1.dp, if (enabled) edge else Palette.Rule, shape)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, tint = if (enabled) ink else Palette.Muted, size = 18.dp, weight = 2f)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) ink else Palette.Muted, fontSize = Type.Body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small rounded pill: a status that opens its setting, or a category filter ([selected] fills it amber). */
@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, dot: Boolean = false) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) Palette.Select else Palette.Sheet)
            .border(1.dp, if (selected) Palette.Select else Palette.Rule, shape)
            .pressable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Palette.Select))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = if (selected) Palette.OnAccent else Palette.Ink, fontSize = Type.Small, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
    }
}

/** A group of rows on a raised, outlined card. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Palette.Sheet).border(1.dp, Palette.Rule, shape), content = content)
}

/** One row of a [Card]: an icon, a title, what it is set to, and a chevron. [last] leaves off the divider. */
@Composable
fun CardRow(glyph: Glyph, title: String, detail: String?, onClick: () -> Unit, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().pressable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, tint = Palette.Muted, size = 20.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, fontSize = Type.Body)
                if (!detail.isNullOrBlank()) Text(detail, fontSize = Type.Small, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            GlyphIcon(Glyph.ChevronRight, tint = Palette.Muted.copy(alpha = 0.7f), size = 18.dp)
        }
        if (!last) Rule(Modifier.padding(start = 50.dp))
    }
}

/** A small uppercase label that opens a group of settings. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = Palette.Muted, fontSize = Type.Label, fontWeight = FontWeight.Medium,
        letterSpacing = 0.12.em, modifier = modifier,
    )
}

/**
 * A short choice shown whole, so nothing is hidden behind a tap: the options side by side, the picked one raised.
 * [options] are (value, label) pairs.
 */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.clip(shape).background(Palette.Page).border(1.dp, Palette.Rule, shape).padding(3.dp)) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Palette.Edge else Color.Transparent)
                    .pressable(role = Role.RadioButton, onClick = { onSelect(value) })
                    .semantics { this.selected = on }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = Type.Small, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) Palette.Ink else Palette.Muted, maxLines = 1)
            }
        }
    }
}

/** A plain screen with a back arrow and a title; what follows scrolls. */
@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Glyph.Back, "Back", onBack)
        }
        Text(title, fontSize = Type.Title, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}
