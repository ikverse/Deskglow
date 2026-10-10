package com.ikverse.deskglow.data

import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.store.AppPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * The live feed's follower with a scripted connection: which topics it asks for, what it shows when
 * the connection drops, and how a session followed with the few topics gets its result.
 */
@RunWith(RobolectricTestRunner::class)
class F1LiveRepositoryTest {
    // Japan's first practice: 11:30 at the track, 02:30 UTC. The clock is a minute into its window.
    private val clock = { Instant.parse("2026-10-09T02:31:00Z").toEpochMilli() }
    private val calendar = MutableStateFlow<F1State>(F1State.Ready(F1Samples.data))
    private val offline = object : Http {
        override fun get(url: String): ByteArray = throw IOException("offline")
    }

    private fun repository() = F1LiveRepository(AppPrefs(ApplicationProvider.getApplicationContext()), source, offline, clock)

    private lateinit var source: Source

    private fun full(topic: String, json: String) = LiveMessage(topic, JSONObject(json), full = true)
    private fun change(topic: String, json: String) = LiveMessage(topic, JSONObject(json), full = false)

    private fun started(status: String = "Started") = listOf(
        full("SessionInfo", """{"Key":5,"Type":"Practice","Name":"Practice 1","StartDate":"2026-10-09T11:30:00","GmtOffset":"09:00:00","Meeting":{"Name":"Japanese Grand Prix"}}"""),
        full("SessionStatus", """{"Status":"$status"}"""),
        full("TrackStatus", """{"Status":"1"}"""),
        full("RaceControlMessages", """{"Messages":[]}"""),
    )

    /** What a connection does when polled: a batch, or a failure. After its script it only idles, as a quiet stream does between keep-alives. */
    private class Script(private val steps: List<() -> List<LiveMessage>>) : LiveConnection {
        private val closed = CountDownLatch(1)
        private var next = 0
        override fun poll(): List<LiveMessage> {
            if (closed.count == 0L) throw IOException("closed")
            if (next < steps.size) return steps[next++]()
            Thread.sleep(50)
            return emptyList()
        }

        override fun close() = closed.countDown()
    }

    private class Source(private vararg val connections: Script) : LiveTimingSource {
        val opened = CopyOnWriteArrayList<List<String>>()
        override fun open(topics: List<String>): LiveConnection {
            opened += topics
            return connections[opened.size - 1]
        }
    }

    private suspend fun until(condition: () -> Boolean) {
        while (!condition()) delay(20)
    }

    @Test
    fun `only the few topics are asked for until the live widget wants the rest, then the connection is made again`() = runBlocking {
        source = Source(Script(listOf({ started("Inactive") })), Script(listOf({ started("Inactive") })))
        val detail = MutableStateFlow(false)
        val seen = CopyOnWriteArrayList<F1LiveState>()
        withTimeout(30_000) {
            val job = launch { repository().updates(calendar, detail).collect { seen += it } }
            until { source.opened.size >= 1 && seen.any { it is F1LiveState.Live } }
            assertEquals(LITE_TOPICS, source.opened[0])
            // The session is on screen before it has started: it is only waiting.
            val live = seen.filterIsInstance<F1LiveState.Live>().first().session
            assertEquals("Practice 1", live.name)
            assertEquals(LivePhase.PreStart, live.phase(Instant.parse("2026-10-09T02:31:00Z")))
            detail.value = true
            until { source.opened.size >= 2 }
            assertEquals(LIVE_TOPICS, source.opened[1])
            job.cancel()
        }
    }

    @Test
    fun `a dropped connection shows the last thing seen, as lost, and when it was last heard`() = runBlocking {
        source = Source(
            Script(listOf({ started("Started") }, { Thread.sleep(300); throw IOException("reset") })),
        )
        val seen = CopyOnWriteArrayList<F1LiveState>()
        withTimeout(30_000) {
            val job = launch { repository().updates(calendar, MutableStateFlow(false)).collect { seen += it } }
            until { seen.any { it is F1LiveState.Live && it.session.signalLost } }
            val lost = seen.filterIsInstance<F1LiveState.Live>().first { it.session.signalLost }.session
            assertEquals("Practice 1", lost.name)
            assertNotNull(lost.signalAt)
            // What was on screen before the drop was not marked lost.
            assertTrue(seen.filterIsInstance<F1LiveState.Live>().any { !it.session.signalLost })
            job.cancel()
        }
    }

    @Test
    fun `a session followed with the few topics gets its classification from the feed's snapshot afterwards`() = runBlocking {
        val result = listOf(
            full("SessionInfo", """{"Key":5,"Type":"Practice","Name":"Practice 1","StartDate":"2026-10-09T11:30:00","GmtOffset":"09:00:00","Meeting":{"Name":"Japanese Grand Prix"}}"""),
            full("SessionStatus", """{"Status":"Finalised"}"""),
            full("DriverList", """{"1":{"Tla":"NOR","TeamColour":"F47600","TeamName":"McLaren"}}"""),
            full("TimingData", """{"Lines":{"1":{"Position":"1","Line":1,"BestLapTime":{"Value":"1:29.874"}}}}"""),
        )
        source = Source(
            Script(listOf({ started() }, { listOf(change("SessionStatus", """{"Status":"Finalised"}""")) })),
            Script(listOf({ result })),
        )
        val seen = CopyOnWriteArrayList<F1LiveState>()
        withTimeout(30_000) {
            val job = launch { repository().updates(calendar, MutableStateFlow(false)).collect { seen += it } }
            until { seen.any { it is F1LiveState.Result && it.session.rows.isNotEmpty() } }
            val kept = seen.filterIsInstance<F1LiveState.Result>().first { it.session.rows.isNotEmpty() }.session
            assertEquals(listOf("NOR"), kept.rows.map { it.code })
            assertTrue(kept.finished)
            // The first connection asked for the few topics; the snapshot afterwards for all of them.
            assertEquals(LITE_TOPICS, source.opened[0])
            assertEquals(LIVE_TOPICS, source.opened[1])
            job.cancel()
        }
    }
}
