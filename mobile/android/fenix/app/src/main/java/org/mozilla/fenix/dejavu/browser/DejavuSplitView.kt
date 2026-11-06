/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.os.StrictMode
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.toolbar.ScrollableToolbar
import mozilla.components.feature.contextmenu.ContextMenuCandidate
import mozilla.components.feature.contextmenu.ContextMenuFeature
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.flowScoped
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.dejavu.menu.menuItem
import org.mozilla.fenix.dejavu.ui.DejavuDropdownMenu
import org.mozilla.fenix.dejavu.ui.DejavuMenuItem
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Shows the other tab of a split view next to the page of the selected tab, like Zen's split views: below or above
 * it, or beside it on screens with room for it, see [SplitSpace]. Each tab keeps its place, and the selected tab is the
 * one in focus, which the bars act on: tapping the other page turns to it, so the browser's own view moves over to it.
 * While split the bars stay in place and both pages fit between them, see [SplitScreen]; a thin divider between the
 * pages resizes them when dragged and closes the split when tapped. Custom tabs are left alone.
 *
 * @param restoreEngineView Sets the browser's page up again for the bars, as it was before the split.
 */
fun Fragment.installDejavuSplitView(
    browserLayout: CoordinatorLayout,
    swipeRefresh: View,
    mainEngineView: EngineView,
    customTabSessionId: String?,
    restoreEngineView: () -> Unit,
    contextMenuCandidates: () -> List<ContextMenuCandidate>,
) {
    if (customTabSessionId != null) return
    val components = requireComponents
    val repository = components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
        WorkspaceRepository.get(requireContext())
    }
    val pane = SplitPaneFeature(
        fragment = this,
        components = components,
        repository = repository,
        browserLayout = browserLayout,
        swipeRefresh = swipeRefresh,
        mainEngineView = mainEngineView,
        restoreEngineView = restoreEngineView,
        contextMenuCandidates = contextMenuCandidates,
    )
    viewLifecycleOwner.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = pane.start()

            override fun onStop(owner: LifecycleOwner) = pane.stop()
        },
    )
}

/**
 * The tab shown in the split pane, the way its split view was made, and whether it comes first in it, on top or on the
 * left.
 */
private data class PaneTarget(
    val tabId: String,
    val engineSession: EngineSession?,
    val selectedTabId: String,
    val orientation: SplitOrientation,
    val paneFirst: Boolean,
)

/**
 * A page can only be shown by one view at a time, so the pane must let go of a tab before the browser shows it, and
 * wait for the browser to let go of the tab it showed before. [SplitViewMiddleware] tells the pane about such changes
 * while they happen, before the browser reacts to them.
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal class SplitPaneFeature(
    private val fragment: Fragment,
    private val components: Components,
    private val repository: WorkspaceRepository,
    private val browserLayout: CoordinatorLayout,
    private val swipeRefresh: View,
    private val mainEngineView: EngineView,
    private val restoreEngineView: () -> Unit,
    private val contextMenuCandidates: () -> List<ContextMenuCandidate>,
) {
    private val store = components.core.store
    private var scope: CoroutineScope? = null
    private var pane: SplitPane? = null
    private var divider: View? = null

    // Pictures of both pages, covering their places while they trade views, see [focus].
    private val covers = mutableListOf<ImageView>()
    private val dropCover = Runnable { removeCover() }
    private var target: PaneTarget? = null
    private var shownTabId: String? = null
    private var shownSession: EngineSession? = null
    private var contextMenu: ContextMenuFeature? = null
    private var previousSelectedTabId: String? = null
    private var mainSessionAtSwitch: GeckoSession? = null
    private var saved: SavedLayout? = null
    private var shownOrientation by mutableStateOf(SplitOrientation.TOP_AND_BOTTOM)
    private var otherWay by mutableStateOf<SplitOrientation?>(null)
    private val retryBind = Runnable { target?.let(::bind) }

    /** Quick retries made for the current target. The browser may never show the new tab, so they slow down. */
    private var bindRetries = 0
    private val layoutListener = View.OnLayoutChangeListener { view, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (view === browserLayout || bottom - top != oldBottom - oldTop) browserLayout.post { applyLayout() }
    }

    /** Whether the split decides where the browser's page goes, instead of the bars. */
    val ownsLayout: Boolean
        get() = saved != null

    fun start() {
        SplitPanes.active = this
        previousSelectedTabId = store.state.selectedTabId
        scope = store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            flow.combine(repository.state) { state, workspaces -> targetOf(state, workspaces) }
                .distinctUntilChanged()
                .collect { update(it) }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        if (SplitPanes.active === this) SplitPanes.active = null
        hide()
    }

    /** Called while the selected tab changes from [previous] to [selected], before the browser shows it. */
    fun onSelectionChanging(previous: String?, selected: String?) {
        previousSelectedTabId = previous
        mainSessionAtSwitch = mainEngineView.asView().findGeckoView()?.session
        if (selected != null && selected == shownTabId) releaseEngine()
    }

    /** Called before the tabs [tabIds] are closed or unloaded. */
    fun onTabsLeaving(tabIds: Collection<String>) {
        if (shownTabId in tabIds) releaseEngine()
    }

    private fun targetOf(state: BrowserState, workspaces: WorkspaceState): PaneTarget? {
        val selected = state.selectedTab ?: return null
        if (selected.content.private || selected.content.fullScreen) return null
        val split = workspaces.splitOf(selected.id) ?: return null
        val partnerId = split.tabIds.firstOrNull { it != selected.id } ?: return null
        val partner = state.findTab(partnerId)?.takeIf { !it.content.private } ?: return null
        return PaneTarget(
            tabId = partner.id,
            engineSession = partner.engineState.engineSession,
            selectedTabId = selected.id,
            orientation = split.orientation,
            paneFirst = split.tabIds.first() == partner.id,
        )
    }

    private fun update(next: PaneTarget?) {
        target = next
        bindRetries = 0
        if (next == null) {
            hide()
        } else {
            show()
            applyLayout()
            bind(next)
        }
    }

    private fun bind(next: PaneTarget) {
        val pane = pane ?: return
        browserLayout.removeCallbacks(retryBind)
        val session = next.engineSession
        if (next.tabId == shownTabId && session === shownSession) return
        if (session == null) {
            releaseEngine()
            store.dispatch(EngineAction.CreateEngineSessionAction(next.tabId))
            return
        }
        val mainSession = mainEngineView.asView().findGeckoView()?.session
        if (next.tabId == previousSelectedTabId && mainSession != null && mainSession === mainSessionAtSwitch) {
            // The browser still shows this tab; try again once it shows the newly selected one.
            // After the quick tries, a slow one keeps the pane from staying empty when the browser takes long.
            val wait = if (bindRetries++ < MAX_BIND_RETRIES) BIND_RETRY_MS else SLOW_BIND_RETRY_MS
            browserLayout.postDelayed(retryBind, wait)
            return
        }
        releaseEngine()
        pane.engineView.render(session)
        shownTabId = next.tabId
        shownSession = session
        bindRetries = 0
        // The pane draws the page it now shows within a few frames, the browser's view too; their pictures can go then.
        if (covers.isNotEmpty()) {
            browserLayout.removeCallbacks(dropCover)
            browserLayout.postDelayed(dropCover, COVER_AFTER_BIND_MS)
        }
        contextMenu = ContextMenuFeature(
            fragmentManager = fragment.parentFragmentManager,
            store = store,
            candidates = contextMenuCandidates(),
            engineView = pane.engineView,
            useCases = components.useCases.contextMenuUseCases,
            tabId = next.tabId,
        ).also { it.start() }
    }

    private fun releaseEngine() {
        contextMenu?.stop()
        contextMenu = null
        if (shownTabId != null) pane?.engineView?.release()
        shownTabId = null
        shownSession = null
    }

    private fun show() {
        if (pane != null) return
        val context = browserLayout.context
        val engineView = components.core.engine.createView(context)
        engineView.asView().findGeckoView()?.let { geckoView ->
            // Drawn as a normal view, so the pane covers the page next to it where their areas meet, and its scrolling
            // does not move the bars.
            geckoView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            ViewCompat.setNestedScrollingEnabled(geckoView, false)
        }
        val line = ComposeView(context).apply {
            setContent {
                FirefoxTheme {
                    SplitDivider(
                        orientation = shownOrientation,
                        otherWay = otherWay,
                        onDragTo = ::resizeTo,
                        onTurn = { way -> target?.let { repository.setSplitOrientation(it.tabId, way) } },
                        onClose = { target?.let { repository.unsplit(setOf(it.tabId)) } },
                    )
                }
            }
        }
        val newPane = SplitPane(context, engineView, onPageTapped = ::focusPane)
        browserLayout.addView(newPane, CoordinatorLayout.LayoutParams(0, 0))
        browserLayout.addView(line, CoordinatorLayout.LayoutParams(0, 0))
        pane = newPane
        divider = line
        takeLayout()
        browserLayout.addOnLayoutChangeListener(layoutListener)
        toolbars().forEach { it.addOnLayoutChangeListener(layoutListener) }
    }

    private fun hide() {
        val current = pane ?: return
        browserLayout.removeCallbacks(retryBind)
        releaseEngine()
        browserLayout.removeOnLayoutChangeListener(layoutListener)
        toolbars().forEach { it.removeOnLayoutChangeListener(layoutListener) }
        browserLayout.removeView(current)
        divider?.let(browserLayout::removeView)
        removeCover()
        pane = null
        divider = null
        SplitScreen.shown = null
        releaseLayout()
    }

    /** Turns to the tab of the pane, which the user tapped: the browser's view moves over to it. */
    private fun focusPane() {
        target?.let { SplitScreen.focus(browserLayout.context, it.tabId) }
    }

    /** Whether the pane shows the tab with [tabId]. */
    fun showsInPane(tabId: String): Boolean = pane != null && target?.tabId == tabId

    /**
     * Turns to the tab with [tabId] of the pane, then calls [then]. The browser's view moves over to it, and the pane
     * takes the page the browser's view showed. Each view can only draw its new page once the other let go of it, so
     * until then pictures of both pages cover their places, and neither page seems to jump.
     */
    fun focus(tabId: String, then: () -> Unit) {
        val pane = pane ?: return
        val mainPlace = placeOf(swipeRefresh)
        val panePlace = placeOf(pane)
        val pictures = arrayOfNulls<Bitmap>(2)
        var waiting = 2
        var done = false
        fun select() {
            if (done) return
            done = true
            if (this.pane != null) {
                removeCover()
                pictures[0]?.let { showCover(it, mainPlace) }
                pictures[1]?.let { showCover(it, panePlace) }
                if (covers.isNotEmpty()) browserLayout.postDelayed(dropCover, COVER_TIMEOUT_MS)
            }
            components.useCases.tabsUseCases.selectTab(tabId)
            then()
        }
        fun pictured(index: Int, picture: Bitmap?) {
            pictures[index] = picture
            waiting--
            if (waiting == 0) select()
        }
        browserLayout.postDelayed({ select() }, PICTURE_TIMEOUT_MS)
        runCatching { mainEngineView.captureThumbnail { pictured(0, it) } }.onFailure { pictured(0, null) }
        runCatching { pane.engineView.captureThumbnail { pictured(1, it) } }.onFailure { pictured(1, null) }
    }

    /**
     * Pictures the page the pane shows, then calls [then] with the picture and where the pane is in the window, or
     * with `null` when it shows none or the picture does not come in time.
     */
    fun picturePage(then: (Bitmap?, PixelRect) -> Unit) {
        val pane = pane
        if (pane == null || shownTabId == null || !pane.isAttachedToWindow) {
            then(null, PixelRect(0, 0, 0, 0))
            return
        }
        val location = IntArray(2).also(pane::getLocationInWindow)
        val place = PixelRect(location[0], location[1], location[0] + pane.width, location[1] + pane.height)
        var done = false
        fun pictured(picture: Bitmap?) {
            if (done) return
            done = true
            then(picture, place)
        }
        browserLayout.postDelayed({ pictured(null) }, PICTURE_TIMEOUT_MS)
        runCatching { pane.engineView.captureThumbnail(::pictured) }.onFailure { pictured(null) }
    }

    private fun placeOf(view: View): PixelRect = (view.layoutParams as CoordinatorLayout.LayoutParams).let {
        PixelRect(it.leftMargin, it.topMargin, it.leftMargin + it.width, it.topMargin + it.height)
    }

    private fun showCover(picture: Bitmap, area: PixelRect) {
        val view = ImageView(browserLayout.context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(picture)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        browserLayout.addView(view, CoordinatorLayout.LayoutParams(area.width, area.height))
        place(view, area)
        covers += view
    }

    private fun removeCover() {
        browserLayout.removeCallbacks(dropCover)
        covers.forEach(browserLayout::removeView)
        covers.clear()
    }

    private fun toolbars(): List<View> = browserLayout.children.filter { it is ScrollableToolbar }.toList()

    /**
     * Keeps the bars in place while split, without scrolling away with the page, and lets the split place the page:
     * the bars would otherwise move it as they scroll, as if it were the only one.
     */
    private fun takeLayout() {
        if (saved != null) return
        val params = swipeRefresh.layoutParams as CoordinatorLayout.LayoutParams
        val bars = toolbars().associateWith { (it.layoutParams as CoordinatorLayout.LayoutParams).behavior }
        saved = SavedLayout(
            width = params.width,
            height = params.height,
            gravity = params.gravity,
            margins = listOf(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin),
            behavior = params.behavior,
            barBehaviors = bars,
        )
        bars.keys.forEach { bar ->
            (bar as ScrollableToolbar).expand()
            (bar.layoutParams as CoordinatorLayout.LayoutParams).behavior = null
            bar.translationY = 0f
            bar.requestLayout()
        }
        params.behavior = null
        swipeRefresh.translationY = 0f
        swipeRefresh.layoutParams = params
        mainEngineView.setDynamicToolbarMaxHeight(0)
        mainEngineView.setVerticalClipping(0)
    }

    private fun releaseLayout() {
        val layout = saved ?: return
        saved = null
        val params = swipeRefresh.layoutParams as CoordinatorLayout.LayoutParams
        params.width = layout.width
        params.height = layout.height
        params.gravity = layout.gravity
        params.setMargins(layout.margins[0], layout.margins[1], layout.margins[2], layout.margins[3])
        params.behavior = layout.behavior
        swipeRefresh.layoutParams = params
        layout.barBehaviors.forEach { (bar, behavior) ->
            (bar.layoutParams as? CoordinatorLayout.LayoutParams)?.let {
                it.behavior = behavior
                bar.layoutParams = it
            }
        }
        restoreEngineView()
    }

    /** The area between the bars, where the two pages go. */
    private fun pageArea(): PixelRect {
        val height = browserLayout.height
        val bars = toolbars().filter { it.isVisible }
        val top = bars.filter { it.top < height / 2 }.maxOfOrNull { it.bottom } ?: 0
        val bottom = bars.filter { it.top >= height / 2 }.minOfOrNull { it.top } ?: height
        return PixelRect(0, top, browserLayout.width, bottom)
    }

    /** Places the browser's page and the pane, and tells the bars how they show. */
    fun applyLayout() {
        val pane = pane ?: return
        val divider = divider ?: return
        val target = target ?: return
        val area = pageArea()
        if (area.width <= 0 || area.height <= 0) return
        val ways = SplitSpace.supported(browserLayout.context)
        val orientation = SplitSpace.shown(target.orientation, ways)
        shownOrientation = orientation
        otherWay = ways.firstOrNull { it != orientation }
        val thickness = (DIVIDER_THICKNESS.value * browserLayout.resources.displayMetrics.density).roundToInt()
        val places = SplitGeometry.place(area, orientation, fraction, thickness, target.paneFirst)
        place(swipeRefresh, places.main)
        place(pane, places.pane)
        place(divider, places.divider)
        SplitScreen.shown = ShownSplit(
            firstTabId = if (target.paneFirst) target.tabId else target.selectedTabId,
            secondTabId = if (target.paneFirst) target.selectedTabId else target.tabId,
            orientation = orientation,
            fraction = fraction.coerceIn(SplitGeometry.MIN_FRACTION, SplitGeometry.MAX_FRACTION),
        )
    }

    private fun place(view: View, rect: PixelRect) {
        val params = view.layoutParams as CoordinatorLayout.LayoutParams
        val gravity = Gravity.TOP or Gravity.LEFT
        if (params.width == rect.width && params.height == rect.height && params.gravity == gravity &&
            params.leftMargin == rect.left && params.topMargin == rect.top
        ) {
            return
        }
        params.width = rect.width
        params.height = rect.height
        params.gravity = gravity
        params.setMargins(rect.left, rect.top, 0, 0)
        view.layoutParams = params
    }

    /**
     * Resizes the pages so that they meet at [position], in window coordinates: a y top and bottom, an x side by side.
     */
    private fun resizeTo(position: Float) {
        val area = pageArea()
        if (area.width <= 0 || area.height <= 0) return
        val location = IntArray(2).also(browserLayout::getLocationInWindow)
        val local = position - if (shownOrientation == SplitOrientation.TOP_AND_BOTTOM) location[1] else location[0]
        fraction = SplitGeometry.fractionAt(area, shownOrientation, local)
        applyLayout()
    }

    /** How the browser's page was placed for the bars before the split, and how the bars scrolled away. */
    private class SavedLayout(
        val width: Int,
        val height: Int,
        val gravity: Int,
        val margins: List<Int>,
        val behavior: CoordinatorLayout.Behavior<*>?,
        val barBehaviors: Map<View, CoordinatorLayout.Behavior<*>?>,
    )

    companion object {
        private const val BIND_RETRY_MS = 50L
        private const val MAX_BIND_RETRIES = 20
        private const val SLOW_BIND_RETRY_MS = 1_000L
        private const val PICTURE_TIMEOUT_MS = 150L
        private const val COVER_AFTER_BIND_MS = 120L
        private const val COVER_TIMEOUT_MS = 1_000L

        /** Share of the page area the first tab of a split gets, kept while the app runs. */
        private var fraction = 0.5f
    }
}

/** The split pane of the browser screen that is shown, if any. */
internal object SplitPanes {
    @Volatile
    var active: SplitPaneFeature? = null
}

/**
 * Tells the split pane about tab changes while they happen, before the browser reacts to them: see
 * [SplitPaneFeature].
 */
internal class SplitViewMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        val pane = SplitPanes.active
        if (pane == null || Looper.myLooper() != Looper.getMainLooper()) {
            next(action)
            return
        }
        when (action) {
            is TabListAction.RemoveTabAction -> pane.onTabsLeaving(listOf(action.tabId))
            is TabListAction.RemoveTabsAction -> pane.onTabsLeaving(action.tabIds)
            is TabListAction.RemoveAllTabsAction, TabListAction.RemoveAllNormalTabsAction ->
                pane.onTabsLeaving(store.state.tabs.map { it.id })
            is EngineAction.SuspendEngineSessionAction -> pane.onTabsLeaving(listOf(action.tabId))
            is EngineAction.KillEngineSessionAction -> pane.onTabsLeaving(listOf(action.tabId))
            else -> Unit
        }
        val before = store.state.selectedTabId
        next(action)
        val after = store.state.selectedTabId
        if (before != after) pane.onSelectionChanging(before, after)
    }
}

/**
 * The page of the tab in the split pane. A tap on it calls [onPageTapped], once the page got it, so that the tap still
 * reaches the page; long presses, like selecting text, and gestures of more fingers leave it be.
 */
@SuppressLint("ViewConstructor")
private class SplitPane(
    context: Context,
    val engineView: EngineView,
    private val onPageTapped: () -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = ViewConfiguration.getLongPressTimeout()
    private var downX = 0f
    private var downY = 0f
    private var tapping = false

    init {
        addView(engineView.asView(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tapping = true
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) tapping = false
            MotionEvent.ACTION_UP -> if (tapping && event.eventTime - event.downTime < longPress) post(onPageTapped)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tapping = false
        }
        return super.dispatchTouchEvent(event)
    }
}

private fun View.findGeckoView(): GeckoView? = when (this) {
    is GeckoView -> this
    is ViewGroup -> children.firstNotNullOfOrNull { it.findGeckoView() }
    else -> null
}

/** How thick the divider between the pages of a split view is, which is easier to grab than it looks. */
private val DIVIDER_THICKNESS = 14.dp

/**
 * The divider between the pages of a split view shown [orientation]: dragging it resizes the pages, tapping it offers
 * to close the split, and to show it [otherWay] on screens with room for both ways. The drag follows the finger in
 * window coordinates, as the divider moves with it.
 *
 * @param onDragTo Called with where the pages should meet, in window coordinates: a y top and bottom, an x side by
 * side.
 */
@Composable
private fun SplitDivider(
    orientation: SplitOrientation,
    otherWay: SplitOrientation?,
    onDragTo: (Float) -> Unit,
    onTurn: (SplitOrientation) -> Unit,
    onClose: () -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var tappedAt by remember { mutableStateOf(IntOffset.Zero) }
    val topAndBottom = orientation == SplitOrientation.TOP_AND_BOTTOM
    val label = stringResource(R.string.dejavu_split_close)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onGloballyPositioned { coordinates = it }
            .semantics { contentDescription = label }
            .pointerInput(orientation) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val layout = coordinates?.takeIf { it.isAttached } ?: return@detectDragGestures
                    val point = layout.localToWindow(change.position)
                    // The pages meet in the middle of the divider, wherever it was grabbed.
                    onDragTo(if (topAndBottom) point.y else point.x)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { point ->
                        tappedAt = IntOffset(point.x.roundToInt(), point.y.roundToInt())
                        menuOpen = true
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = if (topAndBottom) GRIP_LENGTH else GRIP_WIDTH,
                    height = if (topAndBottom) GRIP_WIDTH else GRIP_LENGTH,
                )
                .clip(RoundedCornerShape(GRIP_WIDTH / 2))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = GRIP_ALPHA)),
        )
        // The menu opens where the divider was tapped, as the divider runs across the whole page area.
        Box(modifier = Modifier.align(Alignment.TopStart).offset { tappedAt }) {
            DejavuDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                otherWay?.let { way ->
                    DejavuMenuItem(
                        text = stringResource(way.menuItem.label),
                        onClick = {
                            menuOpen = false
                            onTurn(way)
                        },
                    )
                }
                DejavuMenuItem(
                    text = label,
                    onClick = {
                        menuOpen = false
                        onClose()
                    },
                )
            }
        }
    }
}

private val GRIP_LENGTH = 32.dp
private val GRIP_WIDTH = 4.dp
private const val GRIP_ALPHA = 0.5f
