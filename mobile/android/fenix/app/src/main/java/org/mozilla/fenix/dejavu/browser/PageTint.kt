/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.snapshotFlow
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineView
import mozilla.components.lib.state.ext.flow
import org.mozilla.fenix.R
import org.mozilla.fenix.components.AppStore
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.captureRow
import org.mozilla.geckoview.captureTopEdge

private const val EDGE_SAMPLES = 24
private const val PAGE_MOVE_DEBOUNCE_MS = 250L
private const val SETTLE_MS = 120L
private const val LATER_LOOKS = 5
private const val LATER_LOOK_MS = 1_000L
private const val REMEMBERED_TABS = 32

// Colors this close count as one, so a page's background wins over a logo or text crossing its top edge.
private const val COLOR_BUCKET = 0xF0F0F0
private const val OPAQUE = 0xFF shl 24

/**
 * Paints what shows above the page, behind Dejavu's address bar and the status bar, in the color along the page's top
 * edge, so the address bar floats over the page as in Candy instead of over a band in the app's color. The status bar's
 * icons turn dark over light pages and light over dark ones, and so does the fade behind them; the navigation bar's
 * icons do the same over the page behind them. While a search screen covers the page, they follow the app's theme
 * again, and so does all of it while a split view shows two pages between bars that stay in place.
 *
 * @param behindPage The view whose background shows above the page.
 * @param layout The browser layout holding the fade behind the status bar.
 * @param restoreTheme Gives the status bar the icons of the app's theme back.
 */
internal class PageTint(
    private val window: Window,
    private val engineView: EngineView,
    private val behindPage: View,
    private val layout: CoordinatorLayout,
    private val restoreTheme: () -> Unit,
) {
    private var tinted: Int? = null
    private val untinted = behindPage.background

    /** Follows the page of the selected tab of [browserStore] while [owner] is resumed. */
    @OptIn(FlowPreview::class)
    fun start(owner: LifecycleOwner, browserStore: BrowserStore, appStore: AppStore) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val searches = appStore.flow().map { it.searchState.isSearchActive }.distinctUntilChanged()
                val splits = snapshotFlow { SplitScreen.shown?.tabIds }.distinctUntilChanged()
                merge(pageChanges(browserStore), pageMoves().debounce(PAGE_MOVE_DEBOUNCE_MS), searches, splits).collectLatest {
                    if (appStore.state.searchState.isSearchActive) {
                        restoreTheme()
                        return@collectLatest
                    }
                    val tab = browserStore.state.selectedTab ?: return@collectLatest
                    if (SplitScreen.shown?.tabIds?.contains(tab.id) == true) {
                        untint()
                        return@collectLatest
                    }
                    // A tab shown again takes its color at once, before its page is pictured.
                    rememberedColors[tab.id]?.let(::tint)
                    if (!tab.content.firstContentfulPaint || window.isFullScreen) return@collectLatest
                    // Waits for the page to settle, as scrolling and loading keep changing what is at its top, then
                    // looks again every second while the page loads or its colors still change, as styles and add-ons
                    // like Dark Reader may change them. Each look makes the engine draw a frame of the page for it.
                    delay(SETTLE_MS)
                    var seen = tintLike(tab.id)
                    repeat(LATER_LOOKS) {
                        delay(LATER_LOOK_MS)
                        val now = tintLike(tab.id)
                        if (now == seen && browserStore.state.selectedTab?.content?.loading != true) {
                            return@collectLatest
                        }
                        seen = now
                    }
                }
            }
        }
    }

    private fun pageChanges(store: BrowserStore): Flow<ShownPage?> = store.flow()
        .map { state ->
            state.selectedTab?.let { tab ->
                ShownPage(
                    tabId = tab.id,
                    url = tab.content.url,
                    loading = tab.content.loading,
                    painted = tab.content.firstContentfulPaint,
                    scrollY = tab.readerState.scrollY,
                )
            }
        }
        .distinctUntilChanged()

    /** Where the page is in the window, as the address bar scrolls away and back. */
    private fun pageMoves(): Flow<Int> = callbackFlow {
        val view = engineView.asView()
        val location = IntArray(2)
        var top = Int.MIN_VALUE
        val listener = ViewTreeObserver.OnPreDrawListener {
            view.getLocationInWindow(location)
            if (location[1] != top) {
                top = location[1]
                trySend(top)
            }
            true
        }
        val observer = view.viewTreeObserver
        observer.addOnPreDrawListener(listener)
        awaitClose {
            (if (observer.isAlive) observer else view.viewTreeObserver).removeOnPreDrawListener(listener)
        }
    }

    /**
     * Tints like the page of the tab with [tabId] shows at its top and behind the navigation bar now, remembers those
     * colors for the tab and returns them; `null` when the page could not be pictured.
     */
    private suspend fun tintLike(tabId: String): PageColors? {
        val view = (engineView.asView() as? ViewGroup)?.children?.firstOrNull { it is GeckoView } as? GeckoView
        if (view == null || !view.isAttachedToWindow) return null
        val edge = captureTopEdge(view, EDGE_SAMPLES)?.valueOrNull() ?: return null
        val top = edge.topEdgeColor()
        edge.recycle()
        val row = captureRow(view, view.rowBehindNavigationBar(), EDGE_SAMPLES)?.valueOrNull()
        val colors = PageColors(top, bottom = row?.topEdgeColor() ?: top)
        row?.recycle()
        rememberedColors.put(tabId, colors)
        tint(colors)
        return colors
    }

    private fun tint(colors: PageColors) {
        val isLightPage = isLight(colors.top)
        if (colors.top != tinted) {
            tinted = colors.top
            behindPage.setBackgroundColor(colors.top)
            layout.tintStatusBarScrim(if (isLightPage) Color.WHITE else Color.BLACK)
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLightPage
            isAppearanceLightNavigationBars = isLight(colors.bottom)
        }
    }

    private fun untint() {
        if (tinted != null) {
            tinted = null
            behindPage.background = untinted
            layout.tintStatusBarScrim(ContextCompat.getColor(layout.context, R.color.fx_mobile_surface))
        }
        restoreTheme()
    }

    private data class ShownPage(
        val tabId: String,
        val url: String,
        val loading: Boolean,
        val painted: Boolean,
        val scrollY: Int?,
    )

    companion object {
        private val rememberedColors = LruCache<String, PageColors>(REMEMBERED_TABS)

        /** Shows the tab with [tabId] with [color] above its page, until its page is pictured again. */
        fun rememberColor(tabId: String, color: Int) {
            rememberedColors.put(tabId, PageColors(color, bottom = rememberedColors[tabId]?.bottom ?: color))
        }
    }
}

/** The color along the top edge of a page, and the one of the page behind the navigation bar. */
private data class PageColors(val top: Int, val bottom: Int)

/** The row of [this] page view behind the middle of the navigation bar, or its last row without one. */
private fun View.rowBehindNavigationBar(): Int {
    val top = IntArray(2).also(::getLocationInWindow)[1]
    return rootView.height - navigationBarHeight / 2 - top
}

/** Whether dark text and icons stand out more than light ones on [color]. */
private fun isLight(color: Int): Boolean =
    ColorUtils.calculateContrast(Color.BLACK, color) > ColorUtils.calculateContrast(Color.WHITE, color)

/** The color most of the top row of [this] picture has: the page's background there, not a logo or text crossing it. */
internal fun Bitmap.topEdgeColor(): Int {
    val row = IntArray(width).also { getPixels(it, 0, width, 0, 0, width, 1) }
    return row.groupBy { it and COLOR_BUCKET }.maxBy { it.value.size }.value.first() or OPAQUE
}
