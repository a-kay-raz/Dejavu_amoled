/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.DejavuMotion
import org.mozilla.fenix.dejavu.ui.iconPainter

/** How many rows above and below the long-pressed one go into the stack. */
internal const val MAX_STACK_DEPTH = 2

private val CardHeight = 48.dp
private val RowCorner = 16.dp
private val StackCorner = 24.dp
private val StackPeek = 10.dp
private val BlockedEdgeWidth = 1.5.dp
private val CardElevation = 6.dp
private val BackCardElevation = 2.dp
private val StackMinWidth = 200.dp
private val StackMaxWidth = 300.dp
private val StackIconStart = 8.dp
private val StripeStart = 2.dp
private val StripeWidth = 4.dp
private val StripeHeight = 26.dp
private val IconBoxSize = 36.dp
private val BadgeSize = 22.dp
private const val STACK_WIDTH_FRACTION = 0.66f
private const val DEPTH_SCALE_STEP = 0.04f
private const val EDGE_ALPHA = 0.14f
private const val BACK_EDGE_ALPHA = 0.28f
private const val BACK_CARD_TINT = 0.1f
private const val STAGGER_MS = 30L
private const val FADE_MS = 200
private const val FADE_SCALE = 0.85f
private const val BACKGROUND_FADE_IN = 0.35f
private const val CONTENT_FADE_IN = 0.3f
private const val LIFT_START_FRAMES = 2
private const val BADGE_FADE_START = 0.35f
private const val BADGE_FADE_LENGTH = 0.35f
private const val BADGE_MIN_SCALE = 0.6f
private const val BADGE_INSET = 0.3f
private const val LIFT_THRESHOLD = 0.002f
private const val NANOS_PER_SECOND = 1_000_000_000f

// How fast the cards under the top one catch up with the finger, per second, one rate per depth.
private val FollowRates = floatArrayOf(22f, 16f)

private val LiftSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 380f, visibilityThreshold = LIFT_THRESHOLD)
private val SettleSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 420f, visibilityThreshold = LIFT_THRESHOLD)

/**
 * How a row looks lifted into the drag stack.
 *
 * @property key Key of the row in the list.
 * @property rank Place of the row among the lifted ones, counted from the long-pressed row: negative above it.
 * @property url Page whose icon the card shows, or `null` for a folder.
 * @property stripe Color of the container line at the start, if any.
 * @property iconStart Where the icon starts in the row, so the card lifts off without a jump.
 * @property brightTitle Whether the row shows its title bright, as for loaded tabs and folders, rather than dimmed.
 * @property tabId Tab the row shows, to find its row again after a drop.
 * @property pinId Pinned tab or folder the row shows, to find its row again after a drop.
 */
internal data class StackCard(
    val key: String,
    val rank: Int,
    val title: String,
    val url: String?,
    val stripe: Color?,
    val iconStart: Dp,
    val brightTitle: Boolean,
    val tabId: String?,
    val pinId: String?,
)

/**
 * Sizes of the stack, in pixels.
 *
 * @property width Width of the top card.
 * @property height Height of a card.
 * @property peek How far each card under the top one shows past the one above it.
 * @property maxLeft Furthest the top card goes to the right, so it stays on the page.
 * @property grab Where the finger holds the top card: x as a fraction of its width, y in pixels from its top.
 */
internal data class StackGeometry(
    val width: Float = 0f,
    val height: Float = 0f,
    val peek: Float = 0f,
    val maxLeft: Float = 0f,
    val grab: Offset = Offset.Zero,
)

/**
 * The stack for a list [listWidth] wide and [listLeft] from the start of the page, held where [press] took the
 * long-pressed row at [row].
 */
internal fun Density.stackGeometry(listLeft: Float, listWidth: Float, press: Offset, row: Rect): StackGeometry {
    val width = (listWidth * STACK_WIDTH_FRACTION).coerceIn(min(StackMinWidth.toPx(), listWidth), StackMaxWidth.toPx())
    val height = CardHeight.toPx()
    return StackGeometry(
        width = width,
        height = height,
        peek = StackPeek.toPx(),
        maxLeft = max(0f, listWidth + 2 * listLeft - width),
        grab = Offset(((press.x - row.left) / row.width).coerceIn(0f, 1f), (press.y - row.top).coerceIn(0f, height)),
    )
}

/** A [card] on its way between its row at [from] and the stack. */
@Stable
internal class LiftedCard(val card: StackCard, from: Rect) {
    var from by mutableStateOf(from)
    val lift = Animatable(0f, LIFT_THRESHOLD)
    val fade = Animatable(1f)

    /** Where this card holds on to the stack: the finger for the top card, a little behind it for the others. */
    var follow by mutableStateOf(Offset.Unspecified)

    val depth: Int
        get() = min(abs(card.rank), MAX_STACK_DEPTH)
}

/**
 * Rows lifted off the list while they are dragged. They fly together into a stack under the finger, the long-pressed
 * row on top and the rows above and below it peeking out like a pile of cards. When the finger lifts they fly to their
 * rows: back where they came from, or where they were dropped.
 */
@Stable
internal class DragStackState(private val scope: CoroutineScope) {
    var cards by mutableStateOf<List<LiftedCard>>(emptyList())
        private set

    /** How many items are dragged; the stack shows the number when there is more than one. */
    var count by mutableIntStateOf(0)
        private set

    /** The finger, in the page, while it holds the stack. */
    var anchor by mutableStateOf(Offset.Zero)

    /** Rows cards fly back to, shown dimmed until the cards arrive. */
    var returningKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Whether the cards are landing on the rows they were dropped as, which show as the cards arrive. */
    var landing by mutableStateOf(false)
        private set

    private var geometry = StackGeometry()
    private var job: Job? = null

    /** Lifts [lifted] off their rows into a stack of [geometry] held at [anchor], [count] items in all. */
    fun lift(lifted: List<LiftedCard>, count: Int, anchor: Offset, geometry: StackGeometry) {
        job?.cancel()
        this.count = count
        this.anchor = anchor
        this.geometry = geometry
        returningKeys = emptySet()
        landing = false
        cards = lifted
        job = scope.launch {
            // The frames that first draw the cards can take long; moving them only after keeps the lift from jumping.
            repeat(LIFT_START_FRAMES) { withFrameNanos { } }
            lifted.forEach { card ->
                launch {
                    if (DejavuMotion.animates) delay(STAGGER_MS * card.depth)
                    card.lift.animateTo(1f, LiftSpring)
                }
            }
        }
    }

    /**
     * Lets go of the stack. Each card flies to the row [landings] finds for it, by the key of the row it came from,
     * and hands over to that row; the others head for [fallback] and fade, or fade where they are without one. After
     * a [drop] the cards are [landing] on the rows they were dropped as; otherwise they fly back to the rows they came
     * from, dimmed until they arrive.
     */
    fun release(drop: Boolean, fallback: Offset?, landings: suspend (List<StackCard>) -> Map<String, Rect>) {
        val flying = cards
        if (flying.isEmpty()) return
        job?.cancel()
        if (drop) landing = true else returningKeys = flying.mapTo(mutableSetOf()) { it.card.key }
        job = scope.launch {
            val found = landings(flying.map { it.card })
            flying.map { card -> launch { settle(card, found[card.card.key], fallback) } }.joinAll()
            cards = emptyList()
            returningKeys = emptySet()
            landing = false
        }
    }

    private suspend fun settle(card: LiftedCard, row: Rect?, fallback: Offset?) = coroutineScope {
        if (row != null) {
            card.from = row
        } else {
            launch { card.fade.animateTo(0f, tween(FADE_MS)) }
            if (fallback == null) return@coroutineScope
            // Without a row to take the shape of, the card keeps its size in the stack on its way.
            val size = stackRect(card).size
            card.from = Rect(fallback - Offset(size.width / 2, size.height / 2), size)
        }
        card.lift.animateTo(0f, SettleSpring)
    }

    /** Moves each card [seconds] closer to where the stack is held, the top card right under the finger. */
    fun follow(seconds: Float) {
        cards.forEach { card ->
            card.follow = if (card.depth == 0 || !card.follow.isSpecified) {
                anchor
            } else {
                card.follow + (anchor - card.follow) * (1f - exp(-FollowRates[card.depth - 1] * seconds))
            }
        }
    }

    /** Where [card] sits in the stack: under the finger, peeking out above or below the cards nearer the top. */
    fun stackRect(card: LiftedCard): Rect {
        val at = card.follow.takeIf { it.isSpecified } ?: anchor
        val left = (at.x - geometry.grab.x * geometry.width).coerceIn(0f, geometry.maxLeft)
        val top = at.y - geometry.grab.y + sign(card.card.rank.toFloat()) * card.depth * geometry.peek
        return Rect(left, top, left + geometry.width, top + geometry.height)
    }

    /** Where [card] is now, between its row and the stack. */
    fun rectOf(card: LiftedCard): Rect = lerp(card.from, stackRect(card), card.lift.value)

    /** How far the card of the row with [key] has lifted off it, from 0 to 1; 1 for a row without a card. */
    fun liftOf(key: String): Float = cards.firstOrNull { it.card.key == key }?.lift?.value?.coerceIn(0f, 1f) ?: 1f
}

/**
 * Draws the cards of [state] over the pages they were lifted off. The top card shows a stop sign while the place under
 * the finger refuses them ([blocked]).
 */
@Composable
internal fun DragStack(state: DragStackState, blocked: Boolean) {
    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                state.follow(if (last == 0L) 0f else (now - last) / NANOS_PER_SECOND)
                last = now
            }
        }
    }
    // Rects are in page pixels from the left edge, the same in right-to-left layouts.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        val cards = state.cards
        // The long-pressed row's card goes on top, the ones further from it further down the pile.
        cards.sortedByDescending { it.depth }.forEach { card ->
            key(card.card.key) { LiftedCardView(card, state.rectOf(card), blocked = blocked && card.depth == 0) }
        }
        val top = cards.firstOrNull { it.card.rank == 0 }
        if (top != null && state.count > 1) CountBadge(state.count, top, state.rectOf(top))
    }
}

@Composable
private fun LiftedCardView(card: LiftedCard, rect: Rect, blocked: Boolean) {
    val lift = card.lift.value
    val progress = lift.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val shape = RoundedCornerShape(lerp(RowCorner, StackCorner, progress))
    val depthScale = 1f - DEPTH_SCALE_STEP * card.depth
    val colors = MaterialTheme.colorScheme
    val isTop = card.depth == 0
    // The cards under the top one take a lighter shade, a clearer edge and no text, so the pile shows each card.
    val background = lerp(colors.surfaceContainerHighest, colors.onSurface, BACK_CARD_TINT * card.depth)
    val edgeAlpha = if (isTop) EDGE_ALPHA else BACK_EDGE_ALPHA
    val elevation = if (isTop) CardElevation else BackCardElevation
    // Near its row the card's content gives way to the row's own, which also keeps a loading icon out of view.
    val contentAlpha = (lift / CONTENT_FADE_IN).coerceIn(0f, 1f) * (if (isTop) 1f else 1f - progress)
    // On its row the title takes the row's color, dimmed for a tab that is not loaded; in the stack it is bright.
    val rowTitleColor = if (card.card.brightTitle) colors.onSurface else colors.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .absoluteOffset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer {
                alpha = card.fade.value
                scaleX = lerp(1f, depthScale, progress) * lerp(FADE_SCALE, 1f, card.fade.value)
                scaleY = scaleX
                shadowElevation = elevation.toPx() * progress
                this.shape = shape
                clip = true
            }
            .background(background.copy(alpha = (lift / BACKGROUND_FADE_IN).coerceIn(0f, 1f)))
            .border(
                width = if (blocked) BlockedEdgeWidth else 1.dp,
                color = if (blocked) colors.error else colors.onSurface.copy(alpha = edgeAlpha * progress),
                shape = shape,
            )
            .graphicsLayer { alpha = contentAlpha },
    ) {
        Box(
            modifier = Modifier
                .padding(start = StripeStart)
                .width(StripeWidth)
                .height(StripeHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(card.card.stripe ?: Color.Transparent),
        )
        Spacer(Modifier.width(lerp(card.card.iconStart, StackIconStart, progress) - StripeStart - StripeWidth))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(IconBoxSize)) {
            val url = card.card.url
            if (blocked) {
                Icon(
                    painter = iconPainter(R.drawable.dejavu_ic_no_container_24),
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(20.dp),
                )
            } else if (url != null) {
                SiteIcon(url = url, size = 20.dp)
            } else {
                Icon(
                    painter = iconPainter(iconsR.drawable.mozac_ic_folder_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = card.card.title,
            style = MaterialTheme.typography.bodyLarge,
            color = lerp(rowTitleColor, colors.onSurface, progress),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
    }
}

/** How many items the stack holds, on the top corner of its [top] card at [rect], growing in as the stack forms. */
@Composable
private fun CountBadge(count: Int, top: LiftedCard, rect: Rect) {
    val shown = ((top.lift.value - BADGE_FADE_START) / BADGE_FADE_LENGTH).coerceIn(0f, 1f)
    val inset = with(LocalDensity.current) { BadgeSize.toPx() } * BADGE_INSET
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(
                        x = (rect.right - placeable.width + inset).roundToInt(),
                        y = (rect.top - inset).roundToInt(),
                    )
                }
            }
            .graphicsLayer {
                alpha = shown * top.fade.value
                scaleX = lerp(BADGE_MIN_SCALE, 1f, shown)
                scaleY = scaleX
            }
            .heightIn(min = BadgeSize)
            .widthIn(min = BadgeSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
