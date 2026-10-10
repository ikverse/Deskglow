package com.ikverse.deskglow.store

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A saved F1 result is given back only when the way the feed is read has not changed since it was saved. */
@RunWith(RobolectricTestRunner::class)
class F1LiveResultPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a result saved by an older version is not given back, so it is fetched again`() {
        // As 0.4.0 left it: the result with no format beside it.
        context.getSharedPreferences("deskglow", Context.MODE_PRIVATE).edit { putString("f1_live_result", """{"name":"Qualifying"}""") }
        assertNull(AppPrefs(context).f1LiveResult)
    }

    @Test
    fun `a result saved now is given back`() {
        AppPrefs(context).f1LiveResult = """{"name":"Qualifying"}"""
        assertEquals("""{"name":"Qualifying"}""", AppPrefs(context).f1LiveResult)
    }
}
