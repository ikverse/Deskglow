package com.ikverse.deskglow.ui.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.display.WidgetBody
import com.ikverse.deskglow.layout.Align
import com.ikverse.deskglow.layout.Corner
import com.ikverse.deskglow.layout.other
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.store.MAX_PAGES
import com.ikverse.deskglow.ui.Chip
import com.ikverse.deskglow.ui.EaseOutStrong
import com.ikverse.deskglow.ui.Glyph
import com.ikverse.deskglow.ui.GlyphIcon
import com.ikverse.deskglow.ui.IconAction
import com.ikverse.deskglow.ui.Palette
import com.ikverse.deskglow.ui.Rule
import com.ikverse.deskglow.ui.Type
import com.ikverse.deskglow.ui.pressable
import com.ikverse.deskglow.widgets.LocalEditing
import com.ikverse.deskglow.widgets.Widgets
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The layout editor: the screen as it will look, with every widget movable (drag it), resizable
 * (drag its handle) and, once tapped, with its own small bar of actions above it. Widgets never
 * overlap: whatever is in the way is pushed down and slides back while the drag is still held.
 *
 * It edits the layout of one [orientation]. The portrait layout holds the screen upright while it is
 * open; the landscape layout can be edited with the phone either way. Held upright, the settings sheet
 * lies along the bottom and can be pulled up; on its side, the settings are a panel beside the canvas.
 */
@Composable
fun EditorScreen(graph: AppGraph, orientation: Orientation = Orientation.Portrait, startPage: Int = 0, onDone: () -> Unit) {
    val pageCount by graph.prefs.pageCount(orientation).collectAsStateWithLifecycle()
    var requestedPage by rememberSaveable { mutableIntStateOf(startPage) }
    val page = requestedPage.coerceIn(0, pageCount - 1)
    var addingPage by remember { mutableStateOf(false) }
    var deletingPage by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    val repository = graph.layoutsFor(orientation, page)
    val state = remember(orientation, page) {
        EditorState(repository.layout.value, orientation, copyTarget = graph.layoutsFor(orientation.other, page), save = repository::update,
            // The other layout may have fewer screens: the copy lands on the same screen number, so it is made first.
            beforeCopy = { graph.ensurePages(orientation.other, page + 1) })
    }
    val pages = PageActions(
        count = pageCount,
        current = page,
        onSelect = { requestedPage = it },
        onAdd = { if (pageCount < MAX_PAGES) addingPage = true },
        onDelete = { deletingPage = true },
    )
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
                LandscapeEditor(state, graph, pages, onDone, onReset = { resetting = true })
            } else {
                val screenHeight = maxHeight
                Column(Modifier.fillMaxSize()) {
                    TopBar(state, pages, onDone, onReset = { resetting = true })
                    // The canvas takes whatever the top bar and the sheet leave, so it is big while the sheet is down.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Stage(state, Modifier.fillMaxSize().padding(bottom = DOCK_RESERVE))
                        Dock(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
                        if (state.moreFonts == null) state.toast?.let { ToastBar(state, it, Modifier.align(Alignment.BottomCenter)) }
                    }
                    EditorSheet(state, graph, screenHeight = screenHeight)
                }
            }
            if (state.pickerOpen) AddPicker(state, Modifier.fillMaxSize())
            if (state.copyConfirm) CopyDialog(state)
            if (addingPage) {
                AlertDialog(
                    onDismissRequest = { addingPage = false },
                    title = { Text("Add a screen") },
                    text = { Text("Start with an empty screen, or with a copy of screen ${page + 1}.") },
                    confirmButton = {
                        Row {
                            TextButton(onClick = {
                                addingPage = false
                                graph.addPage(orientation)?.let { requestedPage = it }
                            }, modifier = Modifier.testTag("add blank screen")) { Text("Empty") }
                            TextButton(onClick = {
                                addingPage = false
                                graph.addPage(orientation, copyOf = page)?.let { requestedPage = it }
                            }, modifier = Modifier.testTag("add copied screen")) { Text("Copy") }
                        }
                    },
                    dismissButton = { TextButton(onClick = { addingPage = false }) { Text("Cancel") } },
                    containerColor = Palette.Sheet,
                )
            }
            if (deletingPage) {
                AlertDialog(
                    onDismissRequest = { deletingPage = false },
                    title = { Text("Delete screen ${page + 1}?") },
                    text = { Text("Screen ${page + 1} of the ${orientation.name.lowercase()} layout is removed. The screens after it move up.") },
                    confirmButton = {
                        TextButton(onClick = {
                            deletingPage = false
                            graph.deletePage(orientation, page)
                        }, modifier = Modifier.testTag("confirm delete screen")) { Text("Delete", color = Palette.Danger) }
                    },
                    dismissButton = { TextButton(onClick = { deletingPage = false }) { Text("Cancel") } },
                    containerColor = Palette.Sheet,
                )
            }
            if (resetting) {
                AlertDialog(
                    onDismissRequest = { resetting = false },
                    title = { Text("Reset this layout?") },
                    text = { Text("This screen goes back to the default widgets. You can undo it right after.") },
                    confirmButton = {
                        TextButton(onClick = {
                            resetting = false
                            state.reset()
                        }, modifier = Modifier.testTag("confirm reset")) { Text("Reset", color = Palette.Danger) }
                    },
                    dismissButton = { TextButton(onClick = { resetting = false }) { Text("Cancel") } },
                    containerColor = Palette.Sheet,
                )
            }
            state.moreFonts?.let { MoreFontsSheet(it, state, graph, Modifier.fillMaxSize()) }
            // The font list covers the canvas area, so a message from it (a font that could not be downloaded)
            // is drawn over the whole screen, last, rather than under it. That list has no toolbar to hide.
            if (state.moreFonts != null) state.toast?.let { ToastBar(state, it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/** Room under the canvas for the dock that floats there, so nothing on the canvas is ever covered by it. */
private val DOCK_RESERVE = 64.dp

/**
 * Landscape: the top bar across the screen, then the canvas with the settings panel beside it. The
 * canvas gets what is left of the width, so it is smaller than the real screen (about two thirds on
 * a Note 9); nothing sits on top of it.
 */
@Composable
private fun LandscapeEditor(state: EditorState, graph: AppGraph, pages: PageActions, onDone: () -> Unit, onReset: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val panel = (maxWidth * 0.36f).coerceIn(240.dp, 320.dp)
        Column(Modifier.fillMaxSize()) {
            TopBar(state, pages, onDone, onReset)
            Row(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Stage(state, Modifier.fillMaxSize().padding(bottom = DOCK_RESERVE))
                    Dock(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
                    if (state.moreFonts == null) state.toast?.let { ToastBar(state, it, Modifier.align(Alignment.BottomCenter)) }
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(Palette.Rule))
                EditorSidePanel(state, graph, Modifier.fillMaxHeight().width(panel))
            }
        }
    }
}

/** What the screen switcher shows and does: which screen is open, and how to switch, add or delete one. */
private class PageActions(
    val count: Int,
    val current: Int,
    val onSelect: (Int) -> Unit,
    val onAdd: () -> Unit,
    val onDelete: () -> Unit,
)

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

/** How far the canvas can be pinched in, as a multiple of the size that fits the stage. */
private const val MAX_ZOOM = 6f

/**
 * Where the zoomed canvas may sit: its centre can move from the stage centre by half of what
 * overhangs the stage on each axis, so no edge is pulled past the stage edge, and a canvas that fits
 * on an axis stays centred on it.
 */
private fun clampPan(pan: Offset, content: Size, view: Size): Offset {
    val limitX = ((content.width - view.width) / 2f).coerceAtLeast(0f)
    val limitY = ((content.height - view.height) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-limitX, limitX), pan.y.coerceIn(-limitY, limitY))
}

/**
 * The canvas, fitted to the stage. Pinching with two fingers zooms it (and two fingers moving pan it)
 * so a widget can be placed precisely. The zoom is carried by [unit], the pixels per canvas unit, so
 * every drag and snap below it works at the zoomed scale unchanged, and widgets are redrawn sharp.
 */
@Composable
private fun Stage(state: EditorState, modifier: Modifier) {
    val canvas = state.orientation
    var zoom by remember(canvas) { mutableFloatStateOf(1f) }
    var pan by remember(canvas) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val fit = min(constraints.maxWidth / canvas.width.toFloat(), constraints.maxHeight / canvas.height.toFloat())
        val unit = fit * zoom
        val view = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val content = Size(canvas.width * unit, canvas.height * unit)
        val density = LocalDensity.current
        // The canvas is a drawing in fixed coordinates, not text: it is never mirrored for a right-to-left
        // language, or a widget would move opposite to the finger that drags it.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Looked at before the widgets below see a touch, so that once a second finger is
                    // down the gesture is the stage's and the widget under the first finger lets go.
                    .pointerInput(canvas, fit, constraints.maxWidth, constraints.maxHeight) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    val centre = Offset(size.width / 2f, size.height / 2f)
                                    val focus = event.calculateCentroid(useCurrent = false) - centre
                                    val next = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                    // Keep the point between the fingers under them while the scale changes.
                                    val moved = focus - (focus - pan) * (next / zoom) + event.calculatePan()
                                    zoom = next
                                    pan = clampPan(moved, Size(canvas.width * fit * next, canvas.height * fit * next), view)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .requiredSize(with(density) { content.width.toDp() }, with(density) { content.height.toDp() })
                        .offset {
                            val p = clampPan(pan, content, view)
                            IntOffset(p.x.roundToInt(), p.y.roundToInt())
                        }
                        .pointerInput(Unit) { detectTapGestures { state.select(null) } },
                ) {
                    GridDots(unit)
                    for (item in state.layout.items) {
                        if (!item.visible || Widgets.find(item.type) == null) continue
                        key(item.id) { EditableWidget(state, item, unit) }
                    }
                    CentreGuides(state, unit)
                    // Over the widgets, so a touch on it never reaches the widget beneath.
                    state.selected?.takeIf { it.visible && !state.selecting && state.sheetStop != SheetStop.Full }?.let { ActionPill(state, it, unit, content) }
                }
            }
        }
        if (zoom > 1.001f) {
            Text(
                "${(zoom * 10).roundToInt() / 10f}× · Reset",
                color = Palette.Select,
                fontSize = Type.Small,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Palette.Bar.copy(alpha = 0.85f))
                    .clickable(role = Role.Button) { zoom = 1f; pan = Offset.Zero }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("zoom reset"),
            )
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
    // The dot sits in the middle of its tile, so the pattern is shifted back half a tile to put the dots
    // on the lines widgets snap to (0, 16, 32 ...), and drawn a tile larger to cover the edges it uncovers.
    val half = (16f * unit).roundToInt().coerceAtLeast(4) / 2f
    Box(
        Modifier.fillMaxSize().drawBehind {
            translate(-half, -half) { drawRect(brush, size = Size(size.width + half * 2, size.height + half * 2)) }
        },
    )
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

/** The hairline round every widget while moving, resizing or gathering them, so they can be told apart. */
private val hairline = Color.White.copy(alpha = 0.14f)

/** Room around each widget for its handles and glow, which sit half outside its edges. */
private val CHROME = 16.dp

/** Touch and hold to start selecting. Longer than the default so a slow start to a drag does not trigger it. */
private const val HOLD_TO_SELECT_MS = 800L

@Composable
private fun EditableWidget(state: EditorState, item: WidgetItem, unit: Float) {
    val base = LocalViewConfiguration.current
    val config = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis get() = HOLD_TO_SELECT_MS
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides config) {
        EditableWidgetBody(state, item, unit)
    }
}

@Composable
private fun EditableWidgetBody(state: EditorState, item: WidgetItem, unit: Float) {
    val density = LocalDensity.current
    val chrome = with(density) { CHROME.roundToPx() }
    val selected = state.selectedId == item.id
    val dragging = state.dragId != null
    val inGroup = item.id in state.groupIds
    val actions = remember(item.id, state.selecting, inGroup) { widgetActions(state, item.id, inGroup) }
    val target = IntOffset((item.box.x * unit).roundToInt() - chrome, (item.box.y * unit).roundToInt() - chrome)
    // Widgets pushed out of the way glide; the one being dragged follows the finger exactly.
    val offset by animateIntOffsetAsState(target, if (dragging && state.dragId != item.id) tween(150) else snap(), label = "widget")
    val arrive = remember(item.id) { Animatable(if (state.justAdded == item.id) 0f else 1f) }
    LaunchedEffect(item.id) { if (arrive.value < 1f) arrive.animateTo(1f, tween(220, easing = EaseOutStrong)) }
    Box(
        Modifier
            .offset { offset }
            .size(with(density) { (item.box.w * unit).toDp() + CHROME * 2 }, with(density) { (item.box.h * unit).toDp() + CHROME * 2 })
            // A widget just added or copied fades in and settles from a touch smaller.
            .graphicsLayer {
                val shown = arrive.value
                alpha = shown
                scaleX = 0.96f + 0.04f * shown
                scaleY = 0.96f + 0.04f * shown
            }
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
                    when {
                        selected -> {
                            // The one lit thing on the canvas: a soft amber halo, then a thin amber line.
                            val steps = 5
                            for (i in steps downTo 1) {
                                val grow = 11.dp.toPx() * i / steps
                                drawRoundRect(
                                    Palette.Select.copy(alpha = 0.045f), Offset(-grow, -grow),
                                    Size(size.width + grow * 2, size.height + grow * 2), CornerRadius(4.dp.toPx() + grow),
                                )
                            }
                            drawRoundRect(Palette.Select, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        }
                        inGroup -> drawRoundRect(Palette.Select, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        dragging || state.selecting -> drawRoundRect(hairline, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
                    }
                }
                .pointerInput(item.id) {
                    detectTapGestures(
                        onLongPress = {
                            state.holdWidget(item.id)
                            state.haptic(Haptic.Centre)
                        },
                        onTap = { if (state.selecting) state.toggleInGroup(item.id) else state.selectAndOpen(item.id) },
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
            // Resize from any corner: a 32 dp touch area. The bottom-right one is the lit handle; the others are small and quiet.
            for (corner in Corner.entries) {
                val main = corner == Corner.BottomEnd
                Box(
                    Modifier.align(corner.alignment).size(32.dp)
                        .testTag(if (main) "handle" else "handle ${corner.name}")
                        .semantics { contentDescription = "Resize ${state.titleOf(item)} from ${corner.label}" }
                        .pointerInput(item.id, unit) {
                            var total = Offset.Zero
                            detectDragGestures(
                                onDragStart = { total = Offset.Zero; state.beginDrag(item.id, resize = true, corner = corner) },
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
                    if (main) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(Palette.Select).border(3.dp, Color.Black, CircleShape))
                    } else {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Ink.copy(alpha = 0.55f)).border(2.dp, Color.Black, CircleShape))
                    }
                }
            }
        }
    }
}

private val Corner.alignment
    get() = when (this) {
        Corner.TopStart -> Alignment.TopStart
        Corner.TopEnd -> Alignment.TopEnd
        Corner.BottomStart -> Alignment.BottomStart
        Corner.BottomEnd -> Alignment.BottomEnd
    }

private val Corner.label
    get() = when (this) {
        Corner.TopStart -> "top left"
        Corner.TopEnd -> "top right"
        Corner.BottomStart -> "bottom left"
        Corner.BottomEnd -> "bottom right"
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

private val PILL_BUTTON = 40.dp
private const val PILL_BUTTONS = 4

/**
 * The small bar of actions over the selected widget (below it, when there is no room above): its
 * settings, a copy, hide, delete. It grows out of the widget, and stays out of the way while one is dragged.
 */
@Composable
private fun ActionPill(state: EditorState, item: WidgetItem, unit: Float, content: Size) {
    val density = LocalDensity.current
    val width = with(density) { (PILL_BUTTON * PILL_BUTTONS + 12.dp).toPx() }
    val height = with(density) { (PILL_BUTTON + 4.dp).toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val top = item.box.y * unit
    val bottom = (item.box.y + item.box.h) * unit
    val y = when {
        top - height - gap >= 0f -> top - height - gap
        bottom + gap + height <= content.height -> bottom + gap
        else -> top + gap
    }
    val x = ((item.box.x + item.box.w / 2f) * unit - width / 2f).coerceIn(0f, (content.width - width).coerceAtLeast(0f))
    val appear = remember(item.id) { Animatable(0f) }
    LaunchedEffect(item.id) { appear.animateTo(1f, tween(120, easing = EaseOutStrong)) }
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .graphicsLayer {
                // Grows from the side nearest the widget; gone while a widget is being dragged.
                val shown = if (state.dragId != null) 0f else appear.value
                alpha = shown
                scaleX = 0.95f + 0.05f * shown
                scaleY = 0.95f + 0.05f * shown
                transformOrigin = TransformOrigin(0.5f, if (y < top) 1f else 0f)
            }
            .clip(shape)
            .background(Palette.Raised)
            .border(1.dp, Palette.Edge, shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("action pill"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = state.titleOf(item)
        PillButton(Glyph.Sliders, "Settings for $title", Palette.Ink) {
            state.tab = SheetTab.Settings
            if (state.sheetStop == SheetStop.Peek) state.sheetStop = SheetStop.Half
        }
        PillButton(Glyph.Duplicate, "Duplicate $title", Palette.Ink) { state.duplicate(item.id) }
        PillButton(Glyph.EyeOff, "Hide $title", Palette.Ink) { state.setVisible(item.id, false) }
        PillButton(Glyph.Trash, "Delete $title", Palette.Danger, Modifier.testTag("delete")) { state.delete(item.id) }
    }
}

@Composable
private fun PillButton(glyph: Glyph, description: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) =
    IconAction(glyph, description, onClick, modifier.size(PILL_BUTTON), tint = tint, size = 19.dp)

/** Back, the screen switcher, undo and redo, and the menu with everything less often wanted. */
@Composable
private fun TopBar(state: EditorState, pages: PageActions, onDone: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.Bar)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Glyph.Back, "Back", onDone, Modifier.testTag("done"))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ScreenSwitcher(state, pages) }
            IconAction(Glyph.Undo, "Undo", state::undo, Modifier.testTag("undo"), enabled = state.canUndo)
            IconAction(Glyph.Redo, "Redo", state::redo, Modifier.testTag("redo"), enabled = state.canRedo)
            MoreMenu(state, onReset)
        }
        Rule()
        SelectBar(state)
    }
}

/** "Portrait · Screen 1 ▾": tap for the list of screens, and to add or delete one. */
@Composable
private fun ScreenSwitcher(state: EditorState, pages: PageActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(Palette.Raised)
                .border(1.dp, Palette.Edge, shape)
                .pressable(onClickLabel = "Switch screen") { open = true }
                .padding(start = 14.dp, end = 10.dp)
                .testTag("screens"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${state.orientation.name} · Screen ${pages.current + 1}", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            GlyphIcon(Glyph.ChevronDown, tint = Palette.Muted, size = 16.dp, weight = 2.2f)
        }
        DropdownMenu(open, { open = false }, containerColor = Palette.Raised) {
            for (i in 0 until pages.count) {
                DropdownMenuItem(
                    text = { Text("Screen ${i + 1}", color = if (i == pages.current) Palette.Select else Palette.Ink) },
                    onClick = { open = false; pages.onSelect(i) },
                    leadingIcon = { if (i == pages.current) GlyphIcon(Glyph.Check, tint = Palette.Select, size = 18.dp, weight = 2.2f) else Spacer(Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("screen ${i + 1}"),
                )
            }
            HorizontalDivider(color = Palette.Rule)
            if (pages.count < MAX_PAGES) {
                DropdownMenuItem(
                    text = { Text("Add screen") },
                    onClick = { open = false; pages.onAdd() },
                    leadingIcon = { GlyphIcon(Glyph.Plus, size = 18.dp) },
                    modifier = Modifier.testTag("add screen"),
                )
            }
            if (pages.count > 1) {
                DropdownMenuItem(
                    text = { Text("Delete screen ${pages.current + 1}", color = Palette.Danger) },
                    onClick = { open = false; pages.onDelete() },
                    leadingIcon = { GlyphIcon(Glyph.Trash, tint = Palette.Danger, size = 18.dp) },
                    modifier = Modifier.testTag("delete screen"),
                )
            }
        }
    }
}

/** The ⋯ menu: select several widgets, snap everything to the grid, copy to the other layout, reset. */
@Composable
private fun MoreMenu(state: EditorState, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(Glyph.More, "More", { open = true }, Modifier.testTag("more"))
        DropdownMenu(open, { open = false }, containerColor = Palette.Raised) {
            DropdownMenuItem(
                text = { Text(if (state.selecting) "Stop selecting" else "Select widgets") },
                onClick = { open = false; state.selectMode(!state.selecting) },
                modifier = Modifier.testTag("select"),
            )
            DropdownMenuItem(
                text = { Text("Snap all to grid") },
                onClick = { open = false; state.snapAllToGrid() },
            )
            DropdownMenuItem(
                text = { Text("Copy to ${state.orientation.other.name.lowercase()} layout") },
                onClick = { open = false; state.copyConfirm = true },
                modifier = Modifier.testTag("copy"),
            )
            HorizontalDivider(color = Palette.Rule)
            DropdownMenuItem(
                text = { Text("Reset layout", color = Palette.Danger) },
                onClick = { open = false; onReset() },
                modifier = Modifier.testTag("reset"),
            )
        }
    }
}

/** The floating bar under the canvas: add a widget, arrange them all, snap them to the grid. */
@Composable
private fun Dock(state: EditorState, modifier: Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier.height(52.dp).clip(shape).background(Palette.Raised).border(1.dp, Palette.Edge, shape).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Palette.Select)
                .pressable { state.pickerOpen = true }
                .semantics { contentDescription = "Add widget" }
                .testTag("add widget"),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Plus, tint = Palette.OnAccent, size = 22.dp, weight = 2.4f) }
        IconAction(Glyph.Sparkle, "Auto-arrange", state::smartArrange, Modifier.testTag("auto arrange"))
        IconAction(Glyph.Grid, "Snap all to grid", state::snapAllToGrid, Modifier.testTag("snap all"))
    }
}

/**
 * Select mode: gather widgets by tapping them, then drag any of them to move the lot, or line them up.
 * Alignment is against the box around everything gathered.
 */
@Composable
private fun SelectBar(state: EditorState) {
    if (!state.selecting) return
    val count = state.groupIds.size
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == 0) "Tap widgets" else "$count selected", color = Palette.Muted, fontSize = Type.Small,
            modifier = Modifier.padding(end = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((mode, label) in ALIGN_LABELS) {
                Chip(label, { if (count >= 2) state.align(mode) })
            }
        }
        Spacer(Modifier.width(8.dp))
        Chip("Done", { state.selectMode(false) }, selected = true)
    }
    Rule()
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
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth()
            .clip(shape).background(Palette.ToastFill).border(1.dp, Palette.ToastEdge, shape)
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
