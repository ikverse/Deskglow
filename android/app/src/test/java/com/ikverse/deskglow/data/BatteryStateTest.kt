package com.ikverse.deskglow.data

import android.content.Intent
import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the battery broadcast says about the charger, the health and the charge cycles. */
@RunWith(RobolectricTestRunner::class)
class BatteryStateTest {
    private fun broadcast(vararg extras: Pair<String, Int>) = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
        putExtra(BatteryManager.EXTRA_LEVEL, 80)
        putExtra(BatteryManager.EXTRA_SCALE, 100)
        extras.forEach { (name, value) -> putExtra(name, value) }
    }

    @Test
    fun `the charger, the health and the cycles are read from the broadcast`() {
        val state = batteryState(
            broadcast(BatteryManager.EXTRA_PLUGGED to BatteryManager.BATTERY_PLUGGED_WIRELESS, BatteryManager.EXTRA_HEALTH to BatteryManager.BATTERY_HEALTH_GOOD, "android.os.extra.CYCLE_COUNT" to 212),
            rawCurrent = 300, timeToFullMs = -1,
        )
        assertEquals(BatteryManager.BATTERY_PLUGGED_WIRELESS, state.plugSource)
        assertEquals(BatteryManager.BATTERY_HEALTH_GOOD, state.healthCode)
        assertEquals(212, state.cycles)
    }

    @Test
    fun `a phone that does not say leaves them empty`() {
        val state = batteryState(broadcast(), rawCurrent = 300, timeToFullMs = -1)
        assertEquals(0, state.plugSource)
        assertEquals(0, state.healthCode)
        assertNull(state.cycles)
    }
}
