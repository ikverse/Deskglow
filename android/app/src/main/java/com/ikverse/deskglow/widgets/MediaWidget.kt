package com.ikverse.deskglow.widgets

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.Settings
import kotlin.math.min
import kotlinx.coroutines.delay

object MediaWidget : WidgetType {
    val SHOW_ARTIST = FlagKey("showArtist", true)
    val SHOW_PROGRESS = FlagKey("showProgress", true)

    override val id = "media"
    override val label = "Now playing"
    override val blurb = "Track and artist"
    override val width = 372
    override val height = 52
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ShowField("Show", listOf(SHOW_ARTIST to "Artist", SHOW_PROGRESS to "Progress bar")),
        Common.alignField,
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "Needs notification access (Home › Permissions)."

    @Composable
    override fun Content(settings: Settings) {
        val state by LocalFeeds.current.media.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            if (state == MediaState.NoAccess) return@BoxWithConstraints EditorHint("Allow notification access", h * 0.3f)
            val track = state as? MediaState.Track
            val colour = Color(settings[Common.COLOUR])
            val align = settings[Common.ALIGN]
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (align != "right") {
                    MusicNote(Muted, Modifier.size(pxToDp(h * 0.62f)))
                    Spacer(Modifier.width(pxToDp(h * 0.2f)))
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = horizontal(align)) {
                    val titleSize = pxToSp(min(h * 0.34f, w * 0.05f))
                    if (track == null) {
                        Text("Nothing playing", color = Muted, fontSize = titleSize, maxLines = 1)
                    } else {
                        Text(track.title, color = colour, fontSize = titleSize, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align))
                        if (settings[SHOW_ARTIST] && track.artist.isNotBlank()) {
                            Text(track.artist, color = Muted, fontSize = pxToSp(min(h * 0.24f, w * 0.04f)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign(align))
                        }
                        if (settings[SHOW_PROGRESS] && track.durationMs > 0) {
                            Spacer(Modifier.height(pxToDp(h * 0.06f)))
                            Progress(track, colour, Modifier.fillMaxWidth().height(pxToDp((h * 0.04f).coerceAtLeast(2f))))
                        }
                    }
                }
                if (align == "right") {
                    Spacer(Modifier.width(pxToDp(h * 0.2f)))
                    MusicNote(Muted, Modifier.size(pxToDp(h * 0.62f)))
                }
            }
        }
    }
}

/** The progress bar. While playing it moves on by itself every few seconds, worked out from when the position was read. */
@Composable
private fun Progress(track: MediaState.Track, color: Color, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track) {
        while (track.playing) {
            now = SystemClock.elapsedRealtime()
            delay(5_000)
        }
    }
    val position = if (track.playing) track.positionMs + ((now - track.positionAtElapsedMs) * track.speed).toLong() else track.positionMs
    val fraction = (position.toFloat() / track.durationMs).coerceIn(0f, 1f)
    Box(modifier.background(Color(0xFF222222))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(color))
    }
}

@Composable
private fun MusicNote(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = Stroke(s * 0.075f, cap = StrokeCap.Round)
        val path = Path().apply { moveTo(s * 0.375f, s * 0.71f); lineTo(s * 0.375f, s * 0.23f); lineTo(s * 0.79f, s * 0.15f); lineTo(s * 0.79f, s * 0.62f) }
        drawPath(path, color, style = stroke)
        drawCircle(color, s * 0.11f, Offset(s * 0.283f, s * 0.73f))
        drawCircle(color, s * 0.11f, Offset(s * 0.7f, s * 0.646f))
    }
}
