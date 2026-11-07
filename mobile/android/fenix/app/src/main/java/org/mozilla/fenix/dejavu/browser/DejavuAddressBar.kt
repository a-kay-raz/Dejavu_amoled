/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.toolbar.R as browserToolbarR
import mozilla.components.compose.browser.toolbar.ActionContainer
import mozilla.components.compose.browser.toolbar.R as toolbarR
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.concept.PageOrigin
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.ContextualMenuOption
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent.Source
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.ktx.kotlin.isContentUrl
import mozilla.components.support.utils.ClipboardHandler
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.discover.MENU_SWIPE
import org.mozilla.fenix.dejavu.discover.Tip
import org.mozilla.fenix.dejavu.discover.TipGesture
import org.mozilla.fenix.dejavu.discover.TipHost
import org.mozilla.fenix.dejavu.discover.TipScreen
import org.mozilla.fenix.dejavu.discover.isNewAmong
import org.mozilla.fenix.dejavu.discover.newDot
import org.mozilla.fenix.dejavu.discover.rememberUnseenNewThings
import org.mozilla.fenix.dejavu.discover.tipTarget
import org.mozilla.fenix.dejavu.menu.ActionsBarLayout
import org.mozilla.fenix.dejavu.menu.AskingDialog
import org.mozilla.fenix.dejavu.menu.BORDER_ALPHA
import org.mozilla.fenix.dejavu.menu.BarTab
import org.mozilla.fenix.dejavu.menu.MoreMenuEntry
import org.mozilla.fenix.dejavu.menu.look
import org.mozilla.fenix.dejavu.menu.readOnMainThread
import org.mozilla.fenix.dejavu.menu.rememberBarTab
import org.mozilla.fenix.dejavu.menu.tap
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.ui.DejavuDropdownMenu
import org.mozilla.fenix.dejavu.ui.DejavuMenuItem
import org.mozilla.fenix.dejavu.ui.DejavuToolbarMenus
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.TapIcon
import org.mozilla.fenix.dejavu.ui.rememberIconTaps
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation

/**
 * Dejavu's address bar while a page is shown: only its state, like Safari's. A field in the app's colors shows the
 * page title in the middle, with the site's security icon next to it and the site's domain in small letters below it.
 * While the page loads, colors run through the domain's letters instead of a loading bar. The tab's container, or the
 * "no container" icon, sits at its start. The bar shows above pages or below them, as the layout of the bars has it,
 * see [BarsLayout], with the button the user chose on each side of the field; a side without one leaves its room to
 * the field. Tapping the bar starts a search, and long-pressing it copies the address or pastes one. Where no actions
 * bar opens the "More" menu, swiping the bar opens it; elsewhere swiping it does nothing.
 *
 * @param store The store of the address bar.
 */
@Composable
fun DejavuAddressBar(store: BrowserToolbarStore) {
    // The menu of the container button looks like Dejavu's other menus.
    DejavuToolbarMenus(shownPopupTheme()) { PageAddressBar(store) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageAddressBar(store: BrowserToolbarStore) {
    val split = SplitScreen.shown
    val selectedId by components.core.store.observeAsComposableState { it.selectedTabId }
    if (split != null && selectedId in split.tabIds) {
        DejavuSplitAddressBar(store, split)
        return
    }
    val sides = rememberSideButtons()
    // Not the whole display state, which changes with every step of the loading bar this bar does not show.
    val origin by store.observeAsComposableState { it.displayState.pageOrigin }
    val pageActions by store.observeAsComposableState { it.displayState.pageActionsStart }
    val hint = stringResource(origin.hint)
    val text = origin.url?.toString()?.takeIf { it.isNotBlank() } ?: origin.title?.takeIf { it.isNotBlank() } ?: hint
    val url by components.core.store.observeAsComposableState { it.selectedTab?.content?.url }
    val loading by components.core.store.observeAsComposableState { it.selectedTab?.content?.loading == true }
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val theme = shownPopupTheme()
    val actions = barActions(store, sides.layout)
    val search = { origin.startSearch(store, sides.layout) }
    // Without an actions bar, swiping this bar opens the menu, which a tip shows.
    val opensMenu = !sides.layout.hasActionsBar
    val fromTop = sides.layout.menuFromTop

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ADDRESS_BAR_HEIGHT)
            .tipTarget(
                Tip.PAGE_MENU,
                TipGesture.Swipe(dy = if (fromTop) MENU_SWIPE else -MENU_SWIPE),
                enabled = opensMenu,
                text = if (fromTop) R.string.dejavu_tip_menu_down else R.string.dejavu_tip_menu_up,
                drawsItself = true,
            ),
    ) {
        AddressBarRow(sides = sides, tabId = null, onEvent = { store.dispatch(it) }, onSearch = search) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = if (text == hint) hint else "$text. $hint"
                        role = Role.Button
                        customActions = actions
                    }
                    .combinedClickable(
                        onClick = search,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                    )
                    .padding(padding),
            ) {
                AddressField(
                    text = text,
                    isHint = text == hint,
                    url = url,
                    loading = loading,
                    container = containerAction(),
                    pageActions = pageActions,
                    onInteraction = { store.dispatch(it) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        CompositionLocalProvider(LocalPopupTheme provides theme) {
            LongPressMenu(
                expanded = menuOpen,
                options = origin.contextualMenuOptions,
                onPick = { option ->
                    menuOpen = false
                    store.dispatch(option.event)
                },
                onDismiss = { menuOpen = false },
            )
        }
        if (opensMenu) TipHost(TipScreen.PAGE, allowed = true)
    }
}

/**
 * The address bar while the browser shows the two pages of [split]: both pages, the one in focus as the address bar
 * shows a single page and the other one dimmed. Top and bottom, the pages show in two rows of a taller field, each
 * with its title and its domain, between the buttons beside the address bar, which act on the page in focus; side by
 * side, in two fields as wide as the pages next to them, each with its own buttons. Tapping the page in focus starts a
 * search, as the address bar does; tapping the other one, or one of its buttons, turns to it first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DejavuSplitAddressBar(store: BrowserToolbarStore, split: ShownSplit) {
    val sides = rememberSideButtons()
    val origin by store.observeAsComposableState { it.displayState.pageOrigin }
    val shownPageActions by store.observeAsComposableState { it.displayState.pageActionsStart }
    val hint = stringResource(origin.hint)
    val browserStore = components.core.store
    val tabs by browserStore.observeAsComposableState { state -> split.tabIds.mapNotNull { state.findTab(it) } }
    val selectedId by browserStore.observeAsComposableState { it.selectedTabId }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val theme = shownPopupTheme()
    val turnTo = stringResource(R.string.dejavu_split_focus)
    val stacked = split.orientation == SplitOrientation.TOP_AND_BOTTOM
    val shown = tabs
    val search = { origin.startSearch(store, sides.layout) }
    val dispatch: (BrowserToolbarEvent) -> Unit = { store.dispatch(it) }

    @Composable
    fun Page(tab: TabSessionState, modifier: Modifier) {
        val focused = tab.id == selectedId
        val title = if (focused) {
            origin.url?.toString()?.takeIf { it.isNotBlank() } ?: origin.title?.takeIf { it.isNotBlank() } ?: hint
        } else {
            DejavuToolbar.displayText(tab).takeUnless { it.isBlank() || tab.content.url == ABOUT_HOME_URL } ?: hint
        }
        val focus = { SplitScreen.focus(context, tab.id) }
        val dimmed = Modifier.alpha(if (focused) 1f else UNFOCUSED_ALPHA)
        val touched = Modifier
            .semantics {
                contentDescription = if (focused && title != hint) "$title. $hint" else title
                role = Role.Button
            }
            .combinedClickable(
                onClickLabel = if (focused) hint else turnTo,
                onClick = { if (focused) search() else focus() },
                onLongClick = {
                    if (focused) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    } else {
                        focus()
                    }
                },
            )
        // The page out of focus only shows its container and its site's icon; touching them turns to it first.
        val container = containerAction(tab.id)
        val pageActions = if (focused) shownPageActions else listOfNotNull(siteInfoPreview(tab))
        val onInteraction: (BrowserToolbarEvent) -> Unit = if (focused) dispatch else ({ focus() })
        if (stacked) {
            SplitPageRow(
                title = title,
                isHint = title == hint,
                url = tab.content.url,
                loading = tab.content.loading,
                container = container,
                pageActions = pageActions,
                onInteraction = onInteraction,
                modifier = modifier.then(dimmed).then(touched),
            )
        } else {
            // Side by side, each page has its own buttons, which turn to it before they act on it.
            val onEvent: (BrowserToolbarEvent) -> Unit =
                if (focused) dispatch else ({ event -> SplitScreen.focus(context, tab.id) { dispatch(event) } })
            AddressBarRow(
                sides = sides,
                tabId = tab.id,
                onEvent = onEvent,
                onSearch = { if (focused) search() else focus() },
                modifier = modifier.then(dimmed),
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().then(touched).padding(padding)) {
                    AddressField(
                        text = title,
                        isHint = title == hint,
                        url = tab.content.url,
                        loading = tab.content.loading,
                        container = container,
                        pageActions = pageActions,
                        onInteraction = onInteraction,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().height(if (stacked) SPLIT_ADDRESS_BAR_HEIGHT else ADDRESS_BAR_HEIGHT)) {
        if (stacked) {
            AddressBarRow(sides = sides, tabId = null, onEvent = dispatch, onSearch = search) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .barSurface(
                                RoundedCornerShape(SPLIT_FIELD_RADIUS),
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                    ) {
                        shown.forEachIndexed { index, tab ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = HAIRLINE_ALPHA)),
                                )
                            }
                            Page(tab, Modifier.fillMaxWidth().height(SPLIT_ROW_HEIGHT))
                        }
                    }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                shown.forEachIndexed { index, tab ->
                    val share = if (index == 0) split.fraction else 1f - split.fraction
                    Box(modifier = Modifier.weight(share).fillMaxHeight()) {
                        Page(tab, Modifier.fillMaxSize())
                    }
                }
            }
        }
        CompositionLocalProvider(LocalPopupTheme provides theme) {
            LongPressMenu(
                expanded = menuOpen,
                options = origin.contextualMenuOptions,
                onPick = { option ->
                    menuOpen = false
                    store.dispatch(option.event)
                },
                onDismiss = { menuOpen = false },
            )
        }
    }
}

/**
 * A page of a split shown top and bottom, in a row of the address field: [container] at its start, the page's [title]
 * and the domain of [url] below it, with colors running through the domain while the page is [loading], and
 * [pageActions] at its end.
 */
@Suppress("LongParameterList")
@Composable
private fun SplitPageRow(
    title: String,
    isHint: Boolean,
    url: String,
    loading: Boolean,
    container: Action?,
    pageActions: List<Action>,
    onInteraction: (BrowserToolbarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(horizontal = FIELD_PADDING)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SLOT_SIZE)) {
            ActionContainer(actions = listOfNotNull(container), onInteraction = onInteraction)
        }
        Column(modifier = Modifier.weight(1f).padding(end = TITLE_END_PADDING).clearAndSetSemantics {}) {
            TitleAndDomain(
                text = title,
                color = if (isHint) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                domain = domainOf(url),
                loading = loading,
            )
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SLOT_SIZE)) {
            ActionContainer(actions = pageActions, onInteraction = onInteraction)
        }
    }
}

/**
 * How [DejavuAddressBar] will show [tab] once the browser shows it, for the bar coming in while the tab opens: the same
 * field and buttons, filled from the tab instead of from the address bar's store.
 */
@Composable
internal fun DejavuAddressBarPreview(tab: TabSessionState) {
    val hint = stringResource(R.string.search_hint)
    val text = DejavuToolbar.displayText(tab).takeUnless { it.isBlank() || tab.content.url == ABOUT_HOME_URL } ?: hint
    Box(modifier = Modifier.fillMaxWidth().height(ADDRESS_BAR_HEIGHT)) {
        AddressBarRow(sides = rememberSideButtons(), tabId = tab.id, onEvent = {}, onSearch = {}) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                AddressField(
                    text = text,
                    isHint = text == hint,
                    url = tab.content.url,
                    loading = tab.content.loading,
                    container = containerAction(tab.id),
                    pageActions = listOfNotNull(siteInfoPreview(tab)),
                    onInteraction = {},
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** The buttons beside the address bar in the layout of the bars, as the user set them in the menu's settings. */
private class SideButtons(val layout: BarsLayout, val start: MoreMenuEntry?, val end: MoreMenuEntry?)

@Composable
private fun rememberSideButtons(): SideButtons {
    val context = LocalContext.current
    val fenix = components
    val settings = remember { fenix.readOnMainThread { DejavuSettings.get(context) } }
    val layout by settings.barsLayout.collectAsState()
    val chosen by settings.addressBarButtons.collectAsState()
    val customActions by settings.customActions.collectAsState()
    return remember(layout, chosen, customActions) {
        val keys = settings.addressBarButtonsIn(layout)
        SideButtons(
            layout = layout,
            start = keys.start?.let { ActionsBarLayout.entryOf(it, customActions) },
            end = keys.end?.let { ActionsBarLayout.entryOf(it, customActions) },
        )
    }
}

/**
 * A row of the address bar: [field] in the middle, with the buttons of [sides] beside it, which act on the tab with
 * [tabId], the shown one when `null`, through [onEvent], or start a search with [onSearch]. [field] fills the rest of
 * the row and keeps its rounded field within the [PaddingValues] it is given, so touching the room around the field
 * touches it too; a side without a button leaves its room to the field.
 */
@Composable
private fun AddressBarRow(
    sides: SideButtons,
    tabId: String?,
    onEvent: (BrowserToolbarEvent) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    field: @Composable (PaddingValues) -> Unit,
) {
    val start = sides.start
    val end = sides.end
    val barTab = if (start != null || end != null) rememberBarTab(tabId) else null
    var asking by remember { mutableStateOf<TabAction?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxSize()) {
        if (start != null && barTab != null) {
            Spacer(Modifier.width(ADDRESS_FIELD_SIDE_MARGIN))
            SideButton(start, barTab, Source.AddressBar.BrowserStart, onEvent, onSearch) { asking = it }
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            field(
                PaddingValues(
                    start = if (start != null) SIDE_BUTTON_GAP else ADDRESS_FIELD_SIDE_MARGIN,
                    end = if (end != null) SIDE_BUTTON_GAP else ADDRESS_FIELD_SIDE_MARGIN,
                ),
            )
        }
        if (end != null && barTab != null) {
            SideButton(end, barTab, Source.AddressBar.BrowserEnd, onEvent, onSearch) { asking = it }
            Spacer(Modifier.width(ADDRESS_FIELD_SIDE_MARGIN))
        }
    }
    barTab?.let { AskingDialog(it, asking) { asking = null } }
}

/**
 * A button beside the address bar: [entry] acting on the tab of [barTab] as it does on the actions bar, except that
 * Search starts a search here the way tapping the address bar does, with [onSearch].
 */
@Suppress("LongParameterList")
@Composable
private fun SideButton(
    entry: MoreMenuEntry,
    barTab: BarTab,
    source: Source,
    onEvent: (BrowserToolbarEvent) -> Unit,
    onSearch: () -> Unit,
    ask: (TabAction) -> Unit,
) {
    val state = barTab.state
    val look = entry.look(state)
    val colors = MaterialTheme.colorScheme
    val taps = rememberIconTaps()
    val isNew = entry.isNewAmong(rememberUnseenNewThings())
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ADDRESS_FIELD_HEIGHT)
            .newDot(isNew, inset = SIDE_DOT_INSET)
            .barSurface(CircleShape, if (look.active) colors.secondaryContainer else colors.surfaceContainerHigh)
            .clickable(enabled = look.enabled, role = Role.Button) {
                taps.tap()
                if (entry == MoreMenuEntry.Search) onSearch() else entry.tap(state, source, onEvent, ask)
            }
            .semantics { contentDescription = look.label }
            .alpha(if (look.enabled) 1f else DISABLED_ALPHA),
    ) {
        TapIcon(
            icon = look.icon,
            taps = taps,
            tint = if (look.active) colors.onSecondaryContainer else colors.onSurface,
            modifier = Modifier.size(SIDE_ICON_SIZE),
        )
    }
}

/**
 * What screen readers can do with the address bar besides tapping it: open the page's details, and open the "More"
 * menu where no actions bar does, which a swipe opens.
 */
@Composable
private fun barActions(store: BrowserToolbarStore, layout: BarsLayout): List<CustomAccessibilityAction> {
    val pageInfo = stringResource(R.string.dejavu_page_info)
    val menu = stringResource(R.string.content_description_menu)
    return listOfNotNull(
        CustomAccessibilityAction(pageInfo) {
            store.dispatch(DejavuToolbar.PageInfoRequested)
            true
        },
        CustomAccessibilityAction(menu) {
            store.dispatch(DejavuToolbar.MenuRequested)
            true
        }.takeUnless { layout.hasActionsBar },
    )
}

/**
 * The rounded surface of a part of the address bar, [color] in [shape], with the outline the actions bar has, so it
 * stands out from the page above pages as below them.
 */
private fun Modifier.barSurface(shape: Shape, color: Color): Modifier = composed {
    clip(shape).background(color).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = BORDER_ALPHA), shape)
}

/** Starts a search from the address bar; one below pages keeps the search screen there, right above the keyboard. */
private fun PageOrigin.startSearch(store: BrowserToolbarStore, layout: BarsLayout) {
    val click = onClick ?: return
    if (layout.addressBarAtBottom) DejavuSearchStart.fromBottom()
    store.dispatch(click)
}

/**
 * The rounded field of the address bar: [container] at its start, [text] in the middle, with the domain of [url] in
 * small letters below the text unless the text already says it, and [pageActions], the site's security icon, at its
 * end. While the page is [loading], colors run through the domain's letters.
 */
@Suppress("LongParameterList")
@Composable
private fun AddressField(
    text: String,
    isHint: Boolean,
    url: String?,
    loading: Boolean,
    container: Action?,
    pageActions: List<Action>,
    onInteraction: (BrowserToolbarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(ADDRESS_FIELD_HEIGHT)
            .barSurface(CircleShape, MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = FIELD_PADDING),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SLOT_SIZE)) {
            ActionContainer(actions = listOfNotNull(container), onInteraction = onInteraction)
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) {
            // The bar's own description already says the title.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = TITLE_END_PADDING).clearAndSetSemantics {},
            ) {
                TitleAndDomain(
                    text = text,
                    color = if (isHint) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    domain = url?.let(::domainOf),
                    loading = loading,
                )
            }
        }
        // As wide as the container's slot, so the title stays in the middle.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SLOT_SIZE)) {
            ActionContainer(actions = pageActions, onInteraction = onInteraction)
        }
    }
}

/**
 * [text], a page's title, in [color], with its [domain] in small letters below it unless the text already says it.
 * While the page is [loading], colors run through the domain's letters: those below the title, or those in it.
 */
@Composable
private fun TitleAndDomain(text: String, color: Color, domain: String?, loading: Boolean) {
    val inTitle = domain?.takeIf { text.contains(it, ignoreCase = true) }
    val titleLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { titleLayout[0] = it },
        modifier = if (inTitle != null) {
            Modifier.loadingColors(loading) { titleLayout[0]?.rangeOf(text, inTitle) }
        } else {
            Modifier
        },
    )
    if (domain != null && inTitle == null) {
        Text(
            text = domain,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.loadingColors(loading),
        )
    }
}

/**
 * The site's security icon for [tab], as the address bar's store shows it once the page is shown; none in Reader
 * View.
 */
private fun siteInfoPreview(tab: TabSessionState): Action? {
    if (tab.readerState.active) return null
    val security = tab.content.securityInfo
    val protection = tab.trackingProtection
    val icon = when {
        tab.content.url.isContentUrl() -> iconsR.drawable.mozac_ic_page_portrait_24
        security == SecurityInfo.Unknown -> iconsR.drawable.mozac_ic_globe_24
        !security.isSecure -> iconsR.drawable.mozac_ic_shield_slash_24
        protection.enabled && !protection.ignoredOnTrackingProtection -> iconsR.drawable.mozac_ic_shield_checkmark_24
        else -> iconsR.drawable.mozac_ic_shield_cross_24
    }
    return Action.ActionButtonRes(
        drawableResId = icon,
        contentDescription = browserToolbarR.string.mozac_browser_toolbar_content_description_site_info,
        highlighted = tab.content.permissionHighlights.permissionsChanged || protection.ignoredOnTrackingProtection,
        onClick = object : BrowserToolbarEvent {},
    )
}

@Composable
private fun LongPressMenu(
    expanded: Boolean,
    options: List<ContextualMenuOption>,
    onPick: (ContextualMenuOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember(context) { ClipboardHandler(context) }
    val shown = remember(expanded, options) {
        if (!expanded) return@remember emptyList()
        options.filter { option ->
            when (option) {
                ContextualMenuOption.CopyURLToClipboard -> true
                ContextualMenuOption.PasteFromClipboard -> clipboard.containsText()
                ContextualMenuOption.LoadFromClipboard -> clipboard.containsURL()
            }
        }
    }
    DejavuDropdownMenu(expanded = expanded && shown.isNotEmpty(), onDismissRequest = onDismiss) {
        shown.forEach { option ->
            DejavuMenuItem(text = stringResource(option.label), onClick = { onPick(option) })
        }
    }
}

private val ContextualMenuOption.label: Int
    get() = when (this) {
        ContextualMenuOption.CopyURLToClipboard -> toolbarR.string.mozac_browser_toolbar_long_press_popup_copy
        ContextualMenuOption.PasteFromClipboard -> toolbarR.string.mozac_browser_toolbar_long_press_popup_paste
        ContextualMenuOption.LoadFromClipboard -> toolbarR.string.mozac_browser_toolbar_long_press_popup_paste_and_go
    }

/**
 * The button of the container of the tab with [tabId], the shown tab when `null`, or of "no container", which opens
 * the menu to move the tab to another.
 */
@Composable
private fun containerAction(tabId: String? = null): Action? {
    val context = LocalContext.current
    val browserStore = components.core.store
    val shown by browserStore.observeAsComposableState { state ->
        state.tabOrShown(tabId)?.let { tab ->
            Triple(tab.contextId, state.containers[tab.contextId], tab.content.private)
        }
    }
    val records by remember { DejavuContainerStorage.get(context).records }.collectAsState()
    return remember(shown, records) {
        DejavuToolbar.containerAction(context, browserStore.state, browserStore.state.tabOrShown(tabId))
    }
}

/** The domain of [url], like "google.com" for "https://www.google.com/search", or `null` for one without a host. */
internal fun domainOf(url: String): String? = url.toUri().host?.removePrefix("www.")?.takeIf { it.isNotBlank() }

/** The height of Dejavu's address bar while a page is shown, below the status bar or above the navigation bar. */
internal val ADDRESS_BAR_HEIGHT = 64.dp

/** The height of the address bar showing the two pages of a split top and bottom, each in a row of its own. */
private val SPLIT_ADDRESS_BAR_HEIGHT = 100.dp
private val SPLIT_ROW_HEIGHT = 42.dp
private val SPLIT_FIELD_RADIUS = 24.dp
private const val UNFOCUSED_ALPHA = 0.55f
private const val HAIRLINE_ALPHA = 0.08f
private const val DISABLED_ALPHA = 0.38f

/** The size and place of the rounded field of the address bar, in the middle of the bar. */
internal val ADDRESS_FIELD_HEIGHT = 48.dp
internal val ADDRESS_FIELD_SIDE_MARGIN = 16.dp
internal val ADDRESS_FIELD_MARGIN = (ADDRESS_BAR_HEIGHT - ADDRESS_FIELD_HEIGHT) / 2
private val SIDE_BUTTON_GAP = 8.dp
private val SIDE_ICON_SIZE = 22.dp
private val SIDE_DOT_INSET = 6.dp
private val FIELD_PADDING = 4.dp
private val SLOT_SIZE = 48.dp
private val TITLE_END_PADDING = 8.dp
