/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.feature.containers.ContainerMiddleware
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.dejavu.browser.SplitViewMiddleware
import org.mozilla.fenix.dejavu.browser.TabSleepMiddleware
import org.mozilla.fenix.dejavu.browser.UBlockOriginPromptMiddleware
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.TemporaryContainerMiddleware
import org.mozilla.fenix.dejavu.containers.TemporaryContainers
import org.mozilla.fenix.dejavu.history.ClosedTabOrigins
import org.mozilla.fenix.dejavu.history.ClosedTabOriginsMiddleware
import org.mozilla.fenix.dejavu.history.VisitOrigins
import org.mozilla.fenix.dejavu.history.VisitOriginsMiddleware
import org.mozilla.fenix.dejavu.history.WorkspaceHistoryMiddleware
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.sync.DejavuSync
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.ext.components

/**
 * Middleware Dejavu adds to Fenix's browser store: android-components' container support backed by Dejavu's storage,
 * and [WorkspaceContainerMiddleware]. Also loads Dejavu's data and starts syncing workspaces with Zen, off the main
 * thread, then forgets the visited pages that history no longer has, like after it was deleted on quit.
 * [PrivateNewTabMiddleware] goes before all of Fenix's own.
 */
fun dejavuBrowserMiddleware(context: Context): List<Middleware<BrowserState, BrowserAction>> {
    val isVisited: suspend (List<String>) -> List<Boolean> = { urls ->
        context.components.core.historyStorage.getVisited(urls)
    }
    CoroutineScope(Dispatchers.IO).launch {
        val repository = WorkspaceRepository.get(context)
        DejavuSettings.get(context)
        DejavuContainerStorage.get(context).load()
        val origins = VisitOrigins.get(context).apply { load() }
        ClosedTabOrigins.get(context).load()
        DejavuSync.install(context)
        origins.forgetUnvisited(isVisited)
        repository.forgetUnvisitedOpenPages(isVisited)
    }
    return listOf(
        ContainerMiddleware(context, containerStorage = DejavuContainerStorage.get(context)),
        WorkspaceContainerMiddleware(context),
        TemporaryContainerMiddleware(context),
        SilentlyClosedTabsMiddleware(),
        ClosedTabOriginsMiddleware(
            origins = ClosedTabOrigins.get(context),
            isTemporary = { DejavuContainerStorage.get(context).showsAsTemporary(it) },
            newTemporary = { store -> TemporaryContainers.create(store, DejavuContainerStorage.get(context)) },
        ),
        ClosingPinnedTabsMiddleware(),
        RemovedAllTabsMiddleware { syncIds -> DejavuSync.forgetTabs(context, syncIds) },
        PageOpenedTabsMiddleware(),
        SplitViewMiddleware(),
        VisitOriginsMiddleware(VisitOrigins.get(context), isVisited),
        WorkspaceHistoryMiddleware(),
        TabSleepMiddleware(),
        UBlockOriginPromptMiddleware(),
    )
}

/**
 * Opens the next new tab the user starts as a private tab when [NewTabContainerChoice] asks for it, as the Private chip
 * above the address bar does. It comes first among the browser's middleware, so that none of the others, the one that
 * records history included, ever sees the tab as a normal one.
 */
internal class PrivateNewTabMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is TabListAction.AddTabAction && action.tab.isStartedByUser() &&
            NewTabContainerChoice.peek()?.private == true
        ) {
            NewTabContainerChoice.consume()
            next(action.copy(tab = action.tab.copy(content = action.tab.content.copy(private = true))))
            return
        }
        next(action)
    }
}

/**
 * Remembers the page a pinned tab is on as the user closes its tab, so the pin opens there again instead of on its
 * pinned address, the way a sleeping tab wakes up where it was. Tabs removed all at once are left to
 * [RemovedAllTabsMiddleware].
 */
internal class ClosingPinnedTabsMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        val closing = when (action) {
            is TabListAction.RemoveTabAction -> listOf(action.tabId)
            is TabListAction.RemoveTabsAction -> action.tabIds
            else -> emptyList()
        }
        val repository = WorkspaceRepository.peek()
        if (closing.isNotEmpty() && repository != null) {
            val pinnedTabIds = repository.state.value.pins.mapNotNullTo(HashSet()) { it.tabId }
            val pages = closing.filter { it in pinnedTabIds }.mapNotNull { id ->
                store.state.findTab(id)?.let { tab -> id to (tab.content.url to tab.content.title) }
            }.toMap()
            if (pages.isNotEmpty()) repository.rememberClosingPages(pages)
        }
        next(action)
    }
}

/**
 * Handles tabs removed all at once, like by Delete browsing data, which the user did not close one by one. Sync
 * forgets the ones that are not pinned, through [forgetTabs] with the ids they sync under, rather than closing them on
 * the other devices, and pinned tabs open on their pinned page again, leaving no trace of where they were.
 */
internal class RemovedAllTabsMiddleware(
    private val forgetTabs: (Collection<String>) -> Unit,
) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        val repository = WorkspaceRepository.peek()
        if (repository != null &&
            (action is TabListAction.RemoveAllTabsAction || action is TabListAction.RemoveAllNormalTabsAction)
        ) {
            val state = repository.state.value
            val (pinned, unpinned) = store.state.tabs
                .filterNot { it.content.private }
                .partition { state.pinOf(it.id) != null }
            forgetTabs(unpinned.map { state.syncIdOf(it.id) })
            repository.forgetOpenPages(pinned.mapNotNullTo(HashSet()) { state.pinOf(it.id)?.id })
        }
        next(action)
    }
}

/**
 * Marks the tabs a page opens by itself, like pop-ups and links that open a new window, which come with an engine
 * session the page started, see [WorkspaceState.openedByPages].
 */
internal class PageOpenedTabsMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        next(action)
        if (action !is TabListAction.AddTabAction) return
        val tab = action.tab
        if (tab.parentId != null && tab.engineState.engineSession != null && !tab.content.private) {
            WorkspaceRepository.peek()?.markOpenedByPage(tab.id)
        }
    }
}

/**
 * Tabs Dejavu closes on its own, like the old copy of a tab reopened in another container. They are kept out of undo
 * and so never show up in the recently closed tabs.
 */
object SilentlyClosedTabs {
    private val tabIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Marks [tabId], which is about to be closed, as closed by Dejavu. */
    fun add(tabId: String) {
        tabIds.add(tabId)
    }

    internal fun contains(tabId: String) = tabId in tabIds

    internal fun consume(tabId: String) = tabIds.remove(tabId)
}

/**
 * Removes [SilentlyClosedTabs] from the tabs remembered for undo. Tabs left in undo end up in the recently closed tabs,
 * so this has to happen before that list and its storage see them.
 */
internal class SilentlyClosedTabsMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is UndoAction.AddRecoverableTabs && action.tabs.any { SilentlyClosedTabs.contains(it.state.id) }) {
            next(action.copy(tabs = action.tabs.filterNot { SilentlyClosedTabs.consume(it.state.id) }))
        } else {
            next(action)
        }
    }
}

/**
 * The container the next new tab started by the user opens in, chosen by long-pressing "New Tab" on the home screen or
 * with the chips above the address bar. It overrides the workspace's container once, and is dropped when it expires or
 * the search is abandoned.
 */
object NewTabContainerChoice {
    private const val LIFETIME_MS = 10 * 60 * 1000L

    /** A container for the next new tab, or `null` with [private] for a private tab outside any container. */
    internal class Choice(val pick: ContainerPick?, val private: Boolean, val expiresAt: Long)

    @Volatile
    private var choice: Choice? = null

    fun set(pick: ContainerPick) {
        choice = Choice(pick, private = false, expiresAt = SystemClock.elapsedRealtime() + LIFETIME_MS)
    }

    /** Opens the next new tab as a private tab. */
    fun setPrivate() {
        choice = Choice(null, private = true, expiresAt = SystemClock.elapsedRealtime() + LIFETIME_MS)
    }

    fun clear() {
        choice = null
    }

    /** The pending choice, if it has not expired, without forgetting it. */
    internal fun peek(): Choice? = choice?.takeIf { SystemClock.elapsedRealtime() < it.expiresAt }

    /** Returns and forgets the pending choice, if it has not expired. */
    internal fun consume(): Choice? {
        val current = choice ?: return null
        choice = null
        return current.takeIf { SystemClock.elapsedRealtime() < current.expiresAt }
    }
}

/**
 * Opens new tabs in the container of their workspace, like Zen does for workspaces with a default container, in the
 * container picked with [NewTabContainerChoice], or in a new temporary container. Links from other apps open in the
 * workspace and container chosen in settings. Only tabs the user starts (typed, new tab, links from other apps) are
 * changed; tabs that already have a container, private tabs, and tabs opened by a page keep their context.
 */
internal class WorkspaceContainerMiddleware(private val context: Context) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is TabListAction.AddTabAction) {
            val tab = action.tab
            val parentContextId = tab.parentId?.let { store.state.findTab(it)?.contextId }
            when {
                // Tabs opened by a page load in the page's container; record it so the UI shows it.
                parentContextId != null && tab.contextId == null && !tab.content.private -> {
                    next(action.copy(tab = tab.copy(contextId = parentContextId)))
                    return
                }
                tab.canJoinContainer() -> {
                    // A private tab asked for was made private before, by PrivateNewTabMiddleware.
                    val choice = if (tab.isStartedByUser()) NewTabContainerChoice.consume() else null
                    val containerId = containerFor(store, tab, choice?.pick)
                    if (containerId != null && store.state.containers.containsKey(containerId)) {
                        next(action.copy(tab = tab.copy(contextId = containerId)))
                        return
                    }
                }
            }
        }
        next(action)
    }

    private fun containerFor(
        store: Store<BrowserState, BrowserAction>,
        tab: TabSessionState,
        chosen: ContainerPick?,
    ): String? {
        val repository = WorkspaceRepository.peek()
        val settings = DejavuSettings.peek()
        val workspaces = repository?.state?.value
        var workspace = workspaces?.activeWorkspace
        val pick = if (tab.source is SessionState.Source.External) {
            val target = settings?.externalLinkWorkspaceId?.value?.let { id -> workspaces?.workspaces?.firstOrNull { it.id == id } }
            if (target != null) {
                workspace = target
                repository?.assignTab(tab.id, target.id)
                repository?.selectWorkspace(target.id)
            }
            settings?.externalLinkContainer?.value
        } else {
            chosen
        }
        val storage = DejavuContainerStorage.get(context)
        val temporaryByDefault = settings?.temporaryContainersByDefault?.value == true
        return when (pick) {
            ContainerPick.NoContainer -> null
            ContainerPick.Temporary -> TemporaryContainers.create(store, storage)
            is ContainerPick.Container -> pick.contextId
            // A workspace whose container was removed opens its new tabs like one without a container.
            null -> workspace?.containerId
                ?.let { TemporaryContainers.orReplacement(it, store, storage, temporaryByDefault) }
                ?: if (temporaryByDefault) TemporaryContainers.create(store, storage) else null
        }
    }

    private fun TabSessionState.canJoinContainer(): Boolean =
        isNewOwnTab() && (source is SessionState.Source.External || source in userSources)
}

/** Whether this is a new tab the user started here: typed, a new tab, or opened from the home screen. */
private fun TabSessionState.isStartedByUser(): Boolean = isNewOwnTab() && source in userSources

/** Whether this is a new normal tab of its own: not opened by a page, and not in a container yet. */
private fun TabSessionState.isNewOwnTab(): Boolean =
    contextId == null && !content.private && parentId == null && engineState.engineSession == null

private val userSources = setOf(
    SessionState.Source.Internal.UserEntered,
    SessionState.Source.Internal.NewTab,
    SessionState.Source.Internal.HomeScreen,
)
