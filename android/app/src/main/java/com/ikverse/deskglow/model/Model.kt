package com.ikverse.deskglow.model

import androidx.compose.runtime.Immutable

/**
 * Which way the phone is held. Each way has its own canvas, in units of the Note 9's screen in dp,
 * and its own saved layout. Other screens show the same canvas scaled to fit, so a layout looks the
 * same on every phone.
 */
enum class Orientation(val width: Int, val height: Int, val visibleY: IntRange) {
    /**
     * The phone upright. The editor's settings sheet lies over the bottom of the canvas (from about
     * 440 down), so new widgets prefer to land above it, and not hard against the top edge: [visibleY].
     */
    Portrait(412, 848, 48..440),

    /** The phone on its side, for a dock. The editor's panel sits beside the canvas, so none of it is covered. */
    Landscape(848, 412, 0..412);

    companion object {
        /** A window wider than it is tall shows the landscape canvas. */
        fun of(width: Int, height: Int): Orientation = if (width > height) Landscape else Portrait
    }
}

/** What every canvas has in common. */
object Stage {
    /** Widgets snap to this many units when moved or resized. */
    const val GRID = 4
    /** No widget is made smaller than this on either side. */
    const val MIN_SIZE = 24
}

/** A rectangle on the canvas, in canvas units. */
@Immutable
data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h

    /** Touching edges do not count: two widgets may sit flush against each other. */
    fun overlaps(other: Box): Boolean =
        x < other.right && other.x < right && y < other.bottom && other.y < bottom
}

/**
 * One widget's settings, held as plain JSON values (text, true/false, numbers) under their names.
 * Kept loose on purpose: a setting written by a newer version of the app is carried along untouched
 * by an older one, and a missing setting simply reads as its default.
 */
@Immutable
class Settings(val values: Map<String, Any> = emptyMap()) {
    operator fun <T : Any> get(key: Key<T>): T = key.read(values[key.name])

    fun <T : Any> with(key: Key<T>, value: T): Settings = Settings(values + (key.name to key.write(value)))

    /** These settings with every key in [defaults] that is not set here filled in. */
    fun withDefaults(defaults: Settings): Settings = Settings(defaults.values + values)

    override fun equals(other: Any?): Boolean = other is Settings && other.values == values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "Settings($values)"
}

/** A named, typed setting with the value it has until someone changes it. */
@Immutable
sealed class Key<T : Any>(val name: String, val default: T) {
    abstract fun read(raw: Any?): T
    open fun write(value: T): Any = value
}

@Immutable
class FlagKey(name: String, default: Boolean) : Key<Boolean>(name, default) {
    override fun read(raw: Any?): Boolean = raw as? Boolean ?: default
}

@Immutable
class IntKey(name: String, default: Int) : Key<Int>(name, default) {
    override fun read(raw: Any?): Int = (raw as? Number)?.toInt() ?: default
}

@Immutable
class TextKey(name: String, default: String) : Key<String>(name, default) {
    override fun read(raw: Any?): String = raw as? String ?: default
}

/** A colour, stored as "#RRGGBB" so a saved layout stays readable by eye. Read back as ARGB. */
@Immutable
class ColourKey(name: String, default: Int) : Key<Int>(name, default) {
    override fun read(raw: Any?): Int = (raw as? String)?.let(::parseHexColour) ?: default
    override fun write(value: Int): Any = formatHexColour(value)
}

fun parseHexColour(text: String): Int? {
    val hex = text.removePrefix("#")
    val value = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> (0xFF000000 or value).toInt()
        8 -> value.toInt()
        else -> null
    }
}

fun formatHexColour(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/** One widget on the canvas. [type] names its [com.ikverse.deskglow.widgets.WidgetType]. */
@Immutable
data class WidgetItem(
    val id: String,
    val type: String,
    val box: Box,
    val visible: Boolean = true,
    val settings: Settings = Settings(),
)

/** Everything on the screen, back to front. */
@Immutable
data class Layout(val items: List<WidgetItem>) {
    fun find(id: String): WidgetItem? = items.firstOrNull { it.id == id }

    /** The next unused id: "w" and a number one past the highest in use. */
    fun nextId(): String = "w" + ((items.mapNotNull { it.id.removePrefix("w").toIntOrNull() }.maxOrNull() ?: 0) + 1)

    fun replace(item: WidgetItem): Layout = Layout(items.map { if (it.id == item.id) item else it })

    /** This layout with new boxes for the ids in [boxes]; everything else is left as it is. */
    fun withBoxes(boxes: Map<String, Box>): Layout =
        Layout(items.map { item -> boxes[item.id]?.let { item.copy(box = it) } ?: item })
}
