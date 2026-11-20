package com.ikverse.deskglow.update

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private val http = FakeUpdateHttp()
    private val installed = AppVersion(0, 1, 0)

    private fun checker(trusted: Boolean = true) = UpdateChecker(http, latestUrl = "https://api.example/latest", trustedHost = { trusted })

    private fun release(
        tag: String = "v0.2.0", body: String = "Notes.", draft: Boolean = false, prerelease: Boolean = false,
        assets: String = """[{"name":"Deskglow-0.2.0.apk","size":1234,"browser_download_url":"https://github.com/ikverse/Deskglow/releases/download/v0.2.0/Deskglow-0.2.0.apk"}]""",
    ) = """{"tag_name":"$tag","body":"$body","draft":$draft,"prerelease":$prerelease,"assets":$assets}"""

    private fun ok(body: String) = http.answer(200, body)

    @Test
    fun `a newer release with an app file is offered, with its notes and size`() = runTest {
        ok(release(body = "Adds the updater."))
        val r = assertIs<CheckResult.Newer>(checker().check(installed)).release
        assertEquals(AppVersion(0, 2, 0), r.version)
        assertEquals("Adds the updater.", r.notes)
        assertEquals(1234L, r.apkBytes)
        assertTrue(r.apkUrl.endsWith("Deskglow-0.2.0.apk"))
    }

    @Test
    fun `it asks GitHub the way GitHub asks to be asked`() = runTest {
        ok(release())
        checker().check(installed)
        assertEquals("https://api.example/latest", http.asked.single().url)
        assertEquals("application/vnd.github+json", http.asked.single().accept)
    }

    @Test
    fun `the same version, or an older one, is up to date`() = runTest {
        ok(release(tag = "v0.1.0"))
        assertEquals(CheckResult.UpToDate, checker().check(installed))
        ok(release(tag = "v0.0.9"))
        assertEquals(CheckResult.UpToDate, checker().check(installed))
    }

    @Test
    fun `the installed version carries a debug suffix and still compares`() = runTest {
        ok(release(tag = "v0.1.0"))
        assertEquals(CheckResult.UpToDate, checker().check(AppVersion.parse("0.1.0-debug")!!))
    }

    @Test
    fun `a draft or a pre-release is never offered`() = runTest {
        ok(release(draft = true))
        assertEquals(CheckResult.UpToDate, checker().check(installed))
        ok(release(prerelease = true))
        assertEquals(CheckResult.UpToDate, checker().check(installed))
    }

    @Test
    fun `a newer release with no app file attached says so instead of offering nothing`() = runTest {
        ok(release(assets = "[]"))
        val failed = assertIs<CheckResult.Failed>(checker().check(installed))
        assertTrue("no app file" in failed.message, failed.message)
        ok(release(assets = """[{"name":"notes.txt","size":10,"browser_download_url":"https://github.com/x/notes.txt"}]"""))
        assertIs<CheckResult.Failed>(checker().check(installed))
    }

    @Test
    fun `a tag that is not a version is skipped with a reason`() = runTest {
        ok(release(tag = "nightly"))
        val failed = assertIs<CheckResult.Failed>(checker().check(installed))
        assertTrue("nightly" in failed.message, failed.message)
    }

    @Test
    fun `a download that points outside GitHub is refused`() = runTest {
        ok(release())
        val failed = assertIs<CheckResult.Failed>(checker(trusted = false).check(installed))
        assertTrue("outside GitHub" in failed.message, failed.message)
    }

    @Test
    fun `a download link that is not a link is refused`() = runTest {
        ok(release(assets = """[{"name":"Deskglow-0.2.0.apk","size":5,"browser_download_url":"not a url"}]"""))
        assertIs<CheckResult.Failed>(checker().check(installed))
    }

    @Test
    fun `an app file of no size, or an unreadable size, is refused`() = runTest {
        ok(release(assets = """[{"name":"Deskglow-0.2.0.apk","size":0,"browser_download_url":"https://github.com/a/b.apk"}]"""))
        assertIs<CheckResult.Failed>(checker().check(installed))
        ok(release(assets = """[{"name":"Deskglow-0.2.0.apk","browser_download_url":"https://github.com/a/b.apk"}]"""))
        assertIs<CheckResult.Failed>(checker().check(installed))
    }

    @Test
    fun `the app file is the one named Deskglow, whatever order the assets come in`() = runTest {
        ok(
            release(
                assets = """[{"name":"first.apk","size":5,"browser_download_url":"https://github.com/a/first.apk"},
                    {"name":"Deskglow-0.2.0.apk","size":9,"browser_download_url":"https://github.com/a/Deskglow-0.2.0.apk"},
                    {"name":"last.apk","size":7,"browser_download_url":"https://github.com/a/last.apk"}]""",
            ),
        )
        val r = assertIs<CheckResult.Newer>(checker().check(installed)).release
        assertTrue(r.apkUrl.endsWith("Deskglow-0.2.0.apk"))
        assertEquals(9L, r.apkBytes)
    }

    @Test
    fun `with no asset named Deskglow, the first app file is used`() = runTest {
        ok(
            release(
                assets = """[{"name":"notes.txt","size":1,"browser_download_url":"https://github.com/a/notes.txt"},
                    {"name":"first.apk","size":5,"browser_download_url":"https://github.com/a/first.apk"},
                    {"name":"last.apk","size":7,"browser_download_url":"https://github.com/a/last.apk"}]""",
            ),
        )
        val r = assertIs<CheckResult.Newer>(checker().check(installed)).release
        assertTrue(r.apkUrl.endsWith("first.apk"))
    }

    @Test
    fun `GitHub's answers are turned into plain sentences`() = runTest {
        http.answer(404, "{}")
        assertTrue("No release" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
        http.answer(403, "{}")
        assertTrue("limiting" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
        http.answer(429, "{}")
        assertTrue("limiting" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
        http.answer(500, "{}")
        assertTrue("error 500" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
    }

    @Test
    fun `an answer that is not JSON is reported, not crashed on`() = runTest {
        ok("<html>not json</html>")
        assertTrue("could not be read" in assertIs<CheckResult.Failed>(checker().check(installed)).message)
    }

    @Test
    fun `no network is reported as such`() = runTest {
        http.offline()
        val failed = assertIs<CheckResult.Failed>(checker().check(installed))
        assertTrue("Could not reach GitHub" in failed.message, failed.message)
    }

    @Test
    fun `only https links on GitHub's own hosts are trusted`() {
        fun trusted(url: String) = UpdateChecker.isGitHubHost(URI(url))
        assertTrue(trusted("https://github.com/ikverse/Deskglow/releases/download/v1/a.apk"))
        assertTrue(trusted("https://objects.githubusercontent.com/x"))
        assertTrue(trusted("https://release-assets.githubusercontent.com/x"))
        assertTrue(trusted("HTTPS://GitHub.com/a.apk"))
        assertFalse(trusted("http://github.com/a.apk"))
        assertFalse(trusted("https://evil.example/a.apk"))
        assertFalse(trusted("https://github.com.evil.example/a.apk"))
        assertFalse(trusted("https://notgithub.com/a.apk"))
        assertFalse(trusted("https://githubusercontent.com.evil.example/a.apk"))
        // A host that merely ends with the same letters is somebody else's.
        assertFalse(trusted("https://evilgithubusercontent.com/a.apk"))
        assertFalse(trusted("https://evilgithub.com/a.apk"))
    }

    @Test
    fun `the repository it asks is this app's own`() {
        assertEquals("https://api.github.com/repos/ikverse/Deskglow/releases/latest", UpdateChecker.LATEST_URL)
    }
}
