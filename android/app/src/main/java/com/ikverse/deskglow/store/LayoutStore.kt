package com.ikverse.deskglow.store

import android.util.Log
import androidx.core.util.AtomicFile
import com.ikverse.deskglow.layout.Packer
import com.ikverse.deskglow.layout.Placed
import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.DefaultLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The saved layout as JSON. Every file carries a version number: an update that changes the format
 * adds a step to [migrate], so a layout saved by any earlier version opens without loss.
 */
object LayoutCodec {
    const val VERSION = 1

    fun encode(layout: Layout): String {
        val items = JSONArray()
        for (item in layout.items) {
            val settings = JSONObject()
            for ((name, value) in item.settings.values) settings.put(name, value)
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type)
                    .put("x", item.box.x).put("y", item.box.y)
                    .put("w", item.box.w).put("h", item.box.h)
                    .put("visible", item.visible)
                    .put("settings", settings),
            )
        }
        return JSONObject().put("version", VERSION).put("items", items).toString(2)
    }

    /** Reads a saved layout, upgrading it first if an older version wrote it. Throws on a file that is not a layout. */
    fun decode(text: String): Layout {
        val json = migrate(JSONObject(text))
        val items = json.getJSONArray("items")
        return Layout(
            (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val settings = item.optJSONObject("settings") ?: JSONObject()
                WidgetItem(
                    id = item.getString("id"),
                    type = item.getString("type"),
                    box = Box(item.getInt("x"), item.getInt("y"), item.getInt("w"), item.getInt("h")),
                    visible = item.optBoolean("visible", true),
                    settings = Settings(settings.keys().asSequence().associateWith { settings.get(it) }),
                )
            },
        )
    }

    /** Brings a layout written by any earlier version up to [VERSION]. Version 1 is the first. */
    private fun migrate(json: JSONObject): JSONObject {
        var version = json.optInt("version", 1)
        // Each future format change adds a step here, e.g.
        // if (version == 1) { ...rewrite json...; version = 2 }
        if (version < 1) version = 1
        return json.put("version", version)
    }
}

/**
 * The layout the app shows for one [orientation], shared by the editor, the home preview and the
 * screen saver. Changes are visible everywhere at once and written to disk shortly after the last
 * one, in the background. Each orientation has its own file, so editing one never touches the other.
 */
class LayoutRepository(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val orientation: Orientation = Orientation.Portrait,
    /** What a screen holds before anything has been saved: the stock layout for the first, nothing for an added one. */
    private val default: () -> Layout = { DefaultLayout.create(orientation) },
) {
    private val state = MutableStateFlow(load())
    val layout: StateFlow<Layout> = state.asStateFlow()
    private var pendingSave: Job? = null

    fun update(layout: Layout) {
        if (layout == state.value) return
        state.value = layout
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(SAVE_DELAY_MS)
            withContext(io) { write(layout) }
        }
    }

    fun reset() = update(default())

    /** Forgets this screen for good: a save still waiting is dropped and the file removed. */
    fun discard() {
        pendingSave?.cancel()
        file.delete()
    }

    private fun load(): Layout {
        if (!file.exists()) return default()
        return try {
            tidied(LayoutCodec.decode(file.readText()), orientation)
        } catch (e: Exception) {
            // Keep the unreadable file for inspection rather than overwriting it on the next save.
            Log.w(TAG, "Saved layout could not be read; starting from the default", e)
            file.renameTo(File(file.parentFile, file.name + ".unreadable"))
            default()
        }
    }

    private fun write(layout: Layout) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(LayoutCodec.encode(layout).toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.w(TAG, "Layout could not be saved", e)
        }
    }

    companion object {
        private const val TAG = "LayoutRepository"
        private const val SAVE_DELAY_MS = 300L

        /** A layout whose widgets overlap (one written by hand, or by a version before overlaps were prevented) is tidied. */
        fun tidied(layout: Layout, orientation: Orientation = Orientation.Portrait): Layout {
            val boxes = Packer.tidy(layout.items.filter { it.visible }.map { Placed(it.id, it.box) }, orientation)
            return layout.withBoxes(boxes)
        }
    }
}
