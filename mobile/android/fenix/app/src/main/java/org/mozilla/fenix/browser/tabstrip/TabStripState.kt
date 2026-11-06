/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.tabstrip

import android.graphics.Bitmap
import mozilla.components.browser.state.selector.getNormalOrPrivateTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.dejavu.browser.SiblingTab
import org.mozilla.fenix.dejavu.browser.siblingTabs
import org.mozilla.fenix.dejavu.home.pageUrl

private const val MAX_TABS_WITH_CLOSE_BUTTON_VISIBLE = 7

/**
 * The ui state of the tabs strip.
 *
 * @property tabs The list of [TabStripItem].
 * @property isPrivateMode Whether or not the browser is in private mode.
 * @property tabCounterMenuItems The list of [TabCounterMenuItem]s to be displayed in the tab counter menu.
 */
data class TabStripState(
    val tabs: List<TabStripItem>,
    val isPrivateMode: Boolean,
    val tabCounterMenuItems: List<TabCounterMenuItem>,
) {

    val menuItems
        get() = tabCounterMenuItems.map { it.toMenuItem() }

    companion object {
        val initial =
            TabStripState(
                tabs = emptyList(),
                isPrivateMode = false,
                tabCounterMenuItems = emptyList(),
            )
    }
}

/**
 * The ui state of a tab.
 *
 * @property id The id of the tab.
 * @property title The title of the tab.
 * @property url The url of the tab.
 * @property icon The icon of the tab.
 * @property isPrivate Whether or not the tab is private.
 * @property isSelected Whether or not the tab is selected.
 * @property isCloseButtonVisible Whether or not the close button is visible.
 * @property isPinned Dejavu: whether the item is a pinned tab or essential, which has no close button as it stays.
 * @property isAsleep Dejavu: whether the tab's page is not loaded, like a sleeping tab or a pinned tab whose tab is
 *   closed, which shows faded.
 */
data class TabStripItem(
    val id: String,
    val title: String,
    val url: String,
    val icon: Bitmap? = null,
    val isPrivate: Boolean,
    val isSelected: Boolean,
    val isCloseButtonVisible: Boolean = true,
    val isAsleep: Boolean = false,
    val isPinned: Boolean = false,
)

/**
 * Converts [BrowserState] to [TabStripState] that contains the information needed to render the tabs strip.
 * [TabStripState.isPrivateMode] is determined by the selected tab's privacy state when [isSelectDisabled] is false.
 * Otherwise, the private mode is determined by [isPossiblyPrivateMode].
 *
 * @param isSelectDisabled When true, the tabs will show as unselected.
 * @param isPossiblyPrivateMode Whether or not the browser is in private mode.
 * @param addTab Invoked when conditions are met for adding a new normal browsing mode tab.
 * @param closeTab Invoked when close tab is clicked.
 * @param siblings Dejavu: the tabs of the place the selected tab is in, see [siblingTabs], shown instead of every tab
 *   that is not private.
 */
internal fun BrowserState.toTabStripState(
    isSelectDisabled: Boolean,
    isPossiblyPrivateMode: Boolean,
    addTab: () -> Unit,
    closeTab: (isPrivate: Boolean, numberOfTabs: Int) -> Unit,
    siblings: List<SiblingTab>? = null,
): TabStripState {
    val isPrivateMode =
        if (isSelectDisabled) {
            isPossiblyPrivateMode
        } else {
            selectedTab?.content?.private == true
        }

    val tabs = getNormalOrPrivateTabs(private = isPrivateMode)
    val items =
        siblings?.takeUnless { isPrivateMode }?.toTabStripItems(isSelectDisabled, selectedTabId)
            ?: tabs.map {
                it.toTabStripItem(
                    isSelectDisabled = isSelectDisabled,
                    selectedTabId = selectedTabId,
                    showCloseButtonOnUnselectedTabs = tabs.size <= MAX_TABS_WITH_CLOSE_BUTTON_VISIBLE,
                )
            }

    return TabStripState(
        tabs = items,
        isPrivateMode = isPrivateMode,
        tabCounterMenuItems =
            mapToMenuItems(
                isSelectEnabled = !isSelectDisabled,
                isPrivateMode = isPrivateMode,
                addTab = addTab,
                closeTab = closeTab,
                numberOfTabs = items.size,
            ),
    )
}

/**
 * Dejavu: the tab bar's items for [this], the tabs of the selected tab's place. Pinned tabs and essentials have no
 * close button, as the pins stay.
 */
private fun List<SiblingTab>.toTabStripItems(isSelectDisabled: Boolean, selectedTabId: String?): List<TabStripItem> =
    map { sibling ->
        val tab = (sibling as? SiblingTab.Open)?.tab
        val isSelected = !isSelectDisabled && tab != null && tab.id == selectedTabId
        TabStripItem(
            id = sibling.key,
            title = sibling.title,
            url = tab?.content?.url ?: sibling.pin?.pageUrl.orEmpty(),
            icon = tab?.content?.icon,
            isPrivate = false,
            isSelected = isSelected,
            isCloseButtonVisible = sibling.pin == null && (size <= MAX_TABS_WITH_CLOSE_BUTTON_VISIBLE || isSelected),
            isAsleep = !sibling.isAwake && !isSelected,
            isPinned = sibling.pin != null,
        )
    }

private fun mapToMenuItems(
    isSelectEnabled: Boolean,
    isPrivateMode: Boolean,
    addTab: () -> Unit,
    closeTab: (isPrivate: Boolean, numberOfTabs: Int) -> Unit,
    numberOfTabs: Int,
): List<TabCounterMenuItem> = buildList {
    if (isSelectEnabled || isPrivateMode) {
        add(TabCounterMenuItem.IconItem.NewTab(onClick = addTab))
    }

    if (isSelectEnabled) {
        add(TabCounterMenuItem.Divider)
        add(TabCounterMenuItem.IconItem.CloseTab(onClick = { closeTab(isPrivateMode, numberOfTabs) }))
    }
}

private fun TabSessionState.toTabStripItem(
    isSelectDisabled: Boolean,
    selectedTabId: String?,
    showCloseButtonOnUnselectedTabs: Boolean,
): TabStripItem {
    val isSelected = !isSelectDisabled && id == selectedTabId
    return TabStripItem(
        id = id,
        title = content.title.ifBlank { content.url },
        url = content.url,
        icon = content.icon,
        isPrivate = content.private,
        isSelected = isSelected,
        isCloseButtonVisible = showCloseButtonOnUnselectedTabs || isSelected,
    )
}
