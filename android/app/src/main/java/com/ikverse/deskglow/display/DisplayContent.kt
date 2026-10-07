package com.ikverse.deskglow.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.fonts.LocalFonts
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.Common
import com.ikverse.deskglow.widgets.Widgets
import java.time.LocalDateTime
import kotlin.math.min
import kotlin.math.roundToInt

/** Gives the widgets their data and fonts. Everything that draws widgets sits inside one of these. */
@Composable
fun WidgetHost(graph: AppGraph, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFeeds provides graph.feeds, LocalFonts provides graph.fonts, content = content)
}

/**
 * The layout drawn full screen: the canvas of [orientation] (412 x 848 upright, 848 x 412 on its
 * side) scaled to fit and centred on black. With [burnIn] on, the whole layout drifts a few pixels
 * each minute. Only the drawing layer moves, so the drift redraws nothing and recomposes nothing.
 */
@Composable
fun DisplayContent(layout: Layout, burnIn: Boolean, modifier: Modifier = Modifier, orientation: Orientation = Orientation.Portrait) {
    val minute = LocalFeeds.current.minute.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val unit = min(constraints.maxWidth / orientation.width.toFloat(), constraints.maxHeight / orientation.height.toFloat())
        val density = LocalDensity.current
        // The canvas is a drawing in fixed coordinates, not text: it is never mirrored for a right-to-left
        // phone, or every widget would swap sides and "left" would mean right.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                Modifier
                    .size(with(density) { (orientation.width * unit).toDp() }, with(density) { (orientation.height * unit).toDp() })
                    .graphicsLayer {
                        if (burnIn) {
                            val (dx, dy) = burnInShift(minute)
                            translationX = dx * density.density
                            translationY = dy * density.density
                        }
                    },
            ) {
                for (item in layout.items) {
                    if (!item.visible) continue
                    key(item.id) { WidgetSlot(item, unit) }
                }
            }
        }
    }
}

@Composable
fun WidgetSlot(item: WidgetItem, unit: Float) {
    val density = LocalDensity.current
    WidgetBody(
        item,
        Modifier
            .offset { IntOffset((item.box.x * unit).roundToInt(), (item.box.y * unit).roundToInt()) }
            .size(with(density) { (item.box.w * unit).toDp() }, with(density) { (item.box.h * unit).toDp() }),
    )
}

/** One widget drawn into [modifier]'s box, at its own brightness. */
@Composable
fun WidgetBody(item: WidgetItem, modifier: Modifier) {
    val type = Widgets.find(item.type) ?: return // a widget from a newer version: kept in the layout, not drawn
    // The same settings object for as long as the settings are equal, so an unchanged widget is skipped
    // while its neighbours are dragged about.
    val settings = remember(item.settings, type) { item.settings.withDefaults(type.defaults) }
    Box(modifier.graphicsLayer { alpha = settings[Common.OPACITY] / 100f }) {
        CompositionLocalProvider(LocalTextStyle provides WidgetTextStyle) { type.Content(settings) }
    }
}

/**
 * Text in widgets is sized to fill small boxes, so it is set tight: lines a tenth taller than the
 * text (the app's usual style spaces them for reading, and clipped the weather's last line), with
 * the spare space above the first line and below the last trimmed off.
 */
val WidgetTextStyle = TextStyle(
    lineHeight = 1.1.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Eight resting places on a small circle, one per minute: about 4 dp from the centre at most. */
private val SHIFTS = listOf(0f to 0f, 3f to -2f, 4f to 1f, 2f to 4f, -2f to 4f, -4f to 1f, -3f to -2f, 0f to -4f)

private fun burnInShift(minute: State<LocalDateTime>): Pair<Float, Float> {
    val t = minute.value
    return SHIFTS[((t.hour * 60 + t.minute) % SHIFTS.size)]
}
