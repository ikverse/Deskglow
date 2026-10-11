package com.ikverse.deskglow.display

import android.content.Context
import android.os.PowerManager
import android.view.Window
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.ikverse.deskglow.store.BrightnessMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What every place the display is shown has to set up and tear down alike: the screen saver, Start now and
 * Always on each used to carry their own copy. A host builds one of these around its [window], calls
 * [prepare] once when the window is made, [start] when the display is on screen and [stop] when it is not.
 * Whatever the owner's brightness, cover and presence settings ask for is handled here, so a fix lands in
 * all three, and the next host (a second display, a tile) is three calls.
 */
internal class DisplayHost(private val context: Context, private val window: Window) {
    private val dark = mutableStateOf(false)

    /** Whether the room is dark enough to blank the display ("Match the room's light" only); false otherwise. */
    val roomDark: State<Boolean> get() = dark

    private var coverLock: PowerManager.WakeLock? = null
    private var lightJob: Job? = null
    private var presence = false

    /** Whether the proximity lock that turns the screen off while covered is held right now. */
    val coverHeld: Boolean get() = coverLock != null

    /**
     * The window as the display wants it: the owner's brightness, no system bars, the panel's normal rate.
     * The screen saver has Android's own dim mode and so sets the window's brightness only for a chosen
     * level ([onlyCustom]); the activities set it for every mode that has a fixed value.
     */
    fun prepare(onlyCustom: Boolean = false) {
        val brightness = context.chosenBrightness()
        val level = windowBrightness(brightness)
        if (level != null && (!onlyCustom || brightness.mode == BrightnessMode.Custom)) {
            window.attributes = window.attributes.apply { screenBrightness = level }
        }
        hideSystemBars(window)
        window.setRefreshRate(context, fast = false)
    }

    /**
     * The display is on screen: count it as shown ([presence]), hold the cover lock if asked and set
     * ([cover]), and follow the room's light in [scope] when brightness is on Auto.
     */
    fun start(scope: CoroutineScope, presence: Boolean = true, cover: Boolean = true) {
        if (presence) {
            this.presence = true
            DeskglowPresence.started(context)
        }
        if (cover) setCover(true)
        lightJob?.cancel()
        lightJob = if (context.chosenBrightness().mode == BrightnessMode.Auto) {
            scope.launch { followRoomLight(context, window) { dark.value = it } }
        } else null
    }

    /** Holds the proximity lock ([held]) or lets it go. A host that decides when to hold it (Always on) calls this itself. */
    fun setCover(held: Boolean) {
        coverLock.letGo()
        coverLock = if (held) context.holdCoverOff() else null
    }

    /** The display is no longer on screen: stop counting it, release the lock and stop reading the light. */
    fun stop() {
        if (presence) {
            presence = false
            DeskglowPresence.stopped(context)
        }
        lightJob?.cancel()
        lightJob = null
        setCover(false)
    }
}
