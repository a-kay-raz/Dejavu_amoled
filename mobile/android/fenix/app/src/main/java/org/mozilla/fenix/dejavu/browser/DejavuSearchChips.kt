/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.R
import org.mozilla.fenix.components.appstate.AppAction.SearchAction.SearchEngineSelected
import org.mozilla.fenix.components.appstate.AppAction.SearchAction.SearchStarted
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.NewTabContainerChoice
import org.mozilla.fenix.dejavu.containers.ContainerIcon
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.TemporaryContainerIcon
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.ui.IconTaps
import org.mozilla.fenix.dejavu.ui.TapIcon
import org.mozilla.fenix.dejavu.ui.rememberIconTaps
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.search.ext.searchEngineShortcuts

/** Where a new tab opens, as the chips above the address bar offer it. */
private sealed interface TabPlace {
    data class Container(val contextId: String) : TabPlace
    data object Temporary : TabPlace
    data object Private : TabPlace
}

/**
 * The chips above the address bar while searching. The first line has the containers the search can open in, a new
 * temporary container and a private tab. For a search that opens a new tab ([newTab]) the workspace's own choice is
 * picked: its default container, a temporary container when every new tab gets one, or nothing. A search from a shown
 * tab starts on the tab's own place, and picking another one opens the search in a new tab there. Below are the search
 * engines, the one searched with picked. They take the place of the search engine button of the address bar.
 */
@Composable
fun DejavuSearchChips(newTab: Boolean, modifier: Modifier = Modifier) {
    val fenix = components
    val editedTab = remember(newTab) {
        if (newTab) null else fenix.appStore.state.searchState.sourceTabId?.let { fenix.core.store.state.findTab(it) }
    }
    Column(modifier = modifier.fillMaxWidth().background(searchScreenBackground()).padding(vertical = 4.dp)) {
        ContainerChips(editedTab)
        EngineChips()
    }
}

/** Where [tab] is, as the chips show it: in one of the containers, in a temporary one, private, or nowhere. */
private fun placeOf(tab: TabSessionState, records: List<ContainerRecord>): TabPlace? {
    if (tab.content.private) return TabPlace.Private
    val contextId = tab.contextId ?: return null
    val isTemporary = records.any { it.contextId == contextId && it.showsAsTemporary }
    return if (isTemporary) TabPlace.Temporary else TabPlace.Container(contextId)
}

/** The places a search can open in; with an [editedTab], the place of that tab is picked to start with. */
@Composable
private fun ContainerChips(editedTab: TabSessionState?) {
    val context = LocalContext.current
    val storage = remember { DejavuContainerStorage.get(context) }
    val records by storage.records.collectAsState()
    val repository = remember { WorkspaceRepository.peek() ?: WorkspaceRepository.get(context) }
    val workspace = repository.state.collectAsState().value.activeWorkspace
    val containers = records.orEmpty().filterNot { it.showsAsTemporary }
    // The workspace of a device on the account only takes tabs without a container.
    val isDevice = workspace?.device != null
    val editedPlace = remember(editedTab) { editedTab?.let { placeOf(it, records.orEmpty()) } }
    var picked by remember {
        if (editedTab != null) {
            // An earlier choice must not move this search to a new tab.
            NewTabContainerChoice.clear()
            return@remember mutableStateOf(editedPlace)
        }
        val choice = NewTabContainerChoice.peek()
        val default = when {
            workspace == null || isDevice -> null
            containers.any { it.contextId == workspace.containerId } -> TabPlace.Container(checkNotNull(workspace.containerId))
            DejavuSettings.peek()?.temporaryContainersByDefault?.value == true -> TabPlace.Temporary
            else -> null
        }
        mutableStateOf(
            when {
                choice == null -> default
                choice.private -> TabPlace.Private
                choice.pick is ContainerPick.Container -> TabPlace.Container(choice.pick.contextId)
                choice.pick == ContainerPick.Temporary -> TabPlace.Temporary
                else -> null
            },
        )
    }

    fun pick(place: TabPlace) {
        val next = place.takeUnless { it == picked }
        picked = next
        when {
            // Back on the edited tab's own place, the search stays in that tab.
            editedTab != null && next == editedPlace -> NewTabContainerChoice.clear()
            next == null -> NewTabContainerChoice.set(ContainerPick.NoContainer)
            next is TabPlace.Container -> NewTabContainerChoice.set(ContainerPick.Container(next.contextId))
            next == TabPlace.Temporary -> NewTabContainerChoice.set(ContainerPick.Temporary)
            next == TabPlace.Private -> NewTabContainerChoice.setPrivate()
        }
    }

    ChipRow {
        if (!isDevice) {
            containers.forEach { container ->
                val place = TabPlace.Container(container.contextId)
                SearchChip(label = container.name, selected = picked == place, onClick = { pick(place) }) {
                    ContainerIcon(container, size = CHIP_ICON)
                }
            }
            SearchChip(
                label = stringResource(R.string.dejavu_search_chip_temporary),
                selected = picked == TabPlace.Temporary,
                onClick = { pick(TabPlace.Temporary) },
            ) { taps ->
                TemporaryContainerIcon(size = CHIP_ICON, taps = taps)
            }
        }
        SearchChip(
            label = stringResource(R.string.dejavu_private_workspace),
            selected = picked == TabPlace.Private,
            onClick = { pick(TabPlace.Private) },
        ) { taps ->
            TapIcon(
                icon = R.drawable.dejavu_ic_private_tab_24,
                taps = taps,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CHIP_ICON),
            )
        }
    }
}

@Composable
private fun EngineChips() {
    val fenix = components
    val isPrivate = fenix.appStore.state.mode.isPrivate
    val engines by fenix.core.store.observeAsComposableState { it.search.searchEngineShortcuts }
    val defaultEngine by fenix.core.store.observeAsComposableState { it.search.selectedOrDefaultSearchEngine(isPrivate) }
    val chosen by fenix.appStore.observeAsComposableState { it.searchState.selectedSearchEngine?.searchEngine }
    val current = chosen ?: defaultEngine
    // Like the search engine menu: the search engines first, then searching the bookmarks, tabs and history.
    val (general, local) = engines.partition { it.type != SearchEngine.Type.APPLICATION }
    ChipRow {
        (general + local).forEach { engine ->
            SearchChip(
                label = engine.name,
                selected = engine.id == current?.id,
                onClick = {
                    // The same as picking it from the search engine menu.
                    fenix.appStore.dispatch(SearchEngineSelected(engine, true))
                    fenix.appStore.dispatch(
                        SearchStarted(
                            tabId = fenix.appStore.state.searchState.sourceTabId,
                            source = fenix.appStore.state.searchState.searchAccessPoint,
                        ),
                    )
                },
            ) {
                val painter = remember(engine.id) { BitmapPainter(engine.icon.asImageBitmap()) }
                if (engine.type == SearchEngine.Type.APPLICATION) {
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(CHIP_ICON),
                    )
                } else {
                    Image(painter = painter, contentDescription = null, modifier = Modifier.size(CHIP_ICON))
                }
            }
        }
    }
}

/**
 * The chip that fills [link], the link in the clipboard, into the address bar with [onClick], on a line of its own
 * above the other chips. It shows the link's domain after its name.
 */
@Composable
fun DejavuClipboardChip(link: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val domain = link?.let(::domainOf)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(searchScreenBackground())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.dejavu_ic_clipboard_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CHIP_ICON),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.awesomebar_clipboard_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            domain?.let {
                Spacer(Modifier.width(6.dp))
                // A long domain gives way to the label.
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** The scroll of a [ChipRow] and the middle of the row on the screen, so a picked [SearchChip] can move there. */
private class ChipRowState(val scroll: ScrollState) {
    var middle by mutableStateOf<Float?>(null)
}

private val LocalChipRow = compositionLocalOf<ChipRowState?> { null }

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    val state = remember(scroll) { ChipRowState(scroll) }
    CompositionLocalProvider(LocalChipRow provides state) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { state.middle = it.middleInWindow() }
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun SearchChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable (IconTaps) -> Unit,
) {
    val taps = rememberIconTaps()
    val row = LocalChipRow.current
    var middle by remember { mutableStateOf<Float?>(null) }
    // A picked chip moves to the middle of its row, also one picked for the user, so one past the edge comes into view.
    LaunchedEffect(selected) {
        if (!selected || row == null) return@LaunchedEffect
        val offset = snapshotFlow { middle?.let { chip -> row.middle?.let { chip - it } } }
            .filterNotNull()
            .first()
        row.scroll.animateScrollBy(offset)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .onGloballyPositioned { middle = it.middleInWindow() }
            .height(36.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton) {
                taps.tap()
                onClick()
            }
            .padding(horizontal = 12.dp),
    ) {
        leading(taps)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The middle of this on the screen across, also when the row it scrolls in hides part of it or all of it. */
private fun LayoutCoordinates.middleInWindow() = positionInWindow().x + size.width / 2f

private val CHIP_ICON = 18.dp
