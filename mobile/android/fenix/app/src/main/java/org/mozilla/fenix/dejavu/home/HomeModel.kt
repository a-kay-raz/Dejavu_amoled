/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.support.ktx.util.URLStringUtils
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.workspaces.MAX_FOLDER_DEPTH
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.isSamePage

/** Tabs, pinned tabs (essentials included) and folders picked in selection mode. */
data class Selection(
    val tabIds: Set<String> = emptySet(),
    val pinIds: Set<String> = emptySet(),
    val folderIds: Set<String> = emptySet(),
) {
    val size: Int
        get() = tabIds.size + pinIds.size + folderIds.size

    fun toggleTab(id: String) = copy(tabIds = tabIds.toggle(id))

    /** Picks pinned tab [id], or leaves it out together with the folders it is in, which are no longer whole. */
    fun togglePin(id: String, state: WorkspaceState): Selection = if (id in pinIds) {
        copy(pinIds = pinIds - id, folderIds = folderIds - state.ancestorIds(id))
    } else {
        copy(pinIds = pinIds + id)
    }

    /**
     * Picks folder [id] with everything inside it, its subfolders and their tabs included, or leaves all of that out
     * together with the folders it is in, which are no longer whole.
     */
    fun toggleFolder(id: String, state: WorkspaceState): Selection {
        val inside = state.descendantIds(id)
        val (subfolders, tabs) = state.pins.filter { it.id in inside }.partition { it.isFolder }
        val wholeFolder = subfolders.map { it.id }.toSet() + id
        val folderTabs = tabs.map { it.id }.toSet()
        return if (id in folderIds) {
            copy(pinIds = pinIds - folderTabs, folderIds = folderIds - wholeFolder - state.ancestorIds(id))
        } else {
            copy(pinIds = pinIds + folderTabs, folderIds = folderIds + wholeFolder)
        }
    }

    /** How many items are picked, leaving out those inside a picked folder, which go wherever it goes. */
    fun topLevelCount(state: WorkspaceState): Int {
        val inside = folderIds.flatMap { state.descendantIds(it) }.toSet()
        return tabIds.size + (pinIds - inside).size + (folderIds - inside).size
    }

    operator fun plus(other: Selection) =
        Selection(tabIds + other.tabIds, pinIds + other.pinIds, folderIds + other.folderIds)

    private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

    companion object {
        /** Folder [id] picked with everything inside it. */
        fun ofFolder(id: String, state: WorkspaceState): Selection = Selection().toggleFolder(id, state)
    }
}

/**
 * What an action applies to.
 *
 * @property tabs Open tabs that are not pinned.
 * @property pins Pinned tabs and essentials, open or closed, that are not inside one of [folders].
 * @property pinnedTabs Open browser tabs backing [pins].
 * @property folders Folders that are not inside another one of [folders].
 * @property folderPins Pinned tabs anywhere inside [folders].
 * @property folderTabs Open browser tabs backing [folderPins].
 * @property canAddSubfolder Whether [folders] is a single folder that can hold one more level of folders.
 * @property splitTabIds Open tabs among the targets that are part of a split view.
 * @property restricted Whether the targets are in the workspace of a device on the account, which follows that device:
 *   their tabs can be opened and closed, and closing one, pinned or not, closes it on the device too, but nothing the
 *   device would not take, like pinning, folders, names, essentials or containers. Unpinning only changes how Dejavu
 *   shows a tab, which stays pinned on the device.
 */
data class ActionTargets(
    val tabs: List<TabSessionState> = emptyList(),
    val pins: List<PinnedItem> = emptyList(),
    val pinnedTabs: List<TabSessionState> = emptyList(),
    val folders: List<PinnedItem> = emptyList(),
    val folderPins: List<PinnedItem> = emptyList(),
    val folderTabs: List<TabSessionState> = emptyList(),
    val canAddSubfolder: Boolean = false,
    val splitTabIds: Set<String> = emptySet(),
    val restricted: Boolean = false,
) {
    /** Every pinned tab the action reaches, the ones inside [folders] included. */
    val allPins: List<PinnedItem>
        get() = pins + folderPins

    val openTabs: List<TabSessionState>
        get() = tabs + pinnedTabs + folderTabs

    val awakeTabs: List<TabSessionState>
        get() = openTabs.filter { it.isAwake }

    /** Pinned items that move as a whole: [pins] and [folders] with everything inside them. */
    val itemIds: Set<String>
        get() = (pins + folders).map { it.id }.toSet()

    /** Whether the targets are exactly one folder. */
    val isSingleFolder: Boolean
        get() = folders.size == 1 && tabs.isEmpty() && pins.isEmpty()

    /** URL and title of every target: the live page of open pinned tabs, the page closed ones open on. */
    val links: List<Pair<String, String>>
        get() {
            val open = (pinnedTabs + folderTabs).associateBy { it.id }
            return tabs.map { it.content.url to it.content.title } + allPins.map { pin ->
                val tab = pin.tabId?.let { open[it] }
                if (tab != null) tab.content.url to tab.content.title else pin.pageUrl to (pin.openTitle ?: pin.title)
            }
        }

    /** Containers the targets are in; `null` stands for no container. */
    val containerIds: Set<String?>
        get() {
            val open = (pinnedTabs + folderTabs).associateBy { it.id }
            val pinContainers = allPins.map { pin ->
                val tab = pin.tabId?.let { open[it] }
                if (tab != null) tab.contextId else pin.containerId
            }
            return (tabs.map { it.contextId } + pinContainers).toSet()
        }

    /** Pinned tabs whose open tab has left the pinned page, with that tab. */
    val changedPins: List<Pair<PinnedItem, TabSessionState>>
        get() {
            val open = (pinnedTabs + folderTabs).associateBy { it.id }
            return allPins.mapNotNull { pin ->
                val tab = pin.tabId?.let { open[it] } ?: return@mapNotNull null
                val url = pin.url ?: return@mapNotNull null
                (pin to tab).takeIf { !isSamePage(tab.content.url, url) }
            }
        }

    /** Closed pinned tabs that open on another page than their pinned one, the page their tab closed on. */
    val closedChangedPins: List<PinnedItem>
        get() {
            val openIds = (pinnedTabs + folderTabs).map { it.id }.toSet()
            return allPins.filter { pin -> pin.openUrl != null && pin.tabId !in openIds }
        }

    fun isEmpty() = tabs.isEmpty() && pins.isEmpty() && folders.isEmpty()

    companion object {
        fun of(state: WorkspaceState, tabs: List<TabSessionState>, selection: Selection): ActionTargets {
            val selectedFolders = state.pins.filter { it.isFolder && it.id in selection.folderIds }
            val nested = selectedFolders.flatMap { state.descendantIds(it.id) }.toSet()
            val folders = selectedFolders.filter { it.id !in nested }
            val pins = state.pins.filter { !it.isFolder && it.id in selection.pinIds && it.id !in nested }
            val folderPins = state.pins.filter { !it.isFolder && it.id in nested }
            val targets = ActionTargets(
                tabs = tabs.filter { it.id in selection.tabIds },
                pins = pins,
                pinnedTabs = tabs.backing(pins),
                folders = folders,
                folderPins = folderPins,
                folderTabs = tabs.backing(folderPins),
                canAddSubfolder = folders.singleOrNull()?.let { state.folderDepth(it.id) < MAX_FOLDER_DEPTH } == true,
                restricted = selection.tabIds.any { state.isDeviceWorkspace(state.workspaceOf(it)) } ||
                    (pins + folders).any { state.isDeviceWorkspace(it.workspaceId) },
            )
            return targets.copy(splitTabIds = targets.openTabs.map { it.id }.filter { state.splitOf(it) != null }.toSet())
        }

        /** A pinned tab or essential with its open tab, if any, [restricted] in the workspace of a device. */
        fun ofPin(pin: PinnedItem, tab: TabSessionState?, restricted: Boolean = false) =
            ActionTargets(pins = listOf(pin), pinnedTabs = listOfNotNull(tab), restricted = restricted)

        private fun List<TabSessionState>.backing(pins: List<PinnedItem>): List<TabSessionState> {
            val tabIds = pins.mapNotNull { it.tabId }.toSet()
            return filter { it.id in tabIds }
        }
    }
}

/** Whether the tab currently holds a loaded page in memory, as opposed to sleeping or not restored yet. */
val TabSessionState.isAwake: Boolean
    get() = engineState.engineSession != null

/** The page's title, or the name of its site when the page has no title of its own. */
val TabSessionState.displayTitle: String
    get() = pageLabel(content.title, content.url)

/**
 * Whether [old] and [new] look the same on the home and in its rows, dialogs and actions: the same tabs in the same
 * order, with the same id, address, title, icon, container and awake state. Those are all the tab fields the home
 * reads; the rows of private tabs show their icon. Load progress, security and media change many times per page load
 * and are left out.
 */
fun isSameOnHome(old: List<TabSessionState>, new: List<TabSessionState>): Boolean =
    old === new || (old.size == new.size && old.indices.all { isSameOnHome(old[it], new[it]) })

private fun isSameOnHome(old: TabSessionState, new: TabSessionState): Boolean =
    old === new || (
        old.id == new.id &&
            old.content.url == new.content.url &&
            old.content.title == new.content.title &&
            old.content.icon === new.content.icon &&
            old.contextId == new.contextId &&
            old.isAwake == new.isAwake
        )

/**
 * The page's [title], like browsers show on tabs, or its address when the page has no title or its title is its own
 * address. Any other title is shown as the page wrote it, never made to look like an address.
 */
fun pageLabel(title: String?, url: String): String {
    val text = title?.trim().orEmpty()
    return if (text.isEmpty() || isSamePage(text, url)) displayUrl(url) else text
}

/** [url] without its scheme and "www.", the way Firefox shows addresses. */
fun displayUrl(url: String): String = URLStringUtils.toDisplayUrl(url).toString()

/** The title shown for open tab [tab]: the one the user gave it, or its page's. */
fun WorkspaceState.titleOf(tab: TabSessionState): String =
    tabTitles[tab.id] ?: pinOf(tab.id)?.takeIf { it.staticLabel }?.title ?: tab.displayTitle

/** The title shown for this pinned tab, open in [tab] or closed. */
fun PinnedItem.label(tab: TabSessionState?): String = when {
    staticLabel -> title
    tab != null -> tab.displayTitle
    else -> pageLabel(openTitle ?: title, pageUrl)
}

/** The page a closed pinned tab opens on: the one its tab closed on, or else its pinned address. */
val PinnedItem.pageUrl: String
    get() = openUrl ?: url.orEmpty()

/**
 * What can be done to the tabs in the workspace of a device on the account, see [ActionTargets.restricted]: closing
 * them, which the device follows, and what stays on this device.
 */
internal val DeviceTabActions = setOf(
    TabAction.CLOSE,
    TabAction.SLEEP,
    TabAction.UNPIN,
    TabAction.BOOKMARK,
    TabAction.SHARE,
    TabAction.COPY_LINK,
    TabAction.DUPLICATE,
    TabAction.SPLIT_VIEW,
    TabAction.SPLIT_TOP_AND_BOTTOM,
    TabAction.SPLIT_SIDE_BY_SIDE,
    TabAction.UNSPLIT,
)

/**
 * Whether [targets] can move to [this] workspace. The workspace of a device on the account only takes tabs, which open
 * on the device: pinned tabs and folders cannot go there.
 */
fun Workspace.canTake(targets: ActionTargets): Boolean =
    device == null || (targets.allPins.isEmpty() && targets.folders.isEmpty())

/** The workspace [this] are in, or `null` when they are only essentials, which every workspace shows. */
fun ActionTargets.workspaceIn(state: WorkspaceState): String? =
    tabs.firstOrNull()?.let { state.workspaceOf(it.id) } ?: (pins + folders).firstNotNullOfOrNull { it.workspaceId }

/** Whether [this] action can do anything for [targets]. Actions on folders reach every tab inside them. */
fun TabAction.appliesTo(targets: ActionTargets): Boolean =
    (!targets.restricted || this in DeviceTabActions) && appliesToTargets(targets)

@Suppress("CyclomaticComplexMethod")
private fun TabAction.appliesToTargets(targets: ActionTargets): Boolean = when (this) {
    // In the workspace of a device, closing also takes pinned tabs away, since it closes them on the device.
    TabAction.CLOSE -> targets.openTabs.isNotEmpty() || (targets.restricted && targets.allPins.isNotEmpty())
    TabAction.PIN -> targets.tabs.isNotEmpty()
    TabAction.UNPIN -> targets.allPins.any { !it.essential }
    TabAction.SLEEP -> targets.awakeTabs.isNotEmpty()
    TabAction.BOOKMARK, TabAction.SHARE, TabAction.COPY_LINK -> targets.links.any { it.first.isNotBlank() }
    TabAction.DUPLICATE -> targets.tabs.isNotEmpty() || targets.allPins.isNotEmpty()
    TabAction.RESET_PIN -> targets.changedPins.isNotEmpty() || targets.closedChangedPins.isNotEmpty()
    TabAction.REPLACE_PIN -> targets.changedPins.isNotEmpty()
    TabAction.ADD_TO_ESSENTIALS -> targets.tabs.isNotEmpty() || targets.allPins.any { !it.essential }
    TabAction.REMOVE_FROM_ESSENTIALS -> targets.pins.any { it.essential }
    TabAction.NEW_FOLDER -> !targets.isEmpty() && !targets.isSingleFolder
    TabAction.NEW_SUBFOLDER -> targets.isSingleFolder && targets.canAddSubfolder
    TabAction.RENAME_FOLDER -> targets.isSingleFolder
    TabAction.UNPACK_FOLDER -> targets.folders.isNotEmpty() && targets.tabs.isEmpty() && targets.pins.isEmpty()
    TabAction.DELETE -> targets.pins.isNotEmpty() || targets.folders.isNotEmpty()
    TabAction.MOVE_TO_FOLDER, TabAction.MOVE_TO_WORKSPACE -> !targets.isEmpty()
    TabAction.CHANGE_CONTAINER -> targets.tabs.isNotEmpty() || targets.allPins.isNotEmpty()
    TabAction.RENAME_TAB -> targets.folders.isEmpty() && targets.tabs.size + targets.pins.size == 1
    // Only two tabs picked themselves, not the tabs inside a folder.
    TabAction.SPLIT_VIEW, TabAction.SPLIT_TOP_AND_BOTTOM, TabAction.SPLIT_SIDE_BY_SIDE ->
        targets.folders.isEmpty() && targets.tabs.size + targets.pins.size == 2 && targets.openTabs.size == 2
    TabAction.UNSPLIT -> targets.splitTabIds.isNotEmpty()
}

/** Whether [this] action can do anything for [targets]. */
fun RowAction.appliesTo(targets: ActionTargets): Boolean = when (this) {
    is RowAction.BuiltIn -> action.appliesTo(targets)
    // A custom action sends pages to a server, so it is not offered for private tabs.
    is RowAction.Custom -> targets.tabs.none { it.content.private } && targets.links.any { it.first.isNotBlank() }
}

/**
 * The buttons of one row: the user's choice, without the actions that do nothing for it. Closing cannot take away a
 * pinned tab or a folder, so on their rows Close puts their tabs to sleep, and once a pinned tab sleeps or is closed,
 * Close unpins it, in the workspace of a device too. [isPinned] is for the rows of pinned tabs and folders.
 */
fun rowActions(configured: List<RowAction>, targets: ActionTargets, isPinned: Boolean): List<RowAction> =
    configured
        .mapNotNull { if (isPinned && it == CloseAction) pinnedClose(targets) else it }
        .distinctBy { it.key }
        .filter { it.appliesTo(targets) }

private val CloseAction = RowAction.BuiltIn(TabAction.CLOSE)

/** What Close does on the row of a pinned tab or a folder: sleep while a tab is awake, then unpin a pinned tab. */
private fun pinnedClose(targets: ActionTargets): RowAction? = when {
    targets.awakeTabs.isNotEmpty() -> RowAction.BuiltIn(TabAction.SLEEP)
    targets.folders.isEmpty() -> RowAction.BuiltIn(TabAction.UNPIN)
    else -> null
}

/**
 * What a middle-click does on a row: what its Close button does, except unpinning, which a stray click should not do.
 * So it closes the tab of an unpinned row, puts the tab of a pinned one to sleep, and does nothing otherwise.
 */
fun middleClickAction(targets: ActionTargets, isPinned: Boolean): RowAction? =
    rowActions(listOf(CloseAction), targets, isPinned)
        .firstOrNull { (it as? RowAction.BuiltIn)?.action != TabAction.UNPIN }

/** Folder that a new folder grouping [targets] goes in: their common parent, while it can hold one more level. */
fun WorkspaceState.newFolderParent(targets: ActionTargets): String? =
    (targets.pins + targets.folders).map { it.parentId }.distinct().singleOrNull()
        ?.takeIf { folderDepth(it) < MAX_FOLDER_DEPTH }

/** Where dragged tabs, pinned items and folders are dropped. */
sealed interface DropTarget {
    /** Into a folder of the pinned section. */
    data class IntoFolder(val folderId: String) : DropTarget

    /** Next to a pinned tab or folder, as its sibling. */
    data class NextToPin(val pinId: String, val after: Boolean) : DropTarget

    /** At the start or the end of the pinned section's top level. */
    data class PinnedEdge(val atEnd: Boolean) : DropTarget

    /** Next to an unpinned tab. */
    data class NextToTab(val tabId: String, val after: Boolean) : DropTarget

    /** Onto an unpinned tab, which the one dragged tab joins in a split view, on the same row. */
    data class SplitWith(val tabId: String) : DropTarget

    /** At the start of the unpinned tabs, right below New Tab. */
    data object UnpinnedStart : DropTarget

    /** Into the essentials shared by every workspace. */
    data object Essentials : DropTarget
}

/** Whether a drag on a workspace page can be dropped into the essentials, and whether it would be now. */
enum class EssentialsDrop { NONE, AVAILABLE, ACTIVE }

/** A pinned item and how deep it is nested in folders. */
data class PinnedEntry(val item: PinnedItem, val depth: Int)

/**
 * Flattens the pinned tree of [workspaceId] in display order. Children of collapsed folders are left out unless
 * [expandAll] is set. Items whose parent folder is missing are shown at the top level.
 */
fun WorkspaceState.pinnedTree(workspaceId: String, expandAll: Boolean = false): List<PinnedEntry> {
    val items = pins.filter { it.workspaceId == workspaceId }
    val folderIds = items.filter { it.isFolder }.map { it.id }.toSet()
    val byParent = items.groupBy { item -> item.parentId?.takeIf { it in folderIds } }
    val result = mutableListOf<PinnedEntry>()
    val visited = mutableSetOf<String>()

    fun visit(parentId: String?, depth: Int) {
        byParent[parentId].orEmpty().forEach { item ->
            if (!visited.add(item.id)) return@forEach
            result += PinnedEntry(item, depth)
            if (item.isFolder && (expandAll || !item.collapsed)) visit(item.id, depth + 1)
        }
    }
    visit(null, 0)
    return result
}

/** Dialogs of the home screen. */
sealed interface HomeDialog {
    data class EditWorkspace(val workspaceId: String?) : HomeDialog
    data class DeleteWorkspace(val workspaceId: String) : HomeDialog
    data class NewFolder(
        val workspaceId: String,
        val parentId: String?,
        val targets: ActionTargets = ActionTargets(),
    ) : HomeDialog
    data class RenameFolder(val folder: PinnedItem) : HomeDialog

    /** Renames pinned tab [pinId], or unpinned tab [tabId], currently titled [title]. */
    data class RenameTab(val pinId: String?, val tabId: String?, val title: String) : HomeDialog
    data class DeleteItems(val targets: ActionTargets) : HomeDialog
    data class MoveToFolder(val workspaceId: String, val targets: ActionTargets) : HomeDialog
    data class MoveToWorkspace(val workspaceId: String, val targets: ActionTargets) : HomeDialog
    data class ChangeContainer(val targets: ActionTargets) : HomeDialog
}
