/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.mozilla.fenix.dejavu.ui.DejavuMotion

private const val MOVE_DURATION_MS = 280

/**
 * Lays out an address bar that sits at the top of the screen. While searching, the bar moves down to sit right above
 * the keyboard, full width, with the suggestions above it; it slides there when search starts and back to the top
 * when search ends. An address bar below pages leaves the top empty, so the search bar shows and goes at once there.
 *
 * @param enabled Whether the bar moves at all; when not, [topLayout] is always shown.
 * @param isSearching Whether the address bar is being edited.
 * @param fromTop Whether the address bar sits at the top, for the search bar to slide down from and back up to.
 * @param toolbar The address bar.
 * @param suggestions The search suggestions, shown above the bar while searching.
 * @param topLayout The usual layout with the bar at the top.
 */
@Composable
fun DejavuSearchAboveKeyboard(
    enabled: Boolean,
    isSearching: Boolean,
    fromTop: Boolean,
    toolbar: @Composable () -> Unit,
    suggestions: @Composable (Modifier) -> Unit,
    topLayout: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (enabled && isSearching) 1f else 0f) }
    val fromBottom = remember(isSearching) { isSearching && DejavuSearchStart.isFromBottom() }
    LaunchedEffect(isSearching, enabled) {
        val target = if (enabled && isSearching) 1f else 0f
        if ((target == 1f && fromBottom) || !fromTop) {
            progress.snapTo(target)
        } else {
            if (target == 1f && progress.value == 0f) awaitSteadyFrames()
            progress.animateTo(target, tween(MOVE_DURATION_MS, easing = FastOutSlowInEasing))
        }
    }
    if (!enabled || (!isSearching && progress.value == 0f)) {
        topLayout()
        return
    }
    val shown = if (isSearching && fromBottom) 1f else progress.value

    var height by remember { mutableIntStateOf(0) }
    var barHeight by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxWidth().wrapContentHeight().onSizeChanged { height = it.height }) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isSearching) {
                suggestions(Modifier.fillMaxSize().graphicsLayer { alpha = shown })
            }
        }
        Box(
            modifier = Modifier
                .onSizeChanged { barHeight = it.height }
                .graphicsLayer {
                    translationY = -(1f - shown) * (height - barHeight).coerceAtLeast(0)
                    alpha = if (height == 0) 0f else 1f
                },
        ) {
            toolbar()
        }
    }
}

/**
 * The search screen of the home screen, with the address bar at its bottom, right above the keyboard. When [fromTop],
 * the search started from an address bar at the top of the screen, so the screen slides down from there with the
 * address bar as its lower edge.
 */
@Composable
fun DejavuSearchOverlay(fromTop: Boolean, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (fromTop && !DejavuSearchStart.isFromBottom()) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value == 1f) return@LaunchedEffect
        awaitSteadyFrames()
        progress.animateTo(1f, tween(MOVE_DURATION_MS, easing = FastOutSlowInEasing))
    }
    var height by remember { mutableIntStateOf(0) }
    val barHeight = with(LocalDensity.current) { SEARCH_BAR_HEIGHT.toPx() }
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { height = it.height }
                .graphicsLayer {
                    translationY = -(1f - progress.value) * (height - barHeight).coerceAtLeast(0f)
                    alpha = if (height == 0) 0f else 1f
                }
                .background(searchScreenBackground()),
        ) {
            content()
        }
    }
}

private val SEARCH_BAR_HEIGHT = 64.dp

private const val STEADY_FRAMES = 3
private const val STEADY_FRAME_NANOS = 34_000_000L
private const val MAX_SETTLE_NANOS = 500_000_000L

/**
 * Waits until the screen is drawn at a steady pace again, for at most [maxNanos], so that an animation started next is
 * not skipped through while a screen that just opened is still setting itself up. With animations off, nothing waits.
 */
internal suspend fun awaitSteadyFrames(maxNanos: Long = MAX_SETTLE_NANOS) {
    if (!DejavuMotion.animates) return
    val first = withFrameNanos { it }
    var last = first
    var steady = 0
    while (steady < STEADY_FRAMES && last - first < maxNanos) {
        val now = withFrameNanos { it }
        steady = if (now - last <= STEADY_FRAME_NANOS) steady + 1 else 0
        last = now
    }
}

/**
 * Remembers that a search was just started from the bottom of the screen, so the search screens opening for it appear
 * there at once instead of coming down from the address bar.
 */
object DejavuSearchStart {
    private const val VALID_FOR_MS = 1_500L
    private var startedAt = 0L

    fun fromBottom() {
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Whether the search starting now was started from the bottom of the screen. */
    fun isFromBottom(): Boolean = startedAt != 0L && SystemClock.elapsedRealtime() - startedAt < VALID_FOR_MS
}

/**
 * A search started with New tab over a shown page. It opens over the page, as tapping the address bar does, and what
 * is searched or typed opens in a new tab instead of in that page's tab.
 */
object NewTabSearch {
    @Volatile
    private var tabId: String? = null

    /** Starts a search over the tab with [tabId] that opens in a new tab. */
    fun start(tabId: String) {
        this.tabId = tabId
    }

    /** Lets the next search started from a page open in that page, as usual. */
    fun clear() {
        tabId = null
    }

    /** Whether the search started from the tab with [sourceTabId] opens in a new tab rather than in that tab. */
    fun isFor(sourceTabId: String?): Boolean = sourceTabId != null && sourceTabId == tabId
}
