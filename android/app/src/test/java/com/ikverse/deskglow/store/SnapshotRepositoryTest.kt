package com.ikverse.deskglow.store

import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.widgets.DefaultLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import kotlin.test.assertFailsWith

class SnapshotRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val portrait = DefaultLayout.create(Orientation.Portrait)
    private val landscape = DefaultLayout.create(Orientation.Landscape)

    @Test
    fun `a snapshot survives encoding and keeps both layouts`() {
        val snapshot = Snapshot("a", "Night", 5L, portrait, landscape)
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
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
