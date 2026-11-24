/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.color
import org.mozilla.fenix.dejavu.sync.workspaceIconText
import org.mozilla.fenix.dejavu.ui.iconPainter
import org.mozilla.fenix.dejavu.workspaces.Workspace

/**
 * Tabs, pinned tabs and folders dragged on the home screen. The drag can cross workspaces: holding the finger on the
 * strip at a side of the page turns to the workspace on that side, and the items land wherever they are let go.
 *
 * @property selection What is dragged.
 * @property from The workspace the drag started in.
 * @property canLeave Whether the items can go to other workspaces: those of a device's workspace follow the device.
 * @property pointer The finger, relative to the pages.
 * @property start Where the long press happened.
 * @property moved Whether the finger moved enough to be a drag rather than a long press.
 * @property over The workspace whose page is under the finger, `null` while it is on a side strip or the pages turn.
 * @property target Where the items would land, or `null` when they cannot go there.
 * @property blocked Whether the place under the finger refuses them, like the pinned tabs of a device's workspace.
 * @property side The side strip under the finger: -1 leads to the previous workspace, 1 to the next one, 0 is none.
 */
internal data class SelectionDrag(
    val selection: Selection,
    val from: String,
    val canLeave: Boolean,
    val pointer: Offset,
    val start: Offset,
    val moved: Boolean = false,
    val over: String? = from,
    val target: DropTarget? = null,
    val blocked: Boolean = false,
    val side: Int = 0,
)

/** Where dragged items would land on a page, and whether that page refuses them there. */
internal data class PageDrop(val target: DropTarget?, val blocked: Boolean = false)

/** A workspace page as the drag on the home screen sees it, see [SelectionDrag]. */
internal interface PageDragArea {
    /** Starts dragging the row at [offset], the tab on the end side of a split view's row when [atEnd] is set. */
    fun start(offset: Offset, atEnd: Boolean): SelectionDrag?

    /** Where [drag] would land with the finger at [y], relative to the top of the page. */
    fun drop(y: Float, drag: SelectionDrag): PageDrop

    /** Scrolls the page by [step] pixels and returns how far it went. */
    suspend fun scrollBy(step: Float): Float

    /** Height of the page. */
    val height: Int

    /** Lifts the rows of [drag] that are in view into [stack], the one long-pressed at [press] on top. */
    fun lift(stack: DragStackState, drag: SelectionDrag, press: Offset)

    /** Where the rows [cards] came from are on the page, while they are in view, by the key of each card's row. */
    fun rowRects(cards: List<StackCard>): Map<String, Rect>

    /** Where the rows the items of [cards] moved to are on the page after a drop, by the key of each card's row. */
    fun landings(cards: List<StackCard>): Map<String, Rect>

    /** Where cards dropped on [target] head when their rows are not in view on the page. */
    fun dropFallback(target: DropTarget): Offset?
}

/**
 * The side strip at [x] on a page [width] wide whose strips are [strip] wide: 1 at the end, which leads to the next
 * workspace, -1 at the start, which leads to the previous one, else 0. The end is on the left in right-to-left
 * languages.
 */
internal fun sideAt(x: Float, width: Float, strip: Float, isRtl: Boolean): Int {
    val onLeft = x < strip
    val onRight = x > width - strip
    return when {
        onRight -> if (isRtl) -1 else 1
        onLeft -> if (isRtl) 1 else -1
        else -> 0
    }
}

/**
 * The strip along a side of the page while items are dragged, which turns to [workspace] once the finger rests on it.
 * It points to the start of the page unless [atEnd], and stands out while the finger is on it ([active]).
 */
@Composable
internal fun SideStrip(
    workspace: Workspace,
    container: ContainerRecord?,
    active: Boolean,
    atEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 8.dp, horizontal = 2.dp)
            .width(SideStripWidth - 4.dp)
            .clip(shape)
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = STRIP_ALPHA)
                },
            ),
    ) {
        Icon(
            painter = iconPainter(iconsR.drawable.mozac_ic_chevron_right_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = if (atEnd) 1f else -1f },
        )
        Spacer(Modifier.size(8.dp))
        val icon = workspaceIconText(workspace.icon)
        if (icon != null) {
            Text(text = icon, fontSize = 17.sp)
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(container?.color?.color ?: MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

/** Width of the strips along the sides of the page while items are dragged. */
internal val SideStripWidth = 44.dp

private const val STRIP_ALPHA = 0.92f
