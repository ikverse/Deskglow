package com.ikverse.deskglow.widgets

import androidx.compose.foundation.layout.Arrangement
import com.ikverse.deskglow.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSettingsTest {
    @Test
    fun `a clock saved with the old 24-hour switch keeps its hours, and every other keeps 12-hour time`() {
        val on = Settings(mapOf("h24" to true))
        assertEquals("24", ClockWidget.resolve(on)[Common.TIME_FORMAT])
        val off = Settings(mapOf("h24" to false))
        assertEquals("12", ClockWidget.resolve(off)[Common.TIME_FORMAT])
        assertEquals("12", ClockWidget.resolve(Settings())[Common.TIME_FORMAT])
        // A choice made since is never overwritten by the old switch.
        val chosen = Settings(mapOf("h24" to true, "timeFormat" to "phone"))
        assertEquals("phone", ClockWidget.resolve(chosen)[Common.TIME_FORMAT])
    }

    @Test
    fun `widgets that were always centred stay centred, whatever they saved before alignment existed`() {
        assertEquals("center", ClockWidget.resolve(Settings())[Common.ALIGN])
        assertEquals("center", DateWidget.resolve(Settings())[Common.ALIGN])
        assertEquals("center", NotificationsWidget.resolve(Settings())[Common.ALIGN])
        assertEquals("left", ClockWidget.resolve(Settings(mapOf("align" to "left")))[Common.ALIGN])
        // Widgets that already had alignment still start at the left.
        assertEquals("left", WeatherWidget.resolve(Settings())[Common.ALIGN])
    }

    @Test
    fun `alignment turns into a place in spare room and an arrangement`() {
        assertEquals(0f, alignFraction("left"), 0f)
        assertEquals(0.5f, alignFraction("center"), 0f)
        assertEquals(1f, alignFraction("right"), 0f)
        assertEquals(Arrangement.Start, arrangementOf("left"))
        assertEquals(Arrangement.Center, arrangementOf("center"))
        assertEquals(Arrangement.End, arrangementOf("right"))
    }

    @Test
    fun `the time format is offered the same way on every widget that shows a time`() {
        for (widget in listOf(ClockWidget, AlarmWidget, EventWidget, PrayerWidget)) {
            val fields = widget.fields(widget.resolve(Settings())).filterIsInstance<ChoiceField>().filter { it.label == "Time format" }
            assertEquals("${widget.id} has one time format", 1, fields.size)
            assertEquals(listOf("phone", "12", "24"), fields.single().options.map { it.first })
        }
        // The F1 schedule keeps the key it always stored its choice under.
        val schedule = F1ScheduleWidget.fields(F1ScheduleWidget.defaults).filterIsInstance<ChoiceField>().single { it.label == "Time format" }
        assertEquals("clock", schedule.key.name)
    }

    @Test
    fun `no widget lists one setting twice, and every on-off chip has a name`() {
        for (widget in Widgets.all) {
            val fields = widget.fields(widget.resolve(Settings()))
            val keys = fields.flatMap { field ->
                when (field) {
                    is ToggleField -> listOf(field.key.name)
                    is ShowField -> field.items.map { it.first.name }
                    is ChoiceField -> listOf(field.key.name)
                    is LayoutField -> listOf(field.key.name)
                    is SliderField -> listOf(field.key.name)
                    is ColourField -> listOf(field.key.name)
                    is StyleField -> listOf(field.key.name)
                }
            }
            assertEquals("${widget.id}: $keys", keys.size, keys.toSet().size)
            fields.filterIsInstance<ShowField>().forEach { show -> assertTrue("${widget.id} chips", show.items.size >= 2 && show.items.all { it.second.isNotBlank() }) }
        }
    }

    @Test
    fun `the second colour has the same name on every widget`() {
        val names = Widgets.all.flatMap { it.fields(it.resolve(Settings())) }.filterIsInstance<ColourField>().map { it.label }.toSet()
        assertEquals(setOf("Colour", "Accent colour"), names)
    }

    @Test
    fun `a clock saved with the old seconds switch keeps showing digits, and one without it shows none`() {
        assertEquals("digits", ClockWidget.resolve(Settings(mapOf("seconds" to true)))[ClockWidget.SECONDS_MODE])
        assertEquals("none", ClockWidget.resolve(Settings(mapOf("seconds" to false)))[ClockWidget.SECONDS_MODE])
        assertEquals("none", ClockWidget.resolve(Settings())[ClockWidget.SECONDS_MODE])
        assertEquals("line", ClockWidget.resolve(Settings(mapOf("seconds" to true, "secondsMode" to "line")))[ClockWidget.SECONDS_MODE])
    }
}
