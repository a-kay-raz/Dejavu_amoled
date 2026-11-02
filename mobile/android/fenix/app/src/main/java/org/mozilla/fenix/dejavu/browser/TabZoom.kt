/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas as BitmapCanvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.core.view.drawToBitmap
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.concept.base.images.ImageLoadRequest
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.menu.DejavuActionsBar
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.utils.Settings
import org.mozilla.geckoview.captureTopEdge

private val ROW_RADIUS = 16.dp
private const val ZOOM_IN_MS = 380
private const val OPEN_AT = 0.75f
private const val ZOOM_OUT_MS = 340
private const val REVEAL_MS = 160
private const val REVEAL_TIMEOUT_MS = 1_200L
private const val HOME_TIMEOUT_MS = 1_500L
private const val ROW_FRAMES = 2
private const val SHRINK_SETTLE_NANOS = 150_000_000L
private const val NANOS_PER_MS = 1_000_000L
private const val FRAME_NANOS = 16_666_667L

// A frame coming later than this after the one before it follows a stall, not a steady pace.
private const val STALL_NANOS = 34_000_000L
private const val DRAWN_POLL_MS = 40L
private const val THUMBNAIL_TIMEOUT_MS = 150L
private const val DIM_ALPHA = 0.3f

// The share of a zoom in which the card appears over its row, or fades into it.
private const val CARD_FADE = 0.2f

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Dejavu's zoom between a tab's row on the home screen and its page. Opening a tab grows a picture of its page out of
 * its row until it fills the screen, while the browser's bars come in from the top and the bottom of the screen, then
 * the browser shows the page behind it. Going home shrinks a picture of the page back into the tab's row, while
 * pictures of the bars go back out the same way.
 */
internal object TabZoom {
    private class Placed(val owner: Any, val bounds: Rect)

    /** A zoom in progress, with the picture of the tab's page it grows or shrinks. */
    private sealed class Zoom(val tabId: String, val picture: PageShot?) {
        class In(tabId: String, picture: PageShot?, val row: Rect, val open: () -> Unit) : Zoom(tabId, picture)

        /**
         * A page going home, and [bars], pictures of the browser's bars as it left them. [keepsPicture] says whether
         * opening the tab again can start from the picture, which a split view's is not for.
         */
        class Out(
            tabId: String,
            picture: PageShot,
            val bars: List<BarShot>,
            val leave: () -> Unit,
            val afterLeaving: () -> Unit,
            val keepsPicture: Boolean,
        ) : Zoom(tabId, picture) {
            val homeShown = CompletableDeferred<Unit>()
        }
    }

    private val rows = HashMap<String, Placed>()
    private var zoom by mutableStateOf<Zoom?>(null)
    private var overlay: ComposeView? = null

    // The page left last for the home screen, at half size, so opening that tab again starts from where it was left.
    private var lastPicture: PageShot? = null

    /** The row of the tab with [tabId], placed by [owner], is at [bounds] in the window. */
    fun rowPlaced(tabId: String, owner: Any, bounds: Rect) {
        rows[tabId] = Placed(owner, bounds)
    }

    /** The row of the tab with [tabId] placed by [owner] left the home screen. */
    fun rowGone(tabId: String, owner: Any) {
        if (rows[tabId]?.owner === owner) rows.remove(tabId)
    }

    /**
     * Opens the tab with [tabId] from its row on the home screen of [activity]: grows a picture of its page out of the
     * row until it fills the screen, then shows the browser with [open]. Without a row on screen it just opens. A
     * sleeping tab starts loading its page as the zoom starts, rather than once the browser shows it.
     */
    fun zoomIn(activity: Activity, tabId: String, open: () -> Unit) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val row = rows[tabId]?.bounds?.takeIf { it.isShown }
        if (content == null || row == null || isZooming) {
            open()
            return
        }
        val store = activity.components.core.store
        if (store.state.findTab(tabId)?.engineState?.engineSession == null) {
            store.dispatch(EngineAction.CreateEngineSessionAction(tabId))
        }
        val known = lastPicture?.takeIf { it.tabId == tabId }
        val owner = activity as? LifecycleOwner
        if (known != null || owner == null) {
            start(content, Zoom.In(tabId, known, row, open))
            return
        }
        // Pages shown since the app started have a picture saved when they loaded.
        val components = activity.components
        val isPrivate = components.core.store.state.findTab(tabId)?.content?.private == true
        val request = ImageLoadRequest(tabId, content.width, isPrivate)
        owner.lifecycleScope.launch {
            val thumbnail = withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) {
                components.core.thumbnailStorage.loadThumbnail(request).await()
            }
            start(content, Zoom.In(tabId, thumbnail?.let { savedPicture(content, tabId, it) }, row, open))
        }
    }

    /**
     * Leaves the page of the tab with [tabId] for the home screen with [leave]: pictures the page, then shrinks the
     * picture back into the tab's row once the home screen shows, and runs [afterLeaving] once it is there. Without a
     * page on screen it just leaves, running [afterLeaving] once the home screen took the browser's place. A split view
     * is pictured with both of its pages.
     */
    fun zoomOut(tabId: String, afterLeaving: () -> Unit = {}, leave: () -> Unit) {
        val content = PagePictures.shownView?.rootView?.findViewById<ViewGroup>(android.R.id.content)
        val leaveAtOnce = {
            leave()
            // Navigating takes effect on the next turn of the main thread.
            Handler(Looper.getMainLooper()).post(afterLeaving)
        }
        if (content == null || isZooming || PagePictures.isCapturing) {
            leaveAtOnce()
            return
        }
        PagePictures.capture(tabId) { shot ->
            if (shot == null) {
                leaveAtOnce()
            } else {
                withSplitPartner(content, shot) { picture ->
                    val zoom = Zoom.Out(tabId, picture, barShots(content), leave, afterLeaving, picture === shot)
                    start(content, zoom)
                }
            }
        }
    }

    /** The home screen drew itself for the first time, so a page going home can shrink into its row. */
    fun homeShown() {
        (zoom as? Zoom.Out)?.homeShown?.complete(Unit)
    }

    private val isZooming: Boolean
        get() {
            // A zoom whose screen went away, like with the app's window, is over.
            if (zoom != null && overlay?.isAttachedToWindow != true) finish(zoom)
            return zoom != null
        }

    private fun start(content: ViewGroup, next: Zoom) {
        // The browser takes the color above the page from the picture at once, before it pictures the page itself.
        next.picture?.let { PageTint.rememberColor(next.tabId, it.edgeColor.toArgb()) }
        zoom = next
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = ComposeView(content.context).apply { setContent { ZoomOverlay() } }.also {
            content.addView(it, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun finish(done: Zoom?) {
        if (zoom !== done) return
        zoom = null
        overlay?.let { view -> view.post { (view.parent as? ViewGroup)?.removeView(view) } }
        overlay = null
    }

    @Composable
    private fun ZoomOverlay() {
        val current = zoom ?: return
        key(current) { ZoomCard(current) }
    }

    @Composable
    private fun ZoomCard(current: Zoom) {
        // 0 with the card on the tab's row, 1 with the card filling the screen.
        val progress = remember { Animatable(if (current is Zoom.In) 0f else 1f) }
        // How much the card shows, as it uncovers the page in the browser or a home screen without the tab's row.
        val shown = remember { Animatable(1f) }
        var row by remember { mutableStateOf((current as? Zoom.In)?.row) }
        var origin by remember { mutableStateOf(Offset.Zero) }
        val placeholder = colorResource(R.color.fx_mobile_surface)
        val leftBars = (current as? Zoom.Out)?.bars.orEmpty()
        LaunchedEffect(current) {
            // The zoom lives in TabZoom's static state and its lambdas hold the
            // browser fragment and activity, so it must be cleared even when
            // this animation is cancelled, e.g. the activity is destroyed
            // mid-zoom and the overlay's composition is disposed. Without
            // this, a cancelled zoom keeps the destroyed fragment, activity
            // and the page picture alive for the rest of the process.
            try {
                when (current) {
                    is Zoom.In -> {
                        progress.grow(current.open)
                        withTimeoutOrNull(REVEAL_TIMEOUT_MS) { awaitPageDrawn() }
                        shown.animateTo(0f, tween(REVEAL_MS))
                    }
                    is Zoom.Out -> {
                        current.leave()
                        // The card covers the home screen while it sets itself up, then shrinks into the tab's row, placed
                        // in the frames right after the home screen first drew.
                        withTimeoutOrNull(HOME_TIMEOUT_MS) { current.homeShown.await() }
                        repeat(ROW_FRAMES) { withFrameNanos { } }
                        awaitSteadyFrames(maxNanos = SHRINK_SETTLE_NANOS)
                        row = rows[current.tabId]?.bounds?.takeIf { it.isShown }
                        if (row != null) {
                            progress.animateTo(0f, tween(ZOOM_OUT_MS, easing = Emphasized))
                        } else {
                            shown.animateTo(0f, tween(REVEAL_MS))
                        }
                        current.afterLeaving()
                        if (current.keepsPicture) current.picture?.let(::keepForReopening)
                    }
                }
            } finally {
                finish(current)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInWindow() }
                .graphicsLayer { alpha = shown.value },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawZoom(current.picture, row?.translate(-origin), progress.value, placeholder, origin)
                // The bars left behind go back out as the page shrinks, and are gone once it is in its row.
                drawBars(leftBars, progress.value, origin)
            }
            // The browser's bars come in with the page, so they are in place once it fills the screen.
            if (current is Zoom.In) BrowserBars(current.tabId, arrived = { progress.value })
            // Taps wait for the zoom to end, instead of reaching what is under it.
            Box(modifier = Modifier.fillMaxSize().swallowingTaps())
        }
    }

    /**
     * The bars the browser will show for the tab with [tabId], as their layout has them, coming in from the top and
     * the bottom of the screen: [arrived] says how far, from 0 out of sight to 1 where the browser shows them. They
     * only show what is coming, so screen readers skip them. Browsers with other bars show none.
     */
    @Composable
    private fun BrowserBars(tabId: String, arrived: () -> Float) {
        val components = LocalContext.current.components
        if (!remember { components.settings.showsOnlyDejavuBars }) return
        val tab by components.core.store.observeAsComposableState { it.findTab(tabId) }
        val barsTab = tab ?: return
        val layout = currentBarsLayout()
        val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
        FirefoxTheme {
            Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(sides).clearAndSetSemantics {}) {
                MaterialTheme(colorScheme = DejavuToolbar.glass(MaterialTheme.colorScheme)) {
                    if (layout.addressBarAtBottom) {
                        Box(modifier = Modifier.align(Alignment.BottomCenter).comingIn(arrived, fromTop = false)) {
                            BelowPages { DejavuAddressBarPreview(barsTab) }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .comingIn(arrived, fromTop = true)
                                .statusBarRoomPadding(),
                        ) {
                            DejavuAddressBarPreview(barsTab)
                        }
                    }
                }
                if (layout.hasActionsBar) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter).comingIn(arrived, fromTop = false)) {
                        DejavuActionsBar(onEvent = {}, tabId = tabId)
                    }
                }
            }
        }
    }

    private fun keepForReopening(picture: PageShot) {
        val bitmap = picture.bitmap.asAndroidBitmap()
        val half = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
        lastPicture = PageShot(half.asImageBitmap(), picture.offset, picture.tabId, picture.edgeColor)
    }
}

/** Lets [TabZoom] grow the page of the tab with [tabId] out of this row, and shrink it back into it. */
@Composable
internal fun Modifier.tabZoomTarget(tabId: String?): Modifier {
    if (tabId == null) return this
    val owner = remember { Any() }
    DisposableEffect(tabId) {
        onDispose { TabZoom.rowGone(tabId, owner) }
    }
    return onGloballyPositioned { TabZoom.rowPlaced(tabId, owner, it.boundsInWindow()) }
}

/** Whether the browser shows only Dejavu's own bars around pages. */
private val Settings.showsOnlyDejavuBars: Boolean
    get() = DejavuToolbar.enabled && !shouldUseBottomToolbar && !isTabStripEnabled && !shouldShowTabGroupsStrip

/** Keeps every tap on this from reaching what is under it. */
private fun Modifier.swallowingTaps() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

/** Places this [arrived] of the way from just beyond the top or the bottom edge of the screen to its own place. */
private fun Modifier.comingIn(arrived: () -> Float, fromTop: Boolean) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        val away = ((1f - arrived()) * placeable.height).roundToInt()
        placeable.place(0, if (fromTop) -away else away)
    }
}

/** A row cut away by the edge of its list, or on another workspace's page, has no room left. */
private val Rect.isShown: Boolean
    get() = width > 1f && height > 1f

/**
 * Grows the card from the tab's row to the whole screen, as this goes from 0 to 1, and calls [open] once it is
 * [OPEN_AT] of the way: the browser starts setting itself up then, as the last frames of the growth hardly move the
 * card. Until then a frame coming after a stall moves the card on by one frame only, so the frames that first draw the
 * zoom and the browser's bars skip none of its start.
 */
internal suspend fun Animatable<Float, AnimationVector1D>.grow(open: () -> Unit) {
    val duration = ZOOM_IN_MS * NANOS_PER_MS
    var played = 0L
    var opened = false
    var last = withFrameNanos { it }
    while (played < duration) {
        val now = withFrameNanos { it }
        val step = if (now - last > STALL_NANOS && !opened) FRAME_NANOS else now - last
        last = now
        played = (played + step).coerceAtMost(duration)
        snapTo(Emphasized.transform(played.toFloat() / duration))
        if (!opened && played >= duration * OPEN_AT) {
            opened = true
            open()
        }
    }
}

/** Waits until the browser draws a page, which it can picture only once it does. */
private suspend fun awaitPageDrawn() {
    while (true) {
        delay(DRAWN_POLL_MS)
        val view = PagePictures.shownGeckoView ?: continue
        if (captureTopEdge(view, 1)?.valueOrNull() != null) return
    }
}

/** A picture of one of the browser's bars, shown at [offset] in the window, at the top of the screen or the bottom. */
private class BarShot(val bitmap: ImageBitmap, val offset: Offset, val atTop: Boolean)

/** Pictures of the browser's bars under [content] as they are shown now, when Dejavu's own. */
private fun barShots(content: ViewGroup): List<BarShot> {
    if (!content.context.components.settings.showsOnlyDejavuBars) return emptyList()
    return listOf(R.id.composable_toolbar to true, R.id.navigation_bar to false).mapNotNull { (id, atTop) ->
        content.findViewById<View>(id)
            ?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
            ?.let { view -> runCatching { barShot(view, atTop) }.getOrNull() }
    }
}

private fun barShot(view: View, atTop: Boolean): BarShot {
    val location = IntArray(2).also(view::getLocationInWindow)
    return BarShot(view.drawToBitmap().asImageBitmap(), Offset(location[0].toFloat(), location[1].toFloat()), atTop)
}

/**
 * Draws [bars], pictures of the browser's bars, [progress] of the way from just beyond the top or the bottom edge of
 * the screen to where they were shown.
 */
private fun DrawScope.drawBars(bars: List<BarShot>, progress: Float, origin: Offset) {
    bars.forEach { bar ->
        val away = (1f - progress) * bar.bitmap.height
        drawImage(bar.bitmap, topLeft = bar.offset - origin + Offset(0f, if (bar.atTop) -away else away))
    }
}

/**
 * Calls [then] with [shot], the page in focus, joined with a picture of the other page of the split view shown, each
 * where it shows under [content], so that going home does not leave the other page's place empty. Without a split view
 * or a picture of its other page, [shot] stays as it is.
 */
private fun withSplitPartner(content: ViewGroup, shot: PageShot, then: (PageShot) -> Unit) {
    val split = SplitPanes.active?.takeIf { SplitScreen.shown != null }
    if (split == null) {
        then(shot)
        return
    }
    split.picturePage { picture, place ->
        then(picture?.let { runCatching { joined(content, shot, it, place) }.getOrNull() } ?: shot)
    }
}

/**
 * One picture as wide as [content], holding [shot] and [other], the page shown at [place], with the page color between
 * them.
 */
private fun joined(content: ViewGroup, shot: PageShot, other: Bitmap, place: PixelRect): PageShot {
    val origin = IntArray(2).also(content::getLocationInWindow)
    val top = minOf(shot.offset.y, place.top.toFloat())
    val bottom = maxOf(shot.offset.y + shot.bitmap.height, place.bottom.toFloat())
    val bitmap = Bitmap.createBitmap(content.width, (bottom - top).roundToInt(), Bitmap.Config.ARGB_8888)
    BitmapCanvas(bitmap).apply {
        drawColor(ContextCompat.getColor(content.context, R.color.fx_mobile_surface))
        drawBitmap(shot.bitmap.asAndroidBitmap(), shot.offset.x - origin[0], shot.offset.y - top, null)
        val left = (place.left - origin[0]).toFloat()
        drawBitmap(other, null, RectF(left, place.top - top, left + place.width, place.bottom - top), null)
    }
    return PageShot(bitmap.asImageBitmap(), Offset(origin[0].toFloat(), top), shot.tabId, shot.edgeColor)
}

/**
 * The picture saved for the tab with [tabId] when its page loaded, placed where the browser shows the page: below the
 * status bar, and below the address bar when it is above pages.
 */
private fun savedPicture(content: ViewGroup, tabId: String, bitmap: Bitmap): PageShot {
    val bar = if (content.context.barsLayout.addressBarAtBottom) 0f else ADDRESS_BAR_HEIGHT.value
    val pageTop = content.statusBarHeight + bar * content.resources.displayMetrics.density
    return PageShot(bitmap.asImageBitmap(), Offset(0f, pageTop), tabId, Color(bitmap.topEdgeColor()))
}

/**
 * Draws the card [progress] of the way from [row] to the whole screen, holding [picture], the page with what showed
 * above it scaled to the card's width and cut at its bottom, or [placeholder] without a picture. The screen behind the
 * card dims as it grows.
 */
private fun DrawScope.drawZoom(picture: PageShot?, row: Rect?, progress: Float, placeholder: Color, origin: Offset) {
    val full = Rect(Offset.Zero, size)
    val card = lerp(row ?: full, full, progress)
    val cardAlpha = if (row == null) 1f else (progress / CARD_FADE).coerceIn(0f, 1f)
    if (row != null) drawRect(Color.Black, alpha = DIM_ALPHA * progress)
    val corners = CornerRadius(lerp(ROW_RADIUS.toPx(), 0f, progress))
    clipPath(Path().apply { addRoundRect(RoundRect(card, corners)) }) {
        val scale = card.width / size.width
        translate(card.left, card.top) {
            scale(scale, scale, pivot = Offset.Zero) {
                drawRect(picture?.edgeColor ?: placeholder, size = size, alpha = cardAlpha)
                if (picture != null) {
                    // Saved pictures can be smaller than the page was shown, so they are drawn as wide as the screen.
                    val bitmap = picture.bitmap
                    val height = bitmap.height * size.width / bitmap.width
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(
                            (picture.offset.x - origin.x).roundToInt(),
                            (picture.offset.y - origin.y).roundToInt(),
                        ),
                        dstSize = IntSize(size.width.roundToInt(), height.roundToInt()),
                        alpha = cardAlpha,
                    )
                }
            }
        }
    }
}
