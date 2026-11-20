package com.ikverse.deskglow.update

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkDownloaderTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val http = FakeUpdateHttp()
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val updatesDir by lazy { temp.newFolder("updates") }

    private fun release(size: Long = bytes.size.toLong(), version: AppVersion = AppVersion(0, 2, 0)) =
        Release(version, "", "https://github.com/a.apk", size)

    private fun downloader(dir: File = updatesDir) = ApkDownloader(http, dir)

    @Test
    fun `a complete download is saved under its real name, byte for byte, and progress reaches the full size`() = runTest {
        http.answer(200, bytes)
        val seen = ArrayList<Long>()
        val file = downloader().download(release()) { seen += it }
        assertEquals("deskglow-0.2.0.apk", file.name)
        assertContentEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
        assertEquals(null, http.asked.single().accept)
    }

    @Test
    fun `a download that ends early leaves nothing behind and says how far it got`() = runTest {
        http.answer(200, bytes.copyOf(50_000))
        val e = assertFailsWith<UpdateException> { downloader().download(release()) {} }
        assertTrue("stopped early" in e.message!!, e.message)
        assertTrue("50000" in e.message!!, e.message)
        assertTrue(updatesDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a download larger than the release says is stopped and removed`() = runTest {
        http.answer(200, bytes)
        val e = assertFailsWith<UpdateException> { downloader().download(release(size = 100_000)) {} }
        assertTrue("larger" in e.message!!, e.message)
        assertTrue(updatesDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a connection that breaks halfway is an interrupted download, with nothing left behind`() = runTest {
        http.breakAfter(bytes, 70_000)
        val e = assertFailsWith<UpdateException> { downloader().download(release()) {} }
        assertTrue("interrupted" in e.message!!, e.message)
        assertTrue(updatesDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `no network is an interrupted download`() = runTest {
        http.offline()
        val e = assertFailsWith<UpdateException> { downloader().download(release()) {} }
        assertTrue("interrupted" in e.message!!, e.message)
    }

    @Test
    fun `an error status is reported with its code and saves nothing`() = runTest {
        http.answer(404, "gone")
        val e = assertFailsWith<UpdateException> { downloader().download(release()) {} }
        assertTrue("404" in e.message!!, e.message)
        assertTrue(updatesDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a size of zero, or an absurd one, is refused before any request is made`() = runTest {
        assertFailsWith<UpdateException> { downloader().download(release(size = 0)) {} }
        assertFailsWith<UpdateException> { downloader().download(release(size = ApkDownloader.MAX_BYTES + 1)) {} }
        assertTrue(http.asked.isEmpty())
    }

    @Test
    fun `an older download is cleared when a new one starts`() = runTest {
        val old = File(updatesDir, "deskglow-0.1.5.apk").also { it.writeBytes(byteArrayOf(1)) }
        http.answer(200, bytes)
        downloader().download(release()) {}
        assertFalse(old.exists())
    }

    @Test
    fun `clear removes what was downloaded`() {
        File(updatesDir, "deskglow-0.2.0.apk").writeBytes(byteArrayOf(1))
        File(updatesDir, "deskglow-0.2.0.apk.part").writeBytes(byteArrayOf(1))
        downloader().clear()
        assertTrue(updatesDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `clear on a folder that does not exist is fine`() {
        downloader(File(temp.root, "never-made")).clear()
    }
}
