/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.findNavController
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent.Source
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.appstate.AppAction.FindInPageAction
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.toolbar.DisplayActions
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.browser.ClearData
import org.mozilla.fenix.dejavu.browser.DejavuToolbar
import org.mozilla.fenix.dejavu.browser.SplitScreen
import org.mozilla.fenix.dejavu.browser.SplitSpace
import org.mozilla.fenix.dejavu.browser.navigationBarRoomPadding
import org.mozilla.fenix.dejavu.browser.openDejavuMenu
import org.mozilla.fenix.dejavu.browser.shownPopupTheme
import org.mozilla.fenix.dejavu.browser.tabOrShown
import org.mozilla.fenix.dejavu.browser.workspaceTheme
import org.mozilla.fenix.dejavu.discover.MENU_SWIPE
import org.mozilla.fenix.dejavu.discover.Tip
import org.mozilla.fenix.dejavu.discover.TipGesture
import org.mozilla.fenix.dejavu.discover.TipHost
import org.mozilla.fenix.dejavu.discover.TipScreen
import org.mozilla.fenix.dejavu.discover.isNewAmong
import org.mozilla.fenix.dejavu.discover.newDot
import org.mozilla.fenix.dejavu.discover.rememberUnseenNewThings
import org.mozilla.fenix.dejavu.discover.tipTarget
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.settings.PageBackdrop
import org.mozilla.fenix.dejavu.ui.TapIcon
import org.mozilla.fenix.dejavu.ui.rememberIconTaps
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.ext.components as contextComponents
import org.mozilla.fenix.ext.nav

/**
 * Dejavu's bar below web pages: the buttons the user chose for it, Home and Search among them, on a rounded bar in the
 * colors of the tab's workspace that floats over the bottom of the page. Swiping the bar up opens the "More" menu.
 *
 * @param onEvent Carries out what the user tapped.
 * @param tabId The tab the bar is for, the shown tab when `null`.
 */
@Composable
fun DejavuActionsBar(onEvent: (BrowserToolbarEvent) -> Unit, tabId: String? = null) {
    val context = LocalContext.current
    val fenix = components
    val settings = remember { fenix.readOnMainThread { DejavuSettings.get(context) } }
    val barKeys by settings.actionsBarKeys.collectAsState()
    val customActions by settings.customActions.collectAsState()
    val entries = remember(barKeys, customActions) { ActionsBarLayout.resolve(barKeys, customActions) }
    val barTab = rememberBarTab(tabId)
    val state = barTab.state
    var asking by remember { mutableStateOf<TabAction?>(null) }
    val unseen = rememberUnseenNewThings()

    Box(
        modifier = Modifier
            // Pages run behind the navigation bar, so the bar keeps above it, also while it is hidden.
            .navigationBarRoomPadding()
            .padding(start = BAR_SIDE_MARGIN, end = BAR_SIDE_MARGIN, bottom = ACTIONS_BAR_BOTTOM_MARGIN)
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .tipTarget(Tip.PAGE_MENU, TipGesture.Swipe(dy = -MENU_SWIPE), enabled = tabId == null, drawsItself = true)
            .clip(BAR_SHAPE)
            .background(MaterialTheme.colorScheme.surface)
            .workspaceTheme(shownPopupTheme(tabId))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = BORDER_ALPHA), BAR_SHAPE),
    ) {
        val buttons = entries.forSplitWays(state.splitWays, state.isSplit).map { entry ->
            BarItem(entry.look(state), entry.isNewAmong(unseen)) {
                entry.tap(state, Source.NavigationBar, onEvent) { asking = it }
            }
        }
        // Screen readers cannot swipe the bar up, so every button also offers the menu as an action.
        val menuAction = listOf(
            CustomAccessibilityAction(stringResource(R.string.content_description_menu)) {
                onEvent(DejavuToolbar.MenuRequested)
                true
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            buttons.forEach { BarButton(it, menuAction, Modifier.weight(1f)) }
        }
        // Swiping the bar up opens the menu, which a tip shows, though not below the pages of a split view.
        if (tabId == null) TipHost(TipScreen.PAGE, allowed = SplitScreen.shown == null)
    }

    AskingDialog(barTab, asking) { asking = null }
}

/** The tab the buttons of a bar act on, and what they show about it. */
internal class BarTab(val tab: TabSessionState?, val state: MoreMenuState)

/**
 * The parts of a tab that the buttons of a bar show. A tab changes with every step of a page load and every tracker
 * blocked; the bars follow only these.
 */
private data class BarTabParts(
    val id: String,
    val url: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val loading: Boolean,
    val desktopMode: Boolean,
    val private: Boolean,
    val isPdf: Boolean,
    val isTranslated: Boolean,
    val readerable: Boolean,
    val inReaderView: Boolean,
) {
    constructor(tab: TabSessionState) : this(
        id = tab.id,
        url = tab.content.url,
        canGoBack = tab.content.canGoBack,
        canGoForward = tab.content.canGoForward,
        loading = tab.content.loading,
        desktopMode = tab.content.desktopMode,
        private = tab.content.private,
        isPdf = tab.content.isPdf,
        isTranslated = tab.translationsState.isTranslated,
        readerable = tab.readerState.readerable,
        inReaderView = tab.readerState.active,
    )
}

/** The tab with [tabId], the shown tab when `null`, as the buttons of a bar act on it. */
@Composable
internal fun rememberBarTab(tabId: String?): BarTab {
    val context = LocalContext.current
    val fenix = components
    val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
    val parts by fenix.core.store.observeAsComposableState { state -> state.tabOrShown(tabId)?.let(::BarTabParts) }
    val tab = remember(parts) { fenix.core.store.state.tabOrShown(tabId) }
    val workspaces by repository.state.collectAsState()
    val snackbar by fenix.appStore.observeAsComposableState { it.snackbarState }
    val url = tab?.content?.url.orEmpty()
    val isBookmarked by produceState(false, url, snackbar) {
        value = url.isNotEmpty() &&
            fenix.core.bookmarksStorage.getBookmarksWithUrl(url).getOrDefault(emptyList()).any { it.url == url }
    }
    val state = tab?.let { current ->
        moreMenuState(
            fenix = fenix,
            tab = current,
            workspaces = workspaces,
            isBookmarked = isBookmarked,
            isDesktopMode = current.content.desktopMode,
        )
    } ?: MoreMenuState()
    return BarTab(tab, state)
}

/**
 * Carries out this entry tapped on a bar, coming from [source]: a tab action that asks first goes to [ask], anything
 * else to [onEvent].
 */
internal fun MoreMenuEntry.tap(
    state: MoreMenuState,
    source: Source,
    onEvent: (BrowserToolbarEvent) -> Unit,
    ask: (TabAction) -> Unit,
) {
    val action = (this as? MoreMenuEntry.Tab)?.action
    if (action?.asksFirst == true) ask(action) else onEvent(barEvent(state, source))
}

/** Asks about [action], tapped on a bar, before it runs on the tab of [barTab], and runs [onDone] once answered. */
@Composable
internal fun AskingDialog(barTab: BarTab, action: TabAction?, onDone: () -> Unit) {
    val view = LocalView.current
    val store = components.core.store
    // The tab as it is when asked: the bar keeps it only as it was when what the bar shows last changed.
    val tab = remember(barTab.tab?.id, action) { barTab.tab?.let { store.state.findTab(it.id) ?: it } }
    if (tab != null && action != null) {
        ShownTabActionDialog(
            action = action,
            tab = tab,
            navController = remember(view) { view.findNavController() },
            onDone = onDone,
        )
    }
}

/**
 * The actions bar below web pages: one bar, or while two pages show side by side, one below each page, as wide as it
 * and acting on it. A button of the page out of focus turns to that page first.
 *
 * @param onEvent Carries out what the user tapped, on the tab in focus.
 */
@Composable
fun DejavuActionsBars(onEvent: (BrowserToolbarEvent) -> Unit) {
    val split = SplitScreen.shown
    val selectedId by components.core.store.observeAsComposableState { it.selectedTabId }
    if (split == null || split.orientation != SplitOrientation.SIDE_BY_SIDE || selectedId !in split.tabIds) {
        DejavuActionsBar(onEvent = onEvent)
        return
    }
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth()) {
        split.tabIds.forEachIndexed { index, tabId ->
            val share = if (index == 0) split.fraction else 1f - split.fraction
            Box(modifier = Modifier.weight(share).alpha(if (tabId == selectedId) 1f else UNFOCUSED_ALPHA)) {
                DejavuActionsBar(
                    onEvent = { event -> SplitScreen.focus(context, tabId) { onEvent(event) } },
                    tabId = tabId,
                )
            }
        }
    }
}

private class BarItem(val look: EntryLook, val isNew: Boolean, val onClick: () -> Unit)

@Composable
private fun BarButton(item: BarItem, a11yActions: List<CustomAccessibilityAction>, modifier: Modifier = Modifier) {
    val look = item.look
    val taps = rememberIconTaps()
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .newDot(item.isNew, inset = BAR_DOT_INSET)
                .clip(CircleShape)
                .background(if (look.active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(enabled = look.enabled, role = Role.Button) {
                    taps.tap()
                    item.onClick()
                }
                .semantics {
                    contentDescription = look.label
                    customActions = a11yActions
                }
                .alpha(if (look.enabled) 1f else DISABLED_ALPHA),
        ) {
            TapIcon(
                icon = look.icon,
                taps = taps,
                tint = if (look.active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What tapping [this] on a bar does, coming from [source]: the address bar's own action where it has one. */
private fun MoreMenuEntry.barEvent(state: MoreMenuState, source: Source): BrowserToolbarEvent {
    if (this == MoreMenuEntry.Home) return DisplayActions.HomepageClicked(source)
    if (this == MoreMenuEntry.Search) return DejavuToolbar.SearchClicked
    return when ((this as? MoreMenuEntry.BuiltIn)?.item) {
        MoreMenuItem.BACK -> DisplayActions.NavigateBackClicked(source)
        MoreMenuItem.FORWARD -> DisplayActions.NavigateForwardClicked
        MoreMenuItem.REFRESH ->
            if (state.isLoading) DisplayActions.StopRefreshClicked else DisplayActions.RefreshClicked(bypassCache = false)
        MoreMenuItem.SHARE -> DisplayActions.ShareClicked(source)
        MoreMenuItem.BOOKMARK_PAGE ->
            if (state.isBookmarked) DisplayActions.EditBookmarkClicked(source) else DisplayActions.AddBookmarkClicked(source)
        MoreMenuItem.TRANSLATE -> DisplayActions.TranslateClicked(source)
        else -> DejavuToolbar.MenuEntryClicked(key)
    }
}

/**
 * Carries out the entry with [key] of the actions bar on the shown tab, for the entries the address bar has no action
 * of its own for.
 */
@Suppress("CyclomaticComplexMethod")
internal fun runActionsBarEntry(context: Context, navController: NavController, key: String) {
    val components = context.contextComponents
    val settings = DejavuSettings.get(context)
    val repository = WorkspaceRepository.get(context)
    val tab = components.core.store.state.selectedTab ?: return
    val workspaces = repository.state.value
    val commands = DejavuTabCommands(context, components, repository, settings, tab)
    val item = when (val entry = ActionsBarLayout.entryOf(key, settings.customActions.value) ?: return) {
        is MoreMenuEntry.BuiltIn -> entry.item
        is MoreMenuEntry.Custom -> {
            commands.runCustomAction(entry.action, workspaces)
            return
        }
        is MoreMenuEntry.Tab -> {
            runOnShownTab(entry.action, context, navController, tab)
            return
        }
        MoreMenuEntry.Home, MoreMenuEntry.Search -> return
    }
    val isSplit = workspaces.splits.any { tab.id in it.tabIds }
    val splitWay = item.splitWay ?: SplitSpace.preferred(SplitSpace.supported(context)).takeIf {
        item == MoreMenuItem.SPLIT_VIEW && !isSplit
    }
    if (splitWay != null) {
        navController.openDejavuMenu(fromBar = true, opening = DejavuMenu.Opening.SPLIT_PICKER, splitWay = splitWay)
        return
    }
    if (commands.run(item, workspaces)) return
    when (item) {
        MoreMenuItem.NEW_TAB -> openNewTab(navController, tab)
        MoreMenuItem.PRIVATE_TAB -> openNewTab(navController, tab, private = true)
        MoreMenuItem.EXTENSIONS -> navController.openDejavuMenu(fromBar = true, opening = DejavuMenu.Opening.EXTENSIONS)
        MoreMenuItem.FIND_IN_PAGE -> components.appStore.dispatch(FindInPageAction.FindInPageStarted)
        MoreMenuItem.DESKTOP_SITE ->
            components.useCases.sessionUseCases.requestDesktopSite(!tab.content.desktopMode, tab.id)
        MoreMenuItem.PASSWORDS -> PageBackdrop.openOverPage(tab.id) {
            navController.nav(R.id.browserFragment, NavGraphDirections.actionLoginsListFragment())
        }
        MoreMenuItem.BOOKMARKS -> PageBackdrop.openOverPage(tab.id) {
            navController.nav(R.id.browserFragment, NavGraphDirections.actionGlobalBookmarkFragment(BookmarkRoot.Mobile.id))
        }
        MoreMenuItem.DOWNLOADS -> PageBackdrop.openOverPage(tab.id) {
            navController.nav(R.id.browserFragment, NavGraphDirections.actionGlobalDownloadsFragment())
        }
        MoreMenuItem.HISTORY -> PageBackdrop.openOverPage(tab.id) {
            navController.nav(R.id.browserFragment, NavGraphDirections.actionGlobalHistoryFragment())
        }
        MoreMenuItem.SETTINGS -> PageBackdrop.openOverPage(tab.id) {
            navController.nav(R.id.browserFragment, NavGraphDirections.actionGlobalSettingsFragment())
        }
        MoreMenuItem.CLEAR_DATA, MoreMenuItem.CLEAR_DATA_AND_QUIT -> ClearData.runFromPage(
            context = context,
            tab = tab,
            navController = navController,
            quit = item == MoreMenuItem.CLEAR_DATA_AND_QUIT,
            showChoices = { PageBackdrop.openOverPage(tab.id) { ClearData.showChoices(navController) } },
        )
        MoreMenuItem.OPEN_IN_APP -> {
            val redirect = components.useCases.appLinksUseCases.appLinkRedirect(tab.content.url)
            if (redirect.hasExternalApp()) {
                components.settings.openInAppOpened = true
                components.useCases.appLinksUseCases.openAppLink(redirect.appIntent)
            }
        }
        else -> Unit
    }
}

internal val BAR_HEIGHT = 60.dp
internal val BAR_SIDE_MARGIN = 12.dp

/** The space between the actions bar and the bottom of the screen, above the navigation bar. */
internal val ACTIONS_BAR_BOTTOM_MARGIN = 12.dp
private val BAR_SHAPE = RoundedCornerShape(percent = 50)
internal const val BORDER_ALPHA = 0.14f
private const val DISABLED_ALPHA = 0.38f
private const val UNFOCUSED_ALPHA = 0.75f
private val BAR_DOT_INSET = 10.dp
