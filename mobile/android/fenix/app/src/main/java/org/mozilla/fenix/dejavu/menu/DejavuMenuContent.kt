/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.feature.addons.Addon
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.components.menu.store.MenuAction
import org.mozilla.fenix.components.menu.store.MenuStore
import org.mozilla.fenix.components.menu.store.WebExtensionMenuItem
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.browser.ClearData
import org.mozilla.fenix.dejavu.browser.SplitSpace
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.settings.PageBackdrop
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.ext.components as contextComponents
import org.mozilla.fenix.nimbus.FxNimbus

/** How Dejavu's "More" menu takes the place of Fenix's main menu. */
object DejavuMenu {
    private const val ARG_FROM_BAR = "dejavu_menu_from_bar"
    private const val ARG_OPENING = "dejavu_menu_opening"
    private const val ARG_SPLIT_WAY = "dejavu_menu_split_way"

    /**
     * What the menu shows when it opens. The page's details are no part of the "More" menu, but a panel of their own
     * growing out of the address bar, which the menu's dialog shows the same way, see [PageInfoMenu].
     */
    enum class Opening { MENU, EXTENSIONS, SPLIT_PICKER, PAGE_INFO }

    /** Whether Dejavu's menu replaces Fenix's for [accessPoint]. Custom tabs keep Fenix's menu. */
    fun replaces(accessPoint: MenuAccessPoint): Boolean = accessPoint != MenuAccessPoint.External

    /**
     * Whether the menu is a panel growing out of the bar that opens it, or the page's details growing out of the
     * address bar, which the browser's menu is unless it comes down from the top as a sheet.
     */
    fun floats(accessPoint: MenuAccessPoint, fromTop: Boolean): Boolean =
        accessPoint == MenuAccessPoint.Browser && !fromTop

    /**
     * Whether the menu opened from [accessPoint] with [arguments], as [DejavuMenu.arguments] makes them, comes down
     * from the top of the screen as a sheet, which it does when no bar opened it, like with the menu key.
     */
    fun opensFromTop(context: Context, accessPoint: MenuAccessPoint, arguments: Bundle?): Boolean =
        accessPoint == MenuAccessPoint.Browser &&
            !context.contextComponents.settings.shouldUseBottomToolbar &&
            arguments?.getBoolean(ARG_FROM_BAR) != true

    /** What the menu opened with [arguments] shows first. */
    fun opening(arguments: Bundle?): Opening =
        arguments?.getString(ARG_OPENING)?.let { name -> Opening.entries.firstOrNull { it.name == name } } ?: Opening.MENU

    /**
     * The arguments that open the menu dialog out of the bar that opens it or not, showing [opening] first; the tab
     * picked for a split view shows next to the menu's tab the way [splitWay] has it.
     */
    fun arguments(fromBar: Boolean, opening: Opening, splitWay: SplitOrientation? = null): Bundle = Bundle().apply {
        putBoolean(ARG_FROM_BAR, fromBar)
        putString(ARG_OPENING, opening.name)
        splitWay?.let { putString(ARG_SPLIT_WAY, it.key) }
    }

    /** The way a split view picked in the menu opened with [arguments] shows, if it said. */
    fun splitWay(arguments: Bundle?): SplitOrientation? = SplitOrientation.fromKey(arguments?.getString(ARG_SPLIT_WAY))

    /** A dialog showing its content at the top of the screen, sliding down from the edge. */
    fun createTopSheetDialog(context: Context, onMenuKey: () -> Unit): Dialog =
        object : Dialog(context, R.style.DejavuTopSheetDialog) {
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    onMenuKey()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
        }.apply {
            setCanceledOnTouchOutside(true)
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.TOP)
                setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(TOP_SHEET_DIM)
                setWindowAnimations(R.style.DejavuTopSheetAnimation)
            }
        }

    /**
     * A dialog over the whole screen for the floating menu, which dims the page and moves in by itself. Back and the
     * menu key go to the menu, which shrinks back into its bar before the dialog closes.
     *
     * @param activityWindow The window the menu opens over, whose system bar icons the dialog keeps.
     */
    fun createFloatingDialog(context: Context, activityWindow: Window): Dialog =
        FloatingMenuDialog(context, activityWindow)

    /**
     * Closes [dialog] the way Back does when it shows the floating menu, which shrinks back into its bar first.
     *
     * @return Whether [dialog] shows the floating menu; any other dialog is left to close as usual.
     */
    fun closeFloating(dialog: Dialog?): Boolean {
        val floating = dialog as? FloatingMenuDialog ?: return false
        floating.onBackPressedDispatcher.onBackPressed()
        return true
    }

    private class FloatingMenuDialog(
        context: Context,
        private val activityWindow: Window,
    ) : ComponentDialog(context, R.style.DejavuFloatingMenuDialog) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val own = window ?: return
            own.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            own.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
            own.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.setDecorFitsSystemWindows(own, false)
            @Suppress("DEPRECATION")
            own.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            own.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) own.isNavigationBarContrastEnforced = false
            val behind = WindowCompat.getInsetsController(activityWindow, activityWindow.decorView)
            WindowCompat.getInsetsController(own, own.decorView).apply {
                isAppearanceLightStatusBars = behind.isAppearanceLightStatusBars
                isAppearanceLightNavigationBars = behind.isAppearanceLightNavigationBars
            }
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
    }

    private const val TOP_SHEET_DIM = 0.32f
}

/**
 * The content of the menu dialog when Dejavu replaces Fenix's menu: the "More" menu of the browser, the page's details
 * of the browser, or the extensions of the home screen.
 *
 * @param menuStore Fenix's menu store, which carries out the actions Fenix's menu also has.
 * @param accessPoint Where the menu was opened from.
 * @param fromTop Whether the menu is shown at the top of the screen.
 * @param opening What the menu shows first.
 * @param navController Opens the screens tab actions lead to.
 * @param onEdit Opens the settings of the menu.
 * @param onDismiss Closes the menu.
 */
@Suppress("LongParameterList")
@Composable
fun DejavuMenuContent(
    menuStore: MenuStore,
    accessPoint: MenuAccessPoint,
    fromTop: Boolean,
    opening: DejavuMenu.Opening,
    navController: NavController,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    splitWay: SplitOrientation? = null,
) {
    if (opening == DejavuMenu.Opening.PAGE_INFO && accessPoint == MenuAccessPoint.Browser) {
        PageInfoMenu(menuStore = menuStore, navController = navController, onDismiss = onDismiss)
    } else {
        MoreMenuContent(menuStore, accessPoint, fromTop, opening, navController, onEdit, onDismiss, splitWay)
    }
}

/** The "More" menu of the browser, or the extensions of the home screen, see [DejavuMenuContent]. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun MoreMenuContent(
    menuStore: MenuStore,
    accessPoint: MenuAccessPoint,
    fromTop: Boolean,
    opening: DejavuMenu.Opening,
    navController: NavController,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    splitWay: SplitOrientation?,
) {
    val fenix = components
    val menuState by menuStore.stateFlow.collectAsState()
    val extensions = menuState.extensionMenuState.browserWebExtensionMenuItem
    val manageExtensions = { menuStore.dispatch(MenuAction.Navigate.ManageExtensions) }

    MoreMenuSheet(fromTop = fromTop, onDismiss = onDismiss, floating = DejavuMenu.floats(accessPoint, fromTop)) {
        val closer = LocalMenuCloser.current
        val close: () -> Unit = if (closer == null) onDismiss else ({ closer.close() })
        if (accessPoint == MenuAccessPoint.Home) {
            val hasTab by fenix.core.store.observeAsComposableState { it.selectedTab != null }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.dejavu_ic_extensions_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.dejavu_menu_extensions),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            ExtensionList(
                extensions =
                    if (hasTab) {
                        extensions
                    } else {
                        extensions.openingAddonPages(menuState.extensionMenuState.availableAddons, menuStore)
                    },
                onManage = manageExtensions,
            )
            return@MoreMenuSheet
        }

        val context = LocalContext.current
        val settings = remember { fenix.readOnMainThread { DejavuSettings.get(context) } }
        val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
        val tab by fenix.core.store.observeAsComposableState { it.selectedTab }
        val workspaces by repository.state.collectAsState()
        val rowKeys by settings.moreMenuRows.collectAsState()
        val customActions by settings.customActions.collectAsState()
        val editHidden by settings.moreMenuEditHidden.collectAsState()
        var extensionsExpanded by remember { mutableStateOf(opening == DejavuMenu.Opening.EXTENSIONS) }
        // The way the tab picked for a split view shows, while picking it.
        var pickingSplit by remember {
            mutableStateOf(
                if (opening == DejavuMenu.Opening.SPLIT_PICKER) splitWay ?: SplitOrientation.TOP_AND_BOTTOM else null,
            )
        }
        var asking by remember { mutableStateOf<TabAction?>(null) }
        val current = tab ?: return@MoreMenuSheet
        val state = moreMenuState(
            fenix = fenix,
            tab = current,
            workspaces = workspaces,
            isBookmarked = menuState.browserMenuState?.bookmarkState?.isBookmarked == true,
            isDesktopMode = menuState.isDesktopMode,
        )
        val actions =
            MenuActions(context, fenix, menuStore, repository, settings, current, navController, close, closer)

        asking?.let { action ->
            ShownTabActionDialog(action = action, tab = current, navController = navController, onDone = close)
        }

        pickingSplit?.let { way ->
            val workspaceId = workspaces.workspaceOf(current.id)
            val candidates = fenix.core.store.state.normalTabs.filter { other ->
                other.id != current.id &&
                    workspaces.workspaceOf(other.id) == workspaceId &&
                    workspaces.splits.none { other.id in it.tabIds }
            }
            SplitTabPicker(
                tabs = candidates,
                onPick = { other -> actions.split(workspaces, other, way) },
                onBack = { pickingSplit = null },
            )
            return@MoreMenuSheet
        }

        MoreMenu(
            rows = remember(rowKeys, customActions) { MoreMenuLayout.resolve(rowKeys, customActions) }
                .map { it.forSplitWays(state.splitWays, state.isSplit) },
            state = state,
            extensions = extensions,
            extensionsExpanded = extensionsExpanded,
            onToggleExtensions = { extensionsExpanded = !extensionsExpanded },
            onEntryClick = { entry ->
                when {
                    entry == MoreMenuEntry.BuiltIn(MoreMenuItem.EXTENSIONS) -> extensionsExpanded = !extensionsExpanded
                    entry == MoreMenuEntry.BuiltIn(MoreMenuItem.SPLIT_VIEW) && !state.isSplit ->
                        pickingSplit = SplitSpace.preferred(state.splitWays)
                    entry is MoreMenuEntry.BuiltIn && entry.item.splitWay != null -> pickingSplit = entry.item.splitWay
                    entry is MoreMenuEntry.Tab && entry.action.asksFirst -> asking = entry.action
                    else -> actions.run(entry, state, workspaces)
                }
            },
            onManageExtensions = manageExtensions,
            onEdit = onEdit.takeUnless { editHidden },
            onCustomizeReaderView = if (state.isInReaderView) actions::customizeReaderView else null,
        )
    }
}

/**
 * The buttons of the extensions while no tab is open. They have no page to work on, so each opens the extension's page
 * among the installed [addons] instead, or the list of extensions while those are still loading.
 */
internal fun List<WebExtensionMenuItem>.openingAddonPages(addons: List<Addon>, menuStore: MenuStore) = map { item ->
    item.copy(
        onClick = {
            val addon = addons.firstOrNull { it.id == item.id }
            menuStore.dispatch(
                addon?.let { MenuAction.Navigate.InstalledAddonDetails(it) } ?: MenuAction.Navigate.ManageExtensions
            )
        }
    )
}

/** What the "More" menu and the bars show about [tab]. */
@Suppress("LongParameterList")
@Composable
internal fun moreMenuState(
    fenix: Components,
    tab: TabSessionState,
    workspaces: WorkspaceState,
    isBookmarked: Boolean,
    isDesktopMode: Boolean,
): MoreMenuState {
    val url = tab.content.url
    val pin = workspaces.pinOf(tab.id)
    // Asking the package manager takes a while, so it stays off the main thread.
    val hasExternalApp by produceState(false, url) {
        value = withContext(Dispatchers.IO) {
            runCatching { fenix.useCases.appLinksUseCases.appLinkRedirect(url).hasExternalApp() }.getOrDefault(false)
        }
    }
    val translationEngineSupported = fenix.core.store.state.translationEngine.isEngineSupported == true
    val undoTabs by fenix.core.store.observeAsComposableState { it.undoHistory.tabs }
    val closedTabs by fenix.core.store.observeAsComposableState { it.closedTabs }
    return MoreMenuState(
        canGoBack = tab.content.canGoBack,
        canGoForward = tab.content.canGoForward,
        isLoading = tab.content.loading,
        isBookmarked = isBookmarked,
        isDesktopMode = isDesktopMode,
        isPinned = pin != null && !pin.essential,
        isEssential = pin?.essential == true,
        isPinChanged = pin?.url != null && pin.url != url,
        isSplit = workspaces.splits.any { tab.id in it.tabIds },
        isPrivate = tab.content.private,
        canRestoreClosedTab =
            restoreChoiceOf(tab.content.private, undoTabs.map { it.state }, closedTabs) != RestoreChoice.None,
        hasExternalApp = hasExternalApp,
        canTranslate = translationEngineSupported &&
            FxNimbus.features.translations.value().mainFlowBrowserMenuEnabled &&
            !tab.content.isPdf,
        isTranslated = tab.translationsState.isTranslated,
        isReaderable = tab.readerState.readerable,
        isInReaderView = tab.readerState.active,
        isWebPage = url.startsWith("http://") || url.startsWith("https://"),
        isPdf = tab.content.isPdf,
        isDeviceTab = !tab.content.private &&
            workspaces.isDeviceWorkspace(pin?.workspaceId ?: workspaces.workspaceOf(tab.id)),
        splitWays = SplitSpace.supported(LocalConfiguration.current),
    )
}

/**
 * Carries out the entries of the "More" menu for [tab].
 *
 * @param dismiss Closes the menu.
 * @param closer Closes the floating menu around the actions; `null` when the menu does not float.
 */
@Suppress("LongParameterList")
private class MenuActions(
    private val context: Context,
    components: Components,
    private val menuStore: MenuStore,
    repository: WorkspaceRepository,
    settings: DejavuSettings,
    private val tab: TabSessionState,
    private val navController: NavController,
    private val dismiss: () -> Unit,
    private val closer: MenuCloser?,
) {
    private val commands = DejavuTabCommands(context, components, repository, settings, tab)

    @Suppress("CyclomaticComplexMethod")
    fun run(entry: MoreMenuEntry, state: MoreMenuState, workspaces: WorkspaceState) {
        val item = when (entry) {
            is MoreMenuEntry.BuiltIn -> entry.item
            is MoreMenuEntry.Custom -> {
                if (closer != null) {
                    closer.close { commands.runCustomAction(entry.action, workspaces) }
                    return
                }
                commands.runCustomAction(entry.action, workspaces)
                dismiss()
                return
            }
            is MoreMenuEntry.Tab -> {
                if (closer != null) {
                    closer.close { runOnShownTab(entry.action, context, navController, tab) }
                    return
                }
                dismiss()
                runOnShownTab(entry.action, context, navController, tab)
                return
            }
            MoreMenuEntry.Home, MoreMenuEntry.Search -> return
        }
        if (item == MoreMenuItem.NEW_TAB) {
            if (closer != null) {
                closer.close { openNewTab(navController, tab) }
                return
            }
            dismiss()
            openNewTab(navController, tab)
            return
        }
        if (item == MoreMenuItem.CLEAR_DATA || item == MoreMenuItem.CLEAR_DATA_AND_QUIT) {
            val showChoices = { PageBackdrop.openOverPage(tab.id) { ClearData.showChoices(navController) } }
            val quit = item == MoreMenuItem.CLEAR_DATA_AND_QUIT
            val clear = { ClearData.runFromPage(context, tab, navController, quit, showChoices) }
            if (closer != null) {
                closer.close { clear() }
                return
            }
            dismiss()
            clear()
            return
        }
        if (commands.run(item, workspaces)) {
            dismiss()
            return
        }
        when (item) {
            MoreMenuItem.BACK -> dispatch(MenuAction.Navigate.Back(viewHistory = false))
            MoreMenuItem.FORWARD -> dispatch(MenuAction.Navigate.Forward(viewHistory = false))
            MoreMenuItem.REFRESH -> dispatch(
                if (state.isLoading) MenuAction.Navigate.Stop else MenuAction.Navigate.Reload(bypassCache = false),
            )
            MoreMenuItem.SHARE -> dispatch(MenuAction.Navigate.Share)
            MoreMenuItem.FIND_IN_PAGE -> dispatch(MenuAction.FindInPage)
            MoreMenuItem.BOOKMARK_PAGE -> dispatch(
                if (state.isBookmarked) MenuAction.Navigate.EditBookmark() else MenuAction.AddBookmark,
            )
            MoreMenuItem.DESKTOP_SITE -> dispatch(
                if (state.isDesktopMode) MenuAction.RequestMobileSite else MenuAction.RequestDesktopSite,
            )
            MoreMenuItem.PASSWORDS -> PageBackdrop.openOverPage(tab.id) { dispatch(MenuAction.Navigate.Passwords) }
            MoreMenuItem.BOOKMARKS -> PageBackdrop.openOverPage(tab.id) { dispatch(MenuAction.Navigate.Bookmarks) }
            MoreMenuItem.DOWNLOADS -> PageBackdrop.openOverPage(tab.id) { dispatch(MenuAction.Navigate.Downloads) }
            MoreMenuItem.HISTORY -> PageBackdrop.openOverPage(tab.id) { dispatch(MenuAction.Navigate.History) }
            MoreMenuItem.TRANSLATE -> dispatch(MenuAction.Navigate.Translate)
            MoreMenuItem.OPEN_IN_APP -> dispatch(MenuAction.OpenInApp)
            MoreMenuItem.SETTINGS -> PageBackdrop.openOverPage(tab.id) { dispatch(MenuAction.Navigate.Settings) }
            else -> Unit
        }
    }

    /** Shows the tab of the menu and [other] together, the way [way] has them. */
    fun split(workspaces: WorkspaceState, other: TabSessionState, way: SplitOrientation) {
        commands.split(workspaces, other, way)
        dismiss()
    }

    /** Shows Firefox's settings of reader view at the bottom of the page: its font, text size and colors. */
    fun customizeReaderView() = dispatch(MenuAction.CustomizeReaderView)

    /** Hands [action] to Fenix's menu, which closes the menu once done; a floating menu shrinks away first. */
    private fun dispatch(action: MenuAction) {
        if (closer != null) closer.closeBy { menuStore.dispatch(action) } else menuStore.dispatch(action)
    }
}

internal fun <T> Components.readOnMainThread(block: () -> T): T =
    strictMode.allowViolation(StrictMode::allowThreadDiskReads, block)
