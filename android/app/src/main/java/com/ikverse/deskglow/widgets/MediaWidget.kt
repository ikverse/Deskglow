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
import com.ikverse.deskglow.model.TextKey
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.delay

object MediaWidget : WidgetType {
    val SHOW_ARTIST = FlagKey("showArtist", true)
    /** The old switch for the progress bar; now [PROGRESS_MODE], which a saved widget is brought to by [migrate]. */
    val SHOW_PROGRESS = FlagKey("showProgress", true)
    /** "bar", "thin" (a hairline), "times" (1:23 / 3:45) or "none". */
    val PROGRESS_MODE = TextKey("progressMode", "bar")
    /** "text" says "Nothing playing"; "hide" shows nothing. */
    val WHEN_IDLE = TextKey("whenIdle", "text")

    override val id = "media"
    override val label = "Now playing"
    override val blurb = "Track and artist"
    override val width = 372
    override val height = 52
    override val defaults: Settings = Common.base()

    override fun fields(settings: Settings) = listOf(
        ToggleField("Show artist", SHOW_ARTIST),
        ChoiceField("Progress", PROGRESS_MODE, listOf("bar" to "Bar", "thin" to "Thin", "times" to "Times", "none" to "None")),
        ChoiceField("When nothing plays", WHEN_IDLE, listOf("text" to "Say so", "hide" to "Show nothing")),
        Common.alignField,
        Common.colourField,
        Common.brightnessField,
    )

    /** The progress switch became a choice: a widget with it off shows none, as before. */
    override fun migrate(settings: Settings): Settings =
        if (PROGRESS_MODE.name in settings.values) settings else settings.with(PROGRESS_MODE, if (settings[SHOW_PROGRESS]) "bar" else "none")

    override fun note(settings: Settings) = "Needs notification access (Home › Permissions)."

    @Composable
    override fun Content(settings: Settings) {
        val state by LocalFeeds.current.media.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            if (state == MediaState.NoAccess) return@BoxWithConstraints EditorHint("Allow notification access", h * 0.3f)
            val track = state as? MediaState.Track
            if (track == null && settings[WHEN_IDLE] == "hide") return@BoxWithConstraints EditorHint("Nothing playing", h * 0.3f)
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
                        val mode = settings[PROGRESS_MODE]
                        if (mode != "none" && track.durationMs > 0) {
                            Spacer(Modifier.height(pxToDp(h * 0.06f)))
                            when (mode) {
                                "times" -> ProgressTimes(track, min(h * 0.22f, w * 0.036f), align)
                                "thin" -> Progress(track, colour.copy(alpha = 0.7f), Modifier.fillMaxWidth().height(pxToDp((h * 0.015f).coerceAtLeast(1f))))
                                else -> Progress(track, colour, Modifier.fillMaxWidth().height(pxToDp((h * 0.04f).coerceAtLeast(2f))))
                            }
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

/** Where the track is now: moves on by itself every few seconds while playing, worked out from when the position was read. */
@Composable
private fun rememberPosition(track: MediaState.Track): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track) {
        while (track.playing) {
            now = SystemClock.elapsedRealtime()
            delay(5_000)
        }
    }
    return if (track.playing) track.positionMs + ((now - track.positionAtElapsedMs) * track.speed).toLong() else track.positionMs
}

/** The progress bar. */
@Composable
private fun Progress(track: MediaState.Track, color: Color, modifier: Modifier) {
    val fraction = (rememberPosition(track).toFloat() / track.durationMs).coerceIn(0f, 1f)
    Box(modifier.background(Color(0xFF222222))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(color))
    }
}

/** "1:23 / 3:45". */
@Composable
private fun ProgressTimes(track: MediaState.Track, size: Float, align: String) {
    val position = rememberPosition(track).coerceIn(0L, track.durationMs)
    Text("${mediaTime(position)} / ${mediaTime(track.durationMs)}", color = Muted, fontSize = pxToSp(size), maxLines = 1, softWrap = false, textAlign = textAlign(align))
}

/** "1:23", or "1:02:03" past an hour. */
internal fun mediaTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
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
