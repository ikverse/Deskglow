package com.ikverse.deskglow.data

import com.ikverse.deskglow.widgets.F1LiveSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The broadcast delay: the timing held back so it lines up with a TV picture that lags it. */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldBackTest {
    private val live = F1LiveState.Live(F1LiveSample.race)
    private val result = F1LiveState.Result(F1LiveSample.race)

    @Test
    fun noDelayIsTheFlowItself() {
        val flow = MutableStateFlow<F1LiveState>(F1LiveState.Waiting)
        assertSame(flow, flow.heldBack(0))
    }

    @Test
    fun theFirstComesAtOnceAndTheRestAfterTheDelayInOrder() = runTest {
        val upstream = MutableSharedFlow<F1LiveState>()
        val seen = mutableListOf<F1LiveState>()
        val job = launch { upstream.heldBack(30_000) { testScheduler.currentTime }.collect { seen += it } }
        runCurrent()
        upstream.emit(F1LiveState.Waiting)
        runCurrent()
        assertEquals(listOf<F1LiveState>(F1LiveState.Waiting), seen)

        upstream.emit(live)
        advanceTimeBy(10_000)
        upstream.emit(result)
        advanceTimeBy(19_000)
        runCurrent()
        assertEquals("nothing new before the delay is up", listOf<F1LiveState>(F1LiveState.Waiting), seen)

        advanceTimeBy(1_001)
        assertEquals(listOf(F1LiveState.Waiting, live), seen)

        // The end of the session is held back too, so the result does not come before the TV shows the flag.
        advanceTimeBy(9_000)
        assertEquals(listOf(F1LiveState.Waiting, live), seen)
        advanceTimeBy(1_001)
        assertEquals(listOf(F1LiveState.Waiting, live, result), seen)
        job.cancel()
    }
}
