package com.ikverse.deskglow.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** A published release that can be installed over this app. */
data class Release(val version: AppVersion, val notes: String, val apkUrl: String, val apkBytes: Long)

sealed interface CheckResult {
    /** The newest release is this version or older. */
    data object UpToDate : CheckResult
    data class Newer(val release: Release) : CheckResult
    data class Failed(val message: String) : CheckResult
}

/** Where the app asks whether a newer version exists. Behind an interface so the rest can be tried without a network. */
interface UpdateSource {
    suspend fun check(installed: AppVersion): CheckResult
}

/**
 * Reads the latest release of the app's own GitHub repository. Draft and pre-release builds are not offered
 * (GitHub's "latest" never returns them), and a download is only ever followed to GitHub's own hosts.
 */
class UpdateChecker(
    private val http: UpdateHttp,
    private val latestUrl: String = LATEST_URL,
    private val trustedHost: (URI) -> Boolean = ::isGitHubHost,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateSource {

    override suspend fun check(installed: AppVersion): CheckResult = withContext(io) {
        try {
            http.get(latestUrl, accept = "application/vnd.github+json") { code, body ->
                when {
                    code == 404 -> CheckResult.Failed("No release of Deskglow has been published yet.")
                    code == 403 || code == 429 -> CheckResult.Failed("GitHub is limiting requests from this network. Try again in an hour.")
                    code !in 200..299 -> CheckResult.Failed("GitHub answered with error $code. Try again later.")
                    else -> read(body.readBytes().decodeToString(), installed)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            CheckResult.Failed("Could not reach GitHub. Check the connection and try again.")
        }
    }

    private fun read(body: String, installed: AppVersion): CheckResult {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return CheckResult.Failed("GitHub's answer could not be read.")
        }
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return CheckResult.UpToDate
        val tag = json.optString("tag_name")
        val version = AppVersion.parse(tag) ?: return CheckResult.Failed("The newest release is named \"$tag\", which is not a version number, so it was skipped.")
        if (version <= installed) return CheckResult.UpToDate

        val assets = json.optJSONArray("assets")
        val apks = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }.filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        val apk = apks.firstOrNull { it.optString("name").startsWith("deskglow", ignoreCase = true) } ?: apks.firstOrNull()
            ?: return CheckResult.Failed("Version $version is published but has no app file attached yet. Try again later.")

        val url = parseUrl(apk.optString("browser_download_url"))
        if (url == null || !trustedHost(url)) return CheckResult.Failed("The download for version $version points outside GitHub, so it was refused.")
        val size = apk.optLong("size", -1L)
        if (size <= 0L) return CheckResult.Failed("Version $version has an app file of unknown size, so it was refused.")
        return CheckResult.Newer(Release(version, json.optString("body").trim(), url.toString(), size))
    }

    private fun parseUrl(text: String): URI? = try {
        URI(text).takeIf { it.isAbsolute && it.host != null }
    } catch (_: URISyntaxException) {
        null
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/ikverse/Deskglow/releases/latest"

        /** HTTPS, and a host GitHub serves release files from. */
        fun isGitHubHost(url: URI): Boolean {
            val host = url.host?.lowercase() ?: return false
            return url.scheme.equals("https", ignoreCase = true) &&
                (host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"))
        }
    }
}
