package com.ikverse.deskglow.store

import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.WidgetItem
import com.ikverse.deskglow.widgets.ClockWidget
import com.ikverse.deskglow.widgets.Common
import com.ikverse.deskglow.widgets.DefaultLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.test.assertFailsWith

class LayoutCodecTest {
    @Test
    fun `the default layout survives a save and a load unchanged`() {
        val layout = DefaultLayout.create()
        assertEquals(layout, LayoutCodec.decode(LayoutCodec.encode(layout)))
    }

    @Test
    fun `saved files carry the format version`() {
        assertEquals(LayoutCodec.VERSION, JSONObject(LayoutCodec.encode(DefaultLayout.create())).getInt("version"))
    }

    @Test
    fun `a widget or setting from a newer version is carried along untouched`() {
        val text = """{"version":1,"items":[
            {"id":"w1","type":"crypto","x":0,"y":0,"w":100,"h":40,"visible":true,"settings":{"symbol":"BTCUSDT","color":"#FFFFFF"}},
            {"id":"w2","type":"clock","x":0,"y":60,"w":100,"h":40,"settings":{"style":"dots","futureOption":42}}
        ]}"""
        val layout = LayoutCodec.decode(text)
        assertEquals("crypto", layout.items[0].type)
        assertEquals("BTCUSDT", layout.items[0].settings.values["symbol"])
        assertEquals(42, layout.items[1].settings.values["futureOption"])
        val again = LayoutCodec.decode(LayoutCodec.encode(layout))
        assertEquals(layout, again)
    }

    @Test
    fun `a file with no version is read as version 1, and missing settings fall back to defaults`() {
        val layout = LayoutCodec.decode("""{"items":[{"id":"w1","type":"clock","x":4,"y":8,"w":100,"h":40}]}""")
        val item = layout.items.single()
        assertEquals(Box(4, 8, 100, 40), item.box)
        val settings = item.settings.withDefaults(ClockWidget.defaults)
        assertEquals("squared", settings[ClockWidget.STYLE])
        assertEquals(100, settings[Common.OPACITY])
    }

    @Test
    fun `a file that is not a layout is rejected rather than half read`() {
        assertFailsWith<Exception> { LayoutCodec.decode("not json") }
        assertFailsWith<Exception> { LayoutCodec.decode("""{"version":1}""") }
    }

    @Test
    fun `colours are stored as readable hex and read back exactly`() {
        val key = ColourKey("c", 0)
        val settings = Settings().with(key, 0xFF44B98A.toInt())
        assertEquals("#44B98A", settings.values["c"])
        assertEquals(0xFF44B98A.toInt(), settings[key])
        val item = WidgetItem("w1", "clock", Box(0, 0, 10, 10), true, settings)
        assertEquals(0xFF44B98A.toInt(), LayoutCodec.decode(LayoutCodec.encode(Layout(listOf(item)))).items.single().settings[key])
    }

    @Test
    fun `new ids never repeat one in use`() {
        val layout = DefaultLayout.create()
        assertEquals("w12", layout.nextId())
        assertFalse(layout.items.any { it.id == layout.nextId() })
    }
}
