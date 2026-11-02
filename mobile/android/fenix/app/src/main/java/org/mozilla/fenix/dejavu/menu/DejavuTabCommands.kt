/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.appstate.AppAction.ReaderViewAction.ReaderViewDismissed
import org.mozilla.fenix.components.appstate.AppAction.ReaderViewAction.ReaderViewStarted
import org.mozilla.fenix.dejavu.NewTabContainerChoice
import org.mozilla.fenix.dejavu.actions.ActionContext
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.CustomActionRunner
import org.mozilla.fenix.dejavu.browser.AmoledPages
import org.mozilla.fenix.dejavu.browser.DejavuSearchStart
import org.mozilla.fenix.dejavu.browser.NewTabSearch
import org.mozilla.fenix.dejavu.browser.SavePage
import org.mozilla.fenix.dejavu.browser.SearchBackdrop
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.home.folderPathOf
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.workspaces.MAX_ESSENTIALS
import org.mozilla.fenix.dejavu.workspaces.PinSource
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

/** The entries of the "More" menu that only Dejavu has, carried out on [tab]. */
internal class DejavuTabCommands(
    context: Context,
    private val components: Components,
    private val repository: WorkspaceRepository,
    private val settings: DejavuSettings,
    private val tab: TabSessionState,
) {
    private val appContext = context.applicationContext
    private val sessionUseCases = components.useCases.sessionUseCases

    /**
     * Carries out [item] if it is one of Dejavu's own entries.
     *
     * @return Whether [item] was carried out.
     */
    fun run(item: MoreMenuItem, workspaces: WorkspaceState): Boolean {
        val pin = workspaces.pinOf(tab.id)
        when (item) {
            MoreMenuItem.AMOLED_PAGES -> AmoledPages.toggle(appContext, tab)
            MoreMenuItem.SAVE_PAGE -> SavePage.save(appContext, tab)
            MoreMenuItem.SAVE_AS_PDF -> sessionUseCases.saveToPdf(tab.id)
            MoreMenuItem.PRINT -> sessionUseCases.printContent(tab.id)
            MoreMenuItem.PIN_TAB ->
                if (pin != null && !pin.essential) repository.unpin(setOf(pin.id)) else repository.pinTabs(listOf(tab.toPinSource()))
            MoreMenuItem.ESSENTIAL_TAB -> toggleEssential(workspaces)
            MoreMenuItem.RESET_PINNED_URL -> pin?.url?.let { sessionUseCases.loadUrl(it, tab.id) }
            MoreMenuItem.REPLACE_PINNED_URL -> pin?.let { repository.replacePinUrl(it.id, tab.content.url, tab.content.title) }
            MoreMenuItem.SPLIT_VIEW -> repository.unsplit(setOf(tab.id))
            MoreMenuItem.READER_VIEW ->
                components.appStore.dispatch(if (tab.readerState.active) ReaderViewDismissed else ReaderViewStarted)
            else -> return false
        }
        return true
    }

    /**
     * Shows [tab] and [other] together, the way [way] has them, and next to each other in the tab list when neither is
     * pinned.
     */
    fun split(workspaces: WorkspaceState, other: TabSessionState, way: SplitOrientation) {
        repository.createSplit(tab.id, other.id, way)
        if (workspaces.pinOf(tab.id) == null && workspaces.pinOf(other.id) == null) {
            components.core.store.dispatch(TabListAction.MoveTabsAction(listOf(other.id), tab.id, placeAfter = true))
        }
    }

    fun runCustomAction(action: CustomAction, workspaces: WorkspaceState) {
        if (tab.content.private) return
        val pin = workspaces.pinOf(tab.id)
        val workspaceId = pin?.workspaceId ?: workspaces.workspaceOf(tab.id)
        val container = tab.contextId?.let { id ->
            DejavuContainerStorage.get(appContext).records.value?.firstOrNull { it.contextId == id }?.name
        }
        val actionContext = ActionContext(
            url = tab.content.url,
            title = tab.content.title,
            container = container.orEmpty(),
            workspace = workspaces.workspaces.firstOrNull { it.id == workspaceId }?.name.orEmpty(),
            folderPath = pin?.let { workspaces.folderPathOf(it) }.orEmpty(),
            date = SimpleDateFormat(ISO_DATE_PATTERN, Locale.US).format(Date()),
        )
        components.applicationScope.launch(Dispatchers.Main) {
            val result = CustomActionRunner(components.core.client).run(action, listOf(actionContext))
            Toast.makeText(appContext, result.message(appContext, action, 1), Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleEssential(workspaces: WorkspaceState) {
        val pin = workspaces.pinOf(tab.id)
        if (pin?.essential == true) {
            repository.removeFromEssentials(setOf(pin.id), workspaces.workspaceOf(tab.id))
            return
        }
        val before = repository.state.value.essentials.size
        repository.addToEssentials(
            sources = if (pin == null) listOf(tab.toPinSource()) else emptyList(),
            pinIds = setOfNotNull(pin?.id),
            perContainer = settings.essentialsPerContainer.value,
        )
        if (repository.state.value.essentials.size == before) {
            Toast.makeText(appContext, appContext.getString(R.string.dejavu_essentials_full, MAX_ESSENTIALS), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun TabSessionState.toPinSource() = PinSource(id, content.url, content.title, contextId)

    private companion object {
        const val ISO_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX"
    }
}

/**
 * Opens the home screen with the address bar ready for a new tab, which is private when [tab], the shown tab, is:
 * what the user enters there opens in the new tab.
 */
internal fun openNewTab(navController: NavController, tab: TabSessionState) {
    if (tab.content.private) NewTabContainerChoice.setPrivate() else NewTabContainerChoice.clear()
    if (navController.currentDestination?.id != R.id.browserFragment) {
        NewTabSearch.clear()
        navController.navigate(NavGraphDirections.actionGlobalHome(focusOnAddressBar = true))
        return
    }
    NewTabSearch.start(tab.id)
    if (DejavuSettings.get(navController.context).barsLayout.value.addressBarAtBottom) DejavuSearchStart.fromBottom()
    SearchBackdrop.capturePage(tab.id) {
        navController.navigate(
            NavGraphDirections.actionGlobalHome(focusOnAddressBar = true, sessionToStartSearchFor = tab.id),
        )
    }
}
