/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

/**
 * A workspace groups tabs. Every normal tab belongs to exactly one workspace.
 *
 * @property id Stable identifier, shared with other devices when syncing.
 * @property name User visible name.
 * @property containerId Container (GeckoView context ID) new tabs of this workspace open in, or `null`.
 * @property icon Emoji shown with the name, or the file name of one of Zen's built-in icons as Zen stores it.
 * @property theme Background of the workspace, or `null` for the plain background.
 * @property createdAt Creation time in milliseconds.
 * @property updatedAt Last change time in milliseconds, used to resolve sync conflicts.
 * @property device The device on the Mozilla account whose open tabs the workspace shows, or `null` for the user's own
 *   workspaces. Such a workspace follows the device: tabs can only be opened and closed in it, and its name, icon and
 *   lack of a container come from the device. Local to this device.
 * @property locked Whether the workspace is locked: its page shows blurred and its tabs stay covered until the user
 *   unlocks it with their fingerprint, face or screen lock, see [WorkspaceLock]. Local to this device.
 * @property savesHistory Whether the pages its tabs open are saved to history, the recent searches and the recently
 *   closed tabs, see [org.mozilla.fenix.dejavu.history.WorkspaceHistory]. Local to this device.
 */
data class Workspace(
    val id: String,
    val name: String,
    val containerId: String? = null,
    val icon: String? = null,
    val theme: WorkspaceTheme? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val device: SyncedDevice? = null,
    val locked: Boolean = false,
    val savesHistory: Boolean = true,
) {
    /**
     * Whether it looks like the workspace a fresh Dejavu starts with, named [defaultName]: with its [HomeIcon] or no
     * icon, without a container, and with the gold theme or none.
     */
    fun isLikeNew(defaultName: String): Boolean =
        name == defaultName && (icon == null || icon == HomeIcon) && containerId == null &&
            (theme == null || theme == WorkspaceTheme.Gold)

    companion object {
        /** The icon of the workspace a fresh Dejavu starts with: a house. */
        val HomeIcon: String = String(Character.toChars(0x1F3E0))
    }
}

/** A kind of device on the Mozilla account whose open tabs a workspace shows, see [Workspace.device]. */
enum class SyncedDevice(val key: String) {
    DESKTOP("desktop"),
    PHONE("phone"),
    ;

    companion object {
        fun fromKey(key: String?): SyncedDevice? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A workspace background, like Zen's space themes: a gradient of one to three colors.
 *
 * @property colors ARGB colors of the gradient.
 * @property opacity How strongly the gradient covers the background, from 0 to 1.
 * @property texture Strength of the grain drawn over the gradient, from 0 to 1.
 */
data class WorkspaceTheme(
    val colors: List<Int>,
    val opacity: Float = DEFAULT_OPACITY,
    val texture: Float = 0f,
) {
    companion object {
        const val DEFAULT_OPACITY = 0.35f

        /** The gold of Dejavu's logo, the theme of the workspace a fresh Dejavu starts with. */
        val Gold = WorkspaceTheme(colors = listOf(0xFFFFEDC2, 0xFFFFD67A, 0xFFF2B84B).map { it.toInt() })
    }
}

/** Deepest folder nesting allowed, matching Zen's default `zen.folders.max-subfolders`. */
const val MAX_FOLDER_DEPTH = 5

/** Most essentials allowed, matching Zen's default `zen.tabs.essentials.max`. */
const val MAX_ESSENTIALS = 12

/** Kind of a [PinnedItem]. */
enum class PinKind(val key: String) {
    TAB("tab"),
    FOLDER("folder"),
}

/**
 * An entry of a workspace's pinned section: a pinned tab or a folder. Items are stored as a flat list where each item
 * points to its workspace and parent folder, so folders can be nested and the list maps one to one to sync records.
 * The order of the list is the display order among siblings.
 *
 * @property id Stable identifier, shared with other devices when syncing.
 * @property workspaceId Workspace the item belongs to, or `null` for essentials, which every workspace shows.
 * @property parentId Folder containing the item, or `null` at the top of the pinned section.
 * @property kind Whether this is a pinned tab or a folder.
 * @property title Folder name, or the pinned tab's title.
 * @property url URL a pinned tab reopens; `null` for folders.
 * @property containerId Container a pinned tab reopens in; `null` for folders and tabs without a container.
 * @property collapsed Whether a folder is collapsed. Local to this device, like in Zen.
 * @property icon Folder icon name, as Zen stores it. Not shown yet.
 * @property essential Whether this pinned tab is one of the essentials shared by all workspaces, like in Zen.
 * @property staticLabel Whether [title] was given by the user and is shown instead of the page title, like Zen's
 *   renamed tabs.
 * @property tabId Open browser tab currently backing a pinned tab. Local to this device and never synced.
 * @property openUrl Page a pinned tab was on when its tab closed, when that is not [url]: the pin opens there again
 *   until it is reset. Local to this device and never synced.
 * @property openTitle Title of the page at [openUrl].
 * @property createdAt Creation time in milliseconds.
 * @property updatedAt Last change time in milliseconds, used to resolve sync conflicts.
 * @property color Color of a folder, named like the colors of Firefox's tab groups: blue, purple, cyan, orange, yellow,
 *   pink, green, red or gray. Local to this device and never synced.
 */
data class PinnedItem(
    val id: String,
    val workspaceId: String?,
    val parentId: String?,
    val kind: PinKind,
    val title: String,
    val url: String? = null,
    val containerId: String? = null,
    val collapsed: Boolean = false,
    val icon: String? = null,
    val essential: Boolean = false,
    val staticLabel: Boolean = false,
    val tabId: String? = null,
    val openUrl: String? = null,
    val openTitle: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val color: String? = null,
) {
    val isFolder: Boolean
        get() = kind == PinKind.FOLDER
}

/**
 * Two tabs shown together in the browser, like Zen's split views. Local to this device, like tab assignments.
 *
 * @property id Stable identifier.
 * @property tabIds The two browser tabs of the split: the one shown first, on top or on the left, then the other.
 * @property orientation How the two tabs share the browser, when the screen has room for it.
 */
data class SplitView(
    val id: String,
    val tabIds: List<String>,
    val orientation: SplitOrientation = SplitOrientation.TOP_AND_BOTTOM,
)

/**
 * Two pinned tabs that Zen shows as one split view, synced from it. They show as one row, and open together as a
 * [SplitView].
 *
 * @property id The id of Zen's split view.
 * @property pinIds The two pinned tabs, the one shown first, on top or on the left, then the other.
 * @property orientation How Zen shows them.
 */
data class PinnedSplit(val id: String, val pinIds: List<String>, val orientation: SplitOrientation)

/** How the two tabs of a split view share the browser: one above the other, or next to each other. */
enum class SplitOrientation(val key: String) {
    TOP_AND_BOTTOM("top_and_bottom"),
    SIDE_BY_SIDE("side_by_side"),
    ;

    companion object {
        fun fromKey(key: String?): SplitOrientation? = entries.firstOrNull { it.key == key }
    }
}

/**
 * @property workspaces Workspaces in display order. Never empty.
 * @property pins Pinned tabs and folders of all workspaces.
 * @property activeWorkspaceId The workspace shown on the home screen. New tabs are assigned to it.
 * @property assignments Tab ID to workspace ID. Local to this device.
 * @property splits Tabs shown together in the browser. Local to this device.
 * @property tabTitles Titles the user gave to tabs that are not pinned, by tab ID. Local to this device.
 * @property tabSyncIds Sync ids of open tabs that sync under another id than their own, by tab ID: the id of the pin
 *   they were the tab of, once it was unpinned, so the tab stays the same tab on other devices. Local to this device.
 * @property pinnedSplits Pinned tabs that Zen shows as split views, as its sync last had them.
 * @property openedByPages Open tabs that a page opened by itself, like a pop-up, rather than the user. They stay on
 *   this device and are never sent to a Firefox computer, until the user moves them to a workspace. Local to this
 *   device.
 * @property starters Ids of the workspaces and pinned items a fresh install started with as examples, see
 *   [StarterWorkspaces]. Local to this device.
 */
data class WorkspaceState(
    val workspaces: List<Workspace>,
    val pins: List<PinnedItem>,
    val activeWorkspaceId: String,
    val assignments: Map<String, String>,
    val splits: List<SplitView> = emptyList(),
    val tabTitles: Map<String, String> = emptyMap(),
    val tabSyncIds: Map<String, String> = emptyMap(),
    val pinnedSplits: List<PinnedSplit> = emptyList(),
    val openedByPages: Set<String> = emptySet(),
    val starters: Set<String> = emptySet(),
) {
    val activeIndex: Int
        get() = workspaces.indexOfFirst { it.id == activeWorkspaceId }.coerceAtLeast(0)

    val activeWorkspace: Workspace?
        get() = workspaces.firstOrNull { it.id == activeWorkspaceId }

    /** Essentials in display order. */
    val essentials: List<PinnedItem>
        get() = pins.filter { it.essential }

    /**
     * The essentials shown in a workspace with default container [containerId]: all of them, or with [perContainer]
     * only those whose [essentialsGroupOf] is that container, like Zen's container-specific essentials.
     */
    fun essentialsFor(containerId: String?, perContainer: Boolean): List<PinnedItem> =
        if (perContainer) essentials.filter { essentialsGroupOf(it.containerId) == containerId } else essentials

    /**
     * Which workspaces show essentials of container [containerId] when every container has its own essentials: those
     * with that default container, or `null` for those without one, which also show the essentials of containers no
     * workspace has as its default, like in Zen.
     */
    fun essentialsGroupOf(containerId: String?): String? =
        containerId?.takeIf { id -> workspaces.any { it.containerId == id } }

    /** Whether workspace [workspaceId] shows the open tabs of a device on the account, see [Workspace.device]. */
    fun isDeviceWorkspace(workspaceId: String?): Boolean =
        workspaceId != null && workspaces.any { it.id == workspaceId && it.device != null }

    /** Returns the workspace ID for [tabId], falling back to the active workspace for unassigned tabs. */
    fun workspaceOf(tabId: String): String = assignments[tabId] ?: activeWorkspaceId

    /** Returns the id the open tab [tabId] syncs under when it is not pinned. */
    fun syncIdOf(tabId: String): String = tabSyncIds[tabId] ?: tabId

    /** Returns the pin backed by the open tab [tabId], if any. */
    fun pinOf(tabId: String): PinnedItem? = pins.firstOrNull { it.tabId == tabId }

    /** Returns the split view tab [tabId] is part of, if any. */
    fun splitOf(tabId: String): SplitView? = splits.firstOrNull { tabId in it.tabIds }

    /** Returns the split view of Zen pinned item [pinId] is part of, if any. */
    fun pinnedSplitOf(pinId: String): PinnedSplit? = pinnedSplits.firstOrNull { pinId in it.pinIds }

    /** Returns the direct children of [parentId] in [workspaceId], in display order. */
    fun children(workspaceId: String, parentId: String?): List<PinnedItem> =
        pins.filter { it.workspaceId == workspaceId && it.parentId == parentId }

    /** Returns how many folders [folderId] is nested in, counting itself: 1 for a top level folder. */
    fun folderDepth(folderId: String?): Int {
        var depth = 0
        var current = folderId?.let { id -> pins.firstOrNull { it.id == id && it.isFolder } }
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.id)) {
            depth++
            current = current.parentId?.let { id -> pins.firstOrNull { it.id == id && it.isFolder } }
        }
        return depth
    }

    /** Returns how many folder levels [folderId] spans, counting itself: 1 for a folder without subfolders. */
    fun folderHeight(folderId: String, seen: Set<String> = emptySet()): Int {
        if (folderId in seen) return 0
        val children = pins.filter { it.parentId == folderId && it.isFolder }
        return 1 + (children.maxOfOrNull { folderHeight(it.id, seen + folderId) } ?: 0)
    }

    /** Returns the IDs of the folders [itemId] is nested in, from its own folder up. */
    fun ancestorIds(itemId: String): Set<String> {
        val byId = pins.associateBy { it.id }
        val result = LinkedHashSet<String>()
        var parent = byId[itemId]?.parentId
        while (parent != null && result.add(parent)) parent = byId[parent]?.parentId
        return result
    }

    /** Returns the IDs of every item nested below [folderId]. */
    fun descendantIds(folderId: String): Set<String> {
        val result = mutableSetOf<String>()
        var level = setOf(folderId)
        while (level.isNotEmpty()) {
            level = pins.filter { it.parentId in level && it.id !in result }.map { it.id }.toSet()
            result += level
        }
        return result
    }
}

/** Whether [a] and [b] are the same page, leaving out the part after "#" and a slash at the end. */
fun isSamePage(a: String, b: String): Boolean = a.substringBefore('#').trimEnd('/') == b.substringBefore('#').trimEnd('/')
