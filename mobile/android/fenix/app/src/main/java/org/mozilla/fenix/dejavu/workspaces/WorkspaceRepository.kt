/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.fenix.R

/**
 * What is needed to pin an open tab.
 */
data class PinSource(
    val tabId: String,
    val url: String,
    val title: String,
    val containerId: String?,
)

/** Where dropped items go in a workspace's pinned section. */
sealed interface PinPlacement {
    /** At the end of [folderId]. */
    data class Into(val folderId: String) : PinPlacement

    /** Next to [itemId], as its sibling. */
    data class Next(val itemId: String, val after: Boolean) : PinPlacement

    /** At the start or the end of the top level. */
    data class Edge(val atEnd: Boolean) : PinPlacement
}

/**
 * Keeps workspaces, pinned tabs, folders and tab assignments, and persists them in [SharedPreferences].
 */
@Suppress("TooManyFunctions")
class WorkspaceRepository private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether workspaces saved by an earlier start were found, unlike on a fresh install or after clearing data. */
    val foundSavedState = prefs.contains(KEY_STATE)

    private val defaultName = context.getString(R.string.dejavu_workspace_default_name)
    private val _state = MutableStateFlow(
        WorkspaceSerializer.read(prefs.getString(KEY_STATE, null), defaultName, System.currentTimeMillis()),
    )

    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    /** Saves the workspaces as they are, so that the ones a fresh Dejavu starts with keep their ids. */
    fun save() = prefs.edit { putString(KEY_STATE, WorkspaceSerializer.write(_state.value)) }

    /**
     * Gives a fresh install the examples it starts with, see [StarterWorkspaces]: the Work workspace named [workName],
     * and in Home the folder [folderName] with its pinned tabs, and the essentials. Only once, and only while Home is
     * the one workspace, untouched and without pinned tabs. Saved at once.
     */
    fun giveStarters(workName: String, folderName: String) {
        if (prefs.getBoolean(KEY_STARTERS_GIVEN, false)) return
        prefs.edit { putBoolean(KEY_STARTERS_GIVEN, true) }
        val now = now()
        mutate(saveNow = true) { state ->
            val home = state.workspaces.singleOrNull()?.takeIf { it.isLikeNew(defaultName) && state.pins.isEmpty() }
            if (home == null) state else StarterWorkspaces.around(state, home, workName, folderName, now)
        }
    }

    /** Removes the examples a fresh install started with that are still as they came, see [withoutStarters]. */
    fun dropStarters() = mutate(saveNow = true) { it.withoutStarters() }

    fun selectWorkspace(id: String) = mutate { state ->
        if (state.workspaces.none { it.id == id }) state else state.copy(activeWorkspaceId = id)
    }

    /** Adds a workspace and shows it; returns its id. */
    @Suppress("LongParameterList")
    fun addWorkspace(
        name: String,
        containerId: String?,
        icon: String?,
        theme: WorkspaceTheme?,
        locked: Boolean = false,
        savesHistory: Boolean = true,
    ): String =
        update { state ->
            val now = now()
            val workspace = Workspace(
                id = WorkspaceSerializer.newWorkspaceId(),
                name = name.trim().ifEmpty { defaultName },
                containerId = containerId,
                icon = icon,
                theme = theme,
                createdAt = now,
                updatedAt = now,
                locked = locked,
                savesHistory = savesHistory,
            )
            state.copy(workspaces = state.workspaces + workspace, activeWorkspaceId = workspace.id) to workspace.id
        }

    /**
     * Changes workspace [id]; [locked] and [savesHistory] stay as they are when `null`. The workspace of a device on
     * the account only takes [theme] and those two: the rest is the device's.
     */
    @Suppress("LongParameterList")
    fun updateWorkspace(
        id: String,
        name: String,
        containerId: String?,
        icon: String?,
        theme: WorkspaceTheme?,
        locked: Boolean? = null,
        savesHistory: Boolean? = null,
    ) = mutate { state ->
        state.copy(
            workspaces = state.workspaces.map {
                if (it.id != id) return@map it
                val privacy = it.copy(locked = locked ?: it.locked, savesHistory = savesHistory ?: it.savesHistory)
                if (it.device != null) {
                    privacy.copy(theme = theme, updatedAt = now())
                } else {
                    privacy.copy(
                        name = name.trim().ifEmpty { it.name },
                        containerId = containerId,
                        icon = icon,
                        theme = theme,
                        updatedAt = now(),
                    )
                }
            },
        )
    }

    /** Moves workspace [id] to position [index] among the workspaces. */
    fun moveWorkspace(id: String, index: Int) = mutate { state ->
        val workspace = state.workspaces.firstOrNull { it.id == id } ?: return@mutate state
        val others = state.workspaces - workspace
        state.copy(workspaces = others.toMutableList().apply { add(index.coerceIn(0, others.size), workspace) })
    }

    /**
     * Deletes a workspace, moving its tabs and pinned items to a neighbouring workspace. The last one is kept, and so are
     * the workspaces of devices on the account, which sync adds and removes.
     */
    fun deleteWorkspace(id: String) = mutate { state ->
        val index = state.workspaces.indexOfFirst { it.id == id }
        if (index < 0 || state.workspaces.size == 1 || state.workspaces[index].device != null) return@mutate state
        val target = state.workspaces[if (index > 0) index - 1 else 1].id
        state.copy(
            workspaces = state.workspaces.filterNot { it.id == id },
            pins = state.pins.map { if (it.workspaceId == id) it.copy(workspaceId = target, updatedAt = now()) else it },
            assignments = state.assignments.mapValues { (_, ws) -> if (ws == id) target else ws },
            activeWorkspaceId = if (state.activeWorkspaceId == id) target else state.activeWorkspaceId,
        )
    }

    /**
     * Removes every workspace with its pinned tabs and folders, and the essentials, leaving the one empty workspace a
     * fresh Dejavu starts with. The workspaces of devices on the account stay as they are, since sync adds and removes
     * them. Saved at once.
     */
    fun startFresh() = mutate(saveNow = true) { state ->
        val fresh = WorkspaceSerializer.read(null, defaultName, now())
        val devices = state.workspaces.filter { it.device != null }
        val deviceIds = devices.map { it.id }.toSet()
        val assignments = state.assignments.filterValues { it in deviceIds }
        fresh.copy(
            workspaces = fresh.workspaces + devices,
            pins = state.pins.filter { it.workspaceId in deviceIds },
            assignments = assignments,
            splits = state.splits.filter { split -> split.tabIds.all { it in assignments } },
            openedByPages = state.openedByPages.filter { it in assignments }.toSet(),
        )
    }

    /** Points every workspace and pinned tab using container [oldId] to [newId], or to no container. */
    fun replaceContainer(oldId: String, newId: String?) = mutate { state ->
        val now = now()
        state.copy(
            workspaces = state.workspaces.map {
                if (it.containerId == oldId) it.copy(containerId = newId, updatedAt = now) else it
            },
            pins = state.pins.map { if (it.containerId == oldId) it.copy(containerId = newId, updatedAt = now) else it },
        )
    }

    /** Makes pinned tabs [pinIds] reopen in container [containerId], or without a container when it is `null`. */
    fun setPinContainer(pinIds: Set<String>, containerId: String?) = mutate { state ->
        val now = now()
        state.copy(
            pins = state.pins.map {
                if (it.id in pinIds && !it.isFolder && it.containerId != containerId) {
                    it.copy(containerId = containerId, updatedAt = now)
                } else {
                    it
                }
            },
        )
    }

    /** Removes the pinned tabs of container [containerId] or backed by one of [tabIds]. */
    fun removePinsOf(containerId: String, tabIds: Set<String>) = mutate { state ->
        state.copy(
            pins = state.pins.filterNot { !it.isFolder && (it.containerId == containerId || it.tabId in tabIds) },
        )
    }

    /**
     * Keeps what Dejavu knows of tab [tabId], which is about to be opened or replaced, until the browser shows it:
     * [syncWithTabs] would otherwise forget it in between.
     */
    fun expectTab(tabId: String) {
        expectedTabs.add(tabId)
    }

    /** Puts tab [tabId], open or about to be opened, in workspace [workspaceId]. */
    fun assignTab(tabId: String, workspaceId: String) = mutate { state ->
        if (state.workspaces.none { it.id == workspaceId }) state else state.copy(assignments = state.assignments + (tabId to workspaceId))
    }

    /** Marks tab [tabId] as opened by a page rather than the user, see [WorkspaceState.openedByPages]. */
    fun markOpenedByPage(tabId: String) = mutate { state -> state.copy(openedByPages = state.openedByPages + tabId) }

    /** Hands the workspace, pin and split view of tab [oldTabId] over to [newTabId], which replaces it. */
    fun replaceTab(oldTabId: String, newTabId: String) = mutate { state ->
        val workspaceId = state.assignments[oldTabId] ?: state.pinOf(oldTabId)?.workspaceId
        state.copy(
            pins = state.pins.map { if (it.tabId == oldTabId) it.copy(tabId = newTabId) else it },
            assignments = (state.assignments - oldTabId).let { if (workspaceId != null) it + (newTabId to workspaceId) else it },
            splits = state.splits.map { split ->
                split.copy(tabIds = split.tabIds.map { if (it == oldTabId) newTabId else it })
            },
            tabTitles = state.tabTitles[oldTabId]?.let { state.tabTitles - oldTabId + (newTabId to it) } ?: state.tabTitles,
            tabSyncIds = state.tabSyncIds - oldTabId,
            openedByPages = state.openedByPages.map { if (it == oldTabId) newTabId else it }.toSet(),
        )
    }

    /**
     * Shows tabs [first] and [second] together in the browser, [first] on top or on the left as [orientation] has them.
     * They leave any split view they were in.
     */
    fun createSplit(first: String, second: String, orientation: SplitOrientation = SplitOrientation.TOP_AND_BOTTOM) =
        mutate { state ->
            if (first == second) return@mutate state
            val others = state.splits.filterNot { first in it.tabIds || second in it.tabIds }
            state.copy(splits = others + SplitView(newId(), listOf(first, second), orientation))
        }

    /** Shows the split view of tab [tabId] in [orientation] from now on. */
    fun setSplitOrientation(tabId: String, orientation: SplitOrientation) = mutate { state ->
        state.copy(splits = state.splits.map { if (tabId in it.tabIds) it.copy(orientation = orientation) else it })
    }

    /** Ends the split views that any of [tabIds] is part of. */
    fun unsplit(tabIds: Set<String>) = mutate { state ->
        state.copy(splits = state.splits.filterNot { split -> split.tabIds.any { it in tabIds } })
    }

    /**
     * Moves pinned items [itemIds] of [workspaceId] and pins the open tabs [newPins] at [placement]. Essentials among
     * [itemIds] leave the essentials and become pinned tabs of [workspaceId], like in Zen. Folders that would end up
     * inside themselves or deeper than [MAX_FOLDER_DEPTH] stay where they are.
     */
    fun placePins(
        workspaceId: String,
        itemIds: Set<String>,
        newPins: List<PinSource>,
        placement: PinPlacement,
    ) = mutate { state ->
        val parentId = when (placement) {
            is PinPlacement.Into -> placement.folderId
            is PinPlacement.Next -> state.pins.firstOrNull { it.id == placement.itemId }?.parentId
            is PinPlacement.Edge -> null
        }
        if (parentId != null && state.pins.none { it.id == parentId && it.isFolder && it.workspaceId == workspaceId }) {
            return@mutate state
        }
        val moving = state.pins.filter { item ->
            item.id in itemIds &&
                (item.workspaceId == workspaceId || item.essential) &&
                !(
                    item.isFolder && parentId != null && (
                        parentId == item.id ||
                            parentId in state.descendantIds(item.id) ||
                            state.folderDepth(parentId) + state.folderHeight(item.id) > MAX_FOLDER_DEPTH
                        )
                    )
        }
        val now = now()
        val alreadyPinned = state.pins.mapNotNull { it.tabId }.toSet()
        val created = newPins.filter { it.tabId !in alreadyPinned }.distinctBy { it.tabId }.map { source ->
            state.newPin(source, workspaceId, parentId, essential = false, now = now)
        }
        val placed = moving.map {
            it.copy(parentId = parentId, workspaceId = workspaceId, essential = false, updatedAt = now)
        } + created
        if (placed.isEmpty()) return@mutate state

        val movingIds = moving.map { it.id }.toSet()
        val rest = state.pins.filterNot { it.id in movingIds }
        val index = when (placement) {
            is PinPlacement.Next -> rest.indexOfFirst { it.id == placement.itemId }
                .let { if (it < 0) rest.size else if (placement.after) it + 1 else it }
            is PinPlacement.Edge -> if (placement.atEnd) {
                rest.size
            } else {
                rest.indexOfFirst { it.workspaceId == workspaceId && it.parentId == null }.let { if (it < 0) rest.size else it }
            }
            is PinPlacement.Into -> rest.size
        }
        val placedTabs = placed.mapNotNull { it.tabId }
        state.copy(
            pins = rest.toMutableList().apply { addAll(index, placed) },
            assignments = state.assignments + placedTabs.associateWith { workspaceId },
            tabTitles = state.tabTitles - placedTabs.toSet(),
        )
    }

    /**
     * Makes pinned tabs [pinIds] and the open tabs [sources] essentials, shown in every workspace, as long as there is
     * room for them below [MAX_ESSENTIALS]. With [perContainer] the essentials shown together, see
     * [WorkspaceState.essentialsGroupOf], have their own limit.
     */
    fun addToEssentials(sources: List<PinSource>, pinIds: Set<String>, perContainer: Boolean) = mutate { state ->
        val now = now()
        fun groupOf(containerId: String?) = state.essentialsGroupOf(containerId).takeIf { perContainer }
        val counts = state.essentials.groupingBy { groupOf(it.containerId) }.eachCount().toMutableMap()
        fun takeRoom(containerId: String?): Boolean {
            val group = groupOf(containerId)
            val count = counts[group] ?: 0
            if (count >= MAX_ESSENTIALS) return false
            counts[group] = count + 1
            return true
        }
        val converted = state.pins
            .filter { it.id in pinIds && !it.isFolder && !it.essential && takeRoom(it.containerId) }
            .map { it.copy(essential = true, workspaceId = null, parentId = null, updatedAt = now) }
        val alreadyPinned = state.pins.mapNotNull { it.tabId }.toSet()
        val fresh = sources.filter { it.tabId !in alreadyPinned }.distinctBy { it.tabId }
        val created = fresh.filter { takeRoom(it.containerId) }.map { source ->
            state.newPin(source, workspaceId = null, parentId = null, essential = true, now = now)
        }
        if (converted.isEmpty() && created.isEmpty()) return@mutate state
        val convertedIds = converted.map { it.id }.toSet()
        state.copy(
            pins = state.pins.filterNot { it.id in convertedIds } + converted + created,
            tabTitles = state.tabTitles - created.mapNotNull { it.tabId }.toSet(),
            tabSyncIds = state.tabSyncIds - created.mapNotNull { it.tabId }.toSet(),
        )
    }

    /** Removes essentials [pinIds]; their open tabs stay open as normal tabs of [workspaceId]. */
    fun removeFromEssentials(pinIds: Set<String>, workspaceId: String) = mutate { state ->
        val removed = state.pins.filter { it.id in pinIds && it.essential }
        if (removed.isEmpty()) return@mutate state
        val removedIds = removed.map { it.id }.toSet()
        state.copy(
            pins = state.pins.filterNot { it.id in removedIds },
            assignments = state.assignments + removed.mapNotNull { it.tabId }.associateWith { workspaceId },
            tabTitles = state.titlesKeptFrom(removed),
            tabSyncIds = state.syncIdsKeptFrom(removed),
        )
    }

    /**
     * Moves essential [pinId] to position [index] among the essentials, or with [perContainer] among the essentials
     * shown with it. The other essentials keep their places.
     */
    fun moveEssential(pinId: String, index: Int, perContainer: Boolean) = mutate { state ->
        val item = state.essentials.firstOrNull { it.id == pinId } ?: return@mutate state
        val itemGroup = state.essentialsGroupOf(item.containerId)
        val inGroup = { other: PinnedItem -> !perContainer || state.essentialsGroupOf(other.containerId) == itemGroup }
        val group = state.essentials.filter(inGroup).toMutableList()
        group.remove(item)
        group.add(index.coerceIn(0, group.size), item)
        val reordered = group.iterator()
        val essentials = state.essentials.map { if (inGroup(it)) reordered.next() else it }
        state.copy(pins = state.pins.filterNot { it.essential } + essentials)
    }

    /**
     * Removes pinned items: folders with everything inside them, pinned tabs and essentials. The caller closes the
     * open tabs of the removed pins.
     */
    fun deleteItems(itemIds: Set<String>) = mutate { state ->
        val removed = itemIds + itemIds.flatMap { state.descendantIds(it) }
        state.copy(pins = state.pins.filterNot { it.id in removed })
    }

    /** Pins open tabs in their workspaces, optionally inside [parentId]. Tabs that are already pinned are skipped. */
    fun pinTabs(sources: List<PinSource>, parentId: String? = null) = mutate { state -> state.withPins(sources, parentId) }

    /** Removes pinned tabs. Their open tabs stay open as normal tabs. */
    fun unpin(pinIds: Set<String>) = mutate { state ->
        val removed = state.pins.filter { it.id in pinIds && !it.isFolder }
        state.copy(
            pins = state.pins - removed.toSet(),
            tabTitles = state.titlesKeptFrom(removed),
            tabSyncIds = state.syncIdsKeptFrom(removed),
        )
    }

    /** Gives pinned tab [pinId] the title [name], or shows its page title again when [name] is blank. */
    fun renamePin(pinId: String, name: String) = mutate { state ->
        state.copy(
            pins = state.pins.map {
                if (it.id != pinId || it.isFolder) {
                    it
                } else if (name.isBlank()) {
                    it.copy(staticLabel = false, updatedAt = now())
                } else {
                    it.copy(title = name.trim(), staticLabel = true, updatedAt = now())
                }
            },
        )
    }

    /**
     * Keeps the titles of pinned tabs up to date with their pinned pages, so closed pins show their last title. Only a
     * tab showing its pinned address updates the title, not the pages it went on to. Titles the user gave are kept, as
     * are titles that are only an address.
     *
     * @param pages The address and title of every open tab, by tab id.
     */
    fun refreshPinTitles(pages: Map<String, Pair<String, String>>) = mutate { state ->
        var changed = false
        val pins = state.pins.map { pin ->
            val page = pin.tabId?.let(pages::get)
            val title = page?.second?.trim()
            val onPinnedPage = page != null && pin.url != null && page.first.trimEnd('/') == pin.url.trimEnd('/')
            if (pin.staticLabel || !onPinnedPage || title.isNullOrEmpty() || title == pin.title || title.startsWith("http")) {
                pin
            } else {
                changed = true
                // An example a fresh install started with stays as it came, see withoutStarters.
                val asItCame = pin.id in state.starters && pin.updatedAt == pin.createdAt
                pin.copy(title = title, updatedAt = if (asItCame) pin.updatedAt else now())
            }
        }
        if (changed) state.copy(pins = pins) else state
    }

    /** Makes [url] the address pinned tab [pinId] resets to, like Zen's "Replace pinned URL with current". */
    fun replacePinUrl(pinId: String, url: String, title: String) = mutate { state ->
        state.copy(
            pins = state.pins.map { pin ->
                if (pin.id != pinId || pin.isFolder) {
                    pin
                } else {
                    pin.copy(
                        url = url,
                        title = if (pin.staticLabel) pin.title else title.ifBlank { url },
                        openUrl = null,
                        openTitle = null,
                        updatedAt = now(),
                    )
                }
            },
        )
    }

    /**
     * Remembers the pages that the tabs of [pages] (address and title by tab id) are on as they close, for the pinned
     * tabs among them: the pin opens on that page again rather than on its pinned address.
     */
    fun rememberClosingPages(pages: Map<String, Pair<String, String>>) = mutate { state ->
        state.copy(
            pins = state.pins.map { pin ->
                val (url, title) = pin.tabId?.let(pages::get) ?: return@map pin
                if (pin.url == null || isSamePage(url, pin.url)) {
                    pin.copy(openUrl = null, openTitle = null)
                } else {
                    pin.copy(openUrl = url, openTitle = title)
                }
            },
        )
    }

    /** Lets the closed pinned tabs [pinIds] open on their pinned address again. */
    fun forgetOpenPages(pinIds: Set<String>) = mutate { state ->
        state.copy(pins = state.pins.map { if (it.id in pinIds) it.copy(openUrl = null, openTitle = null) else it })
    }

    /**
     * Lets the closed pinned tabs whose page history no longer has, like after the user deleted it, open on their
     * pinned address again. [isVisited] tells for each page of a list whether history has it.
     */
    suspend fun forgetUnvisitedOpenPages(isVisited: suspend (List<String>) -> List<Boolean>) {
        val pins = state.value.pins.filter { it.openUrl != null }
        if (pins.isEmpty()) return
        val visited = runCatching { isVisited(pins.mapNotNull { it.openUrl }) }.getOrNull() ?: return
        forgetOpenPages(pins.filterIndexed { index, _ -> visited.getOrNull(index) != true }.mapTo(HashSet()) { it.id })
    }

    /** Gives unpinned tab [tabId] the title [name], or shows its page title again when [name] is blank. */
    fun renameTab(tabId: String, name: String) = mutate { state ->
        state.copy(tabTitles = if (name.isBlank()) state.tabTitles - tabId else state.tabTitles + (tabId to name.trim()))
    }

    /** Links a reopened browser tab to its pin and keeps it in the pin's workspace. */
    fun attachPinned(pinId: String, tabId: String) = mutate { state ->
        val pin = state.pins.firstOrNull { it.id == pinId } ?: return@mutate state
        state.copy(
            pins = state.pins.map { if (it.id == pinId) it.copy(tabId = tabId) else it },
            assignments = state.assignments + (tabId to (pin.workspaceId ?: state.activeWorkspaceId)),
        )
    }

    /**
     * Creates a folder in [workspaceId] below [parentId], then moves the pinned items [itemIds] into it and pins
     * [newPins] inside it.
     */
    fun createFolder(
        workspaceId: String,
        parentId: String?,
        name: String,
        itemIds: Set<String> = emptySet(),
        newPins: List<PinSource> = emptyList(),
    ) = mutate { state ->
        if (state.folderDepth(parentId) >= MAX_FOLDER_DEPTH) return@mutate state
        val now = now()
        val folder = PinnedItem(
            id = newId(),
            workspaceId = workspaceId,
            parentId = parentId,
            kind = PinKind.FOLDER,
            title = name.trim().ifEmpty { defaultName },
            createdAt = now,
            updatedAt = now,
        )
        state.copy(pins = state.pins + folder).moved(itemIds, folder.id).withPins(newPins, folder.id)
    }

    fun renameFolder(folderId: String, name: String) = mutate { state ->
        state.copy(
            pins = state.pins.map {
                if (it.id == folderId && name.isNotBlank()) it.copy(title = name.trim(), updatedAt = now()) else it
            },
        )
    }

    /** Opens or closes folder [folderId]. Saved right away, so the folder looks the same after the app is closed. */
    fun toggleFolder(folderId: String) = mutate(saveNow = true) { state ->
        state.copy(pins = state.pins.map { if (it.id == folderId) it.copy(collapsed = !it.collapsed) else it })
    }

    /** Removes a folder and moves its content one level up, to where the folder was, like Zen's "Unpack Folder". */
    fun unpackFolder(folderId: String) = mutate { state ->
        val folder = state.pins.firstOrNull { it.id == folderId && it.isFolder } ?: return@mutate state
        val now = now()
        val children = state.pins.filter { it.parentId == folderId }.map { it.copy(parentId = folder.parentId, updatedAt = now) }
        val childIds = children.map { it.id }.toSet()
        state.copy(
            pins = state.pins.filterNot { it.id in childIds }.flatMap { if (it.id == folderId) children else listOf(it) },
        )
    }

    /**
     * Moves unpinned tabs and pinned items (with everything inside folders) to another workspace, the pinned items to
     * the top level of its pinned section, where they come in the list of pinned items. Tabs a page opened become the
     * user's once moved.
     */
    fun moveToWorkspace(tabIds: Set<String>, itemIds: Set<String>, workspaceId: String) =
        mutate { state ->
            if (state.workspaces.none { it.id == workspaceId }) return@mutate state
            val nested = itemIds.flatMap { state.descendantIds(it) }.toSet()
            val movedPins = itemIds + nested
            val movedTabs = tabIds + state.pins.filter { it.id in movedPins }.mapNotNull { it.tabId }
            val now = now()
            val pins = state.pins.map { pin ->
                when (pin.id) {
                    in itemIds -> pin.copy(workspaceId = workspaceId, parentId = null, essential = false, updatedAt = now)
                    in nested -> pin.copy(workspaceId = workspaceId, updatedAt = now)
                    else -> pin
                }
            }
            state.copy(
                pins = pins,
                assignments = state.assignments + movedTabs.associateWith { workspaceId },
                openedByPages = state.openedByPages - movedTabs.toSet(),
            )
        }

    /**
     * Assigns tabs that have no workspace yet to the active workspace, and forgets tabs that no longer exist once
     * the browser state has been restored. Pins whose tab was closed stay pinned without a tab.
     */
    fun syncWithTabs(tabIds: Set<String>, restoreComplete: Boolean) {
        expectedTabs.removeAll(tabIds)
        val present = tabIds + expectedTabs
        mutate { state -> state.withTabs(present, restoreComplete) }
    }

    private fun WorkspaceState.withTabs(tabIds: Set<String>, restoreComplete: Boolean): WorkspaceState {
        val state = this
        val kept = if (restoreComplete) state.assignments.filterKeys { it in tabIds } else state.assignments
        val added = tabIds.filterNot { it in kept }.associateWith { state.activeWorkspaceId }
        val pins = if (restoreComplete) {
            state.pins.map { if (it.tabId != null && it.tabId !in tabIds) it.copy(tabId = null) else it }
        } else {
            state.pins
        }
        val splits = if (restoreComplete) state.splits.filter { split -> split.tabIds.all { it in tabIds } } else state.splits
        val tabTitles = if (restoreComplete) state.tabTitles.filterKeys { it in tabIds } else state.tabTitles
        val tabSyncIds = if (restoreComplete) state.tabSyncIds.filterKeys { it in tabIds } else state.tabSyncIds
        val openedByPages = if (restoreComplete) state.openedByPages.intersect(tabIds) else state.openedByPages
        return state.copy(
            pins = pins,
            assignments = kept + added,
            splits = splits,
            tabTitles = tabTitles,
            tabSyncIds = tabSyncIds,
            openedByPages = openedByPages,
        )
    }

    private fun WorkspaceState.withPins(sources: List<PinSource>, parentId: String?): WorkspaceState {
        val now = now()
        val alreadyPinned = pins.mapNotNull { it.tabId }.toSet()
        val parent = parentId?.let { id -> pins.firstOrNull { it.id == id && it.isFolder } }
        val added = sources.filter { it.tabId !in alreadyPinned }.distinctBy { it.tabId }.map { source ->
            newPin(source, parent?.workspaceId ?: workspaceOf(source.tabId), parent?.id, essential = false, now = now)
        }
        return copy(
            pins = pins + added,
            assignments = assignments + added.associate { it.tabId!! to (it.workspaceId ?: activeWorkspaceId) },
            tabTitles = tabTitles - added.mapNotNull { it.tabId }.toSet(),
            tabSyncIds = tabSyncIds - added.mapNotNull { it.tabId }.toSet(),
        )
    }

    /** A pinned tab for [source]. A title the user gave the tab becomes the pin's static label. */
    private fun WorkspaceState.newPin(
        source: PinSource,
        workspaceId: String?,
        parentId: String?,
        essential: Boolean,
        now: Long,
    ): PinnedItem {
        val customTitle = tabTitles[source.tabId]
        // A pin takes over the id its tab synced under, so other devices pin that tab instead of opening another.
        val syncId = syncIdOf(source.tabId).takeIf { id -> pins.none { it.id == id } }
        return PinnedItem(
            id = syncId ?: newId(),
            workspaceId = workspaceId,
            parentId = parentId,
            kind = PinKind.TAB,
            title = customTitle ?: source.title.ifBlank { source.url },
            url = source.url,
            containerId = source.containerId,
            essential = essential,
            staticLabel = customTitle != null,
            tabId = source.tabId,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Titles the user gave to [removed] pins, kept for their open tabs. */
    private fun WorkspaceState.titlesKeptFrom(removed: List<PinnedItem>): Map<String, String> =
        tabTitles + removed.filter { it.staticLabel && it.tabId != null }.associate { it.tabId!! to it.title }

    /** The ids of [removed] pins, which their open tabs sync under from now on. */
    private fun WorkspaceState.syncIdsKeptFrom(removed: List<PinnedItem>): Map<String, String> =
        tabSyncIds + removed.filter { it.tabId != null && it.tabId != it.id }.associate { it.tabId!! to it.id }

    private fun WorkspaceState.moved(itemIds: Set<String>, folderId: String?): WorkspaceState {
        val folder = folderId?.let { id -> pins.firstOrNull { it.id == id && it.isFolder } }
        if (folderId != null && folder == null) return this
        val blocked = if (folder == null) {
            emptySet()
        } else {
            itemIds.filter { id ->
                val item = pins.firstOrNull { it.id == id }
                folder.id == id ||
                    folder.id in descendantIds(id) ||
                    (item?.isFolder == true && folderDepth(folder.id) + folderHeight(id) > MAX_FOLDER_DEPTH)
            }
        }
        val moving = pins.filter { it.id in itemIds && it.id !in blocked }
        if (moving.isEmpty()) return this
        val now = now()
        val workspaceId = folder?.workspaceId
        val movingIds = moving.map { it.id }.toSet()
        val nested = movingIds.flatMap { descendantIds(it) }.toSet()
        val updated = moving.map {
            it.copy(parentId = folderId, workspaceId = workspaceId ?: it.workspaceId, essential = false, updatedAt = now)
        }
        val rest = pins.filterNot { it.id in movingIds }.map {
            if (workspaceId != null && it.id in nested && it.workspaceId != workspaceId) it.copy(workspaceId = workspaceId) else it
        }
        val movedTabs = (updated + rest.filter { it.id in nested }).mapNotNull { it.tabId }
        return copy(
            pins = rest + updated,
            assignments = if (workspaceId == null) assignments else assignments + movedTabs.associateWith { workspaceId },
        )
    }

    /**
     * Applies [transform] as one change and returns the second value of its result. [transform] may run more than
     * once when other changes happen at the same time, so it must not have side effects.
     */
    fun <T> update(transform: (WorkspaceState) -> Pair<WorkspaceState, T>): T {
        var result: Pair<WorkspaceState, T>? = null
        mutate { state -> transform(state).also { result = it }.first }
        return checkNotNull(result).second
    }

    /**
     * Applies [transform] and saves the result. Saving happens in the background unless [saveNow] is set, in which
     * case it is written before returning, so it is not lost when the app is closed right after.
     */
    private fun mutate(saveNow: Boolean = false, transform: (WorkspaceState) -> WorkspaceState) {
        var changed: WorkspaceState? = null
        _state.update { old -> transform(old).also { if (it != old) changed = it } }
        changed?.let { prefs.edit(commit = saveNow) { putString(KEY_STATE, WorkspaceSerializer.write(it)) } }
    }

    private fun now() = System.currentTimeMillis()

    private val expectedTabs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun newId() = UUID.randomUUID().toString()

    companion object {
        // Kept from before the rename to Dejavu, so saved data still loads.
        private const val PREFS_NAME = "kaizen_workspaces"
        private const val KEY_STATE = "state"
        private const val KEY_STARTERS_GIVEN = "starters_given"

        @Volatile
        private var instance: WorkspaceRepository? = null

        /** Returns the process wide [WorkspaceRepository]. Reads from disk on first use. */
        fun get(context: Context): WorkspaceRepository =
            instance ?: synchronized(this) {
                instance ?: WorkspaceRepository(context.applicationContext).also { instance = it }
            }

        /** Returns the repository if it has already been loaded, without touching the disk. */
        fun peek(): WorkspaceRepository? = instance
    }
}
