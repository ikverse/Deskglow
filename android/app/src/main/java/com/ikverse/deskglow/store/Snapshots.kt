package com.ikverse.deskglow.store

import androidx.core.util.AtomicFile
import com.ikverse.deskglow.model.Layout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Both layouts as they were at one moment, under a name. */
data class Snapshot(val id: String, val name: String, val created: Long, val portrait: Layout, val landscape: Layout)

/**
 * A snapshot as JSON: the same text is kept in the app and written to an exported backup file. The
 * layouts inside use [LayoutCodec], so they are upgraded by the same migration steps.
 */
object SnapshotCodec {
    const val VERSION = 1
    private const val KIND = "deskglow-backup"

    fun encode(snapshot: Snapshot): String = JSONObject()
        .put("kind", KIND)
        .put("version", VERSION)
        .put("id", snapshot.id)
        .put("name", snapshot.name)
        .put("created", snapshot.created)
        .put("portrait", JSONObject(LayoutCodec.encode(snapshot.portrait)))
        .put("landscape", JSONObject(LayoutCodec.encode(snapshot.landscape)))
        .toString(2)

    /** Throws on text that is not a Deskglow backup. */
    fun decode(text: String): Snapshot {
        val json = JSONObject(text)
        require(json.optString("kind") == KIND) { "Not a Deskglow backup" }
        return Snapshot(
            id = json.getString("id"),
            name = json.getString("name"),
            created = json.getLong("created"),
            portrait = LayoutCodec.decode(json.getJSONObject("portrait").toString()),
            landscape = LayoutCodec.decode(json.getJSONObject("landscape").toString()),
        )
    }
}

/** The named snapshots kept on the phone, newest first. Each is its own file in [dir]. */
class SnapshotRepository(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val state = MutableStateFlow(load())
    val snapshots: StateFlow<List<Snapshot>> = state.asStateFlow()

    fun save(name: String, portrait: Layout, landscape: Layout): Snapshot {
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
