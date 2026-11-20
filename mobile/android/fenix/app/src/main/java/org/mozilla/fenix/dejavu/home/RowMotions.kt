/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CLOSE_MS = 340
private const val CLEAR_MS = 560
private const val REOPEN_MS = 520
private const val MOVE_MS = 480

// The rows below a closing one wait for most of it to pour into its button before they close the gap.
private const val GAP_DELAY_MS = 230
private const val GAP_MS = 280

// A tab closed on the page this long ago that comes back counts as reopened.
private const val REOPEN_WINDOW_MS = 60_000L

// Rows going away this soon after a tap on Clear pour into it.
private const val CLEAR_WINDOW_MS = 2_000L

// The close button a row pours into: the icon of the last button at its end.
private val CloseIcon = 20.dp
private val CloseIconInset = 24.dp

// How tall the slices are that a row pours in, and how many slices across a row going into its end.
private val SliceHeight = 6.dp
private const val ROW_SLICES = 24
private const val SEAM = 0.5f

private const val OPAQUE = 255
private const val WARM_UP_DELAY_MS = 2_000L

private val LiftElevation = 8.dp
private const val LIFT_SCALE = 0.04f
private const val CARD_ALPHA = 0.9f
private val CardCorner = 16.dp

private val GapSpec = tween<IntOffset>(GAP_MS, delayMillis = GAP_DELAY_MS, easing = FastOutSlowInEasing)
private val JumpSpec = snap<IntOffset>()
private val MoveSpec = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/** The buttons rows pour into when they go away, or out of when they come back. */
internal enum class MotionButton { REOPEN, CLEAR }

/**
 * What changed among the rows of a page, from one look at them to the next, by the open tabs each row shows.
 *
 * @property rows The open tabs of every row now, by the key of the row.
 * @property closed Rows whose tabs closed.
 * @property clear Whether the [closed] rows were cleared together with the Clear button.
 * @property reopened Rows whose tabs came back.
 * @property moved Rows whose tabs were on another row before, like a tab that was pinned, by the key of that row.
 */
internal class RowPlan(
    val rows: Map<String, Set<String>>,
    val closed: List<String> = emptyList(),
    val clear: Boolean = false,
    val reopened: List<String> = emptyList(),
    val moved: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = closed.isEmpty() && reopened.isEmpty() && moved.isEmpty()
}

/**
 * What changed from the rows of [last] to those of [rows], each a set of the open tabs a row shows by the key of the
 * row. A row whose tabs show on a new row moved there; one whose tabs all closed, none of them among [openTabIds]
 * anymore, closed, together with the others with the Clear button when [clearing]. A new row showing one of the
 * [returning] tabs is a reopened one. Rows of closed pinned tabs, and folders, show no tabs and never move.
 */
internal fun planRows(
    last: Map<String, Set<String>>?,
    rows: Map<String, Set<String>>,
    openTabIds: Set<String>,
    returning: Set<String>,
    clearing: Boolean,
): RowPlan {
    if (last == null) return RowPlan(rows)
    val removed = last.filterKeys { it !in rows }
    val added = rows.filterKeys { it !in last }
    val moved = mutableMapOf<String, String>()
    added.forEach { (key, tabs) ->
        val from = removed.entries.firstOrNull { (from, fromTabs) ->
            from !in moved.values && fromTabs.any { it in tabs }
        }
        if (from != null) moved[key] = from.key
    }
    val closed = removed.filter { (key, tabs) ->
        key !in moved.values && tabs.isNotEmpty() && tabs.none { it in openTabIds }
    }.keys.toList()
    val reopened = added.filter { (key, tabs) -> key !in moved && tabs.any { it in returning } }.keys.toList()
    return RowPlan(rows, closed, clearing && closed.isNotEmpty(), reopened, moved)
}

/** The smallest rectangle around both [one] and [other]. */
private fun spanning(one: Rect, other: Rect) =
    Rect(min(one.left, other.left), min(one.top, other.top), max(one.right, other.right), max(one.bottom, other.bottom))

/** Where a row is and how it last looked, kept by the row while it shows, see [motionRow]. */
internal class RowPlace {
    var bounds: Rect? = null
    var layer: GraphicsLayer? = null

    /** Whether a motion took the picture over once the row went away, and frees it when it is done. */
    var handedOver = false
}

/**
 * How a row that went away last looked, at [bounds] in the window. Its [picture], taken once its motion started, pours
 * in one go through a mesh; until it is there, the [layer] pours slice by slice.
 */
private class Ghost(val layer: GraphicsLayer, val bounds: Rect) {
    var picture: Bitmap? = null
}

private sealed class Motion(durationMillis: Int) {
    val progress = Animatable(0f)
    val spec = tween<Float>(durationMillis, easing = FastOutSlowInEasing)

    /** Whether this motion draws the row with [key] while it plays, which then hides. */
    open fun draws(key: String) = false
}

/** A row whose tab closed pours into the close button at its end. */
private class Closing(val ghost: Ghost) : Motion(CLOSE_MS)

/** The rows cleared together pour into the Clear button as one, from [button] where it was when they went. */
private class Clearing(val ghosts: List<Ghost>, val button: Rect) : Motion(CLEAR_MS)

/**
 * The rows of reopened tabs, [keys], pour out of the button that reopens closed tabs together, from [button] where it
 * was when they came back; without it, each pours out of the close button at its end.
 */
private class Reopening(val keys: List<String>, val button: Rect?) : Motion(REOPEN_MS) {
    override fun draws(key: String) = key in keys
}

/**
 * The row [key] of a tab that moved to another part of the page flies there from [from], lifting off the page on a
 * [card] on its way.
 */
private class Moving(val key: String, val from: Rect) : Motion(MOVE_MS) {
    var card: GraphicsLayer? = null

    override fun draws(key: String) = key == this.key
}

/**
 * The motions of the rows of a page as they come, go and move, like Dejavu's genie between buttons and pages: a closing
 * tab pours into its close button and cleared tabs into the Clear button, reopened tabs pour back out of the button
 * that reopens them, and a tab that is pinned or unpinned lifts off its row and flies to its new one. Each row keeps
 * its place and a picture of itself here, see [motionRow], so that it can be drawn after it went away, over the list
 * by [RowMotionsOverlay]. [plan] tells what changed while composing, and [start] sets the motions off once that shows.
 */
@Stable
internal class RowMotions(private val graphics: GraphicsContext, private val scope: CoroutineScope) {
    private val places = HashMap<String, RowPlace>()
    private val buttons = HashMap<MotionButton, Rect>()
    private val closedAt = HashMap<String, Long>()
    private var lastRows: Map<String, Set<String>>? = null
    private var clearTappedAt = Long.MIN_VALUE / 2
    private val running = mutableStateListOf<Motion>()
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Where the overlay drawing the motions is in the window. */
    var origin = Offset.Zero

    /** Whether rows pour into the Clear button now, which stays until they are in. */
    val isClearing: Boolean
        get() = running.any { it is Clearing }

    /** Whether any row is on the move now. */
    val isPlaying: Boolean
        get() = running.isNotEmpty()

    /** What changed among [rows], the open tabs of each row by its key, since [start] last saw them. */
    fun plan(rows: Map<String, Set<String>>, openTabIds: Set<String>, reopenedTabId: String?): RowPlan {
        val now = SystemClock.uptimeMillis()
        val returning = closedAt.filterValues { now - it < REOPEN_WINDOW_MS }.keys + setOfNotNull(reopenedTabId)
        return planRows(lastRows, rows, openTabIds, returning, clearing = now - clearTappedAt < CLEAR_WINDOW_MS)
    }

    /**
     * How the other rows move to make room or close gaps while [plan] plays; otherwise they jump to their places. The
     * list only knows where rows were to move them from while they always have a way to move, so they always do.
     */
    fun placementSpec(plan: RowPlan): FiniteAnimationSpec<IntOffset> = when {
        plan.closed.isNotEmpty() && !plan.clear -> GapSpec
        !plan.isEmpty || running.isNotEmpty() -> MoveSpec
        else -> JumpSpec
    }

    /**
     * Sets off the motions of [plan], once it shows. Rows only fly to where their tabs moved with [flights], so not
     * while the cards of a drag land on them.
     */
    fun start(plan: RowPlan, flights: Boolean) {
        val last = lastRows
        lastRows = plan.rows
        if (last == null || plan.isEmpty) return
        val now = SystemClock.uptimeMillis()
        closedAt.entries.removeAll { now - it.value >= REOPEN_WINDOW_MS }
        plan.closed.forEach { key -> last[key]?.forEach { closedAt[it] = now } }
        val clearButton = buttons[MotionButton.CLEAR]
        if (plan.clear && clearButton != null) {
            clearTappedAt = Long.MIN_VALUE / 2
            plan.closed.mapNotNull(::ghostOf).takeIf { it.isNotEmpty() }?.let { play(Clearing(it, clearButton)) }
        } else {
            plan.closed.mapNotNull(::ghostOf).forEach { play(Closing(it)) }
        }
        if (plan.reopened.isNotEmpty()) play(Reopening(plan.reopened, buttons[MotionButton.REOPEN]))
        if (flights) {
            plan.moved.forEach { (key, from) -> places[from]?.bounds?.let { play(Moving(key, it)) } }
        }
    }

    /** The Clear button was tapped: the rows going away next pour into it. */
    fun clearTapped() {
        clearTappedAt = SystemClock.uptimeMillis()
    }

    /** Whether a motion draws the row with [key] now, which then hides. */
    fun draws(key: String): Boolean = running.any { it.draws(key) }

    fun buttonPlaced(button: MotionButton, bounds: Rect) {
        buttons[button] = bounds
    }

    fun buttonGone(button: MotionButton) {
        buttons.remove(button)
    }

    fun join(key: String): RowPlace = RowPlace().also { places[key] = it }

    fun leave(key: String, place: RowPlace) {
        if (places[key] === place) places.remove(key)
        if (!place.handedOver) place.layer?.let(graphics::releaseGraphicsLayer)
        place.layer = null
    }

    fun newLayer(): GraphicsLayer = graphics.createGraphicsLayer()

    /** Lets go of every picture kept, once the page is gone. */
    fun dispose() {
        running.forEach(::release)
        running.clear()
    }

    private fun ghostOf(key: String): Ghost? {
        val place = places[key] ?: return null
        val layer = place.layer ?: return null
        val bounds = place.bounds ?: return null
        place.handedOver = true
        places.remove(key)
        return Ghost(layer, bounds)
    }

    private fun play(motion: Motion) {
        running.filter { old -> old is Moving && motion is Moving && old.key == motion.key }.forEach(::end)
        running.add(motion)
        when (motion) {
            is Closing -> takePicture(motion.ghost)
            is Clearing -> motion.ghosts.forEach(::takePicture)
            else -> Unit
        }
        scope.launch {
            motion.progress.animateTo(1f, motion.spec)
            end(motion)
        }
    }

    /**
     * Takes the row of [ghost] as one picture: drawing the row again for every slice it pours in, at sizes that change
     * every frame, keeps the render thread too busy for a smooth motion on slower phones.
     */
    private fun takePicture(ghost: Ghost) {
        scope.launch {
            // A frame later, so the picture is not taken in the frame that closes the tab, which is busy already.
            withFrameNanos { }
            ghost.picture = runCatching { ghost.layer.toImageBitmap().asAndroidBitmap() }.getOrNull()
        }
    }

    companion object {
        private var warm = false

        /**
         * Takes one empty picture, once while Dejavu runs and a while after the home screen showed: the first picture
         * takes several frames' time, which would otherwise hold up the first row that pours.
         */
        suspend fun warmUp(graphics: GraphicsContext) {
            if (warm) return
            warm = true
            delay(WARM_UP_DELAY_MS)
            val layer = graphics.createGraphicsLayer()
            try {
                layer.record(Density(1f), LayoutDirection.Ltr, IntSize(1, 1)) { }
                layer.toImageBitmap()
            } finally {
                graphics.releaseGraphicsLayer(layer)
            }
        }
    }

    private fun end(motion: Motion) {
        if (running.remove(motion)) release(motion)
    }

    private fun release(motion: Motion) {
        when (motion) {
            is Closing -> graphics.releaseGraphicsLayer(motion.ghost.layer)
            is Clearing -> motion.ghosts.forEach { graphics.releaseGraphicsLayer(it.layer) }
            is Reopening -> motion.keys.forEach { places[it]?.layer?.alpha = 1f }
            is Moving -> motion.card?.let(graphics::releaseGraphicsLayer)
        }
    }

    /**
     * Draws the rows pouring into buttons and out of them, and over them the rows flying on cards of [card] color, with
     * the window's [origin] at this drawing's top left.
     */
    fun DrawScope.drawMotions(card: Color) {
        running.forEach { motion ->
            val tau = motion.progress.value
            when (motion) {
                is Closing -> {
                    val ghost = motion.ghost
                    val picture = ghost.picture
                    if (picture != null) {
                        meshIntoEnd(picture, ghost.bounds, tau)
                    } else {
                        drawIntoEnd(ghost.layer, ghost.bounds, tau)
                    }
                }
                is Clearing -> {
                    val block = motion.ghosts.map { it.bounds }.reduce(::spanning)
                    val button = buttons[MotionButton.CLEAR] ?: motion.button
                    motion.ghosts.forEach { ghost ->
                        val picture = ghost.picture
                        if (picture != null) {
                            meshPouring(picture, ghost.bounds, block, button, tau)
                        } else {
                            drawPouring(ghost.layer, ghost.bounds, block, button, tau)
                        }
                    }
                }
                is Reopening -> drawReopening(motion, 1f - tau)
                is Moving -> Unit
            }
        }
        running.forEach { motion -> if (motion is Moving) drawFlight(motion, card) }
    }

    /** Draws the row of [motion] on its way to its new place, lifted off the page on a card of [color]. */
    private fun DrawScope.drawFlight(motion: Moving, color: Color) {
        val place = places[motion.key] ?: return
        val layer = place.layer ?: return
        val to = place.bounds ?: return
        val progress = motion.progress.value
        val at = lerp(motion.from, to, progress).translate(-origin)
        val lift = sin(PI * progress).toFloat()
        val corner = CardCorner.toPx()
        val card = motion.card ?: newLayer().also { motion.card = it }
        card.setRoundRectOutline(Offset.Zero, at.size, corner)
        card.shadowElevation = LiftElevation.toPx() * lift
        card.scaleX = 1f + LIFT_SCALE * lift
        card.scaleY = card.scaleX
        card.record(IntSize(at.width.roundToInt(), at.height.roundToInt())) {
            drawRoundRect(color, cornerRadius = CornerRadius(corner), alpha = CARD_ALPHA * lift)
            drawLayer(layer)
        }
        translate(at.left, at.top) { drawLayer(card) }
    }

    private fun DrawScope.drawReopening(motion: Reopening, tau: Float) {
        val rows = motion.keys.mapNotNull { key ->
            places[key]?.let { place -> place.layer?.let { layer -> place.bounds?.let { layer to it } } }
        }
        if (rows.isEmpty()) return
        val button = buttons[MotionButton.REOPEN] ?: motion.button
        if (button == null) {
            rows.forEach { (layer, bounds) -> drawIntoEnd(layer, bounds, tau) }
            return
        }
        val block = rows.map { it.second }.reduce(::spanning)
        rows.forEach { (layer, bounds) -> drawPouring(layer, bounds, block, button, tau) }
    }

    /** Draws [layer], the row at [row] among the rows of [block], [tau] of the way down a genie into [button]. */
    private fun DrawScope.drawPouring(layer: GraphicsLayer, row: Rect, block: Rect, button: Rect, tau: Float) {
        layer.alpha = genieAlpha(tau)
        if (layer.alpha <= 0f || block.height <= 0f || row.height <= 0f) return
        val slices = (row.height / SliceHeight.toPx()).roundToInt().coerceAtLeast(1)
        for (slice in 0 until slices) {
            val from = row.top + row.height * slice / slices
            val to = row.top + row.height * (slice + 1) / slices
            val start = genieLine(block, button, tau, (from - block.top) / block.height)
            val end = genieLine(block, button, tau, (to - block.top) / block.height)
            val left = (start.start + end.start) / 2
            val right = (start.end + end.end) / 2
            if (end.along - start.along < SEAM || right - left < SEAM) continue
            drawSlice(
                layer = layer,
                target = Rect(left, start.along, right, end.along),
                scaleX = (right - left) / block.width,
                scaleY = (end.along - start.along) / (to - from),
                source = Offset(block.left - row.left, from - row.top),
            )
        }
    }

    /**
     * Draws [picture], the row at [row] among the rows of [block], [tau] of the way down a genie into [button], as one
     * mesh: the lines across it stay straight, so the mesh needs only the lines down it.
     */
    private fun DrawScope.meshPouring(picture: Bitmap, row: Rect, block: Rect, button: Rect, tau: Float) {
        val alpha = genieAlpha(tau)
        if (alpha <= 0f || block.height <= 0f || block.width <= 0f || row.height <= 0f) return
        val lines = (row.height / SliceHeight.toPx()).roundToInt().coerceAtLeast(1)
        val start = (row.left - block.left) / block.width
        val end = (row.right - block.left) / block.width
        val verts = FloatArray((lines + 1) * 2 * 2)
        var i = 0
        for (line in 0..lines) {
            val across = genieLine(block, button, tau, (row.top + row.height * line / lines - block.top) / block.height)
            for (side in floatArrayOf(start, end)) {
                verts[i++] = lerp(across.start, across.end, side) - origin.x
                verts[i++] = across.along - origin.y
            }
        }
        drawMesh(picture, columns = 1, rows = lines, verts = verts, alpha = alpha)
    }

    /** Draws [picture], the row at [row], [tau] of the way into the close button at its end, as one mesh. */
    private fun DrawScope.meshIntoEnd(picture: Bitmap, row: Rect, tau: Float) {
        val alpha = genieAlpha(tau)
        if (alpha <= 0f || row.width <= 0f) return
        val button = closeButtonOf(row)
        val verts = FloatArray((ROW_SLICES + 1) * 2 * 2)
        var i = 0
        for (edge in 0..1) {
            for (column in 0..ROW_SLICES) {
                val line = genieLine(row, button, tau, column.toFloat() / ROW_SLICES, downThePage = false)
                verts[i++] = line.along - origin.x
                verts[i++] = (if (edge == 0) line.start else line.end) - origin.y
            }
        }
        drawMesh(picture, columns = ROW_SLICES, rows = 1, verts = verts, alpha = alpha)
    }

    /** Draws [picture] bent along the mesh of [columns] by [rows] cells whose points are [verts]. */
    private fun DrawScope.drawMesh(picture: Bitmap, columns: Int, rows: Int, verts: FloatArray, alpha: Float) {
        meshPaint.alpha = (alpha * OPAQUE).roundToInt()
        drawIntoCanvas { it.nativeCanvas.drawBitmapMesh(picture, columns, rows, verts, 0, null, 0, meshPaint) }
    }

    /** The close button a row pours into: the icon of the last button at its end. */
    private fun DrawScope.closeButtonOf(row: Rect): Rect {
        val size = CloseIcon.toPx()
        val center = Offset(row.right - CloseIconInset.toPx(), row.center.y)
        return Rect(center.x - size / 2, center.y - size / 2, center.x + size / 2, center.y + size / 2)
    }

    /** Draws [layer], the row at [row], [tau] of the way into the close button at its end. */
    private fun DrawScope.drawIntoEnd(layer: GraphicsLayer, row: Rect, tau: Float) {
        layer.alpha = genieAlpha(tau)
        if (layer.alpha <= 0f || row.width <= 0f) return
        val button = closeButtonOf(row)
        for (slice in 0 until ROW_SLICES) {
            val from = slice.toFloat() / ROW_SLICES
            val to = (slice + 1).toFloat() / ROW_SLICES
            val start = genieLine(row, button, tau, from, downThePage = false)
            val end = genieLine(row, button, tau, to, downThePage = false)
            val top = (start.start + end.start) / 2
            val bottom = (start.end + end.end) / 2
            if (end.along - start.along < SEAM || bottom - top < SEAM) continue
            drawSlice(
                layer = layer,
                target = Rect(start.along, top, end.along, bottom),
                scaleX = (end.along - start.along) / (row.width * (to - from)),
                scaleY = (bottom - top) / row.height,
                source = Offset(row.width * from, 0f),
            )
        }
    }

    /**
     * Draws the part of [layer] from [source] on, scaled by [scaleX] and [scaleY], into [target], in the window. Slices
     * overlap by a hair, so no seams show between them.
     */
    private fun DrawScope.drawSlice(layer: GraphicsLayer, target: Rect, scaleX: Float, scaleY: Float, source: Offset) {
        val at = target.topLeft - origin
        withTransform(
            {
                clipRect(at.x - SEAM, at.y - SEAM, at.x + target.width + SEAM, at.y + target.height + SEAM)
                translate(at.x, at.y)
                scale(scaleX, scaleY, Offset.Zero)
                translate(-source.x, -source.y)
            },
        ) {
            drawLayer(layer)
        }
    }
}

@Composable
internal fun rememberRowMotions(): RowMotions {
    val graphics = LocalGraphicsContext.current
    val scope = rememberCoroutineScope()
    val motions = remember(graphics) { RowMotions(graphics, scope) }
    DisposableEffect(motions) { onDispose(motions::dispose) }
    LaunchedEffect(graphics) { runCatching { RowMotions.warmUp(graphics) } }
    return motions
}

/**
 * Draws the motions of [motions] over the list of rows it covers: rows pouring into buttons and out of them, and rows
 * flying to their new place on a card, lifted off the page. It draws after the rows took their places, so the rows
 * that come and move are drawn where they are in the same frame.
 */
@Composable
internal fun RowMotionsOverlay(motions: RowMotions, modifier: Modifier = Modifier) {
    val card = MaterialTheme.colorScheme.surfaceContainerHighest
    Spacer(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned { motions.origin = it.positionInWindow() }
            .drawBehind { with(motions) { drawMotions(card) } },
    )
}

/**
 * Lets [motions] follow the row with [key]: where it is and how it looks, so that it can be drawn coming and going. The
 * row hides while a motion draws it.
 */
internal fun Modifier.motionRow(motions: RowMotions, key: String): Modifier = this then MotionRowElement(motions, key)

/** Lets the rows of [motions] pour into [button] and out of it. */
internal fun Modifier.motionButton(motions: RowMotions, button: MotionButton): Modifier =
    this then MotionButtonElement(motions, button)

private data class MotionRowElement(val motions: RowMotions, val key: String) : ModifierNodeElement<MotionRowNode>() {
    override fun create() = MotionRowNode(motions, key)

    override fun update(node: MotionRowNode) = node.update(motions, key)
}

private class MotionRowNode(private var motions: RowMotions, private var key: String) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var place: RowPlace? = null

    fun update(motions: RowMotions, key: String) {
        if (motions === this.motions && key == this.key) return
        leave()
        this.motions = motions
        this.key = key
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        joined().bounds = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
    }

    override fun ContentDrawScope.draw() {
        val place = joined()
        if (place.handedOver) {
            drawContent()
            return
        }
        val layer = place.layer ?: motions.newLayer().also { place.layer = it }
        layer.record { this@draw.drawContent() }
        if (!motions.draws(key)) drawLayer(layer)
    }

    override fun onDetach() = leave()

    private fun joined(): RowPlace = place ?: motions.join(key).also { place = it }

    private fun leave() {
        place?.let { motions.leave(key, it) }
        place = null
    }
}

private data class MotionButtonElement(val motions: RowMotions, val button: MotionButton) :
    ModifierNodeElement<MotionButtonNode>() {
    override fun create() = MotionButtonNode(motions, button)

    override fun update(node: MotionButtonNode) {
        node.motions = motions
        node.button = button
    }
}

private class MotionButtonNode(var motions: RowMotions, var button: MotionButton) :
    Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        motions.buttonPlaced(button, Rect(coordinates.positionInWindow(), coordinates.size.toSize()))
    }

    override fun onDetach() = motions.buttonGone(button)
}
