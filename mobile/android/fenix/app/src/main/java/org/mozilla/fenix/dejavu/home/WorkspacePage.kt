/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.actions.icon
import org.mozilla.fenix.dejavu.actions.label
import org.mozilla.fenix.dejavu.browser.ClearData
import org.mozilla.fenix.dejavu.browser.SplitSpace
import org.mozilla.fenix.dejavu.browser.closeOnMiddleClick
import org.mozilla.fenix.dejavu.browser.tabZoomTarget
import org.mozilla.fenix.dejavu.containers.ContainerIcon
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.NoContainerIcon
import org.mozilla.fenix.dejavu.containers.TemporaryContainerIcon
import org.mozilla.fenix.dejavu.containers.color
import org.mozilla.fenix.dejavu.containers.splitTemporary
import org.mozilla.fenix.dejavu.discover.Tip
import org.mozilla.fenix.dejavu.discover.TipGesture
import org.mozilla.fenix.dejavu.discover.Tips
import org.mozilla.fenix.dejavu.discover.tipTarget
import org.mozilla.fenix.dejavu.sync.workspaceIconText
import org.mozilla.fenix.dejavu.ui.DejavuDropdownMenu
import org.mozilla.fenix.dejavu.ui.DejavuMenuDivider
import org.mozilla.fenix.dejavu.ui.DejavuMenuItem
import org.mozilla.fenix.dejavu.ui.DejavuMenuLabel
import org.mozilla.fenix.dejavu.ui.TapIcon
import org.mozilla.fenix.dejavu.ui.rememberIconTaps
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

private val RowShape = RoundedCornerShape(16.dp)
private val IndentPerLevel = 18.dp
private val ListPadding = 8.dp
private val RowPadding = 2.dp
private const val DIMMED_ALPHA = 0.55f
private const val RESET_SQUARE_ALPHA = 0.14f
private val IconSquareSize = 36.dp
private val MinTouchSize = 48.dp
private const val DRAGGED_ALPHA = 0.35f
private val INSIDE_FOLDER_RANGE = 0.25f..0.75f
private val SPLIT_RANGE = 0.3f..0.7f

// Where the finger of a tip touches a row, how far it drags a tab to pin it, and onto the row above.
private const val ROW_TOUCH = 0.35f
private val PIN_DRAG = (-110).dp
private val ROW_DRAG = (-52).dp

// Where the icon starts in a row, as the drag stack lifts it off.
private val TabIconStart = 8.dp
private val PairIconStart = 10.dp
private val FolderIconStart = 26.dp

private const val KEY_HEADER = "header"
private const val KEY_DIVIDER = "divider"
private const val KEY_NEW_TAB = "new_tab"
private const val PIN_PREFIX = "pin:"
private const val TAB_PREFIX = "tab:"
private const val PAIR_PREFIX = "pair:"
private const val PIN_PAIR_PREFIX = "pin_pair:"
private const val PAIR_SEPARATOR = "|"

// Rows before the first unpinned tab, besides the pinned ones: the header, the divider and New Tab.
private const val FIRST_TAB_OFFSET = 3

/** Callbacks of [WorkspacePage]. */
data class WorkspacePageCallbacks(
    val onTabClick: (TabSessionState) -> Unit,
    val onPinClick: (PinnedItem) -> Unit,
    /** A long press on a row adds [picked] to the selection; returns the whole selection a drag starting now moves. */
    val onStartDrag: (picked: Selection) -> Selection,
    val onRowAction: (RowAction, ActionTargets) -> Unit,
    val onFolderClick: (PinnedItem) -> Unit,
    val onNewFolder: () -> Unit,
    val onNewWorkspace: () -> Unit,
    val onEditWorkspace: () -> Unit,
    val onDeleteWorkspace: () -> Unit,
    /** Moves the workspace [delta] places, negative to the left. */
    val onMoveWorkspace: (delta: Int) -> Unit,
    val onNewTabClick: () -> Unit,
    val onNewTabInContainer: (ContainerPick) -> Unit,
    val onNewPrivateTab: () -> Unit,
    val onManageContainers: () -> Unit,
    val onClearUnpinned: () -> Unit,
    /**
     * Deletes the data chosen for Clear data, from holding Clear, tapping it with no unpinned tabs, or a page asking for
     * it, then quits Dejavu when `quit`.
     */
    val onClearData: (quit: Boolean) -> Unit,
    val onReopenClosedTab: () -> Unit,
    val onOpenClipboard: () -> Unit,
    /** The row of the tab to reveal is in view. */
    val onRevealed: () -> Unit,
)

private enum class DropLine { TOP, BOTTOM, INSIDE }

/** Which row shows the drop position, and how. */
private data class DropLines(val key: String, val line: DropLine) {
    fun of(rowKey: String): DropLine? = line.takeIf { rowKey == key }
}

private data class PageContent(
    val state: WorkspaceState,
    val otherTabs: List<TabSessionState>,
    val workspace: Workspace,
    val pinLines: List<PinLine>,
    val lines: List<TabLine>,
    val lineKeys: Map<String, String>,
    val tabsById: Map<String, TabSessionState>,
    val containers: Map<String, ContainerRecord>,
)

/**
 * Where the list sits in the page, in pixels: [left] from the start of the page and [width] wide. Rows have
 * [rowPadding] above and below them.
 */
private class ListFrame(val left: Float, val width: Float, val rowPadding: Float) {
    fun rowRect(info: LazyListItemInfo) =
        Rect(left, info.offset + rowPadding, left + width, info.offset + info.size - rowPadding)

    /** The row with [key] in the page, while it is in view. */
    fun rowRect(listState: LazyListState, key: String): Rect? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let(::rowRect)
}

/** A row of tabs that are not pinned: one tab, or the two tabs of a split view side by side, like in Zen. */
private data class TabLine(val tabs: List<TabSessionState>) {
    val key: String
        get() = tabs.singleOrNull()?.let { TAB_PREFIX + it.id } ?: PAIR_PREFIX + tabs.joinToString(PAIR_SEPARATOR) { it.id }
}

/** [tabs] as rows, the two tabs of a split view that are both among them in one row, where the first one is. */
private fun tabLines(tabs: List<TabSessionState>, state: WorkspaceState): List<TabLine> {
    val byId = tabs.associateBy { it.id }
    val placed = mutableSetOf<String>()
    return tabs.mapNotNull { tab ->
        if (!placed.add(tab.id)) return@mapNotNull null
        val partner = state.splitOf(tab.id)?.tabIds?.firstOrNull { it != tab.id }?.let(byId::get)
        TabLine(listOfNotNull(tab, partner?.takeIf { placed.add(it.id) }))
    }
}

/** A row of the pinned section: one pinned item, or the two pinned tabs of a split view side by side. */
internal data class PinLine(val entries: List<PinnedEntry>) {
    val key: String
        get() = entries.singleOrNull()?.let { PIN_PREFIX + it.item.id }
            ?: PIN_PAIR_PREFIX + entries.joinToString(PAIR_SEPARATOR) { it.item.id }
}

/**
 * [entries] as rows, the two pinned tabs of a split view in the same folder in one row, where the first one is: a split
 * view open here, or one synced from Zen, open or not.
 */
internal fun pinLines(entries: List<PinnedEntry>, state: WorkspaceState): List<PinLine> {
    val tabPins = entries.filter { !it.item.isFolder }
    val byTabId = tabPins.filter { it.item.tabId != null }.associateBy { it.item.tabId }
    val byPinId = tabPins.associateBy { it.item.id }
    val placed = mutableSetOf<String>()
    return entries.mapNotNull { entry ->
        if (!placed.add(entry.item.id)) return@mapNotNull null
        val pinId = entry.item.id.takeIf { !entry.item.isFolder }
        val tabId = entry.item.tabId?.takeIf { pinId != null }
        val partner = (
            tabId?.let(state::splitOf)?.tabIds?.firstOrNull { it != tabId }?.let(byTabId::get)
                ?: pinId?.let(state::pinnedSplitOf)?.pinIds?.firstOrNull { it != pinId }?.let(byPinId::get)
            )
            ?.takeIf { it.item.parentId == entry.item.parentId && placed.add(it.item.id) }
        PinLine(listOfNotNull(entry, partner))
    }
}

@Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
@Composable
internal fun WorkspacePage(
    state: WorkspaceState,
    workspace: Workspace,
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    containers: Map<String, ContainerRecord>,
    pinnedRowActions: List<RowAction>,
    unpinnedRowActions: List<RowAction>,
    folderRowActions: List<RowAction>,
    selection: Selection?,
    drag: SelectionDrag?,
    dragAreas: MutableMap<String, PageDragArea>,
    dragStack: DragStackState,
    callbacks: WorkspacePageCallbacks,
    canDeleteWorkspace: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    hasClosedTab: Boolean = false,
    hasClipboardLink: Boolean = false,
    revealTabId: String? = null,
    openTabIds: Set<String> = emptySet(),
    hasDataToClear: Boolean = false,
    dataPour: DataPour? = null,
    askedClear: ClearData.Request? = null,
) {
    val tabsById = remember(tabs) { tabs.associateBy { it.id } }
    val awakeTabIds = remember(tabs) { tabs.filter { it.isAwake }.map { it.id }.toSet() }
    // The workspace of a device on the account follows the device: its tabs are only opened and closed here.
    val isDevice = workspace.device != null
    val pinLines = remember(state, workspace.id) { pinLines(state.pinnedTree(workspace.id), state) }
    val pinnedTabIds = remember(state.pins) { state.pins.mapNotNull { it.tabId }.toSet() }
    val otherTabs = remember(tabs, pinnedTabIds) { tabs.filterNot { it.id in pinnedTabIds } }
    val splitTabIds = remember(state.splits) { state.splits.flatMap { it.tabIds }.toSet() }
    val lines = remember(otherTabs, state) { tabLines(otherTabs, state) }
    // The tip about split views points at the second tab, to drop onto the first one, while no tabs are split.
    val showsSplitTip = SplitSpace.supported(LocalConfiguration.current).isNotEmpty() && splitTabIds.isEmpty() &&
        lines.firstOrNull()?.tabs?.size == 1
    // The key of the row each pinned item and tab shows on, for the ones sharing a row with a split view partner.
    val lineKeys = remember(pinLines, lines) {
        pinLines.flatMap { line -> line.entries.map { PIN_PREFIX + it.item.id to line.key } }.toMap() +
            lines.flatMap { line -> line.tabs.map { TAB_PREFIX + it.id to line.key } }
    }

    // Rows come and go and move between the pinned and the other tabs in motions of their own, see RowMotions.
    val motions = rememberRowMotions()
    val rowTabs = pinLines.associate { line ->
        line.key to line.entries.mapNotNullTo(mutableSetOf()) { entry -> entry.item.tabId?.takeIf { it in tabsById } }
    } + lines.associate { line -> line.key to line.tabs.mapTo(mutableSetOf()) { it.id } }
    val plan = motions.plan(rowTabs, openTabIds, revealTabId)
    // The cards of a drag land on the rows their tabs moved to by themselves.
    val flights = drag == null && !dragStack.landing
    SideEffect { motions.start(plan, flights) }
    val placement = motions.placementSpec(plan)

    // Clear data asked for on a page, which closed to here, pours into Clear as holding it does.
    LaunchedEffect(askedClear) {
        val request = askedClear ?: return@LaunchedEffect
        if (ClearData.claim(request)) {
            motions.clearTapped()
            callbacks.onClearData(request.quit)
        }
    }

    val listState = rememberHomeListState(workspace.id)
    val pageContent = PageContent(state, otherTabs, workspace, pinLines, lines, lineKeys, tabsById, containers)
    val content by rememberUpdatedState(pageContent)
    val latestCallbacks by rememberUpdatedState(callbacks)
    val density = LocalDensity.current
    val isDragging = drag != null
    // A row also sees the finger lifting after a long press as a tap; while a long press or drag is in progress
    // (drag != null) row taps are ignored so the long press only selects.

    // The home screen follows the finger and asks the page under it where dragged items would land, and where the
    // rows the drag stack lifts off and lands on are.
    val dragArea = remember(listState, density) {
        fun frame() = with(density) {
            ListFrame(ListPadding.toPx(), listState.layoutInfo.viewportSize.width.toFloat(), RowPadding.toPx())
        }
        object : PageDragArea {
            override fun start(offset: Offset, atEnd: Boolean): SelectionDrag? =
                itemKeyAt(listState, offset.y)?.let { startDrag(it, offset, atEnd, content, latestCallbacks) }

            override fun drop(y: Float, drag: SelectionDrag): PageDrop = pageDrop(listState, y, drag, content)

            override suspend fun scrollBy(step: Float): Float = listState.scrollBy(step)

            override val height: Int
                get() = listState.layoutInfo.viewportSize.height

            override fun lift(stack: DragStackState, drag: SelectionDrag, press: Offset) {
                val key = itemKeyAt(listState, press.y) ?: return
                stack.liftRows(key, press, drag.selection, content, listState, frame(), density)
            }

            override fun rowRects(cards: List<StackCard>): Map<String, Rect> =
                cards.mapNotNull { card -> frame().rowRect(listState, card.key)?.let { card.key to it } }.toMap()

            override fun landings(cards: List<StackCard>): Map<String, Rect> =
                cards.landingsAfterDrop(content, listState, frame())

            override fun dropFallback(target: DropTarget): Offset? =
                dropFallback(target, content.lineKeys, listState, frame())
        }
    }
    DisposableEffect(workspace.id, dragArea) {
        dragAreas[workspace.id] = dragArea
        onDispose { if (dragAreas[workspace.id] === dragArea) dragAreas.remove(workspace.id) }
    }

    fun rowIndexOf(tabId: String): Int? {
        val pinIndex = pinLines.indexOfFirst { line -> line.entries.any { it.item.tabId == tabId } }
        val tabIndex = lines.indexOfFirst { line -> line.tabs.any { it.id == tabId } }
        return when {
            pinIndex >= 0 -> 1 + pinIndex
            tabIndex >= 0 -> pinLines.size + FIRST_TAB_OFFSET + tabIndex
            else -> null
        }
    }

    // The tab the home screen was opened from is in view as it shows, wherever the list was left.
    LaunchedEffect(workspace.id) {
        selectedTabId?.let(::rowIndexOf)?.let { listState.revealWhenShown(it) }
    }

    // Scrolls to a reopened tab once it is back in this workspace, unless its row is already in view.
    LaunchedEffect(revealTabId, pinLines, lines) {
        val index = revealTabId?.let(::rowIndexOf) ?: return@LaunchedEffect
        listState.reveal(index)
        latestCallbacks.onRevealed()
    }

    val dropLines = drag?.takeIf { it.moved && it.over == workspace.id }?.target?.let { dropLinesFor(it, lineKeys) }
    val draggedKeys = drag?.takeIf { it.from == workspace.id }?.let { draggedRowKeys(it.selection, state, lineKeys) }
        .orEmpty()
    // The cards landing after a drop by the rows of their items, wherever those rows are before and after they move.
    val landingCards = if (dragStack.landing) {
        dragStack.cards.mapNotNull { card -> rowKeyAfterDrop(card.card, pageContent)?.let { it to card } }.toMap()
    } else {
        emptyMap()
    }

    // Dragged rows dim as their cards lift off and light up again as they come back, and the rows dropped cards land
    // on show as they arrive. Read while drawing, so the animation does not recompose the rows.
    fun Modifier.rowAlpha(key: String) = graphicsLayer {
        val landing = landingCards[key]
        alpha = when {
            landing != null -> 1f - landing.lift.value.coerceIn(0f, 1f)
            key in draggedKeys || key in dragStack.returningKeys -> lerp(1f, DRAGGED_ALPHA, dragStack.liftOf(key))
            else -> 1f
        }
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val insideColor = MaterialTheme.colorScheme.primaryContainer

    // A middle-click on a row does what its Close button does, but not while rows are being picked or dragged.
    fun middleClick(targets: ActionTargets, isPinned: Boolean): (() -> Unit)? =
        middleClickAction(targets, isPinned)
            ?.takeIf { selection == null && drag == null }
            ?.let { action -> { callbacks.onRowAction(action, targets) } }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = !isDragging,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ListPadding),
        ) {
            item(key = KEY_HEADER) {
                WorkspaceHeader(
                    workspace = workspace,
                    container = workspace.containerId?.let { containers[it] },
                    isDevice = isDevice,
                    canDelete = canDeleteWorkspace && !isDevice,
                    canMoveLeft = canMoveLeft,
                    canMoveRight = canMoveRight,
                    tabButtonsEnabled = selection == null,
                    canReopenClosedTab = hasClosedTab,
                    canOpenClipboard = hasClipboardLink,
                    onOpenClipboard = callbacks.onOpenClipboard,
                    onReopenClosedTab = callbacks.onReopenClosedTab,
                    onNewFolder = callbacks.onNewFolder,
                    onNewWorkspace = callbacks.onNewWorkspace,
                    onEdit = callbacks.onEditWorkspace,
                    onDelete = callbacks.onDeleteWorkspace,
                    onMove = callbacks.onMoveWorkspace,
                    reopenButton = Modifier.motionButton(motions, MotionButton.REOPEN),
                    modifier = Modifier
                        .animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null)
                        .dropLine(dropLines?.of(KEY_HEADER), lineColor, insideColor),
                )
            }

            items(pinLines, key = { it.key }) { line ->
                val key = line.key
                val rowModifier = Modifier
                    .animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null)
                    .motionRow(motions, key)
                    .dropLine(dropLines?.of(key), lineColor, insideColor)
                    .rowAlpha(key)
                val entry = line.entries.first()
                val item = entry.item
                if (line.entries.size > 1) {
                    TabPairRow(
                        halves = line.entries.map { half ->
                            val pin = half.item
                            val tab = pin.tabId?.let { tabsById[it] }
                            val targets = ActionTargets.ofPin(pin, tab, restricted = isDevice)
                            val resetPin = RowAction.BuiltIn(TabAction.RESET_PIN).takeIf { it.appliesTo(targets) }
                            PairHalf(
                                title = pin.label(tab),
                                url = tab?.content?.url ?: pin.pageUrl,
                                container = (tab?.contextId ?: pin.containerId)?.let { containers[it] },
                                isAwake = tab?.isAwake == true,
                                picked = selection?.let { pin.id in it.pinIds },
                                onClick = { if (drag == null) callbacks.onPinClick(pin) },
                                onMiddleClick = middleClick(targets, isPinned = true),
                                onIconClick = resetPin?.let { action ->
                                    { if (drag == null) callbacks.onRowAction(action, targets) }
                                },
                            )
                        },
                        depth = entry.depth,
                        isCurrent = line.entries.any { it.item.tabId != null && it.item.tabId == selectedTabId },
                        modifier = rowModifier,
                    )
                    return@items
                }
                if (item.isFolder) {
                    val targets = ActionTargets.of(state, tabs, Selection(folderIds = setOf(item.id)))
                    FolderRow(
                        folder = item,
                        depth = entry.depth,
                        childCount = state.pins.count { it.parentId == item.id },
                        openTabCount = if (item.collapsed) state.openTabsIn(item.id, awakeTabIds) else 0,
                        selection = selection?.let { item.id in it.folderIds },
                        actions = rowActions(folderRowActions, targets, isPinned = true),
                        onAction = { callbacks.onRowAction(it, targets) },
                        onClick = { if (drag == null) callbacks.onFolderClick(item) },
                        modifier = rowModifier.closeOnMiddleClick(null),
                    )
                } else {
                    val tab = item.tabId?.let { tabsById[it] }
                    val targets = ActionTargets.ofPin(item, tab, restricted = isDevice)
                        .copy(splitTabIds = setOfNotNull(tab?.id).intersect(splitTabIds))
                    val resetPin = RowAction.BuiltIn(TabAction.RESET_PIN).takeIf { it.appliesTo(targets) }
                    TabRow(
                        title = item.label(tab),
                        url = tab?.content?.url ?: item.pageUrl,
                        depth = entry.depth,
                        container = (tab?.contextId ?: item.containerId)?.let { containers[it] },
                        isAwake = tab?.isAwake == true,
                        isCurrent = tab != null && tab.id == selectedTabId,
                        isSplit = tab != null && tab.id in splitTabIds,
                        selection = selection?.let { item.id in it.pinIds },
                        actions = rowActions(pinnedRowActions, targets, isPinned = true),
                        onAction = { callbacks.onRowAction(it, targets) },
                        onClick = { if (drag == null) callbacks.onPinClick(item) },
                        onIconClick = resetPin?.let { action -> { if (drag == null) callbacks.onRowAction(action, targets) } },
                        modifier = rowModifier
                            .tabZoomTarget(tab?.id)
                            .closeOnMiddleClick(middleClick(targets, isPinned = true)),
                    )
                }
            }

            item(key = KEY_DIVIDER) {
                // The cleared tabs and data pour into the Clear button, which stays until they are in.
                val clearing = plan.clear || motions.isClearing || dataPour?.isPouring == true
                SectionDivider(
                    showClear = (otherTabs.isNotEmpty() || hasDataToClear || clearing) && selection == null,
                    onClear = {
                        motions.clearTapped()
                        if (otherTabs.isNotEmpty()) callbacks.onClearUnpinned() else callbacks.onClearData(false)
                    },
                    onClearData = {
                        Tips.done(Tip.CLEAR)
                        motions.clearTapped()
                        callbacks.onClearData(false)
                    },
                    clearButton = Modifier.motionButton(motions, MotionButton.CLEAR).dataPourTarget(dataPour),
                    modifier = Modifier
                        .animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null)
                        .dropLine(dropLines?.of(KEY_DIVIDER), lineColor, insideColor),
                )
            }

            item(key = KEY_NEW_TAB) {
                NewTabRow(
                    enabled = selection == null,
                    containers = containers.values.toList(),
                    showContainers = !isDevice,
                    onClick = callbacks.onNewTabClick,
                    onNewTabInContainer = callbacks.onNewTabInContainer,
                    onNewPrivateTab = callbacks.onNewPrivateTab,
                    onManageContainers = callbacks.onManageContainers,
                    modifier = Modifier
                        .animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null)
                        .dropLine(dropLines?.of(KEY_NEW_TAB), lineColor, insideColor)
                        .tipTarget(Tip.NEW_TAB, TipGesture.Hold, enabled = selection == null, at = ROW_TOUCH),
                )
            }

            itemsIndexed(lines, key = { _, line -> line.key }) { index, line ->
                val key = line.key
                val tab = line.tabs.first()
                val rowModifier = Modifier
                    .animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null)
                    .motionRow(motions, key)
                    .dropLine(dropLines?.of(key), lineColor, insideColor)
                    .rowAlpha(key)
                if (line.tabs.size > 1) {
                    TabPairRow(
                        halves = line.tabs.map { half ->
                            PairHalf(
                                title = state.titleOf(half),
                                url = half.content.url,
                                container = half.contextId?.let { containers[it] },
                                isAwake = half.isAwake,
                                picked = selection?.let { half.id in it.tabIds },
                                onClick = { if (drag == null) callbacks.onTabClick(half) },
                                onMiddleClick = middleClick(
                                    ActionTargets(tabs = listOf(half), restricted = isDevice),
                                    isPinned = false,
                                ),
                            )
                        },
                        depth = 0,
                        isCurrent = line.tabs.any { it.id == selectedTabId },
                        modifier = rowModifier,
                    )
                    return@itemsIndexed
                }
                val targets = ActionTargets(
                    tabs = listOf(tab),
                    splitTabIds = setOf(tab.id).intersect(splitTabIds),
                    restricted = isDevice,
                )
                TabRow(
                    title = state.titleOf(tab),
                    url = tab.content.url,
                    depth = 0,
                    container = tab.contextId?.let { containers[it] },
                    isAwake = tab.isAwake,
                    isCurrent = tab.id == selectedTabId,
                    isSplit = tab.id in splitTabIds,
                    selection = selection?.let { tab.id in it.tabIds },
                    actions = rowActions(unpinnedRowActions, targets, isPinned = false),
                    onAction = { callbacks.onRowAction(it, targets) },
                    onClick = { if (drag == null) callbacks.onTabClick(tab) },
                    modifier = rowModifier
                        .tabZoomTarget(tab.id)
                        .closeOnMiddleClick(middleClick(targets, isPinned = false))
                        .tipTarget(Tip.PIN, TipGesture.Drag(dy = PIN_DRAG), enabled = index == 0, at = ROW_TOUCH)
                        .tipTarget(Tip.ESSENTIAL_ADD, TipGesture.Drag(toTop = true), index == 0, ROW_TOUCH)
                        .tipTarget(
                            Tip.SPLIT,
                            TipGesture.Drag(dy = ROW_DRAG),
                            enabled = index == 1 && showsSplitTip,
                            at = ROW_TOUCH,
                        ),
                )
            }
        }
        RowMotionsOverlay(motions, modifier = Modifier.matchParentSize().padding(horizontal = ListPadding))
    }
}

/**
 * Selects the row with [key] and starts dragging it together with the rest of the selection, from a long press at
 * [offset]. On the row of a split view it picks the tab on the end side when [atEnd] is set, else the one on the start
 * side.
 */
private fun startDrag(
    key: String,
    offset: Offset,
    atEnd: Boolean,
    content: PageContent,
    callbacks: WorkspacePageCallbacks,
): SelectionDrag? {
    fun pick(ids: String) = ids.split(PAIR_SEPARATOR).let { if (it.size > 1 && atEnd) it[1] else it[0] }
    val picked = when {
        key.startsWith(PIN_PREFIX) || key.startsWith(PIN_PAIR_PREFIX) -> {
            val id = pick(key.removePrefix(PIN_PREFIX).removePrefix(PIN_PAIR_PREFIX))
            val pin = content.state.pins.firstOrNull { it.id == id } ?: return null
            if (pin.isFolder) Selection.ofFolder(pin.id, content.state) else Selection(pinIds = setOf(pin.id))
        }
        key.startsWith(TAB_PREFIX) || key.startsWith(PAIR_PREFIX) -> {
            val id = pick(key.removePrefix(TAB_PREFIX).removePrefix(PAIR_PREFIX))
            val tab = content.otherTabs.firstOrNull { it.id == id } ?: return null
            Selection(tabIds = setOf(tab.id))
        }
        else -> return null
    }
    return SelectionDrag(
        selection = callbacks.onStartDrag(picked),
        from = content.workspace.id,
        // The tabs of a device's workspace follow the device, so they stay in it.
        canLeave = content.workspace.device == null,
        pointer = offset,
        start = offset,
    )
}

/** Keys of the rows showing [selection], folders with everything inside them. */
private fun draggedRowKeys(selection: Selection, state: WorkspaceState, lineKeys: Map<String, String>): Set<String> {
    val folderIds = selection.folderIds
    val keys = selection.tabIds.map { TAB_PREFIX + it } +
        (selection.pinIds + folderIds + folderIds.flatMap { state.descendantIds(it) }).map { PIN_PREFIX + it }
    return keys.map { lineKeys[it] ?: it }.toSet()
}

/**
 * Lifts the rows of [selection] that are in view off the list into the stack, from a long press at [press] in the
 * page: the long-pressed row [heldKey] on top, with the rows nearest to it above and below.
 */
@Suppress("LongParameterList")
private fun DragStackState.liftRows(
    heldKey: String,
    press: Offset,
    selection: Selection,
    content: PageContent,
    listState: LazyListState,
    frame: ListFrame,
    density: Density,
) {
    val keys = draggedRowKeys(selection, content.state, content.lineKeys)
    val rows = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
        (info.key as? String)?.takeIf { it in keys }?.let { it to info }
    }
    val heldIndex = rows.indexOfFirst { (key, _) -> key == heldKey }
    if (heldIndex < 0) return
    val lifted = rows.mapIndexedNotNull { index, (key, info) ->
        val rank = index - heldIndex
        if (abs(rank) > MAX_STACK_DEPTH) return@mapIndexedNotNull null
        stackCard(key, rank, content)?.let { LiftedCard(it, frame.rowRect(info)) }
    }
    val geometry = density.stackGeometry(frame.left, frame.width, press, frame.rowRect(rows[heldIndex].second))
    lift(lifted, selection.topLevelCount(content.state), press, geometry)
}

/** How the row with [key] looks lifted into the drag stack, [rank] rows from the long-pressed one. */
private fun stackCard(key: String, rank: Int, content: PageContent): StackCard? {
    val pinLine = content.pinLines.firstOrNull { it.key == key }
    if (pinLine != null) return pinStackCard(pinLine, rank, content)
    val line = content.lines.firstOrNull { it.key == key } ?: return null
    val tab = line.tabs.first()
    return StackCard(
        key = key,
        rank = rank,
        title = content.state.titleOf(tab),
        url = tab.content.url,
        stripe = tab.contextId?.let { content.containers[it] }?.color?.color,
        iconStart = if (line.tabs.size > 1) PairIconStart else TabIconStart,
        brightTitle = tab.isAwake,
        tabId = tab.id,
        pinId = null,
    )
}

/** How a row of the pinned section looks lifted into the drag stack, [rank] rows from the long-pressed one. */
private fun pinStackCard(line: PinLine, rank: Int, content: PageContent): StackCard {
    val entry = line.entries.first()
    val pin = entry.item
    val indent = IndentPerLevel * entry.depth
    val tab = pin.tabId?.takeIf { !pin.isFolder }?.let { content.tabsById[it] }
    val containerId = (tab?.contextId ?: pin.containerId).takeIf { !pin.isFolder }
    return StackCard(
        key = line.key,
        rank = rank,
        title = if (pin.isFolder) pin.title else pin.label(tab),
        url = if (pin.isFolder) null else tab?.content?.url ?: pin.pageUrl,
        stripe = containerId?.let { content.containers[it] }?.color?.color,
        iconStart = indent + when {
            pin.isFolder -> FolderIconStart
            line.entries.size > 1 -> PairIconStart
            else -> TabIconStart
        },
        brightTitle = pin.isFolder || tab?.isAwake == true,
        tabId = pin.tabId,
        pinId = pin.id,
    )
}

/** The rows [this] cards show on after a drop, where they are in view. */
private fun List<StackCard>.landingsAfterDrop(
    content: PageContent,
    listState: LazyListState,
    frame: ListFrame,
): Map<String, Rect> = mapNotNull { card ->
    rowKeyAfterDrop(card, content)?.let { frame.rowRect(listState, it) }?.let { card.key to it }
}.toMap()

/** Key of the row [card] shows on after a drop: the row of its pinned tab or folder, or else of its tab. */
private fun rowKeyAfterDrop(card: StackCard, content: PageContent): String? {
    val pins = content.state.pins
    val pin = card.pinId?.let { id -> pins.firstOrNull { it.id == id } }
        ?: card.tabId?.let { id -> pins.firstOrNull { it.tabId == id && !it.isFolder } }
    val key = when {
        pin != null && pin.workspaceId == content.workspace.id -> PIN_PREFIX + pin.id
        content.otherTabs.any { it.id == card.tabId } -> TAB_PREFIX + card.tabId
        else -> return null
    }
    return content.lineKeys[key] ?: key
}

/**
 * Where the cards of a drop on [target] head when their rows are not in view: into the line showing the drop, or up
 * into the essentials.
 */
private fun dropFallback(
    target: DropTarget,
    lineKeys: Map<String, String>,
    listState: LazyListState,
    frame: ListFrame,
): Offset? {
    val lines = dropLinesFor(target, lineKeys) ?: return Offset(frame.left + frame.width / 2, 0f)
    val row = frame.rowRect(listState, lines.key) ?: return null
    return when (lines.line) {
        DropLine.TOP -> Offset(row.center.x, row.top)
        DropLine.BOTTOM -> Offset(row.center.x, row.bottom)
        DropLine.INSIDE -> row.center
    }
}

/** Key of the list item under [y], relative to the list. */
private fun itemKeyAt(listState: LazyListState, y: Float): String? =
    listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key as? String

/**
 * Where [drag] would land at [y] on the page of [content]. The workspace of a device on the account only takes tabs
 * that are not pinned, from other workspaces, among its own unpinned tabs; it refuses them on its pinned tabs, and
 * anything pinned or a folder anywhere.
 */
private fun pageDrop(listState: LazyListState, y: Float, drag: SelectionDrag, content: PageContent): PageDrop {
    if (content.workspace.device == null) return PageDrop(targetAt(listState, y, drag.selection, content.state))
    if (drag.from == content.workspace.id || y < 0f) return PageDrop(null)
    val key = itemKeyAt(listState, y) ?: listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
    val onPinned = key == null || key == KEY_HEADER || key == KEY_DIVIDER ||
        key.startsWith(PIN_PREFIX) || key.startsWith(PIN_PAIR_PREFIX)
    val onlyTabs = drag.selection.pinIds.isEmpty() && drag.selection.folderIds.isEmpty()
    return if (onPinned || !onlyTabs) PageDrop(null, blocked = true) else PageDrop(DropTarget.UnpinnedStart)
}

/**
 * Where [selection] would land at [y], or `null` when it cannot go there. Above the list lie the essentials, which
 * take tabs but no folders.
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun targetAt(listState: LazyListState, y: Float, selection: Selection, state: WorkspaceState): DropTarget? {
    val movingFolders = selection.folderIds
    if (y < 0f) return DropTarget.Essentials.takeIf { movingFolders.isEmpty() }
    val items = listState.layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return null
    val info = items.firstOrNull { y >= it.offset && y < it.offset + it.size }
        ?: if (y < items.first().offset) items.first() else items.last()
    val fraction = ((y - info.offset) / info.size.toFloat()).coerceIn(0f, 1f)
    val key = info.key as? String ?: return null
    val blockedPins = selection.pinIds + movingFolders + movingFolders.flatMap { state.descendantIds(it) }

    val target = when {
        key == KEY_HEADER -> DropTarget.PinnedEdge(atEnd = false)
        key == KEY_DIVIDER -> DropTarget.PinnedEdge(atEnd = true)
        key == KEY_NEW_TAB -> DropTarget.UnpinnedStart
        key.startsWith(PIN_PREFIX) -> {
            val id = key.removePrefix(PIN_PREFIX)
            val item = state.pins.firstOrNull { it.id == id } ?: return null
            if (id in blockedPins) return null
            if (item.isFolder && fraction in INSIDE_FOLDER_RANGE) {
                DropTarget.IntoFolder(id)
            } else {
                DropTarget.NextToPin(id, after = fraction >= 0.5f)
            }
        }
        key.startsWith(TAB_PREFIX) -> {
            val id = key.removePrefix(TAB_PREFIX)
            if (id in selection.tabIds) return null
            // A single tab dropped on the middle of another one joins it in a split view.
            val single = selection.tabIds.size == 1 && selection.pinIds.isEmpty() && movingFolders.isEmpty()
            if (single && fraction in SPLIT_RANGE) DropTarget.SplitWith(id) else DropTarget.NextToTab(id, after = fraction >= 0.5f)
        }
        // A split view's row shows where its first tab is, so before or after the row is before or after that tab.
        key.startsWith(PAIR_PREFIX) -> {
            val ids = key.removePrefix(PAIR_PREFIX).split(PAIR_SEPARATOR)
            if (ids.any { it in selection.tabIds }) return null
            DropTarget.NextToTab(ids.first(), after = fraction >= 0.5f)
        }
        key.startsWith(PIN_PAIR_PREFIX) -> {
            val ids = key.removePrefix(PIN_PAIR_PREFIX).split(PAIR_SEPARATOR)
            if (ids.any { it in blockedPins }) return null
            DropTarget.NextToPin(ids.first(), after = fraction >= 0.5f)
        }
        else -> null
    }
    val unpinsFolder = movingFolders.isNotEmpty() &&
        (target is DropTarget.NextToTab || target == DropTarget.UnpinnedStart)
    return target?.takeUnless { unpinsFolder }
}

/** How [target] shows, on the row [lineKeys] gives for each pinned item and tab sharing one. */
private fun dropLinesFor(target: DropTarget, lineKeys: Map<String, String>): DropLines? {
    fun rowOf(key: String) = lineKeys[key] ?: key
    return when (target) {
        is DropTarget.IntoFolder -> DropLines(PIN_PREFIX + target.folderId, DropLine.INSIDE)
        is DropTarget.NextToPin ->
            DropLines(rowOf(PIN_PREFIX + target.pinId), if (target.after) DropLine.BOTTOM else DropLine.TOP)
        is DropTarget.PinnedEdge ->
            if (target.atEnd) DropLines(KEY_DIVIDER, DropLine.TOP) else DropLines(KEY_HEADER, DropLine.BOTTOM)
        is DropTarget.NextToTab ->
            DropLines(rowOf(TAB_PREFIX + target.tabId), if (target.after) DropLine.BOTTOM else DropLine.TOP)
        is DropTarget.SplitWith -> DropLines(TAB_PREFIX + target.tabId, DropLine.INSIDE)
        DropTarget.UnpinnedStart -> DropLines(KEY_NEW_TAB, DropLine.BOTTOM)
        DropTarget.Essentials -> null
    }
}

private fun Modifier.dropLine(line: DropLine?, lineColor: Color, insideColor: Color): Modifier =
    if (line == null) {
        this
    } else {
        drawWithContent {
            if (line == DropLine.INSIDE) {
                drawRoundRect(insideColor, cornerRadius = CornerRadius(16.dp.toPx()))
            }
            drawContent()
            val stroke = 3.dp.toPx()
            when (line) {
                DropLine.TOP -> drawLine(lineColor, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                DropLine.BOTTOM -> drawLine(
                    lineColor,
                    Offset(0f, size.height - stroke / 2),
                    Offset(size.width, size.height - stroke / 2),
                    stroke,
                )
                DropLine.INSIDE -> Unit
            }
        }
    }

/**
 * The line between pinned and unpinned tabs, with Zen's "Clear" button for the unpinned ones, which [clearButton] is
 * given to. Holding it runs [onClearData] instead.
 */
@Composable
private fun SectionDivider(
    showClear: Boolean,
    onClear: () -> Unit,
    onClearData: () -> Unit,
    clearButton: Modifier,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp),
    ) {
        // The line has the color of New Tab below it.
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showClear) {
            // A text button that can be held too.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = clearButton
                    .tipTarget(Tip.CLEAR, TipGesture.Hold)
                    .defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
                    .clip(CircleShape)
                    .combinedClickable(
                        role = Role.Button,
                        onLongClickLabel = stringResource(R.string.dejavu_menu_clear_data),
                        onLongClick = onClearData,
                        onClick = onClear,
                    )
                    .padding(ButtonDefaults.TextButtonContentPadding),
            ) {
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_chevron_down_16),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.dejavu_clear_unpinned),
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/**
 * "New Tab" opens the search. Long-pressing it lists the containers to open the new tab in, and a shortcut to the
 * container settings.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun NewTabRow(
    enabled: Boolean,
    containers: List<ContainerRecord>,
    showContainers: Boolean,
    onClick: () -> Unit,
    onNewTabInContainer: (ContainerPick) -> Unit,
    onNewPrivateTab: () -> Unit,
    onManageContainers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val taps = rememberIconTaps()
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RowShape)
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        taps.tap()
                        onClick()
                    },
                    onLongClick = {
                        Tips.done(Tip.NEW_TAB)
                        menuOpen = true
                    },
                )
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            TapIcon(
                icon = R.drawable.dejavu_ic_new_tab_24,
                taps = taps,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).padding(start = 3.dp),
            )
            Spacer(Modifier.width(19.dp))
            Text(
                text = stringResource(R.string.dejavu_new_tab),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DejavuDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            fun pick(choice: ContainerPick) {
                menuOpen = false
                onNewTabInContainer(choice)
            }
            // A device on the account would not keep containers, so its workspace only offers a private tab.
            val (permanent, temporary) = containers.filter { showContainers }.splitTemporary()
            if (showContainers) {
                DejavuMenuLabel(stringResource(R.string.dejavu_new_tab_in_container))
                DejavuMenuItem(
                    text = stringResource(R.string.dejavu_new_tab_no_container),
                    leadingIcon = { NoContainerIcon() },
                    onClick = { pick(ContainerPick.NoContainer) },
                )
            }
            DejavuMenuItem(
                text = stringResource(R.string.dejavu_new_private_tab),
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.dejavu_ic_private_tab_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    menuOpen = false
                    onNewPrivateTab()
                },
            )
            if (!showContainers) return@DejavuDropdownMenu
            DejavuMenuDivider()
            permanent.forEach { container ->
                DejavuMenuItem(
                    text = container.name,
                    leadingIcon = { ContainerIcon(container) },
                    onClick = { pick(ContainerPick.Container(container.contextId)) },
                )
            }
            DejavuMenuItem(
                text = stringResource(R.string.dejavu_container_add),
                leadingIcon = {
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    menuOpen = false
                    onManageContainers()
                },
            )
            DejavuMenuDivider()
            DejavuMenuItem(
                text = stringResource(R.string.dejavu_new_tab_temporary_container),
                leadingIcon = { TemporaryContainerIcon() },
                onClick = { pick(ContainerPick.Temporary) },
            )
            if (temporary.isNotEmpty()) DejavuMenuLabel(stringResource(R.string.dejavu_open_temporary_containers))
            temporary.forEach { container ->
                DejavuMenuItem(
                    text = container.name,
                    leadingIcon = { ContainerIcon(container) },
                    onClick = { pick(ContainerPick.Container(container.contextId)) },
                )
            }
        }
    }
}

/**
 * Workspace name with its default container and icon on the left; tapping the name edits the workspace. On the right,
 * opening the link in the clipboard while [canOpenClipboard] and reopening the tab closed last while
 * [canReopenClosedTab], which [tabButtonsEnabled] allows outside selection mode, then a new folder and the workspace menu.
 * [reopenButton] is given to the button that reopens closed tabs.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun WorkspaceHeader(
    workspace: Workspace,
    container: ContainerRecord?,
    isDevice: Boolean,
    canDelete: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    tabButtonsEnabled: Boolean,
    canReopenClosedTab: Boolean,
    canOpenClipboard: Boolean,
    onOpenClipboard: () -> Unit,
    onReopenClosedTab: () -> Unit,
    onNewFolder: () -> Unit,
    onNewWorkspace: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (delta: Int) -> Unit,
    reopenButton: Modifier,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onEdit)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // The tip about editing the workspace rings what shows of it, not the whole tappable room.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.tipTarget(Tip.WORKSPACE_NAME, TipGesture.Tap, enabled = !isDevice),
            ) {
                if (container != null) {
                    ContainerIcon(container, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                }
                workspaceIconText(workspace.icon)?.let { icon ->
                    Text(text = icon, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = container?.color?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SmallIconButton(
            icon = R.drawable.dejavu_ic_clipboard_24,
            description = R.string.dejavu_open_clipboard_link,
            enabled = tabButtonsEnabled && canOpenClipboard,
            onClick = onOpenClipboard,
        )
        SmallIconButton(
            icon = R.drawable.dejavu_ic_reopen_tab_24,
            description = R.string.dejavu_reopen_closed_tab,
            enabled = tabButtonsEnabled && canReopenClosedTab,
            onClick = onReopenClosedTab,
            modifier = reopenButton,
        )
        // The workspace of a device on the account cannot have folders of its own, nor be deleted: sync handles it.
        if (!isDevice) {
            SmallIconButton(
                icon = R.drawable.dejavu_ic_create_folder_24,
                description = R.string.dejavu_action_new_folder,
                modifier = Modifier.tipTarget(Tip.FOLDER, TipGesture.Tap),
                onClick = onNewFolder,
            )
        }
        Box {
            SmallIconButton(iconsR.drawable.mozac_ic_ellipsis_vertical_24, R.string.dejavu_workspace_menu) {
                menuOpen = true
            }
            DejavuDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DejavuMenuItem(
                    text = stringResource(R.string.dejavu_add_workspace),
                    onClick = {
                        menuOpen = false
                        onNewWorkspace()
                    },
                )
                DejavuMenuItem(
                    text = stringResource(R.string.dejavu_workspace_edit),
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DejavuMenuItem(
                    text = stringResource(R.string.dejavu_workspace_move_left),
                    enabled = canMoveLeft,
                    onClick = {
                        menuOpen = false
                        onMove(-1)
                    },
                )
                DejavuMenuItem(
                    text = stringResource(R.string.dejavu_workspace_move_right),
                    enabled = canMoveRight,
                    onClick = {
                        menuOpen = false
                        onMove(1)
                    },
                )
                if (!isDevice) {
                    DejavuMenuItem(
                        text = stringResource(R.string.dejavu_workspace_delete),
                        enabled = canDelete,
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** The color of a folder named like a color of Firefox's tab groups, in the shade Firefox uses on this background. */
@Composable
private fun groupColor(name: String?): Color? {
    val (onLight, onDark) = GroupColors[name] ?: return null
    return Color(if (MaterialTheme.colorScheme.surface.luminance() < HALF) onDark else onLight)
}

/** Firefox's tab group colors by name, on light and on dark backgrounds. */
private val GroupColors = mapOf(
    "blue" to (0xFF23327B to 0xFFA2D3FF),
    "purple" to (0xFF4F216B to 0xFFE8B7FF),
    "cyan" to (0xFF034554 to 0xFF8FDDF0),
    "orange" to (0xFF701C07 to 0xFFFEBD99),
    "yellow" to (0xFF5F3100 to 0xFFFBCC77),
    "pink" to (0xFF5F1854 to 0xFFFFB0E2),
    "green" to (0xFF004933 to 0xFF90E3C6),
    "red" to (0xFF69172D to 0xFFFFB6BF),
    "gray" to (0xFF5E6A77 to 0xFF99A6B4),
)

private const val HALF = 0.5f

/**
 * How many pinned tabs inside folder [folderId], subfolders included, are open and awake, their tab among
 * [awakeTabIds]. Sleeping tabs are not counted, the same as closed ones.
 */
private fun WorkspaceState.openTabsIn(folderId: String, awakeTabIds: Set<String>): Int {
    val inside = descendantIds(folderId)
    return pins.count { it.id in inside && it.tabId != null && it.tabId in awakeTabIds }
}

/** The icon of a folder: the outline of a folder, dashed when nothing is inside. */
@DrawableRes
internal fun folderIcon(isEmpty: Boolean): Int =
    if (isEmpty) R.drawable.dejavu_ic_folder_empty_24 else iconsR.drawable.mozac_ic_folder_24

/**
 * A folder of the pinned section. Tapping it folds it, or selects it in selection mode. A closed folder shows
 * [openTabCount], the number of awake tabs hidden inside it, next to its icon. The buttons of [actions] act on
 * everything inside the folder; without any, a closed folder shows how many items it holds instead. An empty folder has
 * a dashed icon.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun FolderRow(
    folder: PinnedItem,
    depth: Int,
    childCount: Int,
    openTabCount: Int,
    selection: Boolean?,
    actions: List<RowAction>,
    onAction: (RowAction) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(vertical = 2.dp)
            .clip(RowShape)
            .background(if (selection == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp + IndentPerLevel * depth),
    ) {
        Icon(
            painter = painterResource(
                if (folder.collapsed) iconsR.drawable.mozac_ic_chevron_right_16 else iconsR.drawable.mozac_ic_chevron_down_16,
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            painter = painterResource(folderIcon(isEmpty = childCount == 0)),
            contentDescription = null,
            tint = groupColor(folder.color) ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        if (openTabCount > 0) {
            val description = pluralStringResource(R.plurals.dejavu_folder_open_tabs, openTabCount, openTabCount)
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .semantics { contentDescription = description }
                    .heightIn(min = 18.dp)
                    .widthIn(min = 18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 5.dp),
            ) {
                Text(
                    text = openTabCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = folder.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selection != null) {
            SelectionMark(selected = selection)
        } else if (actions.isNotEmpty()) {
            actions.forEach { action -> RowActionButton(action, onAction) }
        } else if (folder.collapsed && childCount > 0) {
            Text(
                text = childCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 14.dp),
            )
        }
    }
}

/**
 * The row of an open tab. [isPrivate] is for a private tab, whose site icon is its own [icon] when it has one, or else
 * loaded as private browsing does.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun TabRow(
    title: String,
    url: String,
    depth: Int,
    container: ContainerRecord?,
    isAwake: Boolean,
    isCurrent: Boolean,
    isSplit: Boolean,
    selection: Boolean?,
    actions: List<RowAction>,
    onAction: (RowAction) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onIconClick: (() -> Unit)? = null,
    isPrivate: Boolean = false,
    icon: Bitmap? = null,
) {
    val background = when {
        selection == true -> MaterialTheme.colorScheme.secondaryContainer
        isCurrent && selection == null -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(vertical = 2.dp)
            .clip(RowShape)
            .background(background)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                .width(4.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(container?.color?.color ?: Color.Transparent),
        )
        Spacer(Modifier.width(2.dp + IndentPerLevel * depth))
        // Like Zen, a pinned tab that left its pinned page shows its icon on a brighter square; tapping it goes back.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(IconSquareSize)) {
            val interactions = remember { MutableInteractionSource() }
            val resetLabel = stringResource(R.string.dejavu_action_reset_pin)
            if (onIconClick != null && selection == null) {
                // The tap area gets the 48dp minimum without moving the square or the title.
                Box(
                    modifier = Modifier
                        .requiredSize(MinTouchSize)
                        .clickable(interactionSource = interactions, indication = null, onClick = onIconClick)
                        .semantics { contentDescription = resetLabel },
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(IconSquareSize)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (onIconClick != null) {
                            Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = RESET_SQUARE_ALPHA))
                        } else {
                            Modifier
                        },
                    )
                    .indication(interactions, LocalIndication.current),
            ) {
                // Tabs loaded in the browser are shown bright, the others dimmed.
                Box(modifier = Modifier.alpha(if (isAwake) 1f else DIMMED_ALPHA)) {
                    SiteIcon(url = url, size = 20.dp, isPrivate = isPrivate, icon = icon)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isAwake) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isSplit) {
            Icon(
                painter = painterResource(R.drawable.dejavu_ic_split_24),
                contentDescription = stringResource(R.string.dejavu_split_view),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp).size(16.dp),
            )
        }
        if (selection == null) {
            actions.forEach { action -> RowActionButton(action, onAction) }
        } else {
            SelectionMark(selected = selection)
        }
    }
}

/**
 * One of the two tabs of a [TabPairRow].
 *
 * @property picked Whether the tab is picked in selection mode, or `null` outside selection mode.
 * @property onMiddleClick What a middle-click does to the tab, or `null` for nothing.
 * @property onIconClick Takes a pinned tab that left its pinned page back to it, shown with a brighter icon square.
 */
private data class PairHalf(
    val title: String,
    val url: String,
    val container: ContainerRecord?,
    val isAwake: Boolean,
    val picked: Boolean?,
    val onClick: () -> Unit,
    val onMiddleClick: (() -> Unit)? = null,
    val onIconClick: (() -> Unit)? = null,
)

/**
 * The two [halves] of a split view side by side in one row, [depth] folders deep, like Zen shows them. Tapping either
 * opens the split view, or picks that tab in selection mode; Unsplit among the selection actions puts them back on
 * rows of their own.
 */
@Suppress("LongMethod")
@Composable
private fun TabPairRow(
    halves: List<PairHalf>,
    depth: Int,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
) {
    val selecting = halves.any { it.picked != null }
    val resetLabel = stringResource(R.string.dejavu_action_reset_pin)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(vertical = 2.dp)
            .clip(RowShape)
            .background(
                if (isCurrent && !selecting) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
            ),
    ) {
        halves.forEachIndexed { index, half ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            val picked = half.picked
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RowShape)
                    .background(if (picked == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .closeOnMiddleClick(half.onMiddleClick)
                    .clickable(onClick = half.onClick),
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .width(4.dp)
                        .height(26.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(half.container?.color?.color ?: Color.Transparent),
                )
                Spacer(Modifier.width(if (index == 0) 4.dp + IndentPerLevel * depth else 4.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(IconSquareSize)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (half.onIconClick != null) {
                                Modifier
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = RESET_SQUARE_ALPHA))
                                    .clickable(enabled = picked == null, onClick = half.onIconClick)
                                    .semantics { contentDescription = resetLabel }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Box(modifier = Modifier.alpha(if (half.isAwake) 1f else DIMMED_ALPHA)) {
                        SiteIcon(url = half.url, size = 20.dp)
                    }
                }
                Text(
                    text = half.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (half.isAwake) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
                )
                if (picked != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (picked) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .border(
                                1.5.dp,
                                if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                    ) {
                        if (picked) {
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
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(end = 14.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
    ) {
        if (selected) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_checkmark_16),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** A button of a row: [action], which [onAction] carries out on what the row stands for. */
@Composable
private fun RowActionButton(action: RowAction, onAction: (RowAction) -> Unit) {
    val taps = rememberIconTaps()
    IconButton(
        onClick = {
            taps.tap()
            onAction(action)
        },
    ) {
        TapIcon(
            icon = action.icon,
            taps = taps,
            contentDescription = action.label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SmallIconButton(
    icon: Int,
    @StringRes description: Int,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val taps = rememberIconTaps()
    IconButton(
        onClick = {
            taps.tap()
            onClick()
        },
        enabled = enabled,
        modifier = modifier,
    ) {
        TapIcon(
            icon = icon,
            taps = taps,
            contentDescription = stringResource(description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
            modifier = Modifier.size(20.dp),
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
