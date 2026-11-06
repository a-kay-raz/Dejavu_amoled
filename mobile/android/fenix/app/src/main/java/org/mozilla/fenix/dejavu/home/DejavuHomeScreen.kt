/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.content.Context
import android.net.Uri
import android.os.StrictMode
import java.util.UUID
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavDirections
import androidx.navigation.NavOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.observe
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.ktx.kotlin.isUrl
import mozilla.components.support.ktx.kotlin.toNormalizedUrl
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.accounts.FenixFxAEntryPoint
import org.mozilla.fenix.components.appstate.AppAction.SearchAction.SearchStarted
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.dejavu.NewTabContainerChoice
import org.mozilla.fenix.dejavu.actions.ActionPlace
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.browser.ClearData
import org.mozilla.fenix.dejavu.browser.DejavuSearchOverlay
import org.mozilla.fenix.dejavu.browser.DejavuSearchStart
import org.mozilla.fenix.dejavu.browser.SearchBackdrop
import org.mozilla.fenix.dejavu.browser.SearchPageBackdrop
import org.mozilla.fenix.dejavu.browser.SplitSpace
import org.mozilla.fenix.dejavu.browser.TabZoom
import org.mozilla.fenix.dejavu.browser.blurBehindSearch
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.features.wasUpdated
import org.mozilla.fenix.dejavu.openClosedPin
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.settings.resolveRowActions
import org.mozilla.fenix.dejavu.settings.resolveSelectionActions
import org.mozilla.fenix.dejavu.sync.DejavuSync
import org.mozilla.fenix.dejavu.sync.SyncSourceDialog
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.workspacePopupTheme
import org.mozilla.fenix.dejavu.welcome.Welcome
import org.mozilla.fenix.dejavu.workspaces.PinPlacement
import org.mozilla.fenix.dejavu.workspaces.PinSource
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.WallpaperArt
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers
import org.mozilla.fenix.settings.deletebrowsingdata.DeleteBrowsingDataOnQuitType
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Whether the Dejavu workspace home replaces the upstream homepage. It always does: private tabs appear on it as a
 * private workspace, and it switches the app back to normal browsing when it shows.
 */
@Suppress("UNUSED_PARAMETER")
fun isDejavuHomeEnabled(isPrivate: Boolean): Boolean = true

/**
 * Sets the Dejavu workspace home as the content of the homepage [ComposeView].
 *
 * @param navController Used to open settings, account, downloads and the share sheet.
 * @param searchToolbar The upstream home toolbar, shown on top of the workspaces while a search is active.
 * @param onOpenTab Selects the given tab and navigates to the browser.
 * @param onFirstFrameDrawn Invoked once the first frame of the home screen is drawn.
 */
fun ComposeView.setDejavuHomeContent(
    navController: NavController,
    searchToolbar: @Composable () -> Unit,
    onOpenTab: (String) -> Unit,
    onFirstFrameDrawn: () -> Unit,
) {
    setContent {
        FirefoxTheme {
            val fenix = components
            val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
            val settings = remember { fenix.readOnMainThread { DejavuSettings.get(context) } }
            val containerStorage = remember { DejavuContainerStorage.get(context) }
            val wallpaperStore = remember { fenix.readOnMainThread { WorkspaceWallpapers.get(context) } }
            val scope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }
            // Counts the times Clear data deleted data, to look again at what is left to delete.
            var dataCleared by remember { mutableIntStateOf(0) }
            val interactor = remember {
                DefaultDejavuHomeInteractor(
                    context = context,
                    components = fenix,
                    repository = repository,
                    settings = settings,
                    containerStorage = containerStorage,
                    wallpapers = wallpaperStore,
                    navController = navController,
                    scope = scope,
                    snackbar = snackbarHostState,
                    openTab = onOpenTab,
                    onDataCleared = { dataCleared++ },
                )
            }
            val hasDataToClear = rememberHasDataToClear(dataCleared)

            val workspaceState by repository.state.collectAsState()
            val wallpapers by wallpaperStore.wallpapers.collectAsState()
            val customActions by settings.customActions.collectAsState()
            val pinnedKeys by settings.pinnedRowKeys.collectAsState()
            val unpinnedKeys by settings.unpinnedRowKeys.collectAsState()
            val folderKeys by settings.folderRowKeys.collectAsState()
            val hiddenSelectionKeys by settings.hiddenSelectionKeys.collectAsState()
            val essentialsPerContainer by settings.essentialsPerContainer.collectAsState()
            val searchAtBottom by settings.homeSearchAtBottom.collectAsState()
            val containerRecords by containerStorage.records.collectAsState()
            val containers = remember(containerRecords) { containerRecords.orEmpty().associateBy { it.contextId } }
            val tabs by fenix.core.store.observeTabsForHome { it.normalTabs }
            val allPrivateTabs by fenix.core.store.observeTabsForHome { it.privateTabs }
            val isPrivateLocked by fenix.appStore.observeAsComposableState { it.isPrivateScreenLocked }
            val selectedTabId by fenix.core.store.observeAsComposableState { it.selectedTabId }
            // Read only by the downloads button, so download progress redraws nothing else.
            val downloads = fenix.core.store.observeAsComposableState { it.downloadsStatus() }
            val hasClosedTab by fenix.core.store.observeAsComposableState { it.hasClosedTab }
            val hasClipboardLink = rememberClipboardHasLink()
            val restoreComplete by fenix.core.store.observeAsComposableState { it.restoreComplete }
            val isSearchActive by fenix.appStore.observeAsComposableState { it.searchState.isSearchActive }

            LaunchedEffect(Unit) {
                containerStorage.load()
            }

            // Coming home from a private tab leaves the app in private browsing; the home itself is never private.
            LaunchedEffect(Unit) {
                val activity = context as? HomeActivity ?: return@LaunchedEffect
                if (activity.browsingModeManager.mode.isPrivate) activity.browsingModeManager.mode = BrowsingMode.Normal
            }

            LaunchedEffect(isSearchActive) {
                if (!isSearchActive) {
                    delay(SEARCH_ABANDON_GRACE_MS)
                    NewTabContainerChoice.clear()
                }
            }

            LaunchedEffect(tabs, restoreComplete) {
                repository.syncWithTabs(tabs.map { it.id }.toSet(), restoreComplete)
                repository.refreshPinTitles(tabs.associate { it.id to (it.content.url to it.content.title) })
            }

            // A search opened over a page shows the page blurred behind it, else the workspaces blur behind it.
            val searchedPage by SearchBackdrop.page.collectAsState()
            val showsPage = remember(searchedPage) {
                searchedPage?.tabId?.let { it == fenix.appStore.state.searchState.sourceTabId } == true
            }

            // A fresh install, and an update from a Dejavu without them, shows the welcome pages first.
            val updated = remember { context.wasUpdated() }
            val welcomeSeen by settings.welcomeSeen.collectAsState()
            val welcomeDue = Welcome.isDue(welcomeSeen, updated, settings.whatsNewSeen)
            // Once Dejavu found what the account syncs from Firefox and Zen, it asks which one workspaces follow.
            val syncStatus by DejavuSync.status.collectAsState()

            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                DejavuHome(
                    modifier = Modifier.blurBehindSearch(active = isSearchActive && !showsPage),
                    state = workspaceState,
                    tabs = tabs,
                    privateTabs = if (isPrivateLocked) emptyList() else allPrivateTabs,
                    selectedTabId = selectedTabId,
                    hasClosedTab = hasClosedTab,
                    hasClipboardLink = hasClipboardLink,
                    containers = containers,
                    // Remembered, so that the workspace pages can skip recomposing when nothing they show changed.
                    pinnedRowActions = remember(pinnedKeys, customActions) {
                        resolveRowActions(pinnedKeys, ActionPlace.PINNED_ROWS, customActions)
                    },
                    unpinnedRowActions = remember(unpinnedKeys, customActions) {
                        resolveRowActions(unpinnedKeys, ActionPlace.UNPINNED_ROWS, customActions)
                    },
                    folderRowActions = remember(folderKeys, customActions) {
                        resolveRowActions(folderKeys, ActionPlace.FOLDER_ROWS, customActions)
                    },
                    selectionActions = remember(hiddenSelectionKeys, customActions) {
                        resolveSelectionActions(hiddenSelectionKeys, customActions)
                    },
                    essentialsPerContainer = essentialsPerContainer,
                    wallpapers = wallpapers,
                    downloads = downloads,
                    interactor = interactor,
                    searchAtBottom = searchAtBottom,
                    hasDataToClear = hasDataToClear,
                    tipsAllowed = !welcomeDue && !syncStatus.askSource && !isSearchActive,
                )

                if (showsPage) {
                    SearchPageBackdrop(active = isSearchActive, modifier = Modifier.fillMaxSize())
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .systemBarsPadding()
                        .imePadding()
                        .padding(bottom = 64.dp),
                )

                if (isSearchActive) {
                    Box(modifier = Modifier.systemBarsPadding().imePadding()) {
                        DejavuSearchOverlay(
                            fromTop = !fenix.settings.shouldUseBottomToolbar ||
                                fenix.appStore.state.searchState.sourceTabId == null,
                            content = searchToolbar,
                        )
                    }
                }
            }

            LaunchedEffect(Unit) {
                if (Welcome.isDue(settings.welcomeSeen.value, updated, settings.whatsNewSeen)) {
                    if (!updated) Welcome.giveFirstLook(context, repository, wallpaperStore)
                    Welcome.openAtStart(navController)
                } else if (!settings.welcomeSeen.value) {
                    settings.setWelcomeSeen(true)
                }
            }
            // The examples show their sites' icons before their pages were opened, fetched once the start is over.
            LaunchedEffect(Unit) {
                delay(STARTER_ICONS_DELAY_MS)
                Welcome.loadStarterIcons(context, repository)
            }

            val popupTheme = workspacePopupTheme(workspaceState.activeWorkspaceId)
            if (syncStatus.askSource) {
                CompositionLocalProvider(LocalPopupTheme provides popupTheme) {
                    SyncSourceDialog(
                        status = syncStatus,
                        workspaces = workspaceState,
                        tabs = tabs,
                        onChoose = { source, start -> DejavuSync.chooseSource(context, source, start) },
                        onDismiss = { DejavuSync.putOffSourceChoice(context) },
                    )
                }
            }

            LaunchedEffect(Unit) {
                onFirstFrameDrawn()
                TabZoom.homeShown()
                PageGenie.homeShown()
            }
        }
    }
}

/**
 * Whether the data chosen for Clear data has anything to delete, see [ClearData.hasData]: looked at again as tabs,
 * recently closed tabs and downloads come and go, and after Clear data deleted data, which [cleared] counts.
 */
@Composable
private fun rememberHasDataToClear(cleared: Int): Boolean {
    val context = LocalContext.current
    val store = components.core.store
    val chosen = remember(cleared) { ClearData.chosen(context) }
    val clearedAt = remember(cleared) { DejavuSettings.get(context).clearedDataAt }
    val tabs by store.observeAsComposableState { it.tabs.size }
    val closedTabs by store.observeAsComposableState { it.closedTabs.size }
    val downloads by store.observeAsComposableState { it.downloads.size }
    var lastVisit by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(chosen, cleared, tabs) { lastVisit = ClearData.lastVisit(context) }
    return ClearData.hasData(chosen, tabs, closedTabs, downloads, lastVisit, clearedAt)
}

/**
 * How long a container picked for a new tab survives after the search closes. Long enough for the tab created by the
 * search to pick it up, short enough that an abandoned search does not leak it into a later one.
 */
private const val SEARCH_ABANDON_GRACE_MS = 1500L
private const val STARTER_ICONS_DELAY_MS = 2_000L

private fun <T> Components.readOnMainThread(block: () -> T): T =
    strictMode.allowViolation(StrictMode::allowThreadDiskReads, block)

/**
 * The tabs [select] picks from this store, like [observeAsComposableState], but only updated when something the home
 * shows of them changes, see [isSameOnHome]. Load progress and the other updates of a loading page then no longer
 * recompose the home. The tabs keep the values of their last update, so fields that [isSameOnHome] leaves out may be
 * out of date: read those from the store.
 */
@Composable
internal fun BrowserStore.observeTabsForHome(
    select: (BrowserState) -> List<TabSessionState>,
): State<List<TabSessionState>> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val tabs = remember { mutableStateOf(select(state), HomeTabsPolicy) }
    DisposableEffect(this, lifecycleOwner) {
        val subscription = observe(lifecycleOwner) { browserState -> tabs.value = select(browserState) }
        onDispose { subscription?.unsubscribe() }
    }
    return tabs
}

/** Takes a new list of tabs as the same one while the home would show it the same. */
private object HomeTabsPolicy : SnapshotMutationPolicy<List<TabSessionState>> {
    override fun equivalent(a: List<TabSessionState>, b: List<TabSessionState>): Boolean = isSameOnHome(a, b)
}

@Suppress("TooManyFunctions", "LongParameterList")
private class DefaultDejavuHomeInteractor(
    private val context: Context,
    private val components: Components,
    private val repository: WorkspaceRepository,
    private val settings: DejavuSettings,
    private val navController: NavController,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val openTab: (String) -> Unit,
    private val containerStorage: DejavuContainerStorage,
    private val wallpapers: WorkspaceWallpapers,
    private val onDataCleared: () -> Unit,
    private val runner: TabActionRunner = TabActionRunner(
        context = context,
        components = components,
        repository = repository,
        settings = settings,
        containerStorage = containerStorage,
        navController = navController,
        scope = scope,
        notify = { message -> scope.launch { snackbar.showSnackbar(message) } },
        openTab = openTab,
    ),
    private val reopener: ClosedTabReopener = ClosedTabReopener(context, components, repository, scope),
) : DejavuHomeInteractor {
    private val tabsUseCases = components.useCases.tabsUseCases
    private val store = components.core.store

    override fun onWorkspaceSelected(workspaceId: String) = repository.selectWorkspace(workspaceId)

    override fun onSaveWorkspace(
        workspaceId: String?,
        name: String,
        containerId: String?,
        icon: String?,
        theme: WorkspaceTheme?,
        wallpaper: WorkspaceWallpaper?,
    ) {
        val savedId = if (workspaceId == null) {
            repository.addWorkspace(name, containerId, icon, theme)
        } else {
            repository.updateWorkspace(workspaceId, name, containerId, icon, theme)
            workspaceId
        }
        wallpapers.set(savedId, wallpaper)
    }

    override suspend fun onDrawWallpaper(art: WallpaperArt): WorkspaceWallpaper? {
        val screen = context.resources.displayMetrics
        return wallpapers.importArt(art, screen.widthPixels, screen.heightPixels)
    }

    override suspend fun onImportWallpaper(uri: Uri): WorkspaceWallpaper? {
        val screen = context.resources.displayMetrics
        return wallpapers.importPicture(uri, screen.widthPixels, screen.heightPixels)
    }

    override fun onDiscardWallpaper(file: String) = wallpapers.discard(file)

    override fun onCreateContainer(name: String, color: ContainerColor, icon: ContainerState.Icon): String {
        val contextId = UUID.randomUUID().toString()
        scope.launch { containerStorage.saveContainer(contextId, name, color, icon) }
        return contextId
    }

    override fun onDeleteWorkspace(workspaceId: String) {
        repository.deleteWorkspace(workspaceId)
        if (repository.state.value.workspaces.none { it.id == workspaceId }) wallpapers.set(workspaceId, null)
    }

    override fun onMoveWorkspace(workspaceId: String, index: Int) = repository.moveWorkspace(workspaceId, index)

    override fun onTabClick(tabId: String) = openTab(tabId)

    override fun onPinClick(pin: PinnedItem) {
        tabOf(pin, select = true) { showPin(pin, it) }?.let { showPin(pin, it) }
    }

    /** Shows [tabId], the tab of [pin]. */
    private fun showPin(pin: PinnedItem, tabId: String) {
        // The two pinned tabs of a split view synced from Zen open together, the way Zen shows them.
        val state = repository.state.value
        val zenSplit = state.pinnedSplitOf(pin.id)
        if (zenSplit != null && state.splitOf(tabId) == null) {
            val otherId = zenSplit.pinIds.firstOrNull { it != pin.id }
            val other = state.pins.firstOrNull { it.id == otherId }
            val first = zenSplit.pinIds.first() == pin.id
            val split: (String) -> Unit = { otherTabId ->
                repository.createSplit(
                    first = if (first) tabId else otherTabId,
                    second = if (first) otherTabId else tabId,
                    orientation = zenSplit.orientation,
                )
            }
            other?.let { tabOf(it, select = false, onOpened = split) }?.let(split)
        }
        openTab(tabId)
    }

    /**
     * The open tab of [pin]. A pin whose tab closed opens where that tab was, like a sleeping tab wakes up, selected
     * when [select] is set. One whose address is not a web page first asks the user, see [openClosedPin]: then `null`
     * is returned, and [onOpened] gets the tab once the user agrees.
     */
    private fun tabOf(pin: PinnedItem, select: Boolean, onOpened: (String) -> Unit = {}): String? {
        pin.tabId?.takeIf { store.state.findTab(it) != null }?.let { return it }
        return openClosedPin(context, pin, select, onOpened)
    }

    override fun onTabAction(action: TabAction, targets: ActionTargets, workspaceId: String) =
        runner.run(action, targets, workspaceId)

    override fun onCustomAction(action: CustomAction, targets: ActionTargets) = runner.runCustom(action, targets)

    override fun onCreateFolder(workspaceId: String, parentId: String?, name: String, targets: ActionTargets) =
        runner.onCreateFolder(workspaceId, parentId, name, targets)

    override fun onRenameFolder(folderId: String, name: String) = runner.onRenameFolder(folderId, name)

    override fun onRenameTab(pinId: String?, tabId: String?, name: String) = runner.onRenameTab(pinId, tabId, name)

    override fun onMoveToFolder(workspaceId: String, targets: ActionTargets, folderId: String?) =
        runner.onMoveToFolder(workspaceId, targets, folderId)

    override fun onDeleteItems(targets: ActionTargets) = runner.onDeleteItems(targets)

    override fun onMoveToWorkspace(targets: ActionTargets, workspaceId: String) =
        runner.onMoveToWorkspace(targets, workspaceId)

    override fun onReopenClosedTab(): String? = reopener.reopen()

    override fun onOpenClipboardLink(workspaceId: String) {
        // The button is off while the clipboard holds no link.
        val url = components.clipboardHandler.extractURL()?.takeIf { it.isUrl() }?.toNormalizedUrl() ?: return
        // Like the tabs opened by hand, it opens in the workspace's container, except in the workspace of a device on the
        // account, which only holds tabs without one.
        val toDevice = repository.state.value.isDeviceWorkspace(workspaceId)
        val tabId = tabsUseCases.addTab(
            url = url,
            source = if (toDevice) SessionState.Source.Internal.None else SessionState.Source.Internal.UserEntered,
        )
        repository.assignTab(tabId, workspaceId)
        openTab(tabId)
    }

    override fun onChangeContainer(targets: ActionTargets, pick: ContainerPick) = runner.onChangeContainer(targets, pick)

    override fun onManageContainers() = runner.onManageContainers()

    override fun onDrop(workspaceId: String, selection: Selection, target: DropTarget) {
        val state = repository.state.value
        // The workspace of a device on the account follows the device, which takes nothing dragged here.
        if (state.isDeviceWorkspace(workspaceId)) return
        val targets = ActionTargets.of(state, store.state.normalTabs, selection)
        // Dragged here from another workspace: the items come over first, then land where they were dropped.
        val from = targets.workspaceIn(state)
        if (target != DropTarget.Essentials && from != null && from != workspaceId) {
            repository.moveToWorkspace(
                tabIds = targets.tabs.map { it.id }.toSet(),
                itemIds = targets.itemIds,
                workspaceId = workspaceId,
            )
        }
        val placement = when (target) {
            is DropTarget.IntoFolder -> PinPlacement.Into(target.folderId)
            is DropTarget.NextToPin -> PinPlacement.Next(target.pinId, target.after)
            is DropTarget.PinnedEdge -> PinPlacement.Edge(target.atEnd)
            DropTarget.Essentials -> {
                runner.addToEssentials(targets)
                return
            }
            is DropTarget.SplitWith -> {
                val dragged = targets.tabs.singleOrNull()?.takeIf { it.id != target.tabId } ?: return
                if (store.state.findTab(target.tabId) == null) return
                repository.createSplit(target.tabId, dragged.id, SplitSpace.preferred(SplitSpace.supported(context)))
                store.dispatch(TabListAction.MoveTabsAction(listOf(dragged.id), target.tabId, placeAfter = true))
                return
            }
            is DropTarget.NextToTab, DropTarget.UnpinnedStart -> null
        }
        if (placement != null) {
            unsplitLeaving(state, (targets.tabs + targets.pinnedTabs).map { it.id }, targets.openTabs.map { it.id })
            repository.placePins(workspaceId, targets.itemIds, targets.tabs.map { it.toPinSource() }, placement)
            return
        }
        if (targets.folders.isNotEmpty()) return

        // Unpinning: open pinned tabs become normal tabs, closed ones are reopened without loading.
        val moving = targets.tabs.map { it.id } + targets.pinnedTabs.map { it.id } + runner.reopenClosed(targets.pins)
        unsplitLeaving(state, moving, moving)
        repository.unpin(targets.pins.map { it.id }.toSet())
        repository.moveToWorkspace(tabIds = moving.toSet(), itemIds = emptySet(), workspaceId = workspaceId)
        val others = store.state.normalTabs.filter {
            it.id !in moving && state.workspaceOf(it.id) == workspaceId && state.pinOf(it.id) == null
        }
        val (anchor, after) = when (target) {
            is DropTarget.NextToTab -> target.tabId to target.after
            else -> others.firstOrNull()?.id to false
        }
        if (anchor != null && moving.isNotEmpty()) {
            store.dispatch(TabListAction.MoveTabsAction(moving, anchor, after))
        }
    }

    /**
     * Like in Zen, a tab dragged away from the tab it is split with leaves the split view: those of [dragged] whose
     * partner is not among [moving], which also holds the tabs inside dragged folders.
     */
    private fun unsplitLeaving(state: WorkspaceState, dragged: List<String>, moving: List<String>) {
        val leaving = dragged.filter { id -> state.splitOf(id)?.tabIds?.any { it != id && it !in moving } == true }
        if (leaving.isNotEmpty()) repository.unsplit(leaving.toSet())
    }

    override fun onMoveEssential(pinId: String, index: Int) =
        repository.moveEssential(pinId, index, settings.essentialsPerContainer.value)

    override fun onClearData(quit: Boolean): Set<DeleteBrowsingDataOnQuitType> =
        ClearData.run(context, showChoices = { ClearData.showChoices(navController) }, quit, onDataCleared)

    override fun onClearUnpinned(workspaceId: String) {
        val state = repository.state.value
        val tabIds = store.state.normalTabs
            .filter { state.workspaceOf(it.id) == workspaceId && state.pinOf(it.id) == null }
            .map { it.id }
        if (tabIds.isEmpty()) return
        tabsUseCases.removeTabs(tabIds)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = context.resources.getQuantityString(R.plurals.dejavu_tabs_closed, tabIds.size, tabIds.size),
                actionLabel = context.getString(R.string.dejavu_undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) tabsUseCases.undo()
        }
    }

    override fun onToggleFolder(folderId: String) = repository.toggleFolder(folderId)

    override fun onSearchClick() {
        NewTabContainerChoice.clear()
        startSearch()
    }

    override fun onNewPrivateTab() {
        NewTabContainerChoice.setPrivate()
        startSearch()
    }

    /** Opens the search screen, right where it is when the search bar is at the bottom of the home screen. */
    private fun startSearch() {
        if (settings.homeSearchAtBottom.value) DejavuSearchStart.fromBottom()
        components.appStore.dispatch(SearchStarted())
    }

    override fun onClosePrivateTabs() = tabsUseCases.removePrivateTabs()

    override fun onCloseTab(tabId: String) = tabsUseCases.removeTab(tabId)

    override fun onNewTabInContainer(pick: ContainerPick) {
        NewTabContainerChoice.set(pick)
        startSearch()
    }

    override fun onAccountClick() {
        val directions = if (components.backgroundServices.accountManager.authenticatedAccount() != null) {
            NavGraphDirections.actionGlobalAccountSettingsFragment()
        } else {
            NavGraphDirections.actionGlobalTurnOnSync(entrypoint = FenixFxAEntryPoint.HomeMenu)
        }
        openPage(PageGenie.Button.ACCOUNT, directions)
    }

    override fun onSettingsClick() =
        openPage(PageGenie.Button.SETTINGS, NavGraphDirections.actionGlobalSettingsFragment())

    override fun onDownloadsClick() =
        openPage(PageGenie.Button.DOWNLOADS, NavGraphDirections.actionGlobalDownloadsFragment())

    override fun onExtensionsClick() {
        navController.navigate(NavGraphDirections.actionGlobalMenuDialogFragment(accesspoint = MenuAccessPoint.Home))
    }

    override fun onBookmarksClick() =
        openPage(PageGenie.Button.BOOKMARKS, NavGraphDirections.actionGlobalBookmarkFragment(BookmarkRoot.Mobile.id))

    override fun onHistoryClick() = openPage(PageGenie.Button.HISTORY, NavGraphDirections.actionGlobalHistoryFragment())

    /** Opens the page of [button], which pours out of it, without the page's own way of coming in. */
    private fun openPage(button: PageGenie.Button, directions: NavDirections) =
        PageGenie.open(context, button) { navController.navigate(directions, NavOptions.Builder().build()) }

    private fun TabSessionState.toPinSource() = PinSource(id, content.url, content.title, contextId)
}

/** Names of the folders around [item], outermost first, separated by "/". */
internal fun WorkspaceState.folderPathOf(item: PinnedItem): String {
    val names = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var parentId = item.parentId
    while (parentId != null && seen.add(parentId)) {
        val parent = pins.firstOrNull { it.id == parentId } ?: break
        names.add(0, parent.title)
        parentId = parent.parentId
    }
    return names.joinToString("/")
}
