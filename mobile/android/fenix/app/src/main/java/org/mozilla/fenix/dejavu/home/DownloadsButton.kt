/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import java.text.NumberFormat
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.content.DownloadState
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.DejavuIcons
import org.mozilla.fenix.dejavu.ui.IconStyle
import org.mozilla.fenix.dejavu.ui.iconPainter

/**
 * What the downloads button on the home screen shows about the downloads in the browser.
 *
 * @property runningIds Downloads that are not finished: waiting to start, downloading or paused.
 * @property pausedIds The paused ones among them.
 * @property completedIds Downloads that finished.
 * @property failedIds Downloads that failed.
 * @property progress Share of the bytes of the running downloads copied so far, or `null` while none of them knows
 *   its size.
 */
data class DownloadsStatus(
    val runningIds: Set<String> = emptySet(),
    val pausedIds: Set<String> = emptySet(),
    val completedIds: Set<String> = emptySet(),
    val failedIds: Set<String> = emptySet(),
    val progress: Float? = null,
) {
    val isRunning: Boolean
        get() = runningIds.isNotEmpty()

    /** Whether every running download is paused. */
    val isPaused: Boolean
        get() = isRunning && pausedIds.containsAll(runningIds)
}

/** What the downloads button shows about the downloads of [this] state. */
internal fun BrowserState.downloadsStatus(): DownloadsStatus {
    val all = downloads.values
    val running = all.filter { it.status in RunningStatuses }
    val sized = running.filter { (it.contentLength ?: 0L) > 0L }
    val total = sized.sumOf { it.contentLength ?: 0L }
    return DownloadsStatus(
        runningIds = running.mapTo(mutableSetOf()) { it.id },
        pausedIds = running.filter { it.status == DownloadState.Status.PAUSED }.mapTo(mutableSetOf()) { it.id },
        completedIds = all.filter { it.status == DownloadState.Status.COMPLETED }.mapTo(mutableSetOf()) { it.id },
        failedIds = all.filter { it.status == DownloadState.Status.FAILED }.mapTo(mutableSetOf()) { it.id },
        progress = if (total > 0L) (sized.sumOf { it.currentBytesCopied }.toFloat() / total).coerceIn(0f, 1f) else null,
    )
}

private val RunningStatuses = setOf(
    DownloadState.Status.INITIATED,
    DownloadState.Status.DOWNLOADING,
    DownloadState.Status.PAUSED,
)

/**
 * The downloads button of the home screen. While files download, a ring around the icon fills as they do, or spins
 * while their size is unknown, and the arrow keeps sinking into the bowl and dropping back in; a number counts the
 * downloads past one. When the last one finishes the ring fills, pops and a check mark takes the arrow's place for a
 * moment; when it fails the ring flashes red and the icon shakes. Paused downloads hold everything still.
 *
 * @param status The downloads, read only here so their updates redraw nothing else on the home screen.
 */
@Composable
internal fun DownloadsButton(status: State<DownloadsStatus>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val current = status.value
    val finish = remember { Animatable(0f) }
    val failure = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }

    // A download that stops running finished or failed; the button marks it, while it is not busy with others.
    LaunchedEffect(status) {
        var before = status.value
        snapshotFlow { status.value }.collect { now ->
            val ended = before.runningIds - now.runningIds
            before = now
            when {
                ended.isEmpty() -> Unit
                now.isRunning -> if (ended.any { it in now.completedIds }) {
                    launch {
                        pulse.snapTo(1f)
                        pulse.animateTo(0f, tween(PULSE_MS))
                    }
                }
                ended.any { it in now.failedIds } -> launch {
                    failure.snapTo(0f)
                    failure.animateTo(1f, tween(FAILURE_MS, easing = LinearEasing))
                    failure.snapTo(0f)
                }
                ended.any { it in now.completedIds } -> launch {
                    finish.snapTo(0f)
                    finish.animateTo(1f, tween(FINISH_MS, easing = LinearEasing))
                    finish.snapTo(0f)
                }
            }
        }
    }

    // Progress comes every 750 ms; the ring moves on steadily in between, and jumps back for a new download.
    val shownProgress = remember { Animatable(current.progress ?: 0f) }
    LaunchedEffect(current.progress) {
        val target = current.progress ?: return@LaunchedEffect
        if (target < shownProgress.value) {
            shownProgress.snapTo(target)
        } else {
            shownProgress.animateTo(target, tween(PROGRESS_STEP_MS, easing = LinearEasing))
        }
    }
    val ring = animateFloatAsState(if (current.isRunning) 1f else 0f, tween(RING_FADE_MS), label = "DownloadsRing")

    val idle = remember { mutableFloatStateOf(0f) }
    val loop: State<Float>
    val spin: State<Float>
    if (current.isRunning && !current.isPaused) {
        val transition = rememberInfiniteTransition(label = "Downloading")
        loop = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(ARROW_LOOP_MS, easing = LinearEasing)),
            label = "DownloadsArrow",
        )
        spin = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)),
            label = "DownloadsSpin",
        )
    } else {
        loop = idle
        spin = idle
    }

    val colors = MaterialTheme.colorScheme
    val iconColor = colors.onSurface
    val activeColor = if (current.isPaused) colors.onSurfaceVariant else colors.primary
    val trackColor = colors.onSurface.copy(alpha = TRACK_ALPHA)
    val errorColor = colors.error
    val tray = remember { PathParser().parsePathString(TRAY_PATH).toPath() }
    val arrow = remember { PathParser().parsePathString(ARROW_PATH).toPath() }
    val check = remember { checkPath() }
    // The other styles of icons have no parts that move, so their icon stays still inside the ring.
    val styledIcon = if (DejavuIcons.style == IconStyle.DEJAVU) null else iconPainter(R.drawable.dejavu_ic_downloads_24)
    val percent = current.progress?.takeIf { current.isRunning }?.let { NumberFormat.getPercentInstance().format(it) }
    val label = stringResource(R.string.dejavu_downloads)

    // The badge sits outside the button, whose round shape would cut it off.
    Box(modifier = modifier) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.semantics {
                contentDescription = label
                if (percent != null) stateDescription = percent
            },
        ) {
            Canvas(modifier = Modifier.size(ButtonSize)) {
                val f = finish.value
                val q = failure.value
                val celebrating = f > 0f
                val failing = q > 0f
                val ringAlpha = when {
                    failing -> 1f - phase(q, FAILURE_FADE_START, 1f)
                    celebrating -> 1f - phase(f, RING_POP_END, RING_GONE)
                    else -> ring.value
                }
                val sweep = when {
                    failing -> 1f
                    celebrating -> lerp(shownProgress.value, 1f, phase(f, 0f, RING_FILLED))
                    current.progress == null -> null
                    else -> shownProgress.value
                }
                val ringScale = 1f + RING_POP * when {
                    celebrating -> sin(PI.toFloat() * phase(f, RING_POP_START, RING_POP_END))
                    else -> sin(PI.toFloat() * pulse.value)
                }
                val shake = if (failing) sin(q * SHAKES * 2 * PI.toFloat()) * Shake.toPx() * (1f - q) else 0f

                if (ringAlpha > 0f) {
                    scale(ringScale) {
                        drawRing(
                            sweep = sweep,
                            spin = spin.value,
                            color = if (failing) errorColor else activeColor,
                            trackColor = trackColor,
                            alpha = ringAlpha,
                        )
                    }
                }

                // The icon shrinks a little to make room for the ring, and grows back as the finish ends.
                val roomForRing = if (celebrating) 1f - phase(f, CELEBRATION_RETURN, 1f) else ringAlpha
                val glyphScale = lerp(1f, RUNNING_GLYPH_SCALE, roomForRing)
                val drop = when {
                    celebrating -> celebrationDrop(f)
                    else -> arrowDrop(loop.value)
                }
                val checkTrim = if (celebrating) LinearOutSlowInEasing.transform(phase(f, CHECK_START, CHECK_DRAWN)) else 0f
                val checkAlpha = 1f - phase(f, CHECK_FADE_START, CHECK_FADE_END)
                translate(left = shake) {
                    val checking = checkTrim > 0f && checkAlpha > 0f
                    when {
                        styledIcon == null -> drawGlyph(tray, arrow, drop, iconColor, glyphScale)
                        !checking -> drawStyledGlyph(styledIcon, iconColor, glyphScale)
                    }
                    if (checking) drawCheck(check, checkTrim, activeColor.copy(alpha = checkAlpha), glyphScale)
                }
            }
        }
        CountBadge(count = current.runningIds.size, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** How many downloads are running, on the corner of the button, when there is more than one. */
@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    // While it fades away, the badge keeps the last number it showed rather than "1".
    val shown = remember { mutableIntStateOf(count) }
    if (count > 1) shown.intValue = count
    AnimatedVisibility(
        visible = count > 1,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        // Just outside the ring, so it covers none of the progress.
        modifier = modifier.offset(x = BadgeOffset, y = -BadgeOffset),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .sizeIn(minWidth = BadgeSize, minHeight = BadgeSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = shown.intValue.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * The ring around the icon: a faint track, and over it the share [sweep] of a full turn from the top, or a spinning
 * arc at [spin] of its turn while [sweep] is `null`.
 */
private fun DrawScope.drawRing(sweep: Float?, spin: Float, color: Color, trackColor: Color, alpha: Float) {
    val stroke = RingStroke.toPx()
    val inset = stroke / 2 + RingInset.toPx()
    val topLeft = Offset(inset, inset)
    val arcSize = size.copy(width = size.width - 2 * inset, height = size.height - 2 * inset)
    val style = Stroke(width = stroke, cap = StrokeCap.Round)
    drawArc(trackColor, 0f, FULL_TURN, false, topLeft, arcSize, alpha = alpha, style = style)
    if (sweep == null) {
        val length = lerp(SPIN_MIN_SWEEP, SPIN_MAX_SWEEP, (sin(spin * 2 * PI.toFloat()) + 1f) / 2f)
        drawArc(color, spin * FULL_TURN - QUARTER_TURN, length, false, topLeft, arcSize, alpha = alpha, style = style)
    } else if (sweep > 0f) {
        drawArc(color, -QUARTER_TURN, sweep * FULL_TURN, false, topLeft, arcSize, alpha = alpha, style = style)
    }
}

/**
 * Dejavu's downloads icon, the bowl and the arrow [drop] icon units below its place, [glyphScale] of its size. The
 * arrow shows only between the top of the icon and the bottom of the bowl, so it sinks into the bowl and drops in from
 * above.
 */
private fun DrawScope.drawGlyph(tray: Path, arrow: Path, drop: Float, color: Color, glyphScale: Float) {
    val line = Stroke(width = GLYPH_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
    inIconUnits(glyphScale) {
        drawPath(tray, color, style = line)
        clipRect(top = ARROW_CLIP_TOP, bottom = ARROW_CLIP_BOTTOM) {
            translate(top = drop) { drawPath(arrow, color.copy(alpha = color.alpha * ARROW_ALPHA), style = line) }
        }
    }
}

/** The downloads icon of another [IconStyle], [glyphScale] of its size, which the check mark takes the place of. */
private fun DrawScope.drawStyledGlyph(icon: Painter, color: Color, glyphScale: Float) {
    val side = IconSize.toPx() * glyphScale
    translate((size.width - side) / 2, (size.height - side) / 2) {
        with(icon) { draw(Size(side, side), colorFilter = ColorFilter.tint(color)) }
    }
}

/** The check mark drawn as far as [trim] of its length, over the tray. */
private fun DrawScope.drawCheck(check: Path, trim: Float, color: Color, glyphScale: Float) {
    val measure = PathMeasure().apply { setPath(check, false) }
    val part = Path()
    measure.getSegment(0f, measure.length * trim, part, true)
    inIconUnits(glyphScale) {
        drawPath(part, color, style = Stroke(width = CHECK_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Draws [block] in the 24 by 24 units of Firefox's icons, centered and [glyphScale] of the icon size. */
private fun DrawScope.inIconUnits(glyphScale: Float, block: DrawScope.() -> Unit) {
    val unit = IconSize.toPx() / ICON_UNITS * glyphScale
    val left = (size.width - ICON_UNITS * unit) / 2
    val top = (size.height - ICON_UNITS * unit) / 2
    withTransform({
        translate(left, top)
        scale(unit, unit, pivot = Offset.Zero)
    }) { block() }
}

private fun checkPath() = Path().apply {
    moveTo(CHECK_START_X, CHECK_START_Y)
    lineTo(CHECK_CORNER_X, CHECK_CORNER_Y)
    lineTo(CHECK_END_X, CHECK_END_Y)
}

/** How far [t] is from [start] to [end], from 0 to 1. */
private fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)

/**
 * How far the arrow is below its place, in icon units, at [p] of its loop: it rests, sinks into the tray, and drops
 * back in from above to rest again.
 */
private fun arrowDrop(p: Float): Float = when {
    p < ARROW_REST_END -> 0f
    p < ARROW_SINK_END -> SINK_DEPTH * FastOutLinearInEasing.transform(phase(p, ARROW_REST_END, ARROW_SINK_END))
    else -> -ENTER_HEIGHT * (1f - LinearOutSlowInEasing.transform(phase(p, ARROW_SINK_END, 1f)))
}

/** Where the arrow is at [f] of the finish: it sinks into the tray, stays away for the check mark and drops back in. */
private fun celebrationDrop(f: Float): Float = when {
    f < CELEBRATION_SINK_END -> SINK_DEPTH * FastOutLinearInEasing.transform(phase(f, 0f, CELEBRATION_SINK_END))
    f < CELEBRATION_RETURN -> SINK_DEPTH
    else -> -ENTER_HEIGHT * (1f - LinearOutSlowInEasing.transform(phase(f, CELEBRATION_RETURN, 1f)))
}

private val ButtonSize = 40.dp
private val IconSize = 24.dp
private val RingStroke = 2.5.dp
private val RingInset = 1.dp
private val Shake = 3.dp
private val BadgeSize = 16.dp
private val BadgeOffset = 4.dp

private const val ICON_UNITS = 24f
private const val RUNNING_GLYPH_SCALE = 0.8f
private const val TRACK_ALPHA = 0.12f
private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
private const val SPIN_MIN_SWEEP = 50f
private const val SPIN_MAX_SWEEP = 200f
private const val RING_POP = 0.12f
private const val SHAKES = 3

// The lines of dejavu_ic_downloads_24: the bowl, and the arrow in it, drawn lighter.
private const val TRAY_PATH = "M20 12.1802C20 16.6002 17 20.1802 12 20.1802C7 20.1802 4 16.6002 4 12.1802"
private const val ARROW_PATH = "M11.8809 4V14.17M9.32031 11.6797L11.8803 14.2397L14.4403 11.6797"
private const val GLYPH_STROKE = 1.5f
private const val ARROW_ALPHA = 0.4f

// The arrow shows between these lines, in icon units: from above the icon down to the bottom of the bowl.
private const val ARROW_CLIP_TOP = 0.5f
private const val ARROW_CLIP_BOTTOM = 19.4f
private const val SINK_DEPTH = 16.5f
private const val ENTER_HEIGHT = 15f

// The check mark, in icon units, above the bowl.
private const val CHECK_START_X = 7.6f
private const val CHECK_START_Y = 10.4f
private const val CHECK_CORNER_X = 10.8f
private const val CHECK_CORNER_Y = 13.6f
private const val CHECK_END_X = 16.6f
private const val CHECK_END_Y = 7.2f
private const val CHECK_STROKE = 1.6f

private const val ARROW_LOOP_MS = 1400
private const val SPIN_MS = 1100
private const val PROGRESS_STEP_MS = 750
private const val RING_FADE_MS = 300
private const val PULSE_MS = 450
private const val FAILURE_MS = 900
private const val FINISH_MS = 2000

// Parts of the arrow's loop: it rests, then sinks into the tray, then drops back in.
private const val ARROW_REST_END = 0.3f
private const val ARROW_SINK_END = 0.62f

// Parts of the finish: the ring fills, the arrow sinks, the check mark draws as the ring pops and fades, the check
// mark stays a moment and fades, and the arrow drops back in.
private const val RING_FILLED = 0.15f
private const val CELEBRATION_SINK_END = 0.3f
private const val CHECK_START = 0.26f
private const val CHECK_DRAWN = 0.46f
private const val RING_POP_START = 0.25f
private const val RING_POP_END = 0.5f
private const val RING_GONE = 0.72f
private const val CHECK_FADE_START = 0.74f
private const val CHECK_FADE_END = 0.82f
private const val CELEBRATION_RETURN = 0.86f

private const val FAILURE_FADE_START = 0.3f
