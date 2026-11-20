package com.ikverse.deskglow.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.store.Snapshot
import com.ikverse.deskglow.store.SnapshotCodec
import com.ikverse.deskglow.store.SnapshotRepository
import com.ikverse.deskglow.widgets.Common

/** Saves both layouts under a name, brings a saved pair back, and moves backups to and from files. */
@Composable
fun SnapshotsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshots by graph.snapshots.snapshots.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(SnapshotRepository.proposeName(System.currentTimeMillis())) }
    var message by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Snapshot?>(null) }
    var deleting by remember { mutableStateOf<Snapshot?>(null) }
    var restoring by remember { mutableStateOf<Snapshot?>(null) }
    // Kept by id across the app being recreated while the file picker is open, which would otherwise lose it.
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exporting?.let { id -> graph.snapshots.snapshots.value.firstOrNull { it.id == id } }
        exporting = null
        if (uri == null || snapshot == null) return@rememberLauncherForActivityResult
        message = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(SnapshotCodec.encode(snapshot).toByteArray()) }
            "Saved a backup file of “${snapshot.name}”."
        }.getOrElse { "The backup file could not be written." }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            "Added “${graph.snapshots.import(text).name}” to your saved layouts."
        }.getOrElse { "That file is not a Deskglow backup." }
    }

    ScreenFrame("Saved layouts", onBack) {
        Text(
            "Saves your portrait and landscape layouts together. Export a copy to a file to keep it outside the app.",
            fontSize = Type.Small,
            color = Palette.Muted,
        )
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Save now", {
                runCatching { graph.snapshots.save(name, graph.pagesOf(Orientation.Portrait), graph.pagesOf(Orientation.Landscape)) }
                    .onSuccess {
                        message = "Saved “${it.name}”."
                        name = SnapshotRepository.proposeName(System.currentTimeMillis())
                    }
                    .onFailure { message = COULD_NOT_SAVE }
            }, Modifier.weight(1f), kind = ButtonKind.Primary)
            AppButton("Import file", { importLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f))
        }
        message?.let { Text(it, fontSize = Type.Small, color = Palette.Select) }
        if (snapshots.isEmpty()) Text("Nothing saved yet.", color = Palette.Muted)
        for (snapshot in snapshots) {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(snapshot.portrait.first(), Orientation.Portrait, Modifier.width(30.dp))
                        Thumb(snapshot.landscape.first(), Orientation.Landscape, Modifier.width(60.dp))
                        Column(Modifier.weight(1f)) {
                            Text(snapshot.name, fontSize = Type.Body)
                            val date = SnapshotRepository.dateLabel(snapshot.created)
                            val screens = if (snapshot.portrait.size > 1 || snapshot.landscape.size > 1) {
                                " · ${snapshot.portrait.size} portrait, ${snapshot.landscape.size} landscape"
                            } else ""
                            Text(date + screens, fontSize = Type.Small, color = Palette.Muted)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip("Restore", { restoring = snapshot })
                        Chip("Rename", { renaming = snapshot })
                        Chip("Export", {
                            exporting = snapshot.id
                            exportLauncher.launch("deskglow-" + snapshot.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-') + ".json")
                        })
                        Spacer(Modifier.weight(1f))
                        IconAction(Glyph.Trash, "Delete ${snapshot.name}", { deleting = snapshot }, tint = Palette.Danger)
                    }
                }
            }
        }
    }

    restoring?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Restore “${snapshot.name}”?") },
            text = { Text("This replaces both of your current layouts. They are saved first as “Before restore”, so you can come back to them.") },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    val stamp = SnapshotRepository.proposeName(System.currentTimeMillis()).removePrefix("Layout – ")
                    // The current layouts are kept first; if they cannot be, nothing is replaced.
                    val kept = runCatching {
                        graph.snapshots.save("${SnapshotRepository.BEFORE_RESTORE} – $stamp", graph.pagesOf(Orientation.Portrait), graph.pagesOf(Orientation.Landscape))
                    }
                    if (kept.isFailure) {
                        message = COULD_NOT_SAVE
                        return@TextButton
                    }
                    graph.snapshots.pruneBeforeRestore()
                    graph.restorePages(snapshot.portrait, snapshot.landscape)
                    // A font picked from Google Fonts on another phone, or before a reinstall, is fetched again.
                    scope.launch { graph.fontLibrary.fetchMissing(fontIdsOf(snapshot.portrait + snapshot.landscape)) }
                    message = "Restored “${snapshot.name}”."
                }) { Text("Restore", color = Palette.Select) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } },
            containerColor = Palette.Sheet,
        )
    }
    renaming?.let { snapshot ->
        var text by remember(snapshot.id) { mutableStateOf(snapshot.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (runCatching { graph.snapshots.rename(snapshot.id, text) }.isFailure) message = COULD_NOT_SAVE
                    renaming = null
                }) { Text("Rename", color = Palette.Select) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            containerColor = Palette.Sheet,
        )
    }
    deleting?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${snapshot.name}”?") },
            text = { Text("This removes it from the app. A file you exported stays where you put it.") },
            confirmButton = {
                TextButton(onClick = {
                    graph.snapshots.delete(snapshot.id)
                    deleting = null
                }) { Text("Delete", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            containerColor = Palette.Sheet,
        )
    }
}

private const val COULD_NOT_SAVE = "That could not be saved. The phone may be out of space."

/** Every font named in [layouts]' widgets. */
private fun fontIdsOf(layouts: List<Layout>): Set<String> =
    layouts.flatMap { it.items }.flatMap { item -> item.settings.values.values.filterIsInstance<String>() }.filter { it.startsWith("g:") }.toSet()

/**
 * A snapshot's screen as boxes in the widgets' colours on black. A live drawing of every widget, twice per
 * snapshot, made this screen slower with each one kept; a few rectangles do not.
 */
@Composable
private fun Thumb(layout: Layout, orientation: Orientation, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Canvas(
        modifier
            .aspectRatio(orientation.width.toFloat() / orientation.height)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, Palette.Rule, shape),
    ) {
        val scale = size.width / orientation.width
        for (item in layout.items) {
            if (!item.visible) continue
            val box = item.box
            drawRoundRect(
                Color(item.settings[Common.COLOUR]).copy(alpha = 0.35f),
                Offset(box.x * scale, box.y * scale), Size(box.w * scale, box.h * scale),
                CornerRadius(2.dp.toPx()),
            )
        }
    }
}
