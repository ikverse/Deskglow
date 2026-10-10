package com.ikverse.deskglow.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

fun hasCalendarAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

/** One calendar entry as stored: times in ms; all-day entries are kept in UTC by Android. */
data class CalendarEntry(val title: String, val beginMs: Long, val endMs: Long, val allDay: Boolean, val colour: Int? = null, val location: String = "")

/**
 * The next calendar event. Looked up when the screen appears, again whenever the calendar changes,
 * and again when the event shown starts or ends; never on a timer.
 */
fun nextEventUpdates(context: Context): Flow<EventState> = callbackFlow {
    if (!hasCalendarAccess(context)) {
        send(EventState.NoAccess)
        awaitClose {}
        return@callbackFlow
    }
    val wake = Channel<Unit>(Channel.CONFLATED)
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { wake.trySend(Unit) }
    }
    context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
    val job = launch {
        while (isActive) {
            val now = System.currentTimeMillis()
            val entries = runCatching { query(context, now) }.getOrDefault(emptyList())
            val next = nextEvent(entries, now, ZoneId.systemDefault())
            send(next)
            // Look again when the event shown starts or ends, or when the calendar changes; at most daily.
            val until = (next as? EventState.Next)?.let { event ->
                val zone = ZoneId.systemDefault()
                listOf(event.start, event.end).map { it.atZone(zone).toInstant().toEpochMilli() }.filter { it > now }.minOrNull()
            } ?: (now + DAY_MS)
            withTimeoutOrNull((until - now).coerceIn(1_000, DAY_MS)) { wake.receive() }
        }
    }
    awaitClose {
        job.cancel()
        context.contentResolver.unregisterContentObserver(observer)
    }
}.conflate().flowOn(Dispatchers.IO)

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val LOOK_AHEAD_MS = 7 * DAY_MS

private fun query(context: Context, now: Long): List<CalendarEntry> {
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now - DAY_MS)
        ContentUris.appendId(it, now + LOOK_AHEAD_MS)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_COLOR,
        CalendarContract.Instances.EVENT_LOCATION,
    )
    val entries = ArrayList<CalendarEntry>()
    context.contentResolver.query(
        uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            entries += CalendarEntry(
                title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "(No title)",
                beginMs = cursor.getLong(1),
                endMs = cursor.getLong(2),
                allDay = cursor.getInt(3) == 1,
                colour = if (cursor.isNull(4)) null else cursor.getInt(4) or 0xFF000000.toInt(),
                location = cursor.getString(5).orEmpty().trim(),
            )
        }
    }
    return entries
}

/** How many events after the first an agenda can use. */
private const val MAX_LATER = 4

/**
 * The event to show: the earliest timed event that has not ended, or failing that the earliest
 * all-day one that has not ended; with the ones after it, timed before all-day, for an agenda.
 * All-day entries are stored at UTC midnights and are read as dates.
 */
internal fun nextEvent(entries: List<CalendarEntry>, nowMs: Long, zone: ZoneId): EventState {
    fun local(ms: Long, allDay: Boolean): LocalDateTime =
        if (allDay) LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)
        else LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    val timed = entries.filter { !it.allDay && it.endMs > nowMs }.sortedBy { it.beginMs }
    val allDay = entries.filter { it.allDay && local(it.endMs, true) > nowLocal }.sortedBy { it.beginMs }
    val ordered = timed + allDay
    fun event(e: CalendarEntry, later: List<EventState.Next> = emptyList()) =
        EventState.Next(e.title, local(e.beginMs, e.allDay), local(e.endMs, e.allDay), e.allDay, e.colour, e.location, later)
    val first = ordered.firstOrNull() ?: return EventState.None
    return event(first, ordered.drop(1).take(MAX_LATER).map { event(it) })
}
