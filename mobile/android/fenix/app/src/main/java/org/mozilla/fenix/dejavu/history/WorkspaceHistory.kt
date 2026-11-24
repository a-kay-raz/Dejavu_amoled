/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.history

import java.util.concurrent.ConcurrentHashMap
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.RecentlyClosedAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.storage.HistoryMetadataKey
import mozilla.components.concept.storage.PageVisit
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.historymetadata.HistoryMetadataService
import org.mozilla.fenix.historymetadata.toHistoryMetadataKey

/**
 * Keeps the tabs of the workspaces that do not save history, see
 * [org.mozilla.fenix.dejavu.workspaces.Workspace.savesHistory], out of history: their visits and titles
 * ([WorkspaceHistoryMiddleware]), the recent searches and the time spent on pages ([metadataService]), the recently
 * closed tabs ([NoHistoryClosedTabsMiddleware]), and the workspace each page was opened in ([VisitOriginsMiddleware]).
 */
object WorkspaceHistory {
    /** Whether tab [tabId] is in a workspace that does not save history. */
    fun isOff(tabId: String): Boolean = WorkspaceRepository.peek()?.state?.value?.let { isOff(it, tabId) } == true

    /** Whether tab [tabId] is in a workspace of [state] that does not save history. */
    fun isOff(state: WorkspaceState, tabId: String): Boolean {
        val workspaceId = state.pinOf(tabId)?.workspaceId ?: state.workspaceOf(tabId)
        return state.workspaces.firstOrNull { it.id == workspaceId }?.savesHistory == false
    }

    /** [service], which records nothing for the tabs of the workspaces that do not save history. */
    fun metadataService(service: HistoryMetadataService): HistoryMetadataService = NoHistoryMetadataService(service)
}

/**
 * Gives each tab's engine session a [HistoryTrackingDelegate] that saves nothing while the tab is in a workspace that
 * does not save history. It asks at every visit, so a tab moved to another workspace follows that workspace's choice.
 */
internal class WorkspaceHistoryMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        // Before the session is linked, which loads its page.
        if (action is EngineAction.LinkEngineSessionAction) watch(action)
        next(action)
    }

    private fun watch(action: EngineAction.LinkEngineSessionAction) {
        val settings = action.engineSession.settings
        // Engines without history tracking leave both of these out.
        runCatching {
            val delegate = settings.historyTrackingDelegate ?: return
            val inner = (delegate as? TabHistoryDelegate)?.inner ?: delegate
            settings.historyTrackingDelegate = TabHistoryDelegate(inner, action.tabId)
        }
    }
}

/**
 * [inner], which saves nothing while tab [tabId] is in a workspace that does not save history, as [isOff] tells for a
 * tab.
 */
internal class TabHistoryDelegate(
    val inner: HistoryTrackingDelegate,
    private val tabId: String,
    private val isOff: (String) -> Boolean = WorkspaceHistory::isOff,
) : HistoryTrackingDelegate by inner {
    override fun shouldStoreUri(uri: String): Boolean = !isOff(tabId) && inner.shouldStoreUri(uri)

    override suspend fun onVisited(uri: String, visit: PageVisit) {
        if (!isOff(tabId)) inner.onVisited(uri, visit)
    }

    override suspend fun onTitleChanged(uri: String, title: String) {
        if (!isOff(tabId)) inner.onTitleChanged(uri, title)
    }

    override suspend fun onPreviewImageChange(uri: String, previewImageUrl: String) {
        if (!isOff(tabId)) inner.onPreviewImageChange(uri, previewImageUrl)
    }
}

/** [inner], which records nothing for the tabs of the workspaces that do not save history. */
private class NoHistoryMetadataService(private val inner: HistoryMetadataService) : HistoryMetadataService by inner {
    override fun createMetadata(tab: TabSessionState, searchTerms: String?, referrerUrl: String?): HistoryMetadataKey {
        if (!WorkspaceHistory.isOff(tab.id)) return inner.createMetadata(tab, searchTerms, referrerUrl)
        return tab.historyMetadata?.takeIf { it.url == tab.content.url }
            ?: tab.toHistoryMetadataKey(searchTerms, referrerUrl)
    }

    override fun updateMetadata(key: HistoryMetadataKey, tab: TabSessionState) {
        if (!WorkspaceHistory.isOff(tab.id)) inner.updateMetadata(key, tab)
    }
}

/**
 * Keeps the closed tabs of the workspaces that do not save history out of the recently closed tabs; undo still brings
 * them back. Tabs reach undo while they are still open, so their workspace is known there. It comes before the
 * browser's middleware that saves the recently closed tabs.
 */
internal class NoHistoryClosedTabsMiddleware(
    private val workspaces: () -> WorkspaceState? = { WorkspaceRepository.peek()?.state?.value },
) : Middleware<BrowserState, BrowserAction> {
    private val leaving: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        when (action) {
            is UndoAction.AddRecoverableTabs -> {
                val state = workspaces()
                action.tabs.map { it.state.id }.forEach { id ->
                    if (state != null && WorkspaceHistory.isOff(state, id)) leaving.add(id) else leaving.remove(id)
                }
            }
            is RecentlyClosedAction.AddClosedTabsAction -> {
                if (action.tabs.any { it.state.id in leaving }) {
                    val kept = action.tabs.filterNot { leaving.remove(it.state.id) }
                    if (kept.isNotEmpty()) next(action.copy(tabs = kept))
                    return
                }
            }
            else -> Unit
        }
        next(action)
    }
}
