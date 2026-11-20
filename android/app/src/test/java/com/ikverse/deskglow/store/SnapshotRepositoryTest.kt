package com.ikverse.deskglow.store

import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.widgets.DefaultLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import kotlin.test.assertFailsWith

class SnapshotRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val portrait = listOf(DefaultLayout.create(Orientation.Portrait))
    private val landscape = listOf(DefaultLayout.create(Orientation.Landscape))

    @Test
    fun `a snapshot survives encoding and keeps both layouts`() {
        val snapshot = Snapshot("a", "Night", 5L, portrait, landscape)
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `a snapshot keeps every screen in order`() {
        val extra = Layout(emptyList())
        val snapshot = Snapshot("a", "Night", 5L, portrait + extra, landscape + extra + extra)
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        assertEquals(3, decoded.landscape.size)
    }

    @Test
    fun `a version 1 backup with one layout per orientation reads as one screen each`() {
        val v1 = JSONObject()
            .put("kind", "deskglow-backup").put("version", 1).put("id", "x").put("name", "Old").put("created", 1L)
            .put("portrait", JSONObject(LayoutCodec.encode(portrait.single())))
            .put("landscape", JSONObject(LayoutCodec.encode(landscape.single())))
        val decoded = SnapshotCodec.decode(v1.toString())
        assertEquals(portrait, decoded.portrait)
        assertEquals(landscape, decoded.landscape)
    }

    @Test
    fun `saved snapshots are still there when the app starts again, newest first`() {
        var time = 100L
        val dir = temp.newFolder("snapshots")
        val repo = SnapshotRepository(dir) { time }
        repo.save("First", portrait, landscape)
        time = 200L
        repo.save("Second", portrait, landscape)
        assertEquals(listOf("Second", "First"), SnapshotRepository(dir).snapshots.value.map { it.name })
    }

    @Test
    fun `rename and delete change the kept files`() {
        val dir = temp.newFolder("snapshots")
        val repo = SnapshotRepository(dir)
        val saved = repo.save("Old", portrait, landscape)
        repo.rename(saved.id, "New")
        assertEquals("New", SnapshotRepository(dir).snapshots.value.single().name)
        repo.delete(saved.id)
        assertEquals(0, SnapshotRepository(dir).snapshots.value.size)
    }

    @Test
    fun `importing an exported backup adds a copy, and other files are rejected`() {
        val repo = SnapshotRepository(temp.newFolder("snapshots"))
        val saved = repo.save("Desk", portrait, landscape)
        val copy = repo.import(SnapshotCodec.encode(saved))
        assertEquals(2, repo.snapshots.value.size)
        assertEquals(saved.portrait, copy.portrait)
        assertFailsWith<Exception> { repo.import("""{"version":1,"items":[]}""") }
        assertFailsWith<Exception> { repo.import("nonsense") }
    }

    @Test
    fun `only the five newest snapshots made before a restore are kept, and the owner's own are untouched`() {
        var time = 0L
        val repo = SnapshotRepository(temp.newFolder("snapshots")) { time }
        repo.save("Mine", portrait, landscape)
        repeat(8) { time = 10L + it; repo.save("${SnapshotRepository.BEFORE_RESTORE} – $it", portrait, landscape) }
        repo.pruneBeforeRestore()
        val names = repo.snapshots.value.map { it.name }
        assertEquals(6, names.size)
        assertEquals(true, "Mine" in names)
        assertEquals(true, "${SnapshotRepository.BEFORE_RESTORE} – 7" in names && "${SnapshotRepository.BEFORE_RESTORE} – 2" !in names)
    }

    @Test
    fun `read in the background, the kept snapshots arrive, and one saved meanwhile is not lost`() {
        val dir = temp.newFolder("snapshots")
        SnapshotRepository(dir).save("Kept", portrait, landscape)
        val repo = SnapshotRepository(dir, loadInBackground = true)
        repo.save("New", portrait, landscape)
        val deadline = System.currentTimeMillis() + 5_000
        while (repo.snapshots.value.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(setOf("Kept", "New"), repo.snapshots.value.map { it.name }.toSet())
    }

    @Test
    fun `an unreadable file is skipped rather than breaking the list`() {
        val dir = temp.newFolder("snapshots")
        SnapshotRepository(dir).save("Good", portrait, landscape)
        dir.resolve("broken.json").writeText("{")
        assertEquals(listOf("Good"), SnapshotRepository(dir).snapshots.value.map { it.name })
    }

    @Test
    fun `the proposed name carries the date and time`() {
        assertEquals("Layout – 8 Oct, 14:32", SnapshotRepository.proposeName(LocalDateTime.of(2026, 10, 8, 14, 32)))
    }
}
