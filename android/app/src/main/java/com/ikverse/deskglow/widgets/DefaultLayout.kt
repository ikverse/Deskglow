package com.ikverse.deskglow.widgets

import com.ikverse.deskglow.model.Box
import com.ikverse.deskglow.model.Layout
import com.ikverse.deskglow.model.Orientation
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.WidgetItem

/**
 * The layout a new install starts with (and Reset goes back to). Portrait is the mockup's, after the
 * owner's reference screen. Landscape is the same widgets for a dock: the time and the day down the
 * left, the ring in the middle with what is playing under it, and the battery numbers down the right.
 */
object DefaultLayout {
    fun create(orientation: Orientation = Orientation.Portrait): Layout = when (orientation) {
        Orientation.Portrait -> portrait()
        Orientation.Landscape -> landscape()
    }

    /** Hands out ids in order, so both layouts number their widgets the same way. */
    private class Items {
        private var n = 0

        fun add(type: WidgetType, x: Int, y: Int, w: Int, h: Int, extra: (Settings) -> Settings = { it }) =
            WidgetItem("w${++n}", type.id, Box(x, y, w, h), true, extra(type.defaults))
    }

    private fun portrait(): Layout {
        val i = Items()
        return Layout(
            listOf(
                i.add(ClockWidget, 96, 56, 220, 64),
                i.add(DateWidget, 124, 136, 164, 24),
                i.add(NotificationsWidget, 124, 172, 164, 28),
                i.add(RingWidget, 74, 252, 264, 264),
                i.add(StatWidget, 20, 524, 112, 56) { it.with(StatWidget.METRIC, "temp").with(Common.ALIGN, "left") },
                i.add(StatWidget, 148, 524, 116, 56) { it.with(StatWidget.METRIC, "voltage").with(Common.ALIGN, "center") },
                i.add(StatWidget, 280, 524, 112, 56) { it.with(StatWidget.METRIC, "time").with(Common.ALIGN, "right") },
                i.add(WeatherWidget, 20, 612, 176, 64),
                i.add(EventWidget, 216, 612, 176, 64),
                i.add(MediaWidget, 20, 696, 372, 52),
                i.add(StatWidget, 132, 764, 148, 56) {
                    it.with(StatWidget.METRIC, "power").with(Common.ALIGN, "center")
                        .with(StatWidget.SHOW_LABEL, false).with(StatWidget.ACCENT, 0xFFFFFFFF.toInt())
                },
            ),
        )
    }

    /** 848 x 412, 24-unit side margins, everything on the 4-unit grid. */
    private fun landscape(): Layout {
        val i = Items()
        return Layout(
            listOf(
                i.add(ClockWidget, 24, 40, 220, 64),
                i.add(DateWidget, 24, 116, 164, 24),
                i.add(NotificationsWidget, 24, 152, 164, 28),
                i.add(RingWidget, 292, 40, 264, 264),
                i.add(StatWidget, 712, 40, 112, 56) { it.with(StatWidget.METRIC, "temp").with(Common.ALIGN, "right") },
                i.add(StatWidget, 708, 112, 116, 56) { it.with(StatWidget.METRIC, "voltage").with(Common.ALIGN, "right") },
                i.add(StatWidget, 712, 184, 112, 56) { it.with(StatWidget.METRIC, "time").with(Common.ALIGN, "right") },
                i.add(WeatherWidget, 24, 220, 176, 64),
                i.add(EventWidget, 24, 296, 176, 64),
                i.add(MediaWidget, 240, 336, 372, 52),
                i.add(StatWidget, 676, 256, 148, 56) {
                    it.with(StatWidget.METRIC, "power").with(Common.ALIGN, "right")
                        .with(StatWidget.SHOW_LABEL, false).with(StatWidget.ACCENT, 0xFFFFFFFF.toInt())
                },
            ),
        )
    }
}
