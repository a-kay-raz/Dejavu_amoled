/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.discover

import android.os.StrictMode
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.toRect
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.ui.DejavuMotion
import org.mozilla.fenix.ext.components

/**
 * Marks this as the place [tip] points at, where a ghost finger does [gesture], touching it [at] across. A list says
 * with [enabled] which of its rows is the place. The tip says [text] here. A place that [drawsItself] draws its ring
 * and finger on itself, as the bars around pages do, where no [TipHost] covers the screen; on the home screen the host
 * draws them over everything.
 */
@Composable
internal fun Modifier.tipTarget(
    tip: Tip,
    gesture: TipGesture,
    enabled: Boolean = true,
    at: Float = CENTER,
    @StringRes text: Int = tip.text,
    drawsItself: Boolean = false,
): Modifier {
    val owner = remember { Any() }
    DisposableEffect(owner, enabled) {
        onDispose { Tips.remove(owner) }
    }
    if (!enabled) return this
    val placed = onGloballyPositioned { Tips.place(owner, tip, gesture, text, at, drawsItself, it) }
    if (!drawsItself) return placed
    val showing = Tips.shown == tip
    val clock = tipClock(tip.takeIf { showing })
    val ink = MaterialTheme.colorScheme.onSurface
    return placed.drawWithContent {
        drawContent()
        val time = clock.value
        if (showing) {
            // The bars are pills, which the ring follows inside their edge.
            val place = Rect(Offset.Zero, size)
            drawTip(place, gesture, at, time, ink, place, ringGap = -RING_WIDTH.toPx(), ringCorner = size.height / 2)
        }
    }
}

/**
 * Shows the tips of [screen] while [allowed], once the screen has kept still for a moment: the bubble of the first tip
 * due whose place is in view, and when [drawsTargets], the ring and the finger over its place, drawn across [modifier],
 * which then covers the screen. Touches go through to the screen; one outside the bubble closes it. A menu or a dialog
 * over the screen takes the window's focus, and hides the tip until it closes.
 */
@Composable
internal fun TipHost(
    screen: TipScreen,
    allowed: Boolean,
    modifier: Modifier = Modifier,
    drawsTargets: Boolean = false,
) {
    val context = LocalContext.current
    val settings = remember {
        context.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { DejavuSettings.get(context) }
    }
    val enabled by settings.tipsEnabled.collectAsState()
    val seen by settings.seenTips.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    val active = allowed && enabled && focused && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val latestSeen by rememberUpdatedState(seen)
    LaunchedEffect(screen, active) {
        Tips.hide(screen)
        if (!active) return@LaunchedEffect
        delay(if (screen == TipScreen.PAGE) PAGE_SETTLE_MS else HOME_SETTLE_MS)
        snapshotFlow { Tips.pick(screen, latestSeen) }.collectLatest { tip ->
            if (tip != null && Tips.shown != tip) delay(Tips.pauseLeft())
            Tips.show(tip)
        }
    }
    // A tip left showing goes with its screen, and shows again next time.
    DisposableEffect(screen) {
        onDispose { Tips.hide(screen) }
    }

    val tip = Tips.shown?.takeIf { it.screen == screen }
    val target = tip?.let(Tips::targetOf)
    val placement = remember(tip) { BubblePlacement() }
    if (drawsTargets) {
        val clock = tipClock(tip.takeIf { target != null })
        val ink = MaterialTheme.colorScheme.onSurface
        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = modifier
                .onGloballyPositioned { origin = it.positionInWindow() }
                // A layer of its own, so the finger moving on every frame redraws nothing else.
                .graphicsLayer { }
                .drawWithContent {
                    val time = clock.value
                    val place = target?.takeUnless { it.drawsItself } ?: return@drawWithContent
                    val screenArea = Rect(Offset.Zero, size)
                    val bubble = placement.bounds.takeUnless { it.isEmpty }?.toRect()?.translate(-origin) ?: Rect.Zero
                    drawTip(place.bounds.translate(-origin), place.gesture, place.at, time, ink, screenArea, bubble)
                },
        )
    }
    if (tip != null && target != null) {
        key(tip) {
            TipBubble(
                text = stringResource(target.text),
                place = target.bounds,
                placement = placement,
                onGotIt = { Tips.done(tip) },
                onHide = { Tips.setEnabled(settings, false) },
                onTouchedElsewhere = { readable -> if (readable) Tips.done(tip) else Tips.putOff(tip) },
            )
        }
    }
}

/**
 * The milliseconds since [tip] started showing, counted on every frame while it shows, or 0 without one. With
 * animations off, the clock stands still once the ring around the tip's place has shown.
 */
@Composable
private fun tipClock(tip: Tip?): State<Long> = produceState(0L, tip) {
    value = 0L
    if (tip == null) return@produceState
    if (!DejavuMotion.animates) {
        value = RING_APPEAR_MS.toLong()
        return@produceState
    }
    val start = withFrameMillis { it }
    while (true) {
        value = withFrameMillis { it } - start
    }
}

/**
 * The bubble of a tip saying [text], next to its [place] in the window: below it, or above it without room below, its
 * arrow pointing at it, as it tells [placement]. It grows out of its arrow. [onTouchedElsewhere] learns of a touch
 * outside it, which closes it, and whether the bubble showed long enough to be read.
 */
@Composable
private fun TipBubble(
    text: String,
    place: Rect,
    placement: BubblePlacement,
    onGotIt: () -> Unit,
    onHide: () -> Unit,
    onTouchedElsewhere: (readable: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val shownAt = remember { SystemClock.uptimeMillis() }
    val top = WindowInsets.statusBars.getTop(density)
    val position = with(density) {
        BubblePosition(place.roundToIntRect(), BUBBLE_MARGIN.roundToPx(), top, PLACE_GAP.roundToPx(), placement)
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { onTouchedElsewhere(SystemClock.uptimeMillis() - shownAt >= READ_MS) },
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = true),
    ) {
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            appear.animateTo(1f, spring(dampingRatio = BUBBLE_BOUNCE, stiffness = Spring.StiffnessMediumLow))
        }
        val colors = MaterialTheme.colorScheme
        val below = placement.below
        val arrowX = placement.arrowX
        val shape = with(density) {
            remember(below, arrowX) { BubbleShape(arrowX, below, ARROW_SIZE.toPx(), BUBBLE_CORNER.toPx()) }
        }
        Column(
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .graphicsLayer {
                    val grown = appear.value
                    alpha = grown.coerceIn(0f, 1f)
                    scaleX = lerp(BUBBLE_START_SCALE, 1f, grown)
                    scaleY = scaleX
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (size.width > 0f) (arrowX / size.width).coerceIn(0f, 1f) else CENTER,
                        pivotFractionY = if (below) 0f else 1f,
                    )
                }
                .shadow(BUBBLE_ELEVATION, shape)
                .background(colors.inverseSurface, shape)
                .border(1.dp, colors.inverseOnSurface.copy(alpha = BUBBLE_EDGE_ALPHA), shape)
                .padding(vertical = ARROW_SIZE)
                .padding(start = 16.dp, top = 12.dp, end = 6.dp, bottom = 2.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inverseOnSurface,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(
                    onClick = onHide,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = colors.inverseOnSurface.copy(alpha = HIDE_ALPHA),
                    ),
                ) {
                    Text(stringResource(R.string.dejavu_tip_hide))
                }
                TextButton(
                    onClick = onGotIt,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
                ) {
                    Text(stringResource(R.string.dejavu_tip_got_it))
                }
            }
        }
    }
}

/**
 * Where the bubble of a tip went: below its place or above it, with its arrow [arrowX] from its left edge, and its
 * [bounds] in the window.
 */
private class BubblePlacement {
    var below by mutableStateOf(true)
    var arrowX by mutableFloatStateOf(0f)
    var bounds by mutableStateOf(IntRect.Zero)
}

/**
 * Puts a tip's bubble [gap] below its [place], or above it when it does not fit below, between the window's sides
 * [margin] in, and below the status bar's [top]. It tells [placement] where it went.
 */
private data class BubblePosition(
    private val place: IntRect,
    private val margin: Int,
    private val top: Int,
    private val gap: Int,
    private val placement: BubblePlacement,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val height = popupContentSize.height
        val below = place.bottom + gap + height <= windowSize.height - margin || place.top - gap - height < top + margin
        val x = (place.center.x - popupContentSize.width / 2)
            .coerceIn(margin, (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin))
        val y = if (below) place.bottom + gap else place.top - gap - height
        placement.below = below
        placement.arrowX = (place.center.x - x).toFloat()
        placement.bounds = IntRect(IntOffset(x, y), popupContentSize)
        return IntOffset(x, y)
    }
}

/**
 * A bubble with an [arrow] pointing up from its top edge when [below] its place, or down from its bottom edge, [arrowX]
 * from its left edge. The room for the arrow is kept above and below the bubble's rounded card.
 */
private class BubbleShape(
    private val arrowX: Float,
    private val below: Boolean,
    private val arrow: Float,
    private val corner: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val card = Rect(0f, arrow, size.width, size.height - arrow)
        val x = arrowX.coerceIn(corner + arrow, (size.width - corner - arrow).coerceAtLeast(corner + arrow))
        val path = Path().apply {
            addRoundRect(RoundRect(card, CornerRadius(corner)))
            val edge = if (below) card.top else card.bottom
            moveTo(x - arrow, edge)
            lineTo(x, if (below) 0f else size.height)
            lineTo(x + arrow, edge)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * How a ghost finger moves through one round of a gesture, in milliseconds: it comes down, presses, holds, moves, lets
 * go, then rests before the next round.
 */
private class FingerRound(val down: Int, val press: Int, val hold: Int, val move: Int, val up: Int, val rest: Int) {
    val length: Int
        get() = down + press + hold + move + up + rest
}

private val TapRound = FingerRound(down = 250, press = 150, hold = 0, move = 0, up = 350, rest = 1250)
private val HoldRound = FingerRound(down = 250, press = 100, hold = 900, move = 0, up = 350, rest = 1000)
private val DragRound = FingerRound(down = 250, press = 100, hold = 600, move = 900, up = 300, rest = 850)
private val SwipeRound = FingerRound(down = 200, press = 0, hold = 0, move = 550, up = 200, rest = 850)

private val TipGesture.round: FingerRound
    get() = when (this) {
        TipGesture.Tap -> TapRound
        TipGesture.Hold -> HoldRound
        is TipGesture.Drag -> DragRound
        is TipGesture.Swipe -> SwipeRound
    }

/**
 * Draws the breathing ring around a tip's [place], [ringGap] outside it with corners of [ringCorner], and the ghost
 * finger doing [gesture] on it, [time] milliseconds after the tip showed, in [ink]. The finger touches the place [at]
 * across it; a swipe over the page and a drag to the top go by the [screen]'s area, a swipe over the page clear of the
 * tip's [bubble].
 */
@Suppress("LongParameterList")
private fun DrawScope.drawTip(
    place: Rect,
    gesture: TipGesture,
    at: Float,
    time: Long,
    ink: Color,
    screen: Rect,
    bubble: Rect = Rect.Zero,
    ringGap: Float = RING_GAP.toPx(),
    ringCorner: Float = RING_CORNER.toPx(),
) {
    val appear = (time / RING_APPEAR_MS.toFloat()).coerceIn(0f, 1f)
    val breath = (1f - cos(time * 2 * PI / BREATH_MS).toFloat()) / 2f
    val ring = place.inflate(ringGap)
    val corner = min(ring.height / 2f, ringCorner)
    val glow = GLOW_WIDTH.toPx()
    drawRoundRect(
        color = ink.copy(alpha = GLOW_ALPHA * breath * appear),
        topLeft = ring.inflate(glow / 2).topLeft,
        size = ring.inflate(glow / 2).size,
        cornerRadius = CornerRadius(corner + glow / 2),
        style = Stroke(glow),
    )
    drawRoundRect(
        color = ink.copy(alpha = (RING_ALPHA + RING_BREATH * breath) * appear),
        topLeft = ring.topLeft,
        size = ring.size,
        cornerRadius = CornerRadius(corner),
        style = Stroke(RING_WIDTH.toPx()),
    )
    val played = time - FINGER_DELAY_MS
    if (played < 0) return
    val start = fingerStart(place, gesture, at, screen, bubble)
    val end = fingerEnd(start, gesture, screen)
    drawFinger(gesture.round, (played % gesture.round.length).toInt(), start, end, ink)
}

/**
 * Where the finger touches down: [at] across [place], or for a swipe over the page, over the page beside it, past the
 * [bubble] when that lies on the same side.
 */
private fun DrawScope.fingerStart(place: Rect, gesture: TipGesture, at: Float, screen: Rect, bubble: Rect): Offset {
    if (gesture !is TipGesture.Swipe || !gesture.overPage) return Offset(place.left + place.width * at, place.center.y)
    val away = OVER_PAGE.toPx()
    val clear = BUBBLE_CLEARANCE.toPx()
    val above = place.center.y > screen.center.y
    var y = if (above) place.top - away else place.bottom + away
    if (!bubble.isEmpty) y = if (above) min(y, bubble.top - clear) else max(y, bubble.bottom + clear)
    return Offset(screen.center.x - gesture.dx.toPx() / 2, y)
}

/** Where the finger lets go, having touched down at [start]. */
private fun DrawScope.fingerEnd(start: Offset, gesture: TipGesture, screen: Rect): Offset = when (gesture) {
    is TipGesture.Drag -> if (gesture.toTop) {
        Offset(start.x, screen.top + TOP_DROP.toPx())
    } else {
        start + Offset(0f, gesture.dy.toPx())
    }
    is TipGesture.Swipe -> start + Offset(gesture.dx.toPx(), gesture.dy.toPx())
    TipGesture.Tap, TipGesture.Hold -> start
}

/**
 * Draws the finger [t] milliseconds into a [round] from [start] to [end]: a hold fills a ring around it, a move leaves
 * a fading trail, and letting go of a tap or a hold sends out a ripple.
 */
private fun DrawScope.drawFinger(round: FingerRound, t: Int, start: Offset, end: Offset, ink: Color) {
    val pressedAt = round.down + round.press
    val heldAt = pressedAt + round.hold
    val movedAt = heldAt + round.move
    val upAt = movedAt + round.up
    val ripples = round.move == 0 || round.hold > 0
    when {
        t < round.down -> {
            val landed = t / round.down.toFloat()
            finger(start, lerp(LANDING_SCALE, 1f, landed), landed, ink)
        }
        t < pressedAt -> finger(start, lerp(1f, PRESSED_SCALE, (t - round.down) / round.press.toFloat()), 1f, ink)
        t < heldAt -> {
            finger(start, PRESSED_SCALE, 1f, ink)
            holdRing(start, (t - pressedAt) / round.hold.toFloat(), 1f, ink)
        }
        t < movedAt -> {
            val moved = FastOutSlowInEasing.transform((t - heldAt) / round.move.toFloat())
            val now = lerp(start, end, moved)
            trail(start, now, 1f, ink)
            finger(now, PRESSED_SCALE, 1f, ink)
            if (round.hold > 0) holdRing(now, 1f, 1f - moved, ink)
        }
        t < upAt -> {
            val gone = (t - movedAt) / round.up.toFloat()
            if (round.move > 0) trail(start, end, 1f - gone, ink)
            if (ripples) ripple(end, gone, ink)
            finger(end, PRESSED_SCALE, 1f - gone, ink)
        }
    }
}

private fun DrawScope.finger(center: Offset, scale: Float, alpha: Float, ink: Color) {
    val radius = FINGER_RADIUS.toPx() * scale
    drawCircle(ink.copy(alpha = FINGER_FILL_ALPHA * alpha), radius, center)
    drawCircle(ink.copy(alpha = FINGER_EDGE_ALPHA * alpha), radius, center, style = Stroke(FINGER_EDGE.toPx()))
}

private fun DrawScope.holdRing(center: Offset, filled: Float, alpha: Float, ink: Color) {
    val radius = (FINGER_RADIUS + HOLD_RING_GAP).toPx()
    drawArc(
        color = ink.copy(alpha = HOLD_RING_ALPHA * alpha),
        startAngle = -90f,
        sweepAngle = 360f * filled,
        useCenter = false,
        topLeft = center - Offset(radius, radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(HOLD_RING_WIDTH.toPx(), cap = StrokeCap.Round),
    )
}

private fun DrawScope.trail(from: Offset, to: Offset, alpha: Float, ink: Color) {
    drawLine(ink.copy(alpha = TRAIL_ALPHA * alpha), from, to, TRAIL_WIDTH.toPx(), StrokeCap.Round)
}

private fun DrawScope.ripple(center: Offset, spread: Float, ink: Color) {
    val radius = FINGER_RADIUS.toPx() * lerp(1f, RIPPLE_SCALE, spread)
    drawCircle(ink.copy(alpha = RIPPLE_ALPHA * (1f - spread)), radius, center, style = Stroke(FINGER_EDGE.toPx()))
}

private const val CENTER = 0.5f
private const val HOME_SETTLE_MS = 1_200L
private const val PAGE_SETTLE_MS = 2_000L

// A bubble closed by a touch elsewhere this soon after it showed was not read, so it comes back later.
private const val READ_MS = 2_500L

private val BUBBLE_MAX_WIDTH = 300.dp
private val BUBBLE_MARGIN = 16.dp
private val BUBBLE_CORNER = 18.dp
private val BUBBLE_ELEVATION = 8.dp
private val ARROW_SIZE = 8.dp
private val PLACE_GAP = 6.dp
private const val BUBBLE_START_SCALE = 0.6f
private const val BUBBLE_BOUNCE = 0.62f
private const val BUBBLE_EDGE_ALPHA = 0.16f
private const val HIDE_ALPHA = 0.72f

private val RING_GAP = 4.dp
private val RING_WIDTH = 2.dp
private val RING_CORNER = 18.dp
private val GLOW_WIDTH = 6.dp
private const val RING_APPEAR_MS = 300
private const val BREATH_MS = 1_600.0
private const val RING_ALPHA = 0.35f
private const val RING_BREATH = 0.4f
private const val GLOW_ALPHA = 0.14f

private const val FINGER_DELAY_MS = 400L
private val FINGER_RADIUS = 13.dp
private val FINGER_EDGE = 1.5.dp
private val HOLD_RING_GAP = 6.dp
private val HOLD_RING_WIDTH = 2.5.dp
private val TRAIL_WIDTH = 3.dp
private val OVER_PAGE = 140.dp
private val BUBBLE_CLEARANCE = 44.dp
private val TOP_DROP = 88.dp
private const val LANDING_SCALE = 1.3f
private const val PRESSED_SCALE = 0.82f
private const val RIPPLE_SCALE = 2.6f
private const val FINGER_FILL_ALPHA = 0.28f
private const val FINGER_EDGE_ALPHA = 0.75f
private const val HOLD_RING_ALPHA = 0.9f
private const val TRAIL_ALPHA = 0.25f
private const val RIPPLE_ALPHA = 0.5f
