package com.ikverse.deskglow.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import com.ikverse.deskglow.data.F1Track
import kotlin.math.min

/*
 * PitWall's motion. Every animation here is set off by something happening (a place changing hands,
 * a flag coming out, a new lap, one screen giving way to another), runs once and stops: nothing
 * loops, so a PitWall on which nothing is changing draws no frames. Only the cars on the map move all
 * the time, and only while a session runs. All of it is off in the editor, whose samples stand still.
 */

internal val PitPurple = Color(0xFFB45CFF)

/** One screen, or one card, giving way to the next: a plain crossfade. */
internal fun pitFade(animate: Boolean, ms: Int = 450): ContentTransform =
    if (animate) fadeIn(tween(ms)) togetherWith fadeOut(tween(ms)) else EnterTransition.None togetherWith ExitTransition.None

/** How a row of a list arrives, moves to its new place and leaves; nothing when [animate] is off. */
internal fun <T> pitSpec(animate: Boolean, ms: Int): FiniteAnimationSpec<T>? = if (animate) tween(ms, easing = FastOutSlowInEasing) else null

internal fun pitPlacement(animate: Boolean): FiniteAnimationSpec<IntOffset>? = pitSpec(animate, 420)

/**
 * [text] whose characters roll over one by one as they change, so a lap count or a countdown ticks
 * like a counter and the characters that stay the same stay still. Figures are set at one width, so
 * nothing beside them shifts.
 */
@Composable
internal fun RollingText(text: String, colour: Color, px: Float, animate: Boolean, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Medium) {
    if (!animate) {
        Text(text, color = colour, fontSize = pxToSp(px), fontWeight = weight, maxLines = 1, softWrap = false, modifier = modifier)
        return
    }
    val size = pxToSp(px)
    val style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
    // Read as the one piece of text it is, not letter by letter.
    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        text.forEachIndexed { i, c ->
            AnimatedContent(
                targetState = c,
                transitionSpec = {
                    (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                        (slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(200))) using SizeTransform(clip = false)
                },
                label = "figure $i",
            ) { shown ->
                Text(shown.toString(), color = colour, fontSize = size, fontWeight = weight, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The session's state in a tinted pill: its colour eases to a new flag's and its words fade over. */
@Composable
internal fun StatusPill(label: String, shade: Color, px: Float, animate: Boolean) {
    val colour by animateColorAsState(shade, tween(if (animate) 400 else 0), label = "flag colour")
    Box(
        Modifier.background(colour.copy(alpha = 0.2f), RoundedCornerShape(pxToDp(px)))
            .padding(horizontal = pxToDp(px * 0.75f), vertical = pxToDp(px * 0.22f)),
    ) {
        AnimatedContent(label, transitionSpec = { pitFade(animate, 250) }, label = "flag words") { words ->
            Text(words, color = colour, fontSize = pxToSp(px), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A brief wash behind a row of the order when something happens to its car: green for a place gained,
 * red for one lost, purple for a new fastest lap. Nothing on the row's first showing.
 */
@Composable
internal fun rowPulse(position: Int, fastest: Boolean, animate: Boolean): Modifier {
    val alpha = remember { Animatable(0f) }
    val was = remember { intArrayOf(position, if (fastest) 1 else 0) }
    var tint by remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(position, fastest) {
        val moved = position - was[0]
        val newFastest = fastest && was[1] == 0
        was[0] = position
        was[1] = if (fastest) 1 else 0
        if (!animate || (moved == 0 && !newFastest)) return@LaunchedEffect
        tint = if (newFastest) PitPurple else if (moved < 0) Gain else Loss
        alpha.snapTo(0.4f)
        alpha.animateTo(0f, tween(900))
    }
    return Modifier.drawBehind {
        val a = alpha.value
        if (a > 0f) drawRoundRect(tint.copy(alpha = a), cornerRadius = CornerRadius(size.height * 0.2f))
    }
}

/** The circuit as [TrackOutline] draws it in a box of [size]: the closed line and how thick it is drawn. Null when the box is too small. */
private fun outlineOf(track: F1Track, size: Size, strokeShare: Float): Pair<Path, Float>? {
    val stroke = (min(size.width, size.height) * strokeShare).coerceAtLeast(1.5f)
    val spanW = if (track.aspect >= 1f) 1f else track.aspect
    val spanH = if (track.aspect >= 1f) 1f / track.aspect else 1f
    val scale = min((size.width - 2 * stroke) / spanW, (size.height - 2 * stroke) / spanH)
    if (scale <= 0f || track.points.size < 2) return null
    val dx = (size.width - spanW * scale) / 2
    val dy = (size.height - spanH * scale) / 2
    val path = Path()
    track.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(dx + x * scale, dy + y * scale) else path.lineTo(dx + x * scale, dy + y * scale) }
    path.close()
    return path to stroke
}

/** A colour running once round the circuit from the start line: how far it has got, and how strongly it shows. */
private class Sweep {
    var colour by mutableStateOf<Color?>(null)
    val progress = Animatable(0f)
    val strength = Animatable(1f)

    suspend fun run(shade: Color) {
        colour = shade
        strength.snapTo(1f)
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
    }

    suspend fun fadeAway() {
        strength.animateTo(0f, tween(600))
        colour = null
    }
}

/**
 * The flag on the circuit itself, drawn under the map's own line. A new flag's colour ([shade]) runs
 * once round the track and stays while the flag is out; with [pulse] (a red flag, a safety car) it
 * then dips and comes back once. When the flag goes in, the colour fades. [finish] turning true (the
 * chequered flag) and [enter] (PitWall has just turned to this screen) each send [plain] once round
 * and let it fade.
 */
@Composable
internal fun FlagTrack(
    track: F1Track, shade: Color?, plain: Color, pulse: Boolean, finish: Boolean, enter: Boolean, animate: Boolean,
    modifier: Modifier, strokeShare: Float = 0.03f,
) {
    var steady by remember { mutableStateOf(shade) }
    val steadyStrength = remember { Animatable(1f) }
    val flag = remember { Sweep() }
    val passing = remember { Sweep() }
    LaunchedEffect(shade) {
        flag.colour = null
        steadyStrength.snapTo(1f)
        if (shade == steady) return@LaunchedEffect
        if (!animate) {
            steady = shade
            return@LaunchedEffect
        }
        if (shade == null) {
            steadyStrength.animateTo(0f, tween(600))
            steady = null
            steadyStrength.snapTo(1f)
        } else {
            flag.run(shade)
            steady = shade
            flag.colour = null
            if (pulse) {
                steadyStrength.animateTo(0.35f, tween(350))
                steadyStrength.animateTo(1f, tween(450))
            }
        }
    }
    val finished = remember { booleanArrayOf(finish) }
    LaunchedEffect(Unit) {
        if (enter && animate) {
            passing.run(plain)
            passing.fadeAway()
        }
    }
    LaunchedEffect(finish) {
        val justFinished = finish && !finished[0]
        finished[0] = finish
        if (animate && justFinished) {
            passing.run(plain)
            passing.fadeAway()
        }
    }
    Box(
        modifier.drawWithCache {
            val outline = outlineOf(track, size, strokeShare)
            val measure = PathMeasure()
            val part = Path()
            onDrawBehind {
                val (path, stroke) = outline ?: return@onDrawBehind
                val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                steady?.let { drawPath(path, it.copy(alpha = it.alpha * steadyStrength.value), style = style) }
                for (sweep in arrayOf(flag, passing)) {
                    val colour = sweep.colour ?: continue
                    measure.setPath(path, false)
                    part.reset()
                    measure.getSegment(0f, measure.length * sweep.progress.value, part, true)
                    drawPath(part, colour.copy(alpha = colour.alpha * sweep.strength.value), style = style)
                }
            }
        },
    )
}

/** The circuit's outline, as [TrackOutline] draws it; with [draw] it is drawn on once, from the start line round, when first shown. */
@Composable
internal fun DrawnOutline(track: F1Track, colour: Color, draw: Boolean, modifier: Modifier, strokeShare: Float = 0.035f) {
    val progress = remember { Animatable(if (draw) 0f else 1f) }
    LaunchedEffect(Unit) { if (draw) progress.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    Box(
        modifier.drawWithCache {
            val outline = outlineOf(track, size, strokeShare)
            val measure = PathMeasure()
            val part = Path()
            onDrawBehind {
                val (path, stroke) = outline ?: return@onDrawBehind
                val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val shown = progress.value
                if (shown >= 1f) drawPath(path, colour, style = style)
                else {
                    measure.setPath(path, false)
                    part.reset()
                    measure.getSegment(0f, measure.length * shown, part, true)
                    drawPath(part, colour, style = style)
                }
            }
        },
    )
}
