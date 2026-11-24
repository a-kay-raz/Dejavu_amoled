/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.ColorScheme
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarMenu
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuButton
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuButton.ContentDescription.StringContentDescription
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuButton.Icon
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuButton.Text.StringText
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuDivider
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarState
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import mozilla.components.lib.state.Store
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.components.toolbar.PageOriginInteractions
import org.mozilla.fenix.components.toolbar.gestures.ToolbarVerticalGesturesHandler
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.TemporaryContainers
import org.mozilla.fenix.dejavu.containers.drawable
import org.mozilla.fenix.dejavu.containers.reopenInContainer
import org.mozilla.fenix.dejavu.containers.splitTemporary
import org.mozilla.fenix.dejavu.discover.Tip
import org.mozilla.fenix.dejavu.discover.Tips
import org.mozilla.fenix.dejavu.home.displayTitle
import org.mozilla.fenix.dejavu.home.titleOf
import org.mozilla.fenix.dejavu.menu.DejavuMenu
import org.mozilla.fenix.dejavu.menu.MoreMenuSwipe
import org.mozilla.fenix.dejavu.menu.runActionsBarEntry
import org.mozilla.fenix.dejavu.ui.DejavuIcons
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.requireComponents

/**
 * Dejavu's changes to the browser toolbar: one row with the address bar and the buttons beside it, above pages or
 * below them, and the actions bar below pages unless the address bar is the only bar, see [BarsLayout]. The menu opens
 * by swiping the actions bar, or the address bar where it is the only one, so it has no menu button, and tabs are
 * managed from the home screen, so it has no tab counter.
 */
object DejavuToolbar {
    const val enabled: Boolean = true

    val showTabCounter: Boolean = false

    /** Colors of a translucent toolbar and address bar, so the page shows through them. */
    fun glass(scheme: ColorScheme): ColorScheme = scheme.copy(
        surface = scheme.surface.copy(alpha = GLASS_BAR_ALPHA),
        surfaceContainerHighest = scheme.onSurface.copy(alpha = GLASS_FIELD_ALPHA),
    )

    private const val GLASS_BAR_ALPHA = 0.84f
    private const val GLASS_FIELD_ALPHA = 0.09f

    /**
     * What the address bar shows until it is focused: the tab's title as the home screen shows it, or the address
     * when the page has no title.
     */
    fun displayText(tab: TabSessionState?): String {
        tab ?: return ""
        return WorkspaceRepository.peek()?.state?.value?.titleOf(tab) ?: tab.displayTitle
    }

    /**
     * The icon of the container of [tab], the shown one unless given, in the container's color, or the "no container"
     * icon outside of containers; `null` for a private tab, which takes none. Tapping it lists the other containers to
     * reopen the shown tab in.
     */
    fun containerAction(context: Context, state: BrowserState, tab: TabSessionState? = state.selectedTab): Action? {
        if (tab == null || tab.content.private) return null
        val contextId = tab.contextId ?: return noContainerAction(context)
        val record = DejavuContainerStorage.get(context).records.value?.firstOrNull { it.contextId == contextId }
        val container = state.containers[contextId]
        val icon = record?.icon ?: container?.icon ?: return null
        val color = record?.color ?: container?.let { ContainerColor.fromAcColor(it.color) } ?: return null
        val drawable = tintedIcon(context, icon, color) ?: return null
        return Action.ActionButton(
            drawable = drawable,
            shouldTint = false,
            contentDescription = context.getString(R.string.dejavu_toolbar_container, record?.name ?: container?.name.orEmpty()),
            onClick = BrowserToolbarMenu { containerMenu(context, current = contextId) },
        )
    }

    private fun noContainerAction(context: Context): Action? {
        val icon = DejavuIcons.of(R.drawable.dejavu_ic_no_container_24)
        val drawable = AppCompatResources.getDrawable(context, icon) ?: return null
        return Action.ActionButton(
            drawable = drawable,
            shouldTint = true,
            contentDescription = context.getString(R.string.dejavu_workspace_no_container),
            onClick = BrowserToolbarMenu { containerMenu(context, current = null) },
        )
    }

    /** The user picked a container, by its [ContainerPick.key], in the container menu of the address bar. */
    data class ContainerPicked(val pickKey: String) : DejavuToolbarEvent

    /** The user tapped the entry with [key] of the actions bar. */
    data class MenuEntryClicked(val key: String) : DejavuToolbarEvent

    /** The user tapped the search button of the actions bar. */
    data object SearchClicked : DejavuToolbarEvent

    /** The user asked for the whole "More" menu without swiping the bar that opens it, from a screen reader. */
    data object MenuRequested : DejavuToolbarEvent

    /** The user asked for the page's details, from a screen reader. */
    data object PageInfoRequested : DejavuToolbarEvent

    /** Carries out [event], with [navController] and the [store] of the address bar. */
    fun onEvent(
        context: Context,
        navController: NavController,
        store: Store<BrowserToolbarState, BrowserToolbarAction>,
        event: DejavuToolbarEvent,
    ) {
        when (event) {
            is ContainerPicked -> onContainerPicked(context, event)
            is MenuEntryClicked -> runActionsBarEntry(context, navController, event.key)
            SearchClicked -> {
                DejavuSearchStart.fromBottom()
                store.dispatch(PageOriginInteractions.OriginClicked)
            }
            MenuRequested -> navController.openDejavuMenu(fromBar = true)
            PageInfoRequested -> navController.openDejavuPageInfo()
        }
    }

    /** Reopens the shown tab in the container of [event], keeping its place, pin and workspace. */
    private fun onContainerPicked(context: Context, event: ContainerPicked) {
        val pick = ContainerPick.fromKey(event.pickKey) ?: return
        val components = context.components
        val store = components.core.store
        val tab = store.state.selectedTab ?: return
        val contextId = when (pick) {
            ContainerPick.NoContainer -> null
            ContainerPick.Temporary -> TemporaryContainers.create(store, DejavuContainerStorage.get(context))
            is ContainerPick.Container -> pick.contextId
        }
        if (contextId == tab.contextId) return
        val repository = WorkspaceRepository.peek()
        repository?.state?.value?.pinOf(tab.id)?.let { repository.setPinContainer(setOf(it.id), contextId) }
        components.reopenInContainer(tab, contextId, repository, selected = true, load = true)
    }

    /** The containers to reopen the shown tab in, which is in the container [current], or in none when `null`. */
    private fun containerMenu(context: Context, current: String?): List<BrowserToolbarMenuItem> {
        val others = DejavuContainerStorage.get(context).records.value.orEmpty().filter { it.contextId != current }
        val (permanent, temporary) = others.splitTemporary()
        fun button(icon: Icon, text: String, pick: ContainerPick) =
            BrowserToolbarMenuButton(icon, StringText(text), StringContentDescription(text), ContainerPicked(pick.key))
        fun containerButtons(records: List<ContainerRecord>) = records.mapNotNull { record ->
            val drawable = tintedIcon(context, record.icon, record.color) ?: return@mapNotNull null
            val pick = ContainerPick.Container(record.contextId)
            button(Icon.DrawableIcon(drawable, shouldTint = false), record.name, pick)
        }
        return buildList {
            if (current != null) {
                add(
                    button(
                        Icon.DrawableResIcon(DejavuIcons.of(R.drawable.dejavu_ic_no_container_24)),
                        context.getString(R.string.dejavu_workspace_no_container),
                        ContainerPick.NoContainer,
                    ),
                )
                if (permanent.isNotEmpty()) add(BrowserToolbarMenuDivider)
            }
            addAll(containerButtons(permanent))
            if (isNotEmpty()) add(BrowserToolbarMenuDivider)
            add(
                button(
                    Icon.DrawableResIcon(DejavuIcons.of(R.drawable.dejavu_ic_temporary_container_24)),
                    context.getString(R.string.dejavu_new_temporary_container),
                    ContainerPick.Temporary,
                ),
            )
            addAll(containerButtons(temporary))
        }
    }

    private fun tintedIcon(context: Context, icon: ContainerState.Icon, color: ContainerColor) =
        AppCompatResources.getDrawable(context, icon.drawable)?.mutate()?.apply { setTint(color.argb.toInt()) }
}

/**
 * Handles Back on a tab with no earlier page: closes the tab, then shows the tab it was opened from, or the home screen
 * when that one is gone too. A tab opened on the homepage counts as having no earlier page, as Back would show the
 * home screen with the tab left open. A pinned tab keeps its pin. Custom tabs and tabs opened by other apps keep
 * Fenix's behavior. Called before the tab goes back in its history. A tab of a locked workspace, which the browser
 * covers, is left as it is for the workspaces.
 *
 * @return Whether Back was handled.
 */
fun Fragment.handleDejavuBackPressed(customTabSessionId: String?): Boolean {
    if (customTabSessionId != null) return false
    if (showsLockedTab()) {
        findNavController().navigate(NavGraphDirections.actionGlobalHome())
        return true
    }
    val tab = requireComponents.core.store.state.selectedTab ?: return false
    if (tab.source is SessionState.Source.External || tab.canGoBackToPage) return false

    closeShownTab(requireComponents, findNavController(), tab)
    return true
}

/** The tab with [tabId], or the shown tab when [tabId] is `null`. */
internal fun BrowserState.tabOrShown(tabId: String?): TabSessionState? =
    if (tabId == null) selectedTab else findTab(tabId)

/** Whether Back would show an earlier page of this tab, rather than the homepage it was opened on. */
internal val TabSessionState.canGoBackToPage: Boolean
    get() {
        val earlier = content.history.items.take(content.history.currentIndex)
        return if (earlier.isEmpty()) content.canGoBack else earlier.any { it.uri != ABOUT_HOME_URL }
    }

/**
 * Gives the screen its usual rotation back when a page leaves fullscreen while this browser screen is stopped, as when
 * the app goes to the background during a sideways video. The features that turn the screen back stop with the browser
 * screen and miss it, so the app could stay sideways (issue #185).
 */
fun Fragment.restoreDejavuRotationAfterFullscreen() {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

/** Closes [tab], the shown one, then shows the tab it was opened from, or the home screen when that one is gone too. */
internal fun closeShownTab(components: Components, navController: NavController, tab: TabSessionState) {
    if (tab.parentId?.let { components.core.store.state.findTab(it) } != null) {
        components.useCases.tabsUseCases.removeTab(tab.id, selectParentIfExists = true)
    } else {
        // The page shrinks back into the tab's row on the home screen before the tab closes, so it does not switch to
        // another tab first either.
        TabZoom.zoomOut(
            tabId = tab.id,
            leave = { navController.navigate(NavGraphDirections.actionGlobalHome()) },
            afterLeaving = { components.useCases.tabsUseCases.removeTab(tab.id) },
        )
    }
}

/** An interaction with Dejavu's parts of the browser toolbars, carried out by [DejavuToolbar.onEvent]. */
sealed interface DejavuToolbarEvent : BrowserToolbarEvent

/**
 * Opens the "More" menu of the browser, as swiping the bar that opens it does.
 *
 * @param fromBar Whether the menu grows out of the bar that opens it, as when swiping that bar, rather than coming
 *   down from the top of the screen.
 * @param opening What the menu shows first.
 * @param splitWay How a tab picked for a split view shows next to the shown one.
 */
fun NavController.openDejavuMenu(
    fromBar: Boolean,
    opening: DejavuMenu.Opening = DejavuMenu.Opening.MENU,
    splitWay: SplitOrientation? = null,
) {
    val directions = NavGraphDirections.actionGlobalMenuDialogFragment(accesspoint = MenuAccessPoint.Browser)
    if (currentDestination?.id != R.id.browserFragment) return
    if (opening == DejavuMenu.Opening.MENU) Tips.done(Tip.PAGE_MENU)
    val arguments = DejavuMenu.arguments(fromBar, opening, splitWay)
    navigate(directions.actionId, directions.arguments.apply { putAll(arguments) })
}

/** Opens the details of the shown page, which grow out of the address bar. */
fun NavController.openDejavuPageInfo() = openDejavuMenu(fromBar = true, opening = DejavuMenu.Opening.PAGE_INFO)

/**
 * Opens the "More" menu with a swipe on the bar that opens it, the menu following the finger until it lets go: when
 * [down], a swipe down on the address bar at the top, else a swipe up on the bar at the bottom. It only takes the swipe
 * where the layout of the bars opens the menu that way.
 */
internal fun NavController.moreMenuSwipe(down: Boolean) = object : ToolbarVerticalGesturesHandler.MenuSwipe {
    override fun onStart(): Boolean {
        if (currentDestination?.id != R.id.browserFragment || context.barsLayout.menuFromTop != down) return false
        MoreMenuSwipe.start()
        openDejavuMenu(fromBar = true)
        return true
    }

    override fun onMove(distance: Float) = MoreMenuSwipe.move(distance)

    override fun onEnd(velocity: Float) = MoreMenuSwipe.end(velocity)
}
