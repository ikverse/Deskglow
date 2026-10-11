package com.ikverse.deskglow.store

import android.util.Log
import androidx.core.util.AtomicFile
import java.io.File

/**
 * The larger answers the app keeps between runs (weather, prayer times, the F1 data), one file each in [dir].
 * Settings stay in SharedPreferences, which rewrites its whole file on every change and reads it on the main
 * thread at start; these are tens of kilobytes and change every half hour, so they live here instead.
 * Each write goes through an [AtomicFile], so a write cut short never leaves half an answer behind.
 * Reads and writes happen on the calling thread: the repositories that use them already run on the IO dispatcher.
 */
class CacheFiles(private val dir: File) {
    /** The text saved as [name], or null if there is none (or it could not be read). */
    fun read(name: String): String? {
        val file = File(dir, name)
        if (!file.exists()) return null
        return runCatching { AtomicFile(file).readFully().decodeToString() }
            .onFailure { Log.w(TAG, "$name could not be read", it) }
            .getOrNull()
    }

    /** Saves [text] as [name]; null removes it. */
    fun write(name: String, text: String?) {
        val file = File(dir, name)
        if (text == null) {
            AtomicFile(file).delete()
            return
        }
        dir.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.w(TAG, "$name could not be saved", e)
        }
    }

    private companion object {
        const val TAG = "CacheFiles"
    }
}
