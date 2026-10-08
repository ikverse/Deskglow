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
class EditorState(initial: Layout, val orientation: Orientation = Orientation.Portrait, private val save: (Layout) -> Unit) {
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

    private var session: DragSession? = null

    val selected: WidgetItem? get() = selectedId?.let(layout::find)

    fun typeOf(item: WidgetItem): WidgetType? = Widgets.find(item.type)

    fun settingsOf(item: WidgetItem): Settings = typeOf(item)?.let { item.settings.withDefaults(it.defaults) } ?: item.settings

    fun titleOf(item: WidgetItem): String = typeOf(item)?.title(settingsOf(item)) ?: item.type

    private fun commit(next: Layout) {
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
        toast = Toast("Aligned ${members.size} widgets", undo = { commit(layout.withBoxes(previous)) })
    }

    // ---- dragging: every step is worked out from where everything was when the drag began ----

    fun beginDrag(id: String, resize: Boolean) {
        val item = layout.find(id) ?: return
        // In select mode a drag moves the gathered group, or just the one widget, and selects nothing.
        if (!selecting) select(id)
        val companions = if (!resize && id in groupIds && groupIds.size >= 2) groupIds else emptySet()
        session = DragSession(id, resize, item.box, visiblePlaced(), orientation, companions)
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
        toast = Toast("${titleOf(item)} deleted", undo = { restore(item, index) })
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
        commit(layout.replace(item.copy(settings = next)))
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

        /** How far one accessibility action moves or resizes a widget: four grid steps, the spacing of the dots. */
        const val STEP = 16
    }
}
