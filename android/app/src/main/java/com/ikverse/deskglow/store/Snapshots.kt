package com.ikverse.deskglow.store

import androidx.core.util.AtomicFile
import com.ikverse.deskglow.model.Layout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Every screen of both layouts as they were at one moment, under a name. Each list is in screen order and never empty. */
data class Snapshot(val id: String, val name: String, val created: Long, val portrait: List<Layout>, val landscape: List<Layout>)

/**
 * A snapshot as JSON: the same text is kept in the app and written to an exported backup file. The
 * layouts inside use [LayoutCodec], so they are upgraded by the same migration steps.
 */
object SnapshotCodec {
    /** Version 2 keeps a list of screens per orientation; version 1 held a single layout, which reads as one screen. */
    const val VERSION = 2
    private const val KIND = "deskglow-backup"

    fun encode(snapshot: Snapshot): String = JSONObject()
        .put("kind", KIND)
        .put("version", VERSION)
        .put("id", snapshot.id)
        .put("name", snapshot.name)
        .put("created", snapshot.created)
        .put("portrait", pages(snapshot.portrait))
        .put("landscape", pages(snapshot.landscape))
        .toString(2)

    private fun pages(layouts: List<Layout>) = JSONArray().also { array -> layouts.forEach { array.put(JSONObject(LayoutCodec.encode(it))) } }

    private fun readPages(json: JSONObject, key: String): List<Layout> {
        val layouts = when (val value = json.get(key)) {
            is JSONArray -> (0 until value.length()).map { LayoutCodec.decode(value.getJSONObject(it).toString()) }
            else -> listOf(LayoutCodec.decode(json.getJSONObject(key).toString()))
        }
        require(layouts.isNotEmpty()) { "A backup needs at least one screen" }
        return layouts.take(MAX_PAGES)
    }

    /** Throws on text that is not a Deskglow backup. */
    fun decode(text: String): Snapshot {
        val json = JSONObject(text)
        require(json.optString("kind") == KIND) { "Not a Deskglow backup" }
        return Snapshot(
            id = json.getString("id"),
            name = json.getString("name"),
            created = json.getLong("created"),
            portrait = readPages(json, "portrait"),
            landscape = readPages(json, "landscape"),
        )
    }
}

/** The named snapshots kept on the phone, newest first. Each is its own file in [dir]. */
class SnapshotRepository(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val state = MutableStateFlow(load())
    val snapshots: StateFlow<List<Snapshot>> = state.asStateFlow()

    fun save(name: String, portrait: List<Layout>, landscape: List<Layout>): Snapshot {
        val created = now()
        val snapshot = Snapshot(UUID.randomUUID().toString(), name.trim().ifBlank { proposeName(created) }, created, portrait, landscape)
        add(snapshot)
        return snapshot
    }

    /** Adds a backup file's snapshot as a new entry, so importing twice never overwrites anything. Throws if the text is not a backup. */
    fun import(text: String): Snapshot {
        val snapshot = SnapshotCodec.decode(text).copy(id = UUID.randomUUID().toString())
        add(snapshot)
        return snapshot
    }

    fun rename(id: String, name: String) {
        val current = state.value.firstOrNull { it.id == id } ?: return
        val renamed = current.copy(name = name.trim().ifBlank { current.name })
        write(renamed)
        publish(state.value.map { if (it.id == id) renamed else it })
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        publish(state.value.filter { it.id != id })
    }

    private fun add(snapshot: Snapshot) {
        write(snapshot)
        publish(state.value + snapshot)
    }

    private fun publish(list: List<Snapshot>) {
        state.value = list.sortedByDescending { it.created }
    }

    private fun load(): List<Snapshot> =
        (dir.listFiles { file -> file.extension == "json" } ?: emptyArray())
            // A file that cannot be read is left alone on disk and left out of the list.
            .mapNotNull { file -> runCatching { SnapshotCodec.decode(file.readText()) }.getOrNull() }
            .sortedByDescending { it.created }

    private fun write(snapshot: Snapshot) {
        dir.mkdirs()
        val atomic = AtomicFile(File(dir, "${snapshot.id}.json"))
        val out = atomic.startWrite()
        try {
            out.write(SnapshotCodec.encode(snapshot).toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    companion object {
        private val format = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

        /** The name offered when saving, e.g. "Layout – 8 Oct, 14:32". */
        fun proposeName(at: LocalDateTime): String = "Layout – ${format.format(at)}"
        fun proposeName(millis: Long): String = proposeName(local(millis))
        fun dateLabel(millis: Long): String = format.format(local(millis))

        private fun local(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    }
}
