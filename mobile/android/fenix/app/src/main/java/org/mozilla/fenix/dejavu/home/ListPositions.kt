/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** Where each workspace's list was scrolled to as the home screen closed, by the workspace's id. */
private val listPositions = HashMap<String, Pair<Int, Int>>()

/**
 * The state of the list of the workspace with [key], scrolled to where it was the last time the home screen showed
 * it, rather than to its top.
 */
@Composable
internal fun rememberHomeListState(key: String): LazyListState {
    val listState = remember(key) {
        listPositions[key]?.let { (index, offset) -> LazyListState(index, offset) } ?: LazyListState()
    }
    DisposableEffect(listState) {
        onDispose { listPositions[key] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }
    return listState
}

/**
 * Brings the row at [index] into view at once, after the list first lays out: the row of the tab the home screen was
 * opened from, which the page shrinks into (see TabZoom).
 */
internal suspend fun LazyListState.revealWhenShown(index: Int) {
    snapshotFlow { layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
    reveal(index, animate = false)
}

/**
 * Scrolls as little as it takes to show the row at [index] whole. A row below the ones in view comes in at the bottom,
 * so the rows above it, the header with its buttons first, stay in view as far as they can.
 */
internal suspend fun LazyListState.reveal(index: Int, animate: Boolean = true) {
    val layout = layoutInfo
    val visible = layout.visibleItemsInfo
    val first = visible.firstOrNull() ?: return
    val last = visible.last()
    val row = visible.firstOrNull { it.index == index }
    val end = layout.viewportEndOffset
    suspend fun toItem() = if (animate) animateScrollToItem(index) else scrollToItem(index)
    suspend fun by(distance: Int) {
        if (animate) animateScrollBy(distance.toFloat()) else scrollBy(distance.toFloat())
    }
    when {
        row != null && row.offset < layout.viewportStartOffset -> toItem()
        row != null -> if (row.offset + row.size > end) by(row.offset + row.size - end)
        index < first.index -> toItem()
        else -> by(last.offset + last.size - end + (index - last.index) * last.size)
    }
}
