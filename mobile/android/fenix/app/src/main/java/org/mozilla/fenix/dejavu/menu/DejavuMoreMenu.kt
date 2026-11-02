/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.components.menu.store.WebExtensionMenuItem
import org.mozilla.fenix.dejavu.browser.navigationBarRoomPadding
import org.mozilla.fenix.dejavu.browser.shownPopupTheme
import org.mozilla.fenix.dejavu.browser.statusBarRoomPadding
import org.mozilla.fenix.dejavu.browser.workspaceTheme
import org.mozilla.fenix.dejavu.discover.NewThing
import org.mozilla.fenix.dejavu.discover.isNewAmong
import org.mozilla.fenix.dejavu.discover.newDot
import org.mozilla.fenix.dejavu.discover.rememberUnseenNewThings
import org.mozilla.fenix.dejavu.home.displayTitle
import org.mozilla.fenix.dejavu.ui.TapIcon
import org.mozilla.fenix.dejavu.ui.WordFittedText
import org.mozilla.fenix.dejavu.ui.rememberIconTaps
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation

/** What the "More" menu shows about the tab it was opened for. */
data class MoreMenuState(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val isBookmarked: Boolean = false,
    val isDesktopMode: Boolean = false,
    val isPinned: Boolean = false,
    val isEssential: Boolean = false,
    val isPinChanged: Boolean = false,
    val isSplit: Boolean = false,
    val isPrivate: Boolean = false,
    val hasExternalApp: Boolean = false,
    val canTranslate: Boolean = false,
    val isTranslated: Boolean = false,
    val isReaderable: Boolean = false,
    val isInReaderView: Boolean = false,
    val isWebPage: Boolean = false,
    val isPdf: Boolean = false,
    val isAmoledDisabled: Boolean = false,
    val isDeviceTab: Boolean = false,
    val splitWays: List<SplitOrientation> = emptyList(),
)

/** How an entry of the "More" menu looks for the shown tab. */
internal data class EntryLook(
    val label: String,
    @param:DrawableRes val icon: Int,
    val enabled: Boolean = true,
    val active: Boolean = false,
)

@Composable
internal fun MoreMenuEntry.look(state: MoreMenuState): EntryLook = when (this) {
    is MoreMenuEntry.BuiltIn -> item.look(state)
    is MoreMenuEntry.Tab -> EntryLook(stringResource(action.label), action.icon, enabled = action.canRunOnShownTab(state))
    // A custom action sends the page to a server, so it does not run on a private tab, like pinning and moving it.
    is MoreMenuEntry.Custom ->
        EntryLook(action.name, iconsR.drawable.mozac_ic_lightning_24, enabled = state.isWebPage && !state.isPrivate)
    MoreMenuEntry.Home -> EntryLook(stringResource(R.string.dejavu_actions_bar_home), R.drawable.dejavu_ic_home_24)
    MoreMenuEntry.Search ->
        EntryLook(stringResource(R.string.dejavu_actions_bar_search), R.drawable.dejavu_ic_search_24)
}

@Composable
private fun MoreMenuItem.look(state: MoreMenuState): EntryLook {
    val item = this
    val default = EntryLook(stringResource(item.label), item.icon)
    // A tab of a device on the account follows the device, which takes no pins, essentials or pinned pages.
    if (state.isDeviceTab && item in DeviceLockedItems) return default.copy(enabled = false)
    return when (item) {
        MoreMenuItem.BACK -> default.copy(enabled = state.canGoBack)
        MoreMenuItem.FORWARD -> default.copy(enabled = state.canGoForward)
        MoreMenuItem.REFRESH -> if (state.isLoading) {
            EntryLook(stringResource(R.string.dejavu_menu_stop), iconsR.drawable.mozac_ic_cross_24)
        } else {
            default
        }
        MoreMenuItem.BOOKMARK_PAGE -> if (state.isBookmarked) {
            val label = stringResource(R.string.dejavu_menu_edit_bookmark)
            EntryLook(label, R.drawable.dejavu_ic_bookmarked_24, active = true)
        } else {
            default.copy(enabled = state.isWebPage)
        }
        MoreMenuItem.DESKTOP_SITE -> default.copy(active = state.isDesktopMode)
        MoreMenuItem.AMOLED_PAGES -> default.copy(enabled = state.isWebPage, active = !state.isAmoledDisabled)
        MoreMenuItem.READER_VIEW -> if (state.isInReaderView) {
            val label = stringResource(R.string.dejavu_menu_close_reader_view)
            EntryLook(label, iconsR.drawable.mozac_ic_reader_view_fill_24, active = true)
        } else {
            // Firefox offers it only on pages its check takes for articles; Dejavu lets every web page try, as the
            // check leaves out pages like Wikipedia's main page, and a page with nothing to show stays as it is.
            default.copy(enabled = state.isReaderable || (state.isWebPage && !state.isPdf))
        }
        // A pinned tab of a device can still be unpinned here, which leaves it pinned on the device.
        MoreMenuItem.PIN_TAB -> if (state.isPinned) {
            EntryLook(stringResource(R.string.dejavu_menu_unpin_tab), iconsR.drawable.mozac_ic_pin_slash_24, active = true)
        } else {
            default.copy(enabled = !state.isPrivate && state.isWebPage && !state.isDeviceTab)
        }
        MoreMenuItem.ESSENTIAL_TAB -> if (state.isEssential) {
            EntryLook(stringResource(R.string.dejavu_menu_remove_essential), iconsR.drawable.mozac_ic_tab_ungroup_24, active = true)
        } else {
            default.copy(enabled = !state.isPrivate && state.isWebPage)
        }
        MoreMenuItem.SPLIT_VIEW -> if (state.isSplit) {
            EntryLook(stringResource(R.string.dejavu_menu_unsplit), R.drawable.dejavu_ic_unsplit_24, active = true)
        } else {
            // With room for one way of splitting only, the entry shows that way.
            val icon = if (state.splitWays.singleOrNull() == SplitOrientation.SIDE_BY_SIDE) {
                R.drawable.dejavu_ic_split_side_24
            } else {
                item.icon
            }
            default.copy(icon = icon, enabled = !state.isPrivate)
        }
        MoreMenuItem.SPLIT_TOP_AND_BOTTOM, MoreMenuItem.SPLIT_SIDE_BY_SIDE -> default.copy(enabled = !state.isPrivate)
        MoreMenuItem.RESET_PINNED_URL, MoreMenuItem.REPLACE_PINNED_URL -> default.copy(enabled = state.isPinChanged)
        MoreMenuItem.OPEN_IN_APP -> default.copy(enabled = state.hasExternalApp)
        MoreMenuItem.TRANSLATE -> default.copy(enabled = state.canTranslate, active = state.isTranslated)
        MoreMenuItem.SHARE, MoreMenuItem.FIND_IN_PAGE, MoreMenuItem.SAVE_AS_PDF, MoreMenuItem.PRINT,
        -> default.copy(enabled = state.isWebPage)
        // Reader view and the PDF viewer are the browser's own pages, which the page saver cannot read.
        MoreMenuItem.SAVE_PAGE -> default.copy(enabled = state.isWebPage && !state.isInReaderView && !state.isPdf)
        MoreMenuItem.NEW_TAB, MoreMenuItem.EXTENSIONS, MoreMenuItem.PASSWORDS, MoreMenuItem.BOOKMARKS,
        MoreMenuItem.DOWNLOADS, MoreMenuItem.HISTORY, MoreMenuItem.SETTINGS, MoreMenuItem.CLEAR_DATA,
        MoreMenuItem.CLEAR_DATA_AND_QUIT,
        -> default
    }
}

/** The entries of the "More" menu that do nothing for a tab of a device on the account. */
private val DeviceLockedItems = setOf(
    MoreMenuItem.ESSENTIAL_TAB,
    MoreMenuItem.RESET_PINNED_URL,
    MoreMenuItem.REPLACE_PINNED_URL,
)

/**
 * The surface of the "More" menu: a sheet that comes down from the top when the address bar is at the top, and up
 * from the bottom otherwise. A sheet at the top can be swiped up to close it; one at the bottom is closed by its
 * bottom sheet dialog.
 *
 * @param floating Shows the menu as a panel growing out of the actions bar instead, see [FloatingMoreMenu].
 */
@Composable
fun MoreMenuSheet(
    fromTop: Boolean,
    onDismiss: () -> Unit,
    floating: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (floating) {
        FloatingMoreMenu(onDismiss = onDismiss, content = content)
        return
    }
    val corner = 28.dp
    val shape = if (fromTop) {
        RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
    } else {
        RoundedCornerShape(topStart = corner, topEnd = corner)
    }
    val dismissDistance = with(LocalDensity.current) { 64.dp.toPx() }
    val swipeUp = remember(dismissDistance) { SwipeUpToDismiss(dismissDistance, onDismiss) }
    val shownOffset by animateFloatAsState(targetValue = swipeUp.offset, label = "MoreMenuSheetOffset")
    val handleDrag = rememberDraggableState { delta -> swipeUp.drag(delta) }
    val bottomSheetScroll = rememberNestedScrollInteropConnection()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = shownOffset }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .workspaceTheme(shownPopupTheme())
            .then(if (fromTop) Modifier.statusBarRoomPadding() else Modifier.navigationBarRoomPadding())
            .nestedScroll(if (fromTop) swipeUp else bottomSheetScroll),
    ) {
        if (!fromTop) SheetHandle()
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            content = content,
        )
        if (fromTop) {
            SheetHandle(
                modifier = Modifier.draggable(
                    state = handleDrag,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> swipeUp.release(velocity) },
                ),
            )
        }
    }
}

/** Lets a sheet at the top of the screen follow upward swipes that its content does not scroll, and close. */
private class SwipeUpToDismiss(
    private val dismissDistance: Float,
    private val onDismiss: () -> Unit,
) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set

    fun drag(delta: Float) {
        offset = (offset + delta).coerceAtMost(0f)
    }

    fun release(velocity: Float) {
        if (offset < -dismissDistance || (offset < 0f && velocity < -FLING_VELOCITY)) onDismiss() else offset = 0f
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (offset >= 0f || available.y <= 0f) return Offset.Zero
        val consumed = available.y.coerceAtMost(-offset)
        offset += consumed
        return Offset(0f, consumed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        drag(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset >= 0f) return Velocity.Zero
        release(available.y)
        return available
    }

    private companion object {
        const val FLING_VELOCITY = 1500f
    }
}

@Composable
private fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HANDLE_ALPHA)),
        )
    }
}

/**
 * The rows of the "More" menu.
 *
 * @param rows Entries of each row. A row with only the extensions item shows the extensions drawer.
 * @param state What the menu knows about the tab.
 * @param extensions Extension buttons for the tab.
 * @param extensionsExpanded Whether the extensions drawer is open.
 * @param onToggleExtensions Opens or closes the extensions drawer.
 * @param onEntryClick Invoked when an enabled entry is clicked.
 * @param onManageExtensions Opens the extensions settings.
 * @param onEdit Opens the settings of the menu, from a button below the rows; without it there is no such button.
 */
@Suppress("LongParameterList")
@Composable
fun MoreMenu(
    rows: List<List<MoreMenuEntry>>,
    state: MoreMenuState,
    extensions: List<WebExtensionMenuItem>,
    extensionsExpanded: Boolean,
    onToggleExtensions: () -> Unit,
    onEntryClick: (MoreMenuEntry) -> Unit,
    onManageExtensions: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val unseen = rememberUnseenNewThings()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        rows.forEach { row ->
            val single = row.singleOrNull()
            if (single is MoreMenuEntry.BuiltIn && single.item == MoreMenuItem.EXTENSIONS) {
                ExtensionsDrawer(
                    extensions = extensions,
                    expanded = extensionsExpanded,
                    onToggle = onToggleExtensions,
                    onManage = onManageExtensions,
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { entry ->
                        MenuTile(
                            look = entry.look(state),
                            onClick = { onEntryClick(entry) },
                            modifier = Modifier.weight(1f),
                            isNew = entry.isNewAmong(unseen),
                        )
                    }
                    repeat(MoreMenuLayout.MAX_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (onEdit != null) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onEdit,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    // The new buttons for the menu wait in its settings.
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_edit_24),
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .newDot(NewThing.CLEAR_DATA_ENTRIES in unseen, inset = EDIT_DOT_INSET),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dejavu_menu_edit))
                }
            }
        }
    }
}

@Composable
private fun MenuTile(look: EntryLook, onClick: () -> Unit, modifier: Modifier = Modifier, isNew: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = look.enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp)
            .alpha(if (look.enabled) 1f else DISABLED_ALPHA),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .newDot(isNew)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (look.active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
        ) {
            Icon(
                painter = painterResource(look.icon),
                contentDescription = null,
                tint = if (look.active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        WordFittedText(
            text = look.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/** A full width row that opens a drawer with the buttons of the installed extensions. */
@Composable
fun ExtensionsDrawer(
    extensions: List<WebExtensionMenuItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onManage: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(targetValue = if (expanded) HALF_TURN else 0f, label = "ExtensionsChevron")
    val taps = rememberIconTaps()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    taps.tap()
                    if (extensions.isEmpty()) onManage() else onToggle()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TapIcon(icon = R.drawable.dejavu_ic_extensions_24, taps = taps, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dejavu_menu_extensions),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (extensions.isEmpty()) {
                        stringResource(R.string.dejavu_menu_manage_extensions)
                    } else {
                        extensions.joinToString(", ") { it.label }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (extensions.isNotEmpty()) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_chevron_down_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation),
                )
            }
        }
        AnimatedVisibility(visible = expanded && extensions.isNotEmpty()) {
            ExtensionList(extensions = extensions, onManage = onManage)
        }
    }
}

/** The buttons of the installed extensions, then a link to their settings. */
@Composable
fun ExtensionList(extensions: List<WebExtensionMenuItem>, onManage: () -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_ALPHA))
        extensions.forEach { extension -> ExtensionRow(extension) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_ALPHA))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onManage)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.dejavu_ic_settings_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.dejavu_menu_manage_extensions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ExtensionRow(extension: WebExtensionMenuItem) {
    val enabled = extension.enabled != false
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = extension.onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        val icon = extension.icon
        if (icon != null) {
            Image(bitmap = icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp))
        } else {
            Icon(
                painter = painterResource(R.drawable.dejavu_ic_extensions_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = extension.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val badge = extension.badgeText?.takeIf { it.isNotBlank() }
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = extension.badgeTextColor?.let { Color(it) } ?: MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(extension.badgeBackgroundColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** Lists the tabs the shown tab can be put in a split view with. */
@Composable
fun SplitTabPicker(
    tabs: List<TabSessionState>,
    onPick: (TabSessionState) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_back_24),
                contentDescription = stringResource(R.string.dejavu_menu_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.dejavu_menu_split_with),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (tabs.isEmpty()) {
            Text(
                text = stringResource(R.string.dejavu_menu_split_no_tabs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        tabs.forEach { tab ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPick(tab) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                val icon = tab.content.icon
                if (icon != null) {
                    Image(bitmap = icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp))
                } else {
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_globe_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = tab.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val EDIT_DOT_INSET = 1.dp
private const val HANDLE_ALPHA = 0.4f
private const val DISABLED_ALPHA = 0.38f
private const val DIVIDER_ALPHA = 0.5f
private const val HALF_TURN = 180f
