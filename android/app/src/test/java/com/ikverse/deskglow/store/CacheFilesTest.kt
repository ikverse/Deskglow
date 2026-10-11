package com.ikverse.deskglow.store

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The big answers live in files; the preferences keep only small settings. */
@RunWith(RobolectricTestRunner::class)
class CacheFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("deskglow", Context.MODE_PRIVATE)

    @Test
    fun `text written is read back, and null removes it`() {
        val files = CacheFiles(File(context.cacheDir, "cache-files-test"))
        assertNull(files.read("a.json"))
        files.write("a.json", """{"a":1}""")
        assertEquals("""{"a":1}""", files.read("a.json"))
        files.write("a.json", null)
        assertNull(files.read("a.json"))
    }

    @Test
    fun `a cache an older version kept in the preferences is moved to a file once`() {
        prefs.edit { putString("weather_cache", """{"temp":25}""") }
        val first = AppPrefs(context)
        assertEquals("""{"temp":25}""", first.weatherCache)
        assertFalse(prefs.contains("weather_cache"))
        assertEquals("""{"temp":25}""", AppPrefs(context).weatherCache)
    }

    @Test
    fun `a cache saved now stays out of the preferences`() {
        val prefsObject = AppPrefs(context)
        prefsObject.f1Cache = """{"calendar":[]}"""
        assertFalse(prefs.contains("f1_cache"))
        assertEquals("""{"calendar":[]}""", AppPrefs(context).f1Cache)
    }
}
