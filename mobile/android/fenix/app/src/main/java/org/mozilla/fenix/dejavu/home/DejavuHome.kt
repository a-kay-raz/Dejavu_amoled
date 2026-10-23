/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.actions.icon
import org.mozilla.fenix.dejavu.actions.label
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.color
import org.mozilla.fenix.dejavu.sync.workspaceIconText
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.glass
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme

/**
 * User actions on the Dejavu home screen that leave the screen's own UI state.
 */
@Suppress("TooManyFunctions")
interface DejavuHomeInteractor : TabEditor {
    fun onWorkspaceSelected(workspaceId: String)
    fun onSaveWorkspace(workspaceId: String?, name: String, containerId: String?, icon: String?, theme: WorkspaceTheme?)

    /** Creates a container and returns its id, so that a workspace can use it right away. */
    fun onCreateContainer(name: String, color: ContainerColor, icon: ContainerState.Icon): String
    fun onDeleteWorkspace(workspaceId: String)

    /** Moves workspace [workspaceId] to position [index] among the workspaces. */
    fun onMoveWorkspace(workspaceId: String, index: Int)
    fun onTabClick(tabId: String)
    fun onPinClick(pin: PinnedItem)

    /** Runs a built-in action that needs no further input from the user, from the page of [workspaceId]. */
    fun onTabAction(action: TabAction, targets: ActionTargets, workspaceId: String)

    /** Sends the requests of a custom action for [targets]. */
    fun onCustomAction(action: CustomAction, targets: ActionTargets)

    /** Moves dragged tabs, pinned items and folders to [target] in [workspaceId], from that workspace or another one. */
    fun onDrop(workspaceId: String, selection: Selection, target: DropTarget)

    /** Reopens the tab closed last in its workspace and shows it; returns the tab's id, or `null` when none is left. */
    fun onReopenClosedTab(): String?

    /** Opens the link in the clipboard in a new tab of [workspaceId]. */
    fun onOpenClipboardLink(workspaceId: String)

    /** Moves essential [pinId] to position [index] among the essentials shown with it. */
    fun onMoveEssential(pinId: String, index: Int)

    /** Closes the unpinned tabs of [workspaceId], with a way to undo. */
    fun onClearUnpinned(workspaceId: String)
    fun onToggleFolder(folderId: String)
    fun onSearchClick()

    /** Starts a new private tab. */
    fun onNewPrivateTab()

    /** Closes every private tab, which removes the private workspace. */
    fun onClosePrivateTabs()
    fun onCloseTab(tabId: String)

    /** Starts a new tab in the container [pick]. */
    fun onNewTabInContainer(pick: ContainerPick)
    fun onAccountClick()
    fun onSettingsClick()
    fun onDownloadsClick()
    fun onExtensionsClick()
    fun onBookmarksClick()
    fun onHistoryClick()
}

/**
 * Workspace based home screen. The essentials sit on top of every workspace. Each workspace is a page with its pinned
 * tabs and folders, then its other tabs; swiping horizontally switches workspace. Long-pressing a tab or folder starts
 * selection mode; keeping the finger down and moving drags the selection, also to other workspaces: resting the finger
 * on the strip that shows at a side of the page turns to the workspace on that side, see [SelectionDrag].
 * [hasClosedTab] tells whether a closed tab is left to reopen, and [hasClipboardLink] whether the clipboard holds a
 * link to open.
 */
@Suppress("LongMethod", "LongParameterList", "CognitiveComplexMethod", "CyclomaticComplexMethod")
@Composable
fun DejavuHome(
    state: WorkspaceState,
    tabs: List<TabSessionState>,
    privateTabs: List<TabSessionState>,
    selectedTabId: String?,
    hasClosedTab: Boolean,
    hasClipboardLink: Boolean,
    containers: Map<String, ContainerRecord>,
    pinnedRowActions: List<RowAction>,
    unpinnedRowActions: List<RowAction>,
    folderRowActions: List<RowAction>,
    selectionActions: List<RowAction>,
    essentialsPerContainer: Boolean,
    interactor: DejavuHomeInteractor,
    modifier: Modifier = Modifier,
) {
    // The private workspace is an extra last page, there while private tabs are open.
    val privateIndex = state.workspaces.size.takeIf { privateTabs.isNotEmpty() }
    val pagerState = rememberPagerState(
        initialPage = if (privateTabs.any { it.id == selectedTabId } && privateIndex != null) privateIndex else state.activeIndex,
    ) { state.workspaces.size + if (privateIndex != null) 1 else 0 }
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf<Selection?>(null) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var drag by remember { mutableStateOf<SelectionDrag?>(null) }
    // The pages composed now, which tell where dragged items would land on them.
    val dragAreas = remember { mutableMapOf<String, PageDragArea>() }
    var pagesWidth by remember { mutableIntStateOf(0) }
    var revealTabId by remember { mutableStateOf<String?>(null) }
    val onPrivatePage = privateIndex != null && pagerState.currentPage == privateIndex
    // While any of the private workspace shows, even halfway through a swipe.
    val privatePageShown by remember(privateIndex) {
        derivedStateOf {
            privateIndex != null && (
                pagerState.currentPage == privateIndex ||
                    pagerState.layoutInfo.visiblePagesInfo.any { it.index == privateIndex }
                )
        }
    }
    SecureWindowWhile(privatePageShown)
    val currentWorkspace = state.workspaces.getOrNull(pagerState.currentPage) ?: state.workspaces.last()
    val tabsById = remember(tabs) { tabs.associateBy { it.id } }
    // The workspace of a device on the account shows what the device has, which has no essentials.
    val essentials = if (onPrivatePage || currentWorkspace.device != null) {
        emptyList()
    } else {
        state.essentialsFor(currentWorkspace.containerId, essentialsPerContainer)
    }
    val grain = rememberGrainBrush()

    val isNightHome = (LocalContext.current.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    fun themeAt(page: Int): WorkspaceTheme? =
        if (isNightHome) null else if (page == privateIndex) privateWorkspaceTheme else state.workspaces.getOrNull(page)?.theme

    // Like Zen, there is only one private workspace: asking for a private tab elsewhere leads to it once it exists.
    val newPrivateTab: () -> Unit = {
        if (privateIndex != null && !onPrivatePage) {
            scope.launch { pagerState.animateScrollToPage(privateIndex) }
        } else {
            interactor.onNewPrivateTab()
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        state.workspaces.getOrNull(pagerState.settledPage)?.let { interactor.onWorkspaceSelected(it.id) }
    }

    // Follows workspace changes made elsewhere, without leaving the private workspace the home opened on.
    var followedActiveIndex by remember { mutableIntStateOf(state.activeIndex) }
    LaunchedEffect(state.activeIndex, state.workspaces.size) {
        val changed = state.activeIndex != followedActiveIndex
        followedActiveIndex = state.activeIndex
        if (pagerState.settledPage != state.activeIndex && (changed || pagerState.settledPage != privateIndex)) {
            pagerState.animateScrollToPage(state.activeIndex)
        }
    }

    LaunchedEffect(tabs, state.pins) {
        val current = selection ?: return@LaunchedEffect
        val tabIds = tabs.map { it.id }.toSet()
        val (folders, pins) = state.pins.partition { it.isFolder }
        val pinIds = pins.map { it.id }.toSet()
        val folderIds = folders.map { it.id }.toSet()
        val cleaned = Selection(
            tabIds = current.tabIds.filter { it in tabIds }.toSet(),
            pinIds = current.pinIds.filter { it in pinIds }.toSet(),
            folderIds = current.folderIds.filter { it in folderIds }.toSet(),
        )
        selection = cleaned.takeIf { it.size > 0 }
    }

    LaunchedEffect(revealTabId) {
        if (revealTabId != null) {
            delay(REVEAL_TIMEOUT_MS)
            revealTabId = null
        }
    }

    val latestState by rememberUpdatedState(state)
    val latestTabs by rememberUpdatedState(tabs)
    val latestInteractor by rememberUpdatedState(interactor)
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val stripWidth = with(LocalDensity.current) { SideStripWidth.toPx() }
    val autoScrollEdge = with(LocalDensity.current) { AutoScrollEdge.toPx() }

    // Follows the finger to [pointer]: the side strip it rests on, the page under it and where the items would land.
    fun aim(current: SelectionDrag, pointer: Offset): SelectionDrag {
        val workspaces = latestState.workspaces
        val moved = current.moved || (pointer - current.start).getDistance() > touchSlop
        val page = pagerState.currentPage
        val side = sideAt(pointer.x, pagesWidth.toFloat(), stripWidth, isRtl)
            .takeIf { moved && current.canLeave && page + it in workspaces.indices } ?: 0
        val over = workspaces.getOrNull(page)?.id?.takeIf { side == 0 && !pagerState.isScrollInProgress }
        val drop = over?.takeIf { moved }?.let { dragAreas[it] }?.drop(pointer.y, current) ?: PageDrop(null)
        return current.copy(
            pointer = pointer,
            moved = moved,
            side = side,
            over = over,
            target = drop.target,
            blocked = drop.blocked,
        )
    }

    // Lets go of the dragged items. They land where the finger points; otherwise nothing moves, and the pages turn back
    // to the workspace they are in.
    fun endDrag(drop: Boolean) {
        val current = drag ?: return
        drag = null
        val over = current.over
        val target = current.target
        if (drop && current.moved && over != null && target != null && !current.blocked) {
            if (over != current.from && latestState.isDeviceWorkspace(over)) {
                latestInteractor.onMoveToWorkspace(ActionTargets.of(latestState, latestTabs, current.selection), over)
            } else {
                latestInteractor.onDrop(over, current.selection, target)
            }
            selection = null
            return
        }
        val home = latestState.workspaces.indexOfFirst { it.id == current.from }
        if (home >= 0 && home != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(home) }
    }

    // Resting the finger on a side strip turns to the workspace on that side, and on to the next ones while it stays.
    val dragSide = drag?.side ?: 0
    LaunchedEffect(dragSide) {
        if (dragSide == 0) return@LaunchedEffect
        while (true) {
            delay(SIDE_DWELL_MS)
            val next = pagerState.currentPage + dragSide
            if (next !in latestState.workspaces.indices) break
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            // Not cancelled when the finger leaves the strip, so the pages never stop between two workspaces.
            scope.launch { pagerState.animateScrollToPage(next) }.join()
        }
    }

    // Once the pages stop turning, the page now under the finger tells where the items would land.
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) drag?.let { drag = aim(it, it.pointer) }
    }

    // Near the top or the bottom of the page, the page scrolls under the dragged items.
    val isDragging = drag != null
    LaunchedEffect(isDragging) {
        while (isDragging) {
            val current = drag ?: break
            val area = current.over?.takeIf { current.moved }?.let { dragAreas[it] }
            val y = current.pointer.y
            val step = when {
                area == null || y < 0f -> 0f
                y < autoScrollEdge -> -AUTO_SCROLL_STEP
                y > area.height - autoScrollEdge -> AUTO_SCROLL_STEP
                else -> 0f
            }
            if (area != null && step != 0f && area.scrollBy(step) != 0f) drag = drag?.let { aim(it, it.pointer) }
            delay(AUTO_SCROLL_FRAME_MS)
        }
    }

    val essentialsDrop = drag.let { current ->
        when {
            current == null || !current.moved || current.selection.folderIds.isNotEmpty() || !current.canLeave ||
                onPrivatePage || currentWorkspace.device != null -> EssentialsDrop.NONE
            current.target == DropTarget.Essentials -> EssentialsDrop.ACTIVE
            else -> EssentialsDrop.AVAILABLE
        }
    }

    BackHandler(enabled = selection != null) { selection = null }

    fun runAction(action: RowAction, targets: ActionTargets, workspaceId: String) {
        when (action) {
            is RowAction.Custom -> interactor.onCustomAction(action.action, targets)
            is RowAction.BuiltIn -> when (action.action) {
                TabAction.MOVE_TO_WORKSPACE -> dialog = HomeDialog.MoveToWorkspace(workspaceId, targets)
                TabAction.MOVE_TO_FOLDER -> dialog = HomeDialog.MoveToFolder(workspaceId, targets)
                TabAction.NEW_FOLDER -> dialog = HomeDialog.NewFolder(workspaceId, state.newFolderParent(targets), targets)
                TabAction.NEW_SUBFOLDER ->
                    targets.folders.singleOrNull()?.let { dialog = HomeDialog.NewFolder(workspaceId, parentId = it.id) }
                TabAction.RENAME_FOLDER -> targets.folders.singleOrNull()?.let { dialog = HomeDialog.RenameFolder(it) }
                TabAction.RENAME_TAB -> {
                    val pin = targets.pins.singleOrNull()
                    val tab = targets.tabs.singleOrNull()
                    dialog = when {
                        pin != null -> HomeDialog.RenameTab(pin.id, null, pin.label(targets.pinnedTabs.firstOrNull()))
                        tab != null -> HomeDialog.RenameTab(null, tab.id, state.titleOf(tab))
                        else -> null
                    }
                }
                TabAction.DELETE -> dialog = HomeDialog.DeleteItems(targets)
                TabAction.CHANGE_CONTAINER -> dialog = HomeDialog.ChangeContainer(targets)
                else -> interactor.onTabAction(action.action, targets, workspaceId)
            }
        }
        selection = null
    }

    fun onPinClick(pin: PinnedItem) {
        val current = selection
        if (current != null) {
            selection = current.togglePin(pin.id, state).takeIf { it.size > 0 }
        } else {
            interactor.onPinClick(pin)
        }
    }

    // Dialogs and menus take on the look of the workspace they open over.
    CompositionLocalProvider(LocalPopupTheme provides themeAt(pagerState.currentPage)) {
        // The theme is drawn behind the system bars too, with the content kept clear of them.
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .drawBehind {
                    val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    val page = floor(position).toInt()
                    drawWorkspaceTheme(
                        theme = themeAt(page),
                        next = themeAt(page + 1),
                        fraction = position - page,
                        grain = grain,
                    )
                }
                .systemBarsPadding()
                .imePadding(),
        ) {
            val activeSelection = selection
            if (activeSelection == null) {
                TopBar(interactor, onSearchClick = if (onPrivatePage) newPrivateTab else interactor::onSearchClick)
            } else if (essentialsDrop != EssentialsDrop.NONE) {
                EssentialsDropBar(active = essentialsDrop == EssentialsDrop.ACTIVE)
            } else {
                SelectionTopBar(
                    count = activeSelection.size,
                    onClose = { selection = null },
                    onSelectAll = {
                        selection = Selection(
                            tabIds = tabs
                                .filter {
                                    state.workspaceOf(it.id) == currentWorkspace.id && state.pinOf(it.id) == null
                                }
                                .map { it.id }.toSet(),
                            pinIds = state.pins.filter { it.workspaceId == currentWorkspace.id && !it.isFolder }
                                .map { it.id }.toSet(),
                        )
                    },
                )
            }

            if (essentials.isNotEmpty()) {
                EssentialsGrid(
                    essentials = essentials,
                    tabsById = tabsById,
                    selectedTabId = selectedTabId,
                    containers = containers,
                    selection = activeSelection,
                    isDropTarget = essentialsDrop == EssentialsDrop.ACTIVE,
                    onClick = ::onPinClick,
                    onStartDrag = { pin -> selection = (selection ?: Selection()) + Selection(pinIds = setOf(pin.id)) },
                    onMove = { pinId, index ->
                        interactor.onMoveEssential(pinId, index)
                        selection = null
                    },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            val selectionTargets = activeSelection?.let { ActionTargets.of(state, tabs, it) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { pagesWidth = it.width }
                    // The drag lives above the pages, so it goes on while they turn to another workspace.
                    .pointerInput(isRtl, stripWidth, touchSlop) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val workspace = latestState.workspaces.getOrNull(pagerState.currentPage)
                                val atEnd = if (isRtl) offset.x < size.width / 2f else offset.x >= size.width / 2f
                                drag = workspace?.let { dragAreas[it.id] }?.start(offset, atEnd)
                                if (drag != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, amount ->
                                val current = drag ?: return@detectDragGesturesAfterLongPress
                                change.consume()
                                drag = aim(current, current.pointer + amount)
                            },
                            onDragEnd = { endDrag(drop = true) },
                            onDragCancel = { endDrag(drop = false) },
                        )
                    },
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = activeSelection == null,
                    modifier = Modifier.fillMaxSize(),
                    key = { state.workspaces.getOrNull(it)?.id ?: PRIVATE_PAGE_KEY },
                ) { page ->
                    if (page == privateIndex) {
                        PrivateWorkspacePage(
                            tabs = privateTabs,
                            selectedTabId = selectedTabId,
                            onTabClick = { interactor.onTabClick(it.id) },
                            onCloseTab = { interactor.onCloseTab(it.id) },
                            onNewTab = interactor::onNewPrivateTab,
                            onCloseAll = interactor::onClosePrivateTabs,
                        )
                        return@HorizontalPager
                    }
                    val workspace = state.workspaces[page]
                    WorkspacePage(
                        state = state,
                        workspace = workspace,
                        tabs = tabs.filter { state.workspaceOf(it.id) == workspace.id },
                        selectedTabId = selectedTabId,
                        containers = containers,
                        pinnedRowActions = pinnedRowActions,
                        unpinnedRowActions = unpinnedRowActions,
                        folderRowActions = folderRowActions,
                        selection = activeSelection,
                        drag = drag,
                        dragAreas = dragAreas,
                        callbacks = WorkspacePageCallbacks(
                            onTabClick = { tab ->
                                val current = selection
                                if (current != null) selection = current.toggleTab(tab.id).takeIf { it.size > 0 } else interactor.onTabClick(tab.id)
                            },
                            onPinClick = ::onPinClick,
                            onStartDrag = { picked ->
                                val started = (selection ?: Selection()) + picked
                                selection = started
                                started
                            },
                            onRowAction = { action, targets -> runAction(action, targets, workspace.id) },
                            onFolderClick = { folder ->
                                val current = selection
                                if (current != null) {
                                    selection = current.toggleFolder(folder.id, state).takeIf { it.size > 0 }
                                } else {
                                    interactor.onToggleFolder(folder.id)
                                }
                            },
                            onNewFolder = { dialog = HomeDialog.NewFolder(workspace.id, parentId = null) },
                            onNewWorkspace = { dialog = HomeDialog.EditWorkspace(null) },
                            onEditWorkspace = { dialog = HomeDialog.EditWorkspace(workspace.id) },
                            onDeleteWorkspace = { dialog = HomeDialog.DeleteWorkspace(workspace.id) },
                            onMoveWorkspace = { delta -> interactor.onMoveWorkspace(workspace.id, page + delta) },
                            onNewTabClick = interactor::onSearchClick,
                            onNewTabInContainer = interactor::onNewTabInContainer,
                            onNewPrivateTab = newPrivateTab,
                            onManageContainers = interactor::onManageContainers,
                            onClearUnpinned = { interactor.onClearUnpinned(workspace.id) },
                            onReopenClosedTab = { revealTabId = interactor.onReopenClosedTab() },
                            onOpenClipboard = { interactor.onOpenClipboardLink(workspace.id) },
                            onRevealed = { revealTabId = null },
                        ),
                        canDeleteWorkspace = state.workspaces.size > 1,
                        canMoveLeft = page > 0,
                        canMoveRight = page < state.workspaces.size - 1,
                        hasClosedTab = hasClosedTab,
                        hasClipboardLink = hasClipboardLink,
                        revealTabId = revealTabId,
                    )
                }

                val dragging = drag?.takeIf { it.moved }
                if (dragging != null && dragging.canLeave && !onPrivatePage) {
                    val page = pagerState.currentPage
                    state.workspaces.getOrNull(page - 1)?.let { previous ->
                        SideStrip(
                            workspace = previous,
                            container = previous.containerId?.let { containers[it] },
                            active = dragging.side == -1,
                            atEnd = false,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                    state.workspaces.getOrNull(page + 1)?.let { next ->
                        SideStrip(
                            workspace = next,
                            container = next.containerId?.let { containers[it] },
                            active = dragging.side == 1,
                            atEnd = true,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
                if (dragging != null) {
                    DragGhost(
                        label = dragging.label,
                        isFolder = dragging.isFolder,
                        pointer = dragging.pointer,
                        blocked = dragging.blocked,
                    )
                }
            }

            if (activeSelection == null) {
                WorkspaceBar(
                    workspaces = state.workspaces,
                    containers = containers,
                    activeIndex = pagerState.currentPage,
                    hasPrivate = privateIndex != null,
                    onDotClick = { index ->
                        interactor.onWorkspaceSelected(state.workspaces[index].id)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    onPrivateClick = { privateIndex?.let { scope.launch { pagerState.animateScrollToPage(it) } } },
                    onMove = interactor::onMoveWorkspace,
                    onDownloadsClick = interactor::onDownloadsClick,
                    onExtensionsClick = interactor::onExtensionsClick,
                    onBookmarksClick = interactor::onBookmarksClick,
                    onHistoryClick = interactor::onHistoryClick,
                )
            } else if (selectionTargets != null) {
                SelectionBar(
                    actions = selectionActions.filter { it.appliesTo(selectionTargets) },
                    onAction = { runAction(it, selectionTargets, currentWorkspace.id) },
                )
            }
        }

        dialog?.let { current ->
            HomeDialogs(
                dialog = current,
                state = state,
                containers = containers,
                interactor = interactor,
                onDismiss = { dialog = null },
            )
        }
    }
}

@Composable
private fun TopBar(interactor: DejavuHomeInteractor, onSearchClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
    ) {
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_avatar_circle_24,
            contentDescription = stringResource(R.string.dejavu_account),
            onClick = interactor::onAccountClick,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .glass(glass, CircleShape)
                .clickable(onClick = onSearchClick)
                .padding(horizontal = 16.dp),
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_search_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.dejavu_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        BarIconButton(
            icon = iconsR.drawable.mozac_ic_settings_24,
            contentDescription = stringResource(R.string.dejavu_settings),
            onClick = interactor::onSettingsClick,
        )
    }
}

/** Replaces the selection bar on top while dragged tabs can be dropped into the essentials. */
@Composable
private fun EssentialsDropBar(active: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_grid_add_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.dejavu_essentials_drop),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The top bar of selection mode: exit, the number of selected items, and select all unless [onSelectAll] is `null`. */
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
    ) {
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_cross_24,
            contentDescription = stringResource(R.string.dejavu_selection_exit),
            onClick = onClose,
        )
        Text(
            text = pluralStringResource(R.plurals.dejavu_selection_count, count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (onSelectAll != null) {
            BarIconButton(
                icon = iconsR.drawable.mozac_ic_select_all_24,
                contentDescription = stringResource(R.string.dejavu_selection_select_all),
                onClick = onSelectAll,
            )
        }
    }
}

@Composable
private fun SelectionBar(
    actions: List<RowAction>,
    onAction: (RowAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        actions.chunked(SELECTION_ACTIONS_PER_ROW).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { action ->
                    SelectionAction(action, onAction, Modifier.weight(1f))
                }
                repeat(SELECTION_ACTIONS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SelectionAction(
    action: RowAction,
    onAction: (RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDestructive = action is RowAction.BuiltIn && (action.action == TabAction.CLOSE || action.action == TabAction.DELETE)
    val color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onAction(action) }
            .padding(vertical = 8.dp, horizontal = 2.dp),
    ) {
        Icon(
            painter = painterResource(action.icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SELECTION_ACTIONS_PER_ROW = 6

// How long the finger rests on a side strip before the pages turn to the next workspace.
private const val SIDE_DWELL_MS = 600L
private val AutoScrollEdge = 64.dp
private const val AUTO_SCROLL_STEP = 14f
private const val AUTO_SCROLL_FRAME_MS = 16L

// How long a reopened tab may take to come back before the home stops waiting to scroll to it.
private const val REVEAL_TIMEOUT_MS = 3000L

@Composable
internal fun BarIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * A workspace being dragged along the workspace bar.
 *
 * @property workspaceId The long-pressed workspace.
 * @property x Finger position along the row of workspaces.
 * @property index Position the workspace would be dropped at.
 */
private data class WorkspaceDrag(val workspaceId: String, val x: Float, val index: Int)

/**
 * Downloads, one dot or icon per workspace, and history. Tapping a workspace shows it; long-pressing and dragging one
 * sideways moves it.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun WorkspaceBar(
    workspaces: List<Workspace>,
    containers: Map<String, ContainerRecord>,
    activeIndex: Int,
    hasPrivate: Boolean,
    onDotClick: (Int) -> Unit,
    onPrivateClick: () -> Unit,
    onMove: (workspaceId: String, index: Int) -> Unit,
    onDownloadsClick: () -> Unit,
    onExtensionsClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
) {
    var drag by remember { mutableStateOf<WorkspaceDrag?>(null) }
    val lefts = remember { mutableStateMapOf<String, Float>() }
    val latestWorkspaces by rememberUpdatedState(workspaces)
    val latestOnMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val cellWidth = with(LocalDensity.current) { WorkspaceCellSize.toPx() }
    val activeId = workspaces.getOrNull(activeIndex)?.id
    val scrollState = rememberScrollState()

    // With many workspaces the row scrolls; keep the shown one in view. The private workspace comes last.
    val isPrivateActive = hasPrivate && activeIndex == workspaces.size
    LaunchedEffect(activeId, lefts[activeId], scrollState.viewportSize, isPrivateActive, scrollState.maxValue) {
        if (isPrivateActive) {
            scrollState.animateScrollTo(scrollState.maxValue)
            return@LaunchedEffect
        }
        val left = activeId?.let { lefts[it] } ?: return@LaunchedEffect
        val target = (left + cellWidth / 2 - scrollState.viewportSize / 2f).roundToInt()
        scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue))
    }

    fun indexAt(x: Float): Int {
        val start = lefts.values.minOrNull() ?: 0f
        return ((x - start) / cellWidth).toInt().coerceIn(0, (latestWorkspaces.size - 1).coerceAtLeast(0))
    }

    val current = drag
    val ordered = if (current == null) {
        workspaces
    } else {
        val dragged = workspaces.firstOrNull { it.id == current.workspaceId }
        if (dragged == null) {
            workspaces
        } else {
            (workspaces - dragged).toMutableList().apply { add(current.index.coerceIn(0, size), dragged) }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
    ) {
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_download_24,
            contentDescription = stringResource(R.string.dejavu_downloads),
            onClick = onDownloadsClick,
        )
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_extension_24,
            contentDescription = stringResource(R.string.dejavu_extensions),
            onClick = onExtensionsClick,
        )

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .fadingEdges(scrollState)
                .horizontalScroll(scrollState)
                .pointerInput(cellWidth) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val index = indexAt(offset.x)
                            val workspace = latestWorkspaces.getOrNull(index)
                            if (workspace != null) {
                                drag = WorkspaceDrag(workspace.id, offset.x, index)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, amount ->
                            val dragging = drag ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            val x = dragging.x + amount.x
                            drag = dragging.copy(x = x, index = indexAt(x))
                        },
                        onDragEnd = {
                            val dragging = drag
                            drag = null
                            val from = latestWorkspaces.indexOfFirst { it.id == dragging?.workspaceId }
                            if (dragging != null && from >= 0 && from != dragging.index) {
                                latestOnMove(dragging.workspaceId, dragging.index)
                            }
                        },
                        onDragCancel = { drag = null },
                    )
                },
        ) {
            ordered.forEach { workspace ->
                WorkspaceDot(
                    workspace = workspace,
                    container = workspace.containerId?.let { containers[it] },
                    isActive = workspace.id == activeId,
                    isDragged = current?.workspaceId == workspace.id,
                    onClick = {
                        val index = workspaces.indexOfFirst { it.id == workspace.id }
                        if (drag == null && index >= 0) onDotClick(index)
                    },
                    modifier = Modifier.onPlaced { lefts[workspace.id] = it.positionInParent().x },
                )
            }
            if (hasPrivate) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(WorkspaceCellSize)
                        .clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.dejavu_private_workspace), onClick = onPrivateClick),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(WorkspaceMarkSize)
                            .clip(CircleShape)
                            .background(
                                if (isPrivateActive) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                            ),
                    ) {
                        Icon(
                            painter = painterResource(iconsR.drawable.mozac_ic_private_mode_24),
                            contentDescription = stringResource(R.string.dejavu_private_workspace),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp).alpha(if (isPrivateActive) 1f else INACTIVE_ICON_ALPHA),
                        )
                    }
                }
            }
        }

        BarIconButton(
            icon = iconsR.drawable.mozac_ic_bookmark_tray_24,
            contentDescription = stringResource(R.string.dejavu_bookmarks),
            onClick = onBookmarksClick,
        )
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_history_24,
            contentDescription = stringResource(R.string.dejavu_history),
            onClick = onHistoryClick,
        )
    }
}

private val WorkspaceCellSize = 48.dp
private val WorkspaceMarkSize = 36.dp
private val FadingEdgeWidth = 20.dp
private const val INACTIVE_WORKSPACE_ALPHA = 0.45f
private const val INACTIVE_ICON_ALPHA = 0.55f

/** A workspace in the workspace bar: its icon, or a dot in its container's color. */
@Suppress("LongParameterList")
@Composable
private fun WorkspaceDot(
    workspace: Workspace,
    container: ContainerRecord?,
    isActive: Boolean,
    isDragged: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = container?.color?.color ?: MaterialTheme.colorScheme.onSurface
    val icon = workspaceIconText(workspace.icon)
    val background = when {
        isDragged -> MaterialTheme.colorScheme.primaryContainer
        isActive && icon != null -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(WorkspaceCellSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = workspace.name
                selected = isActive
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(WorkspaceMarkSize).clip(CircleShape).background(background),
        ) {
            if (icon != null) {
                Text(
                    text = icon,
                    fontSize = 17.sp,
                    modifier = Modifier.alpha(if (isActive || isDragged) 1f else INACTIVE_ICON_ALPHA),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(if (isActive) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) base else base.copy(alpha = INACTIVE_WORKSPACE_ALPHA)),
                )
            }
        }
    }
}

/** Fades a horizontally scrolling row out on the sides it can still scroll to. */
private fun Modifier.fadingEdges(scrollState: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edge = FadingEdgeWidth.toPx()
            if (scrollState.value > 0) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = edge),
                    size = Size(edge, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (scrollState.value < scrollState.maxValue) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - edge),
                    topLeft = Offset(size.width - edge, 0f),
                    size = Size(edge, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
