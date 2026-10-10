package com.ikverse.deskglow.display

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnTest {
    private val timedOut = ScreenOff(
        enabled = true, wasShowing = false, coverHeld = false, dreaming = false, inCall = false, screenBackOn = false,
    )

    @Test
    fun `the screen timing out shows the display`() {
        assertEquals(OffAction.Show, decideOnScreenOff(timedOut))
    }

    @Test
    fun `power pressed on a Deskglow screen goes to the lock screen instead`() {
        assertEquals(OffAction.WakeToLockScreen, decideOnScreenOff(timedOut.copy(wasShowing = true)))
    }

    @Test
    fun `a screen saver that is running means nothing is shown over it`() {
        assertEquals(OffAction.Nothing, decideOnScreenOff(timedOut.copy(dreaming = true)))
    }

    @Test
    fun `power pressed while the Deskglow screen saver was up still goes to the lock screen`() {
        assertEquals(OffAction.WakeToLockScreen, decideOnScreenOff(timedOut.copy(wasShowing = true, dreaming = true)))
    }

    @Test
    fun `a call keeps it out of the way`() {
        assertEquals(OffAction.Nothing, decideOnScreenOff(timedOut.copy(inCall = true)))
        assertEquals(OffAction.Nothing, decideOnScreenOff(timedOut.copy(inCall = true, wasShowing = true)))
    }

    @Test
    fun `the screen coming back during the wait cancels it`() {
        assertEquals(OffAction.Nothing, decideOnScreenOff(timedOut.copy(screenBackOn = true)))
        assertEquals(OffAction.Nothing, decideOnScreenOff(timedOut.copy(screenBackOn = true, wasShowing = true)))
    }

    @Test
    fun `the display switching the screen off for a covered phone is not a power press`() {
        assertEquals(OffAction.Nothing, decideOnScreenOff(timedOut.copy(coverHeld = true, wasShowing = true)))
    }

    @Test
    fun `switched off does nothing`() {
        assertEquals(OffAction.Nothing, decideOnScreenOff(timedOut.copy(enabled = false)))
        assertEquals(OffAction.Nothing, decideOnScreenOff(timedOut.copy(enabled = false, wasShowing = true)))
    }

    @Test
    fun `a few presses of the power button are fine`() {
        val guard = LoopGuard()
        repeat(6) { assertTrue(guard.admit(it * 1_000L)) }
    }

    @Test
    fun `a loop is stopped, then allowed again after the pause`() {
        val guard = LoopGuard(max = 3, windowMs = 10_000, pauseMs = 60_000)
        assertTrue(guard.admit(0))
        assertTrue(guard.admit(100))
        assertTrue(guard.admit(200))
        assertFalse(guard.admit(300))
        assertFalse(guard.admit(30_000))
        assertTrue(guard.admit(61_000))
    }

    @Test
    fun `old acts fall out of the window`() {
        val guard = LoopGuard(max = 2, windowMs = 10_000, pauseMs = 60_000)
        assertTrue(guard.admit(0))
        assertTrue(guard.admit(1_000))
        assertTrue(guard.admit(20_000))
        assertTrue(guard.admit(21_000))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `covering for the delay turns the screen off, uncovering brings it back`() = runTest {
        val covered = MutableSharedFlow<Boolean>()
        val changes = mutableListOf<Boolean>()
        val job = launch { applyCoverDelay(covered, 5_000, 5_000) { changes += it } }
        runCurrent()
        covered.emit(true)
        advanceTimeBy(4_999)
        assertEquals(emptyList<Boolean>(), changes)
        advanceTimeBy(2)
        assertEquals(listOf(true), changes)
        covered.emit(false)
        runCurrent()
        assertEquals(listOf(true, false), changes)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a hand passing over the sensor does nothing`() = runTest {
        val covered = MutableSharedFlow<Boolean>()
        val changes = mutableListOf<Boolean>()
        val job = launch { applyCoverDelay(covered, 5_000, 5_000) { changes += it } }
        runCurrent()
        covered.emit(true)
        advanceTimeBy(2_000)
        covered.emit(false)
        advanceTimeBy(10_000)
        // Only the "clear" is reported, which is harmless; the screen never went off.
        assertEquals(listOf(false), changes)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `uncovering restarts the count`() = runTest {
        val covered = MutableSharedFlow<Boolean>()
        val changes = mutableListOf<Boolean>()
        val job = launch { applyCoverDelay(covered, 5_000, 5_000) { changes += it } }
        runCurrent()
        covered.emit(true)
        advanceTimeBy(4_000)
        covered.emit(false)
        runCurrent()
        covered.emit(true)
        advanceTimeBy(4_000)
        assertFalse(true in changes)
        advanceTimeBy(1_100)
        assertTrue(true in changes)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a phone already covered when the display starts goes off at once, once`() = runTest {
        val covered = MutableSharedFlow<Boolean>()
        val changes = mutableListOf<Boolean>()
        val job = launch { applyCoverDelay(covered, 5_000, 0) { changes += it } }
        runCurrent()
        covered.emit(true)
        runCurrent()
        assertEquals(listOf(true), changes)
        covered.emit(false)
        runCurrent()
        covered.emit(true)
        advanceTimeBy(4_000)
        assertEquals(listOf(true, false), changes)
        advanceTimeBy(1_100)
        assertEquals(listOf(true, false, true), changes)
        job.cancel()
    }
}
