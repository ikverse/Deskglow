package com.ikverse.deskglow.ui.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.display.WidgetBody
import com.ikverse.deskglow.display.WidgetTextStyle
import com.ikverse.deskglow.layout.Align
import androidx.compose.material3.LocalTextStyle
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.ui.Palette
import com.ikverse.deskglow.ui.Rule
import com.ikverse.deskglow.widgets.LocalEditing
import com.ikverse.deskglow.widgets.Widgets
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The layout editor: the screen as it will look, with every widget movable (drag it), resizable
 * (drag its corner handle) and removable (its red ×). Widgets never overlap: whatever is in the way
 * is pushed down and slides back while the drag is still held.
 *
 * It edits the layout of one [orientation]. The portrait layout holds the screen upright while it is
 * open; the landscape layout can be edited with the phone either way. Held upright, the settings sheet
 * lies along the bottom; on its side, the settings are a panel beside the canvas.
 */
@Composable
fun EditorScreen(graph: AppGraph, orientation: Orientation = Orientation.Portrait, onDone: () -> Unit) {
    val repository = graph.layoutsFor(orientation)
    val state = remember(orientation) { EditorState(repository.layout.value, orientation, repository::update) }
    HoldOrientation(orientation)
    val view = LocalView.current
    state.haptic = { kind ->
        val constant = when (kind) {
            Haptic.Step -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.Centre -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }
    BackHandler {
        when {
            state.moreFonts != null -> state.moreFonts = null
            state.pickerOpen -> state.pickerOpen = false
            state.selecting -> state.selectMode(false)
            state.selectedId != null -> state.select(null)
            else -> onDone()
        }
    }
    CompositionLocalProvider(LocalEditing provides true) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            // The arrangement follows how the phone is actually held, not which layout is being edited.
            if (maxWidth > maxHeight) {
                LandscapeEditor(state, graph, onDone)
            } else {
                // The canvas sits between the top bar and the settings sheet; nothing lies over it.
                val sheetHeight = maxHeight * 0.44f
                Column(Modifier.fillMaxSize()) {
                    TopBar(state, onDone, Modifier)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Stage(state, Modifier.fillMaxSize())
                        if (state.moreFonts == null) state.toast?.let { ToastBar(state, it, Modifier.align(Alignment.BottomCenter)) }
                    }
                    EditorSheet(state, graph, openHeight = sheetHeight)
                }
            }
            if (state.pickerOpen) AddPicker(state, Modifier.fillMaxSize())
            state.moreFonts?.let { MoreFontsSheet(it, state, graph, Modifier.fillMaxSize()) }
            // The font list covers the canvas area, so a message from it (a font that could not be downloaded)
            // is drawn over the whole screen, last, rather than under it. That list has no toolbar to hide.
            if (state.moreFonts != null) state.toast?.let { ToastBar(state, it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/**
 * Landscape: the top bar across the screen, then the canvas with the settings panel beside it. The
 * canvas gets what is left of the width, so it is smaller than the real screen (about two thirds on
 * a Note 9); nothing sits on top of it.
 */
@Composable
private fun LandscapeEditor(state: EditorState, graph: AppGraph, onDone: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val panel = (maxWidth * 0.36f).coerceIn(240.dp, 320.dp)
        Column(Modifier.fillMaxSize()) {
            TopBar(state, onDone, Modifier)
            Row(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Stage(state, Modifier.fillMaxSize())
                    if (state.moreFonts == null) state.toast?.let { ToastBar(state, it, Modifier.align(Alignment.BottomCenter)) }
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(Palette.Rule))
                EditorSidePanel(state, graph, Modifier.fillMaxHeight().width(panel))
            }
        }
    }
}

/**
 * Keeps the screen upright while the portrait layout is edited, and lets it turn freely again on
 * leaving. The landscape layout is never held: it can be edited with the phone either way.
 */
@Composable
private fun HoldOrientation(orientation: Orientation) {
    val context = LocalContext.current
    DisposableEffect(orientation) {
        if (orientation == Orientation.Landscape) return@DisposableEffect onDispose { }
        val activity = context.findActivity()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            // Turning the phone recreates the activity, and the new one holds the screen again: only let go for real.
            if (activity?.isChangingConfigurations != true) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun Stage(state: EditorState, modifier: Modifier) {
    val canvas = state.orientation
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val unit = min(constraints.maxWidth / canvas.width.toFloat(), constraints.maxHeight / canvas.height.toFloat())
        val density = LocalDensity.current
        // The canvas is a drawing in fixed coordinates, not text: it is never mirrored for a right-to-left
        // language, or a widget would move opposite to the finger that drags it.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                Modifier
                    .size(with(density) { (canvas.width * unit).toDp() }, with(density) { (canvas.height * unit).toDp() })
                    .pointerInput(Unit) { detectTapGestures { state.select(null) } },
            ) {
                GridDots(unit)
                for (item in state.layout.items) {
                    if (!item.visible || Widgets.find(item.type) == null) continue
                    key(item.id) { EditableWidget(state, item, unit) }
                }
                CentreGuides(state, unit)
            }
        }
    }
}

/**
 * Faint dots every 16 units: the grid widgets snap to (at a quarter of this spacing). One dot is
 * drawn once into a small tile, and the tile is repeated by the graphics card: a single rectangle
 * per frame instead of fourteen hundred dots.
 */
@Composable
private fun GridDots(unit: Float) {
    val brush = remember(unit) {
        val tile = (16f * unit).roundToInt().coerceAtLeast(4)
        val bitmap = ImageBitmap(tile, tile)
        val paint = Paint().apply { color = Color.White.copy(alpha = 0.16f); isAntiAlias = true }
        androidx.compose.ui.graphics.Canvas(bitmap).drawCircle(Offset(tile / 2f, tile / 2f), tile / 16f, paint)
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }
    Box(Modifier.fillMaxSize().drawBehind { drawRect(brush) })
}

/**
 * The canvas centre lines, shown while a moved widget's centre is within reach of them: faint on
 * the way, bright once the widget sits on one. Drawn only, so a touch goes straight through.
 */
@Composable
private fun CentreGuides(state: EditorState, unit: Float) {
    val orientation = state.orientation
    Box(
        Modifier.fillMaxSize().drawBehind {
            val c = state.centring
            val width = 1.dp.toPx()
            if (c.nearX) {
                val colour = if (c.lockX) Palette.Select else Palette.Select.copy(alpha = 0.4f)
                drawLine(colour, Offset(orientation.width * unit / 2f, 0f), Offset(orientation.width * unit / 2f, size.height), width)
            }
            if (c.nearY) {
                val colour = if (c.lockY) Palette.Select else Palette.Select.copy(alpha = 0.4f)
                drawLine(colour, Offset(0f, orientation.height * unit / 2f), Offset(size.width, orientation.height * unit / 2f), width)
            }
        },
    )
}

private val dashColour =Color.White.copy(alpha = 0.3f)

/** Room around each widget for its × and handle, which sit half outside its edges. */
private val CHROME = 16.dp

@Composable
private fun EditableWidget(state: EditorState, item: WidgetItem, unit: Float) {
    val density = LocalDensity.current
    val chrome = with(density) { CHROME.roundToPx() }
    val selected = state.selectedId == item.id
    val dragging = state.dragId != null
    val inGroup = item.id in state.groupIds
    val actions = remember(item.id, state.selecting, inGroup) { widgetActions(state, item.id, inGroup) }
    val target = IntOffset((item.box.x * unit).roundToInt() - chrome, (item.box.y * unit).roundToInt() - chrome)
    // Widgets pushed out of the way glide; the one being dragged follows the finger exactly.
    val offset by animateIntOffsetAsState(target, if (dragging && state.dragId != item.id) tween(150) else snap(), label = "widget")
    Box(
        Modifier
            .offset { offset }
            .size(with(density) { (item.box.w * unit).toDp() + CHROME * 2 }, with(density) { (item.box.h * unit).toDp() + CHROME * 2 })
            // Each widget is drawn once into its own texture and then only moved, so dragging one
            // widget (and the ones it pushes) costs the others nothing.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .testTag("widget ${item.id}"),
    ) {
        Box(
            Modifier
                .padding(CHROME)
                .fillMaxSize()
                // For TalkBack: the widget has a name, and moving and resizing, which a screen reader
                // cannot drag, are offered as actions.
                .semantics(mergeDescendants = true) {
                    contentDescription = state.titleOf(item)
                    customActions = actions
                }
                .drawBehind {
                    if (selected || inGroup) {
                        drawRect(Palette.Select, style = Stroke(1.5.dp.toPx()))
                    } else {
                        drawRect(dashColour, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                    }
                }
                .pointerInput(item.id) {
                    detectTapGestures(
                        onLongPress = {
                            state.holdWidget(item.id)
                            state.haptic(Haptic.Centre)
                        },
                        onTap = { if (state.selecting) state.toggleInGroup(item.id) else state.select(item.id) },
                    )
                }
                .pointerInput(item.id, unit) {
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = { total = Offset.Zero; state.beginDrag(item.id, resize = false) },
                        onDrag = { change, amount ->
                            change.consume()
                            total += amount
                            state.dragTo(total.x / unit, total.y / unit)
                        },
                        onDragEnd = state::endDrag,
                        onDragCancel = state::endDrag,
                    )
                },
        ) {
            WidgetBody(item, Modifier.fillMaxSize())
        }
        if (selected) {
            // Delete: one tap, with Undo for five seconds.
            Box(
                Modifier.align(Alignment.TopStart).padding(start = CHROME - 11.dp, top = CHROME - 11.dp).size(22.dp)
                    .clip(CircleShape).background(Palette.Danger).border(3.dp, Color.Black, CircleShape)
                    .clickable(role = Role.Button) { state.delete(item.id) }
                    .semantics { contentDescription = "Delete ${state.titleOf(item)}" }
                    .testTag("delete"),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(9.dp)) {
                    val w = 1.8.dp.toPx()
                    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
                }
            }
            // Resize: a 32 dp touch area around a 22 dp dot, so it is easy to catch with a thumb.
            Box(
                Modifier.align(Alignment.BottomEnd).size(32.dp).testTag("handle")
                    .semantics { contentDescription = "Resize ${state.titleOf(item)}" }
                    .pointerInput(item.id, unit) {
                        var total = Offset.Zero
                        detectDragGestures(
                            onDragStart = { total = Offset.Zero; state.beginDrag(item.id, resize = true) },
                            onDrag = { change, amount ->
                                change.consume()
                                total += amount
                                state.dragTo(total.x / unit, total.y / unit)
                            },
                            onDragEnd = state::endDrag,
                            onDragCancel = state::endDrag,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Palette.Select).border(3.dp, Color.Black, CircleShape))
            }
        }
    }
}

/** Moving and resizing as actions a screen reader can offer, each one step of [EditorState.STEP] units. */
private fun widgetActions(state: EditorState, id: String, inGroup: Boolean): List<CustomAccessibilityAction> {
    fun action(label: String, dx: Int, dy: Int, resize: Boolean) =
        CustomAccessibilityAction(label) { state.nudge(id, dx, dy, resize); true }
    val step = EditorState.STEP
    val grouping = if (state.selecting) {
        listOf(CustomAccessibilityAction(if (inGroup) "Remove from group" else "Add to group") { state.toggleInGroup(id); true })
    } else emptyList()
    return grouping + listOf(
        action("Move up", 0, -step, resize = false),
        action("Move down", 0, step, resize = false),
        action("Move left", -step, 0, resize = false),
        action("Move right", step, 0, resize = false),
        action("Wider", step, 0, resize = true),
        action("Narrower", -step, 0, resize = true),
        action("Taller", 0, step, resize = true),
        action("Shorter", 0, -step, resize = true),
    )
}

@Composable
private fun TopBar(state: EditorState, onDone: () -> Unit, modifier: Modifier) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    Column(modifier.fillMaxWidth().background(Palette.Bar)) {
        // Each button has a slot of its own, so "Reset" turning into "Tap again to reset" cannot push the others about.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1.3f), contentAlignment = Alignment.CenterStart) {
                TextButton(onClick = {
                    if (armed) {
                        armed = false
                        state.reset()
                    } else armed = true
                }) { Text(if (armed) "Tap again to reset" else "Reset", color = if (armed) Palette.Danger else Palette.Select, fontSize = 15.sp) }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton(onClick = { state.pickerOpen = true }) { Text("+ Add widget", color = Palette.Select, fontSize = 15.sp) }
            }
            Box(Modifier.weight(0.7f), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = onDone) { Text("Done", color = Palette.Select, fontSize = 15.sp) }
            }
        }
        Rule()
        SelectBar(state)
    }
}

/**
 * Select mode: gather widgets by tapping them, then drag any of them to move the lot, or line them up.
 * Alignment is against the box around everything gathered.
 */
@Composable
private fun SelectBar(state: EditorState) {
    val count = state.groupIds.size
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { state.selectMode(!state.selecting) }, modifier = Modifier.testTag("select")) {
            Text(if (state.selecting) "Cancel" else "Select", color = Palette.Select, fontSize = 15.sp)
        }
        if (state.selecting) {
            Text(
                if (count == 0) "Tap widgets" else "$count selected", color = Palette.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                for ((mode, label) in ALIGN_LABELS) {
                    TextButton(onClick = { state.align(mode) }, enabled = count >= 2) {
                        Text(label, color = if (count >= 2) Palette.Select else Palette.Muted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
    if (state.selecting) Rule()
}

private val ALIGN_LABELS = listOf(
    Align.Left to "Left", Align.CentreX to "Centre", Align.Right to "Right",
    Align.Top to "Top", Align.CentreY to "Middle", Align.Bottom to "Bottom",
)

@Composable
private fun ToastBar(state: EditorState, toast: Toast, modifier: Modifier) {
    LaunchedEffect(toast.id) {
        delay(5_000)
        if (state.toast?.id == toast.id) state.toast = null
    }
    Row(
        modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(6.dp)).background(Palette.ToastFill).border(1.dp, Palette.ToastEdge, RoundedCornerShape(6.dp))
            .clickable { state.toast = null }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A live region: a screen reader says "Clock deleted" when it appears, without being asked.
        Text(
            toast.message, fontSize = 14.sp,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        toast.undo?.let { undo ->
            TextButton(onClick = {
                state.toast = null
                undo()
            }) { Text("Undo", color = Palette.Select, fontSize = 14.sp) }
        }
        TextButton(onClick = { state.toast = null }) { Text("Dismiss", color = Palette.Muted, fontSize = 14.sp) }
    }
}

/** The add-widget picker: every kind of widget, each with a live preview. */
@Composable
private fun AddPicker(state: EditorState, modifier: Modifier) {
    Column(modifier.background(Palette.Page).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Add a widget", color = Palette.Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.pickerOpen = false }) { Text("Cancel", color = Palette.Select, fontSize = 15.sp) }
        }
        Rule()
        LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize()) {
            items(Widgets.all, key = { it.id }) { type ->
                Column(
                    Modifier.clickable { state.add(type) }.drawBehind {
                        drawLine(Palette.Rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                        drawLine(Palette.Rule, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
                    }.padding(12.dp),
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(92.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                        val density = LocalDensity.current
                        val scale = min(constraints.maxWidth * 0.94f / type.width, constraints.maxHeight * 0.9f / type.height)
                        Box(Modifier.size(with(density) { (type.width * scale).toDp() }, with(density) { (type.height * scale).toDp() })) {
                            CompositionLocalProvider(LocalTextStyle provides WidgetTextStyle) { type.Content(type.defaults) }
                        }
                    }
                    Text(type.label, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(type.blurb, fontSize = 12.sp, color = Palette.Muted)
                }
            }
        }
    }
}
