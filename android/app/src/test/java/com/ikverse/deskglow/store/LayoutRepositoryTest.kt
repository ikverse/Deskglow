package com.ikverse.deskglow.store

import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.DefaultLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/** One layout per orientation: separate defaults, separate files, separate edits. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LayoutRepositoryTest {
    private val dir: File = Files.createTempDirectory("deskglow-layouts").toFile()
    private val portraitFile = File(dir, "layout.json")
    private val landscapeFile = File(dir, "layout-landscape.json")

    @Test
    fun `each orientation starts from its own default`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val portrait = LayoutRepository(portraitFile, scope, dispatcher)
        val landscape = LayoutRepository(landscapeFile, scope, dispatcher, Orientation.Landscape)
        assertEquals(DefaultLayout.create(Orientation.Portrait), portrait.layout.value)
        assertEquals(DefaultLayout.create(Orientation.Landscape), landscape.layout.value)
    }

    @Test
    fun `editing one leaves the other alone, and each is saved to its own file`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val portrait = LayoutRepository(portraitFile, scope, dispatcher)
        val landscape = LayoutRepository(landscapeFile, scope, dispatcher, Orientation.Landscape)

        val trimmed = Layout(DefaultLayout.create(Orientation.Landscape).items.drop(1))
        landscape.update(trimmed)
        advanceUntilIdle()

        assertEquals(trimmed, landscape.layout.value)
        assertEquals(DefaultLayout.create(Orientation.Portrait), portrait.layout.value)
        assertTrue(landscapeFile.exists())
        assertFalse("editing landscape must not write the portrait file", portraitFile.exists())
        assertEquals(trimmed, LayoutCodec.decode(landscapeFile.readText()))
    }

    @Test
    fun `reset goes back to that orientation's own default`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val landscape = LayoutRepository(landscapeFile, CoroutineScope(dispatcher), dispatcher, Orientation.Landscape)
        landscape.update(Layout(emptyList()))
        landscape.reset()
        assertEquals(DefaultLayout.create(Orientation.Landscape), landscape.layout.value)
    }

    @Test
    fun `a saved layout is tidied against its own canvas height`() = runTest {
        // 380 + 52 runs off a 412-unit-high landscape canvas, but sits comfortably on an 848-unit portrait one.
        val file = File(dir, "hangs-off.json")
        file.writeText(LayoutCodec.encode(Layout(listOf(WidgetItem("w1", "clock", Box(0, 380, 100, 52))))))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        assertEquals(360, LayoutRepository(file, scope, dispatcher, Orientation.Landscape).layout.value.items.single().box.y)
        assertEquals(380, LayoutRepository(file, scope, dispatcher, Orientation.Portrait).layout.value.items.single().box.y)
    }
}
