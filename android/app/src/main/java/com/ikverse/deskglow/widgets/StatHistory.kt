package com.ikverse.deskglow.widgets

/**
 * The last few minutes of the battery numbers a stat can graph. It is filled only by a stat that is on
 * screen with its graph on, so nothing is kept or counted while the display is off; readings older than
 * [KEEP_MS] are dropped as they are read. Used from the main thread only.
 */
object StatHistory {
    /** How far back the graph reaches. */
    const val KEEP_MS = 10 * 60_000L

    /** Readings closer together than this are not both kept. */
    const val GAP_MS = 15_000L

    /** The most readings held for one number. */
    const val MAX = 40

    class Sample(val atMs: Long, val value: Double)

    private val samples = HashMap<String, ArrayDeque<Sample>>()

    /** Notes [value] for [metric] at [nowMs], unless one was noted less than [GAP_MS] ago. */
    fun add(metric: String, value: Double, nowMs: Long) {
        val list = samples.getOrPut(metric) { ArrayDeque() }
        prune(list, nowMs)
        val last = list.lastOrNull()
        if (last != null && nowMs - last.atMs < GAP_MS) return
        list.addLast(Sample(nowMs, value))
        while (list.size > MAX) list.removeFirst()
    }

    /** The readings of [metric] from the last [KEEP_MS] before [nowMs], oldest first. */
    fun recent(metric: String, nowMs: Long): List<Sample> {
        val list = samples[metric] ?: return emptyList()
        prune(list, nowMs)
        return list.toList()
    }

    /** Forgets everything, for tests. */
    fun clear() = samples.clear()

    private fun prune(list: ArrayDeque<Sample>, nowMs: Long) {
        while (list.isNotEmpty() && nowMs - list.first().atMs > KEEP_MS) list.removeFirst()
    }
}
