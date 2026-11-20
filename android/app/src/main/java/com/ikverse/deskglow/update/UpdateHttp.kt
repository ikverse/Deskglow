package com.ikverse.deskglow.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The updater's way onto the internet. Unlike the app's [com.ikverse.deskglow.data.Http] it hands over the status code
 * (GitHub's 404 and 403 each mean something the user should be told) and streams the body (an app file is too big to
 * hold in memory). Swapped for a fake in tests.
 */
interface UpdateHttp {
    /** Fetches [url] and gives [read] the status code and the body; any network failure is an [IOException]. */
    @Throws(IOException::class)
    fun <T> get(url: String, accept: String?, read: (code: Int, body: InputStream) -> T): T
}

/** Android's built-in client. It follows GitHub's redirect from the release page to the file's storage host. */
object UrlConnectionUpdateHttp : UpdateHttp {
    const val USER_AGENT = "Deskglow-Android"

    override fun <T> get(url: String, accept: String?, read: (code: Int, body: InputStream) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (accept != null) connection.setRequestProperty("Accept", accept)
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream) ?: ByteArrayInputStream(ByteArray(0))
            return body.use { read(code, it) }
        } finally {
            connection.disconnect()
        }
    }
}
