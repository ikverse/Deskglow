package com.ikverse.deskglow.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.AppGraph
import com.ikverse.deskglow.display.DisplayContent
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.store.Snapshot
import com.ikverse.deskglow.store.SnapshotCodec
import com.ikverse.deskglow.store.SnapshotRepository

/** Saves both layouts under a name, brings a saved pair back, and moves backups to and from files. */
@Composable
fun SnapshotsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshots by graph.snapshots.snapshots.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(SnapshotRepository.proposeName(System.currentTimeMillis())) }
    var message by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Snapshot?>(null) }
    var deleting by remember { mutableStateOf<Snapshot?>(null) }
    var restoring by remember { mutableStateOf<Snapshot?>(null) }
    var exporting by remember { mutableStateOf<Snapshot?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exporting
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
            fontSize = 13.sp,
            color = Palette.Muted,
        )
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val saved = graph.snapshots.save(name, graph.pagesOf(Orientation.Portrait), graph.pagesOf(Orientation.Landscape))
                message = "Saved “${saved.name}”."
                name = SnapshotRepository.proposeName(System.currentTimeMillis())
            }) { Text("Save current layouts", color = Palette.Select) }
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import from file", color = Palette.Select) }
        }
        message?.let { Text(it, fontSize = 13.sp, color = Palette.Accent) }
        Rule()
        if (snapshots.isEmpty()) Text("Nothing saved yet.", color = Palette.Muted)
        for (snapshot in snapshots) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(snapshot.portrait.first(), Orientation.Portrait, Modifier.width(30.dp))
                    Thumb(snapshot.landscape.first(), Orientation.Landscape, Modifier.width(60.dp))
                    Column(Modifier.weight(1f)) {
                        Text(snapshot.name, fontSize = 16.sp)
                        val date = SnapshotRepository.dateLabel(snapshot.created)
                        val screens = if (snapshot.portrait.size > 1 || snapshot.landscape.size > 1) {
                            " · ${snapshot.portrait.size} portrait, ${snapshot.landscape.size} landscape"
                        } else ""
                        Text(date + screens, fontSize = 13.sp, color = Palette.Muted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { restoring = snapshot }) { Text("Restore", color = Palette.Select) }
                    TextButton(onClick = { renaming = snapshot }) { Text("Rename", color = Palette.Select) }
                    TextButton(onClick = {
                        exporting = snapshot
                        exportLauncher.launch("deskglow-" + snapshot.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-') + ".json")
                    }) { Text("Export", color = Palette.Select) }
                    TextButton(onClick = { deleting = snapshot }) { Text("Delete", color = Palette.Danger) }
                }
                Rule()
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
                    graph.snapshots.save("Before restore – $stamp", graph.pagesOf(Orientation.Portrait), graph.pagesOf(Orientation.Landscape))
                    graph.restorePages(snapshot.portrait, snapshot.landscape)
                    message = "Restored “${snapshot.name}”."
                }) { Text("Restore", color = Palette.Select) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } },
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
                    graph.snapshots.rename(snapshot.id, text)
                    renaming = null
                }) { Text("Rename", color = Palette.Select) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
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
        )
    }
}

@Composable
private fun Thumb(layout: Layout, orientation: Orientation, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(orientation.width.toFloat() / orientation.height)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Palette.Rule, RoundedCornerShape(6.dp)),
    ) {
        DisplayContent(layout, burnIn = false, orientation = orientation)
    }
}
