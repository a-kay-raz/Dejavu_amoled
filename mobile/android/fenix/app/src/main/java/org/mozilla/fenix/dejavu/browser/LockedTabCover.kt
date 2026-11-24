/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flow
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.LockCover
import org.mozilla.fenix.dejavu.ui.awaitWindowFocus
import org.mozilla.fenix.dejavu.ui.mayAskToUnlock
import org.mozilla.fenix.dejavu.ui.rememberUnlockPrompt
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceLock
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Covers the browser, its bars included, while it shows a tab of a locked workspace that is not unlocked, see
 * [WorkspaceLock]. The cover asks to unlock by itself once, and also leads back to the workspaces. It follows the lock
 * while the browser is in the background too, so the page is already covered when Dejavu shows again. Custom tabs
 * are left alone.
 */
fun Fragment.installLockedTabCover(customTabSessionId: String?, root: ViewGroup) {
    if (customTabSessionId != null) return
    val components = requireComponents
    val locked = mutableStateOf<Workspace?>(null)
    val cover = ComposeView(requireContext()).apply {
        isVisible = false
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            FirefoxTheme {
                val workspace by locked
                workspace?.let { shown ->
                    LockedTab(shown) {
                        this@installLockedTabCover.findNavController().navigate(NavGraphDirections.actionGlobalHome())
                    }
                }
            }
        }
    }
    root.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val context = requireContext().applicationContext
    // The page and the bars under the cover stay out of reach of screen readers too, as they were before it.
    val hidden = mutableMapOf<View, Int>()
    fun hideFromScreenReaders(hide: Boolean) {
        if (hide) {
            root.children.filter { it !== cover && it !in hidden }.forEach { view ->
                hidden[view] = view.importantForAccessibility
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        } else {
            hidden.forEach { (view, importance) -> view.importantForAccessibility = importance }
            hidden.clear()
        }
    }
    viewLifecycleOwner.lifecycleScope.launch {
        val repository = withContext(Dispatchers.IO) { WorkspaceRepository.get(context) }
        val shownTab = components.core.store.flow().map { it.selectedTab?.takeUnless { tab -> tab.content.private }?.id }
        combine(shownTab.distinctUntilChanged(), repository.state, WorkspaceLock.unlocked) { tabId, state, unlocked ->
            tabId?.let { WorkspaceLock.hiddenWorkspaceOf(state, it, unlocked) }
        }.distinctUntilChanged().collect { workspace ->
            locked.value = workspace
            cover.isVisible = workspace != null
            // Views the browser adds later, like its bars, would come above it.
            if (workspace != null) cover.bringToFront()
            hideFromScreenReaders(workspace != null)
        }
    }
}

/**
 * Whether the browser shows a tab of a locked workspace, so [installLockedTabCover] covers it, or a private tab hidden
 * since Dejavu was left, which [leaveHiddenPrivateTab] leaves.
 */
internal fun Fragment.showsLockedTab(): Boolean {
    val tab = requireComponents.core.store.state.selectedTab ?: return false
    if (tab.content.private) return WorkspaceLock.privateHidden.value
    val state = WorkspaceRepository.peek()?.state?.value ?: return false
    return WorkspaceLock.hiddenWorkspaceOf(state, tab.id) != null
}

/**
 * Leaves a private tab for the home screen once the private tabs lock, or hide as Dejavu was left, see
 * [WorkspaceLock.privateHidden]: they show there as the private workspace, blurred under its cover like a locked
 * workspace, instead of Firefox's own screen to unlock them, which custom tabs keep. It leaves as the browser starts
 * again, before the page shows.
 */
fun Fragment.leaveHiddenPrivateTab(customTabSessionId: String?) {
    if (customTabSessionId != null) return
    val components = requireComponents
    val owner = viewLifecycleOwner
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val shownPrivate = components.core.store.flow().map { it.selectedTab?.content?.private == true }
            val locked = components.appStore.flow().map { it.isPrivateScreenLocked && it.mode.isPrivate }
            combine(shownPrivate, locked, WorkspaceLock.privateHidden) { private, isLocked, hidden ->
                private && (isLocked || hidden)
            }.distinctUntilChanged().filter { it }.collect {
                findNavController().navigate(NavGraphDirections.actionGlobalHome())
            }
        }
    }
}

/**
 * [filter] of the open tabs the search suggests switching to, which also leaves out the pages only tabs of locked
 * workspaces show, until those are unlocked.
 */
fun withoutLockedTabs(store: BrowserStore, filter: ((Uri) -> Boolean)?): (Uri) -> Boolean = { uri ->
    filter?.invoke(uri) != false && !showsOnlyInLockedTabs(store, uri.toString())
}

private fun showsOnlyInLockedTabs(store: BrowserStore, url: String): Boolean {
    val state = WorkspaceRepository.peek()?.state?.value ?: return false
    val tabs = store.state.normalTabs.filter { it.content.url.toUri().toString() == url }
    return tabs.isNotEmpty() && tabs.all { WorkspaceLock.hiddenWorkspaceOf(state, it.id) != null }
}

@Composable
private fun LockedTab(workspace: Workspace, onLeave: () -> Unit) {
    val context = LocalContext.current
    val prompt = rememberUnlockPrompt()
    val unlock = { automatic: Boolean ->
        prompt.ask(context.getString(R.string.dejavu_workspace_unlock_title, workspace.name), automatic) {
            WorkspaceLock.unlock(workspace.id)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    // Asked again each time Dejavu shows, as coming back from other apps or the phone's lock screen.
    LaunchedEffect(workspace.id) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            view.awaitWindowFocus()
            if (context.mayAskToUnlock() && WorkspaceLock.askOnce(workspace.id)) unlock(true)
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LockCover(
            title = stringResource(R.string.dejavu_workspace_locked, workspace.name),
            onUnlock = { unlock(false) },
            onLeave = onLeave,
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
        )
    }
}
