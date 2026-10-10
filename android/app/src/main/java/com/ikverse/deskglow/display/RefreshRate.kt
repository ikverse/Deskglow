package com.ikverse.deskglow.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Window

/** One way the panel can run, reduced to what the choice of refresh rate needs. */
internal data class PanelMode(val id: Int, val width: Int, val height: Int, val hz: Float)

/** The slowest rate worth resting at: below this a static picture can start to shimmer on some panels. */
private const val MIN_REST_HZ = 48f

/**
 * The mode to run in, among those with the same resolution as [current] (a different resolution would
 * resize the picture): the fastest for [fast], else the slowest that is still [MIN_REST_HZ] or more,
 * which is normally 60 Hz. Null when nothing qualifies.
 */
internal fun chooseMode(modes: List<PanelMode>, current: PanelMode, fast: Boolean): PanelMode? {
    val sameSize = modes.filter { it.width == current.width && it.height == current.height }
    return if (fast) sameSize.maxByOrNull { it.hz }
    else sameSize.filter { it.hz >= MIN_REST_HZ - 0.5f }.minByOrNull { it.hz }
}

/**
 * Runs [Window]'s display at its fastest rate ([fast]) or at its slowest ordinary one. A clock that
 * changes once a minute looks the same at 60 Hz as at 120 Hz, so the display rests slow and goes fast
 * only for the swipe between screens. Also stops Android 15's boost that raises the rate after every
 * touch, which a double tap to close would otherwise trigger. Leaves the phone alone where it offers
 * no choice.
 */
internal fun Window.setRefreshRate(context: Context, fast: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) setFrameRateBoostOnTouchEnabled(false)
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
    val modes = display.supportedModes.map { PanelMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
    val current = display.mode.let { PanelMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
    val mode = chooseMode(modes, current, fast) ?: return
    attributes = attributes.apply { preferredDisplayModeId = mode.id }
}
