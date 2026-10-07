package com.ikverse.deskglow.data

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * What is playing, from Android's media sessions (the same source as the lock-screen player). Reading
 * them needs notification access. Listens only while the screen is showing.
 */
fun mediaUpdates(context: Context): Flow<MediaState> = callbackFlow {
    if (!hasNotificationAccess(context)) {
        send(MediaState.NoAccess)
        awaitClose {}
        return@callbackFlow
    }
    val manager = context.getSystemService(MediaSessionManager::class.java)
    val handler = Handler(Looper.getMainLooper())
    var current: MediaController? = null

    val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { current?.let { trySend(trackOf(it)) } }
        override fun onPlaybackStateChanged(state: PlaybackState?) { current?.let { trySend(trackOf(it)) } }
        override fun onSessionDestroyed() {
            current = null
            trySend(MediaState.Idle)
        }
    }

    fun follow(controllers: List<MediaController>?) {
        val pick = choose(controllers.orEmpty())
        if (pick?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(controllerCallback)
            current = pick
            pick?.registerCallback(controllerCallback, handler)
        }
        trySend(current?.let(::trackOf) ?: MediaState.Idle)
    }

    val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { follow(it) }
    try {
        manager.addOnActiveSessionsChangedListener(sessionsListener, watcherComponent(context), handler)
        follow(manager.getActiveSessions(watcherComponent(context)))
    } catch (e: SecurityException) {
        trySend(MediaState.NoAccess)
    }
    awaitClose {
        current?.unregisterCallback(controllerCallback)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
    }
}

/** The session that is playing, else the most recent one that has a track loaded. */
private fun choose(controllers: List<MediaController>): MediaController? =
    controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        ?: controllers.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }

private fun trackOf(controller: MediaController): MediaState {
    val metadata = controller.metadata ?: return MediaState.Idle
    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return MediaState.Idle
    val state = controller.playbackState
    return MediaState.Track(
        title = title,
        artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
        durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
        positionMs = state?.position?.coerceAtLeast(0) ?: 0,
        positionAtElapsedMs = state?.lastPositionUpdateTime ?: 0,
        playing = state?.state == PlaybackState.STATE_PLAYING,
        speed = state?.playbackSpeed ?: 1f,
    )
}
