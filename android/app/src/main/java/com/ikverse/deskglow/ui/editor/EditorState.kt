package com.ikverse.deskglow.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ikverse.deskglow.layout.Align
import com.ikverse.deskglow.layout.Centring
import com.ikverse.deskglow.layout.DragSession
import com.ikverse.deskglow.layout.Packer
import com.ikverse.deskglow.layout.Placed
import com.ikverse.deskglow.layout.Retarget
import com.ikverse.deskglow.layout.other
import com.ikverse.deskglow.store.LayoutRepository
import com.ikverse.deskglow.model.Key
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.DefaultLayout
import com.ikverse.deskglow.widgets.StyleKind
import com.ikverse.deskglow.widgets.WidgetType
import com.ikverse.deskglow.widgets.Widgets

enum class SheetTab { Widgets, Settings }

/** A light tick for each grid square a resize crosses, a harder one on reaching the centre. */
enum class Haptic { Step, Centre }

/** A message across the top for five seconds, with Undo when there is something to undo. */
data class Toast(val message: String, val undo: (() -> Unit)? = null, val id: Long = System.nanoTime())

/**
 * Everything the editor does, apart from drawing it. Every change goes straight to [save] (the
 * shared layout), so the home preview and the screen saver always match what the editor shows. It
 * edits the layout of one [orientation].
 */
@Stable
class EditorState(
    initial: Layout,
    val orientation: Orientation = Orientation.Portrait,
    /** Where "copy to the other orientation" writes; null when there is nowhere to copy to. */
    private val copyTarget: LayoutRepository? = null,
    private val save: (Layout) -> Unit,
) {
    var layout by mutableStateOf(initial)
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var tab by mutableStateOf(SheetTab.Widgets)
    var sheetOpen by mutableStateOf(true)
    /** The widget being dragged or resized, if any. The sheet tucks away and pushed widgets glide while this is set. */
    var dragId by mutableStateOf<String?>(null)
        private set
    /** Where the moved widget stands against the canvas centre lines; the stage draws the guides from it. */
    var centring by mutableStateOf(Centring.None)
        private set
    /** Told when the screen should buzz; set by the screen, which has a view to buzz through. */
    var haptic: (Haptic) -> Unit = {}
    var pickerOpen by mutableStateOf(false)
    var moreFonts by mutableStateOf<StyleKind?>(null)
    var toast by mutableStateOf<Toast?>(null)

    /** Select mode: tapping widgets gathers them into [groupIds] instead of selecting one. Never saved. */
    var selecting by mutableStateOf(false)
        private set
    /** The widgets gathered in select mode; dragging any one of them (once there are two) moves them all. */
    var groupIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Asking whether to replace the other orientation's layout with a rearranged copy of this one. */
    var copyConfirm by mutableStateOf(false)

    private var session: DragSession? = null

    // ---- history: each change is one step back; a whole drag is one step ----

    private val undoStack = ArrayDeque<Layout>()
    private val redoStack = ArrayDeque<Layout>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** The layout as a drag began; it becomes a history step on the drag's first change, so a drag that moves nothing leaves none. */
    private var dragBase: Layout? = null
    /** The last setting changed and when, so a slider dragged through many values is one step. */
    private var lastMerge: Pair<String, Long>? = null

    private fun record(before: Layout) {
        undoStack.addLast(before)
        while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        syncHistory()
    }

    private fun syncHistory() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun undo() {
        if (dragId != null) return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(layout)
        goTo(previous)
    }

    fun redo() {
        if (dragId != null) return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(layout)
        goTo(next)
    }

    private fun goTo(target: Layout) {
        layout = target
        save(target)
        lastMerge = null
        groupIds = groupIds.filterTo(HashSet()) { layout.find(it)?.visible == true }
        if (selectedId != null && layout.find(selectedId!!)?.visible != true) select(null)
        syncHistory()
    }

    val selected: WidgetItem? get() = selectedId?.let(layout::find)

    fun typeOf(item: WidgetItem): WidgetType? = Widgets.find(item.type)

    fun settingsOf(item: WidgetItem): Settings = typeOf(item)?.let { item.settings.withDefaults(it.defaults) } ?: item.settings

    fun titleOf(item: WidgetItem): String = typeOf(item)?.title(settingsOf(item)) ?: item.type

    /** [mergeKey]: changes in quick succession under the same key share one history step. */
    private fun commit(next: Layout, mergeKey: String? = null) {
        if (next != layout) {
            val now = System.currentTimeMillis()
            val base = dragBase
            when {
                dragId != null -> if (base != null) {
                    record(base)
                    dragBase = null
                }
                mergeKey != null && lastMerge?.let { it.first == mergeKey && now - it.second < MERGE_WINDOW_MS } == true -> Unit
                else -> record(layout)
            }
            lastMerge = mergeKey?.let { it to now }
        }
        layout = next
        save(next)
    }

    private fun visiblePlaced(except: String? = null) =
        layout.items.filter { it.visible && it.id != except }.map { Placed(it.id, it.box) }

    fun select(id: String?) {
        selectedId = id?.takeIf { layout.find(it) != null }
        if (selectedId != null) tab = SheetTab.Settings else if (tab == SheetTab.Settings) tab = SheetTab.Widgets
    }

    // ---- grouping: gather widgets to move or align them together ----

    fun selectMode(on: Boolean) {
        selecting = on
        groupIds = emptySet()
        if (on) select(null)
    }

    /** Touch and hold: starts select mode with this widget gathered, or toggles it when already selecting. */
    fun holdWidget(id: String) {
        if (!selecting) selectMode(true)
        toggleInGroup(id)
    }

    fun toggleInGroup(id: String) {
        val item = layout.find(id) ?: return
        if (!selecting || !item.visible) return
        groupIds = if (id in groupIds) groupIds - id else groupIds + id
    }

    /** Lines the gathered widgets up on [mode]; the rest are pushed down if they are in the way. Undo moves them back. */
    fun align(mode: Align) {
        val members = layout.items.filter { it.visible && it.id in groupIds }
        if (members.size < 2) return
        val aligned = Packer.align(members.map { Placed(it.id, it.box) }, mode)
        val boxes = Packer.resolveGroup(aligned, visiblePlaced().filter { it.id !in groupIds }, orientation)
        if (boxes == null) {
            toast = Toast(NO_ALIGN)
            return
        }
        val moved = boxes.filter { (id, box) -> layout.find(id)?.box != box }
        if (moved.isEmpty()) {
            toast = Toast("Already lined up")
            return
        }
        val previous = moved.mapValues { (id, _) -> layout.find(id)!!.box }
        commit(layout.withBoxes(boxes))
        val after = layout
        // Straight back through the history if nothing else has changed since; otherwise move just these back.
        toast = Toast("Aligned ${members.size} widgets", undo = { if (layout == after) undo() else commit(layout.withBoxes(previous)) })
    }

    /** Moves every visible widget's corners onto the nearest dots of the background grid. Undo moves them back. */
    fun snapAllToGrid() {
        val boxes = Packer.snapToGrid(visiblePlaced(), orientation)
        val moved = boxes.filter { (id, box) -> layout.find(id)?.box != box }
        if (moved.isEmpty()) {
            toast = Toast("Already on the grid")
            return
        }
        val previous = moved.mapValues { (id, _) -> layout.find(id)!!.box }
        commit(layout.withBoxes(boxes))
        val after = layout
        toast = Toast("Snapped ${moved.size} widgets to grid", undo = { if (layout == after) undo() else commit(layout.withBoxes(previous)) })
    }

    // ---- dragging: every step is worked out from where everything was when the drag began ----

    fun beginDrag(id: String, resize: Boolean) {
        val item = layout.find(id) ?: return
        // In select mode a drag moves the gathered group, or just the one widget, and selects nothing.
        if (!selecting) select(id)
        val companions = if (!resize && id in groupIds && groupIds.size >= 2) groupIds else emptySet()
        session = DragSession(id, resize, item.box, visiblePlaced(), orientation, companions)
        dragBase = layout
        dragId = id
    }

    /** [dx], [dy]: canvas units from where the drag began. */
    fun dragTo(dx: Float, dy: Float) {
        val current = session ?: return
        val before = layout.find(current.id)?.box
        val boxes = current.update(dx, dy) ?: return
        val after = boxes[current.id]
        if (current.resize) {
            // One tick for every grid square the edge crosses.
            if (before != null && after != null && (after.w != before.w || after.h != before.h)) haptic(Haptic.Step)
        } else {
            // One tick for every grid square it moves.
            if (before != null && after != null && (after.x != before.x || after.y != before.y)) haptic(Haptic.Step)
            val now = current.centring
            // A harder one the moment the widget settles on a centre line, not again while it stays there.
            if ((now.lockX && !centring.lockX) || (now.lockY && !centring.lockY)) haptic(Haptic.Centre)
            centring = now
        }
        commit(layout.withBoxes(boxes))
    }

    fun endDrag() {
        session = null
        dragId = null
        dragBase = null
        centring = Centring.None
    }

    /**
     * A whole move or resize in one step, for anyone who cannot drag (a screen reader's actions):
     * [dx], [dy] canvas units. It goes through the same drag as a finger would, so widgets are still
     * pushed, never overlap, and stop at the canvas edge.
     */
    fun nudge(id: String, dx: Int, dy: Int, resize: Boolean) {
        beginDrag(id, resize)
        dragTo(dx.toFloat(), dy.toFloat())
        endDrag()
    }

    // ---- adding, deleting, hiding ----

    fun add(type: WidgetType): Boolean {
        val id = layout.nextId()
        val boxes = Packer.place(id, type.width, type.height, null, visiblePlaced(), orientation)
        pickerOpen = false
        if (boxes == null) {
            toast = Toast(NO_ROOM)
            return false
        }
        val item = WidgetItem(id, type.id, boxes.getValue(id), true, type.defaults)
        commit(Layout(layout.items + item).withBoxes(boxes))
        select(id)
        return true
    }

    fun delete(id: String) {
        val index = layout.items.indexOfFirst { it.id == id }
        if (index < 0) return
        val item = layout.items[index]
        commit(Layout(layout.items.filterIndexed { i, _ -> i != index }))
        groupIds -= id
        if (selectedId == id) select(null)
        val after = layout
        // Straight back through the history if nothing else has changed since; otherwise put it back where it was.
        toast = Toast("${titleOf(item)} deleted", undo = {
            if (layout == after) {
                undo()
                select(item.id)
            } else restore(item, index)
        })
    }

    /** Puts a deleted widget back in its old place and order; anything that has moved into that space is pushed down. */
    private fun restore(item: WidgetItem, index: Int) {
        val boxes = Packer.place(item.id, item.box.w, item.box.h, item.box, visiblePlaced(), orientation)
        if (boxes == null) {
            toast = Toast(NO_ROOM)
            return
        }
        val items = layout.items.toMutableList().apply { add(index.coerceAtMost(size), item) }
        commit(Layout(items).withBoxes(boxes))
        select(item.id)
    }

    fun setVisible(id: String, visible: Boolean) {
        val item = layout.find(id) ?: return
        if (!visible) {
            commit(layout.replace(item.copy(visible = false)))
            groupIds -= id
            if (selectedId == id) select(null)
            return
        }
        val boxes = Packer.place(id, item.box.w, item.box.h, item.box, visiblePlaced(except = id), orientation)
        if (boxes == null) {
            toast = Toast(NO_ROOM)
            return
        }
        commit(layout.replace(item.copy(visible = true)).withBoxes(boxes))
    }

    // ---- settings ----

    fun <T : Any> set(key: Key<T>, value: T) {
        val item = selected ?: return
        val type = typeOf(item) ?: return
        val next = type.normalise(settingsOf(item).with(key, value))
        commit(layout.replace(item.copy(settings = next)), mergeKey = "${item.id}:${key.name}")
    }

    /** Replaces the other orientation's layout with this one rearranged to suit it. Undo puts the old one back. */
    fun copyToOther() {
        copyConfirm = false
        val target = copyTarget ?: return
        val before = target.layout.value
        val converted = Retarget.convert(layout, orientation, orientation.other)
        target.update(converted.layout)
        val name = orientation.other.name.lowercase()
        val note = if (converted.hidden > 0) " ${converted.hidden} did not fit and ${if (converted.hidden == 1) "was" else "were"} hidden." else ""
        toast = Toast("Copied to $name.$note", undo = { target.update(before) })
    }

    fun reset() {
        commit(DefaultLayout.create(orientation))
        selectedId = null
        selecting = false
        groupIds = emptySet()
        tab = SheetTab.Widgets
        toast = null
    }

    companion object {
        const val NO_ROOM = "No room for it. Shrink or remove a widget first."
        const val NO_ALIGN = "Those would overlap. Try another alignment."

        /** How many steps back the history keeps. */
        const val HISTORY_LIMIT = 50
        private const val MERGE_WINDOW_MS = 1_000L

        /** How far one accessibility action moves or resizes a widget: four grid steps, the spacing of the dots. */
        const val STEP = 16
    }
}
