/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.containers

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContainerAction
import mozilla.components.browser.state.action.RestoreCompleteAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.ext.components

/**
 * Temporary containers, like Firefox's Temporary Containers add-on: made on demand for new tabs, named "tmp1", "tmp2"
 * and so on with the lowest free number, and destroyed with their cookies and site data once their last tab is
 * closed.
 */
object TemporaryContainers {
    private const val NAME_PREFIX = "tmp"

    /** Starts a new temporary container and returns its context ID. */
    fun create(store: Store<BrowserState, BrowserAction>, storage: DejavuContainerStorage): String {
        val taken = store.state.containers.values.filter { storage.showsAsTemporary(it.contextId) }.map { it.name }.toSet()
        val number = generateSequence(1) { it + 1 }.first { "$NAME_PREFIX$it" !in taken }
        val contextId = UUID.randomUUID().toString()
        storage.markTemporary(contextId)
        store.dispatch(
            ContainerAction.AddContainerAction(
                ContainerState(
                    contextId = contextId,
                    name = "$NAME_PREFIX$number",
                    color = ContainerColor.WHITE.acColor,
                    icon = ContainerState.Icon.CIRCLE,
                ),
            ),
        )
        return contextId
    }

    /**
     * The container to open a tab of container [contextId] in: that one while it exists, or while the containers are
     * not read from disk yet. One that was removed gives way to a new temporary container when [temporaryByDefault], so
     * new tabs open in one, or else to none.
     */
    fun orReplacement(
        contextId: String?,
        store: Store<BrowserState, BrowserAction>,
        storage: DejavuContainerStorage,
        temporaryByDefault: Boolean,
    ): String? {
        val containers = storage.records.value
        return when {
            contextId == null || containers == null -> contextId
            storage.isTemporary(contextId) || containers.any { it.contextId == contextId } -> contextId
            temporaryByDefault -> create(store, storage)
            else -> null
        }
    }
}

/**
 * Destroys temporary containers once no tab uses them: a while after their last tab is closed, so that the close can
 * still be undone, and at startup for the ones left without tabs. A closed tab brought back later, from the recently
 * closed tabs, opens in a new temporary container instead of the destroyed one.
 */
internal class TemporaryContainerMiddleware(private val context: Context) : Middleware<BrowserState, BrowserAction> {
    private val handler = Handler(Looper.getMainLooper())
    private var store: Store<BrowserState, BrowserAction>? = null
    private val check = Runnable { store?.let(::destroyUnused) }

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        next(if (action is TabListAction.RestoreAction) withLiveContainers(store, action) else action)
        val removesTabs = action is TabListAction.RemoveTabAction ||
            action is TabListAction.RemoveTabsAction ||
            action is TabListAction.RemoveAllTabsAction ||
            action == TabListAction.RemoveAllNormalTabsAction ||
            action == TabListAction.RemoveAllPrivateTabsAction ||
            action is UndoAction.ClearRecoverableTabs ||
            action == RestoreCompleteAction
        if (removesTabs) {
            this.store = store
            handler.removeCallbacks(check)
            handler.postDelayed(check, GRACE_MS)
        }
    }

    /**
     * [action] with each tab whose container was removed, like a temporary container destroyed with its cookies after
     * the tab closed, in a new temporary container: a destroyed one never comes back. Tabs that shared a container
     * share the new one.
     */
    private fun withLiveContainers(
        store: Store<BrowserState, BrowserAction>,
        action: TabListAction.RestoreAction,
    ): TabListAction.RestoreAction {
        val storage = DejavuContainerStorage.get(context)
        val live = mutableMapOf<String, String>()
        val tabs = action.tabs.map { tab ->
            val contextId = tab.state.contextId ?: return@map tab
            val liveId = live.getOrPut(contextId) {
                TemporaryContainers.orReplacement(contextId, store, storage, temporaryByDefault = true) ?: contextId
            }
            if (liveId == contextId) tab else tab.copy(state = tab.state.copy(contextId = liveId))
        }
        return if (tabs == action.tabs) action else action.copy(tabs = tabs)
    }

    private fun destroyUnused(store: Store<BrowserState, BrowserAction>) {
        val storage = DejavuContainerStorage.get(context)
        val state = store.state
        val used = usedContextIds(state)
        state.containers.keys.filter { storage.isTemporary(it) && it !in used }.forEach { contextId ->
            context.components.core.geckoRuntime.storageController.clearDataForSessionContext(contextId)
            store.dispatch(ContainerAction.RemoveContainerAction(contextId))
            WorkspaceRepository.peek()?.replaceContainer(contextId, null)
            storage.forgetTemporary(contextId)
        }
    }

    internal companion object {
        /**
         * The containers of open tabs and of tabs undo can bring back. Recently closed tabs keep none: a temporary
         * container and its cookies are deleted when its last tab closes, as Settings says.
         */
        fun usedContextIds(state: BrowserState): Set<String> =
            (state.tabs + state.customTabs).mapNotNull { it.contextId }.toSet() +
                state.undoHistory.tabs.mapNotNull { it.state.contextId }

        /** Longer than the time a closed tab can be brought back with undo. */
        private const val GRACE_MS = 20_000L
    }
}
