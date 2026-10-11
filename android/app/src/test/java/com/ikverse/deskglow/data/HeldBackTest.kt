package com.ikverse.deskglow.data

import com.ikverse.deskglow.widgets.F1LiveSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The broadcast delay: the timing held back so it lines up with a TV picture that lags it. Whenever a
 * widget starts reading, nothing may show on it before the TV has had time to show it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldBackTest {
    private val green = F1LiveState.Live(F1LiveSample.race.copy(flag = TrackFlag.Clear))
    private val yellow = F1LiveState.Live(F1LiveSample.race.copy(flag = TrackFlag.Yellow))
    private val result = F1LiveState.Result(F1LiveSample.race.copy(finished = true))

    /** The feed as the app has it: the shared state, and the history every state is written to as it comes. */
    private class Feed(private val scope: TestScope) {
        val history = LiveHistory()
        val flow = MutableStateFlow<F1LiveState>(F1LiveState.Waiting)

        fun says(state: F1LiveState) {
            history.record(state, scope.testScheduler.currentTime)
            flow.value = state
        }
    }

    private fun TestScope.watch(feed: Feed, delayMs: Long, seen: MutableList<F1LiveState>) =
        launch { feed.flow.heldBack(delayMs, feed.history) { testScheduler.currentTime }.collect { seen += it } }

    @Test
    fun noDelayIsTheFlowItself() {
        val flow = MutableStateFlow<F1LiveState>(F1LiveState.Waiting)
        assertSame(flow, flow.heldBack(0, LiveHistory()))
    }

    @Test
    fun aWidgetOpenedDuringAFlagTheTvHasNotShownYetStartsWhereTheTvIs() = runTest {
        val feed = Feed(this)
        feed.says(green)
        advanceTimeBy(50_000)
        feed.says(yellow)
        advanceTimeBy(10_000)

        // Opened 10 s after the yellow, 30 s behind: the TV is still on green.
        val seen = mutableListOf<F1LiveState>()
        val job = watch(feed, 30_000, seen)
        runCurrent()
        assertEquals(listOf<F1LiveState>(green), seen)

        advanceTimeBy(19_000)
        runCurrent()
        assertEquals("the yellow is not shown before the TV shows it", listOf<F1LiveState>(green), seen)
        advanceTimeBy(1_001)
        assertEquals(listOf(green, yellow), seen)
        job.cancel()
    }

    @Test
    fun eachLaterStateComesTheDelayAfterItCameInOrderAndTheEndToo() = runTest {
        val feed = Feed(this)
        feed.says(green)
        advanceTimeBy(60_000)
        val seen = mutableListOf<F1LiveState>()
        val job = watch(feed, 30_000, seen)
        runCurrent()

        feed.says(yellow)
        advanceTimeBy(10_000)
        feed.says(result)
        advanceTimeBy(19_000)
        runCurrent()
        assertEquals("nothing new before the delay is up", listOf<F1LiveState>(green), seen)

        advanceTimeBy(1_001)
        assertEquals(listOf(green, yellow), seen)

        // The end of the session is held back too, so the result does not come before the TV shows the flag.
        advanceTimeBy(9_000)
        assertEquals(listOf(green, yellow), seen)
        advanceTimeBy(1_001)
        assertEquals(listOf(green, yellow, result), seen)
        job.cancel()
    }

    @Test
    fun withNothingFromThatLongAgoTheLastResultStandsInAndTheLiveStateWaits() = runTest {
        val feed = Feed(this)
        advanceTimeBy(100_000)
        // The app has just started: the feed first says the saved result, then goes live.
        feed.says(result)
        advanceTimeBy(2_000)
        feed.says(yellow)
        advanceTimeBy(1_000)

        val seen = mutableListOf<F1LiveState>()
        val job = watch(feed, 30_000, seen)
        runCurrent()
        assertEquals(listOf<F1LiveState>(result), seen)

        // The yellow came at 102 s, so it shows at 132 s.
        advanceTimeBy(28_000)
        runCurrent()
        assertEquals(listOf<F1LiveState>(result), seen)
        advanceTimeBy(1_001)
        assertEquals(listOf(result, yellow), seen)
        job.cancel()
    }

    @Test
    fun withOnlyLiveStatesTooRecentNothingLiveShows() = runTest {
        val feed = Feed(this)
        advanceTimeBy(100_000)
        feed.says(yellow)
        advanceTimeBy(1_000)

        val seen = mutableListOf<F1LiveState>()
        val job = watch(feed, 30_000, seen)
        runCurrent()
        assertEquals(listOf<F1LiveState>(F1LiveState.Waiting), seen)
        advanceTimeBy(29_001)
        assertEquals(listOf(F1LiveState.Waiting, yellow), seen)
        job.cancel()
    }

    @Test
    fun aChangedDelayStartsAgainWhereTheTvNowIs() = runTest {
        val feed = Feed(this)
        feed.says(green)
        advanceTimeBy(40_000)
        feed.says(yellow)
        advanceTimeBy(20_000)

        val longer = mutableListOf<F1LiveState>()
        val shorter = mutableListOf<F1LiveState>()
        val a = watch(feed, 30_000, longer)
        val b = watch(feed, 10_000, shorter)
        runCurrent()
        assertEquals(listOf<F1LiveState>(green), longer)
        assertEquals(listOf<F1LiveState>(yellow), shorter)
        a.cancel()
        b.cancel()
    }

    @Test
    fun theHistoryKeepsTheLongestDelayAndTheStateBeforeIt() {
        val history = LiveHistory(keepMs = 90_000)
        history.record(green, 0)
        history.record(yellow, 50_000)
        history.record(result, 200_000)
        // Green is gone; yellow is what showed 90 s before the result came, so it is kept.
        assertEquals(yellow, history.oldest())
        assertEquals(yellow, history.lastBy(150_000)?.second)
    }
}
