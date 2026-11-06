/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.dejavu.browser.closeOnMiddleClick
import org.mozilla.fenix.dejavu.browser.tabZoomTarget
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.color
import org.mozilla.fenix.dejavu.discover.Tip
import org.mozilla.fenix.dejavu.discover.TipGesture
import org.mozilla.fenix.dejavu.discover.tipTarget
import org.mozilla.fenix.dejavu.workspaces.PinnedItem

private val TileHeight = 52.dp
private val TileGap = 8.dp
private val MinTileWidth = 64.dp
private val TileShape = RoundedCornerShape(14.dp)
private const val MIN_COLUMNS = 3
private const val DIMMED_ALPHA = 0.55f
private const val CLOSED_TILE_ALPHA = 0.5f
private const val OPEN_OUTLINE_ALPHA = 0.5f

/**
 * An essential being reordered.
 *
 * @property pinId The long-pressed essential.
 * @property start Where the long press happened, relative to the grid.
 * @property pointer Finger position, relative to the grid.
 * @property moved Whether the finger moved enough to be a drag rather than a long press.
 * @property index Position the essential would be dropped at.
 */
private data class EssentialDrag(
    val pinId: String,
    val start: Offset,
    val pointer: Offset,
    val moved: Boolean,
    val index: Int,
)

/**
 * The essentials every workspace shares, as a grid of icons like in Zen. They take as few rows as fit, holding as many
 * essentials each as can be, see [essentialRows], and the tiles of a row share its whole width, so the grid never has
 * gaps. Long-pressing one selects it; keeping the finger down and moving reorders it.
 *
 * @param isDropTarget Whether tabs dragged on the workspace page would be dropped here.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun EssentialsGrid(
    essentials: List<PinnedItem>,
    tabsById: Map<String, TabSessionState>,
    selectedTabId: String?,
    containers: Map<String, ContainerRecord>,
    selection: Selection?,
    isDropTarget: Boolean,
    onClick: (PinnedItem) -> Unit,
    onStartDrag: (PinnedItem) -> Unit,
    onMove: (pinId: String, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<EssentialDrag?>(null) }
    val latestEssentials by rememberUpdatedState(essentials)
    val latestOnStartDrag by rememberUpdatedState(onStartDrag)
    val latestOnMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val density = LocalDensity.current
    val shape = RoundedCornerShape(18.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(shape)
            .border(2.dp, if (isDropTarget) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .padding(4.dp),
    ) {
        val columns = ((maxWidth + TileGap) / (MinTileWidth + TileGap)).toInt().coerceAtLeast(MIN_COLUMNS)
        val gridWidth = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { TileGap.toPx() }
        val cellHeight = with(density) { (TileHeight + TileGap).toPx() }

        // The first essential of the row at [position] and how many essentials the row holds.
        fun rowAt(position: Offset): Pair<Int, Int>? {
            val sizes = essentialRows(latestEssentials.size, columns)
            val row = (position.y / cellHeight).toInt().coerceAtLeast(0)
            val size = sizes.getOrNull(row) ?: return null
            return sizes.take(row).sum() to size
        }

        // How far apart the tiles of a row of [size] essentials are, a tile and a gap.
        fun cellWidth(size: Int) = (gridWidth + gapPx) / size

        // The essential under [position], or `null` between the tiles.
        fun tileAt(position: Offset): Int? {
            val (start, size) = rowAt(position) ?: return null
            val cell = cellWidth(size)
            val column = (position.x / cell).toInt()
            val onTile = column in 0 until size && position.x - column * cell < cell - gapPx
            return if (onTile) start + column else null
        }

        // The place an essential dropped at [position] takes: the one of the nearest tile.
        fun indexAt(position: Offset): Int {
            val last = (latestEssentials.size - 1).coerceAtLeast(0)
            val (start, size) = rowAt(position) ?: return last
            val column = ((position.x + gapPx / 2) / cellWidth(size)).toInt().coerceIn(0, size - 1)
            return (start + column).coerceIn(0, last)
        }

        val current = drag
        val ordered = if (current != null && current.moved) {
            val dragged = essentials.firstOrNull { it.id == current.pinId }
            if (dragged == null) {
                essentials
            } else {
                (essentials - dragged).toMutableList().apply { add(current.index.coerceIn(0, size), dragged) }
            }
        } else {
            essentials
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(TileGap),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(columns, gridWidth, cellHeight) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val index = tileAt(offset) ?: -1
                            val item = latestEssentials.getOrNull(index)
                            if (item != null) {
                                drag = EssentialDrag(item.id, offset, offset, moved = false, index = index)
                                latestOnStartDrag(item)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, amount ->
                            val dragging = drag ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            val pointer = dragging.pointer + amount
                            val moved = dragging.moved || (pointer - dragging.start).getDistance() > touchSlop
                            drag = dragging.copy(
                                pointer = pointer,
                                moved = moved,
                                index = if (moved) indexAt(pointer) else dragging.index,
                            )
                        },
                        onDragEnd = {
                            val dragging = drag
                            drag = null
                            if (dragging != null && dragging.moved) latestOnMove(dragging.pinId, dragging.index)
                        },
                        onDragCancel = { drag = null },
                    )
                },
        ) {
            ordered.inRows(essentialRows(ordered.size, columns)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(TileGap), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { item ->
                        val tab = item.tabId?.let { tabsById[it] }
                        EssentialTile(
                            item = item,
                            tab = tab,
                            container = (tab?.contextId ?: item.containerId)?.let { containers[it] },
                            isCurrent = tab != null && tab.id == selectedTabId,
                            selection = selection?.let { item.id in it.pinIds },
                            isDragged = current?.moved == true && current.pinId == item.id,
                            onClick = { if (drag == null) onClick(item) },
                            modifier = Modifier
                                .weight(1f)
                                .tabZoomTarget(tab?.id)
                                // Essentials stay, so a middle-click does nothing, and does not open one either.
                                .closeOnMiddleClick(null)
                                .tipTarget(
                                    Tip.ESSENTIAL_HOLD,
                                    TipGesture.Hold,
                                    enabled = selection == null && item.id == essentials.firstOrNull()?.id,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * How many of [count] essentials each row of the grid holds, when a row has room for [columns]: as few rows as that
 * allows, as evenly filled as can be, the fuller rows first. Six essentials in rows of four make two rows of three.
 */
internal fun essentialRows(count: Int, columns: Int): List<Int> {
    if (count <= 0 || columns <= 0) return emptyList()
    val rows = (count + columns - 1) / columns
    return List(rows) { row -> count / rows + if (row < count % rows) 1 else 0 }
}

/** This list cut into rows of [sizes] items, in order. */
internal fun <T> List<T>.inRows(sizes: List<Int>): List<List<T>> {
    var start = 0
    return sizes.map { size -> subList(start, start + size).also { start += size } }
}

@Suppress("LongParameterList")
@Composable
private fun EssentialTile(
    item: PinnedItem,
    tab: TabSessionState?,
    container: ContainerRecord?,
    isCurrent: Boolean,
    selection: Boolean?,
    isDragged: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = item.label(tab)
    val isAwake = tab?.isAwake == true
    // Like tab rows, an open essential stands out: a solid tile with an outline, the shown one in the accent color.
    // A closed or sleeping one is faded.
    val background = when {
        selection == true -> MaterialTheme.colorScheme.secondaryContainer
        isAwake || isCurrent -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = CLOSED_TILE_ALPHA)
    }
    val outline = when {
        isDragged || (isCurrent && selection == null) -> MaterialTheme.colorScheme.primary
        isAwake && selection == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = OPEN_OUTLINE_ALPHA)
        else -> Color.Transparent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(TileHeight)
            .clip(TileShape)
            .background(background)
            .border(if (isDragged || isCurrent) 2.dp else 1.dp, outline, TileShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = title },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .alpha(if (isAwake) 1f else DIMMED_ALPHA),
        ) {
            SiteIcon(url = tab?.content?.url ?: item.pageUrl, size = 22.dp)
        }
        if (container != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .width(16.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(container.color.color),
            )
        }
        if (selection == true) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_checkmark_16),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
