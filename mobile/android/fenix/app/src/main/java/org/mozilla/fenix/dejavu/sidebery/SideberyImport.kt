/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sidebery

import java.util.UUID
import mozilla.components.browser.state.state.ContainerState
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.workspaces.MAX_ESSENTIALS
import org.mozilla.fenix.dejavu.workspaces.MAX_FOLDER_DEPTH
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.dejavu.workspaces.isSamePage

/**
 * Brings a Sidebery snapshot into Dejavu's workspaces. Each tab panel adds to the workspace it was exported from, or to
 * the one with its name, or becomes a new workspace. The panel's group tabs become folders, with the tabs below them as
 * pinned tabs inside; its other tabs become unpinned tabs, which open without loading their pages; and its pinned tabs
 * become pinned tabs of the workspace. The pinned tabs every panel shows become essentials while there is room for
 * them. What Dejavu already has is not added again, so importing the same file twice adds nothing the second time.
 */
internal object SideberyImport {
    /** A container the import adds, as Firefox's container of the same name is not in Dejavu yet. */
    data class NewContainer(
        val contextId: String,
        val name: String,
        val color: ContainerColor,
        val icon: ContainerState.Icon,
    )

    /** An unpinned tab the import opens, without loading its page. */
    data class NewTab(val id: String, val url: String, val title: String, val contextId: String?)

    /**
     * What an import adds.
     *
     * @property leftOut Tabs that are not brought in: pages only a computer opens, like local files, and the tabs of
     *   private windows.
     */
    data class Summary(
        val newWorkspaces: Int = 0,
        val folders: Int = 0,
        val pinnedTabs: Int = 0,
        val essentials: Int = 0,
        val unpinnedTabs: Int = 0,
        val newContainers: Int = 0,
        val leftOut: Int = 0,
    ) {
        /** Whether the import adds nothing. */
        val isEmpty: Boolean
            get() = newWorkspaces + folders + pinnedTabs + essentials + unpinnedTabs == 0
    }

    /**
     * @property state The workspaces with what the import adds.
     * @property containers The containers to add before [state] is used.
     * @property tabs The tabs to open, which [state] already puts in their workspaces.
     */
    data class Plan(
        val state: WorkspaceState,
        val containers: List<NewContainer>,
        val tabs: List<NewTab>,
        val summary: Summary,
    )

    /**
     * Plans the import of [snapshot] into [state]. It has no side effects, so it can run in
     * [org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository.update].
     *
     * @param containers Dejavu's containers.
     * @param openTabs The id and address of every normal tab that is open.
     * @param perContainerEssentials Whether every container has its own essentials, see
     *   [WorkspaceState.essentialsFor].
     * @param defaultName The name of a workspace whose panel has none.
     */
    @Suppress("LongParameterList")
    fun plan(
        snapshot: SideberySnapshot,
        state: WorkspaceState,
        containers: List<ContainerRecord>,
        openTabs: List<Pair<String, String>>,
        perContainerEssentials: Boolean,
        defaultName: String,
        now: Long = System.currentTimeMillis(),
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Plan = Planner(snapshot, state, containers, openTabs, perContainerEssentials, defaultName, now, newId).plan()
}

@Suppress("LongParameterList", "TooManyFunctions")
private class Planner(
    private val snapshot: SideberySnapshot,
    private val state: WorkspaceState,
    containers: List<ContainerRecord>,
    openTabs: List<Pair<String, String>>,
    private val perContainerEssentials: Boolean,
    private val defaultName: String,
    private val now: Long,
    private val newId: () -> String,
) {
    private val permanent = containers.filterNot { it.showsAsTemporary }
    private val workspaces = state.workspaces.toMutableList()
    private val pins = state.pins.toMutableList()
    private val assignments = state.assignments.toMutableMap()
    private val tabTitles = state.tabTitles.toMutableMap()
    private val newContainers = mutableListOf<SideberyImport.NewContainer>()
    private val newTabs = mutableListOf<SideberyImport.NewTab>()
    private val contextIds = mutableMapOf<String, String?>()
    private val workspaceOfPanel = mutableMapOf<String, String>()
    private val essentialCounts = mutableMapOf<String?, Int>()
    private val openUrls: Map<String, List<String>> = openTabs.filter { (tabId, _) -> state.pinOf(tabId) == null }
        .groupBy({ (tabId, _) -> state.workspaceOf(tabId) }, { (_, url) -> url })
    private var summary = SideberyImport.Summary()

    fun plan(): SideberyImport.Plan {
        val matched = mutableSetOf<String>()
        snapshot.panels.forEach { panel -> mapPanel(panel, matched) }
        state.essentials.forEach { essential ->
            val group = essentialsGroupOf(essential.containerId)
            essentialCounts[group] = (essentialCounts[group] ?: 0) + 1
        }
        val fallback = snapshot.panels.firstNotNullOfOrNull { workspaceOfPanel[it.id] }
            ?: state.workspaces.firstOrNull { it.id == state.activeWorkspaceId && it.device == null }?.id
            ?: state.workspaces.firstOrNull { it.device == null }?.id
            ?: state.activeWorkspaceId

        snapshot.windows.forEach { window ->
            if (window.private) {
                val tabs = window.tabs.count { !SideberyFile.isGroupUrl(it.url) && !SideberyFile.isEmptyPage(it.url) }
                summary = summary.copy(leftOut = summary.leftOut + tabs)
                return@forEach
            }
            // Each panel's own tree, as a tab of another panel can come between a group and the tabs inside it.
            val trees = mutableMapOf<String?, Tree>()
            window.tabs.forEach { tab ->
                val workspaceId = tab.panelId?.let(workspaceOfPanel::get)
                if (tab.pinned) {
                    if (!SideberyFile.isEmptyPage(tab.url)) addPinned(tab, workspaceId, fallback)
                } else {
                    val tree = trees.getOrPut(tab.panelId) { Tree(workspaceId ?: fallback) }
                    if (SideberyFile.isEmptyPage(tab.url)) tree.skip(tab) else tree.add(tab)
                }
            }
        }

        return SideberyImport.Plan(
            state = state.copy(workspaces = workspaces, pins = pins, assignments = assignments, tabTitles = tabTitles),
            containers = newContainers,
            tabs = newTabs,
            summary = summary.copy(newContainers = newContainers.size),
        )
    }

    /** Finds the workspace for [panel], the one it was exported from or the one with its name, or adds one. */
    private fun mapPanel(panel: SideberyPanel, matched: MutableSet<String>) {
        val own = workspaces.filter { it.device == null && it.id !in matched }
        val name = panel.name.trim()
        val existing = own.firstOrNull { SideberyExport.panelIdOf(it) == panel.id }
            ?: own.firstOrNull { name.isNotEmpty() && it.name.trim().equals(name, ignoreCase = true) }
        val workspaceId = existing?.id ?: Workspace(
            id = "{${newId()}}",
            name = name.ifEmpty { defaultName },
            containerId = contextIdOf(panel.containerId),
            theme = themeOf(panel.color),
            createdAt = now,
            updatedAt = now,
        ).also { workspace ->
            workspaces += workspace
            summary = summary.copy(newWorkspaces = summary.newWorkspaces + 1)
        }.id
        matched += workspaceId
        workspaceOfPanel[panel.id] = workspaceId
    }

    /** Adds pinned tab [tab] of [workspaceId], or an essential when every panel shows it. */
    private fun addPinned(tab: SideberyTab, workspaceId: String?, fallback: String) {
        val url = SideberyFile.webAddress(tab.url)
        if (url == null) {
            summary = summary.copy(leftOut = summary.leftOut + 1)
            return
        }
        if (workspaceId == null) {
            if (pins.any { it.essential && it.url != null && isSamePage(it.url, url) }) return
            val contextId = contextIdOf(tab.containerId)
            val group = essentialsGroupOf(contextId)
            val count = essentialCounts[group] ?: 0
            if (count < MAX_ESSENTIALS) {
                essentialCounts[group] = count + 1
                pins += pinOf(workspaceId = null, parentId = null, tab, url).copy(essential = true)
                summary = summary.copy(essentials = summary.essentials + 1)
                return
            }
        }
        addPin(workspaceId ?: fallback, parentId = null, tab, url)
    }

    /** Pins [tab] in [workspaceId] inside [parentId], unless it is pinned there already or is an essential. */
    private fun addPin(workspaceId: String, parentId: String?, tab: SideberyTab, url: String) {
        val known = state.pins.any {
            !it.isFolder && it.url != null && isSamePage(it.url, url) &&
                (it.essential || it.workspaceId == workspaceId && it.parentId == parentId)
        }
        if (known) return
        pins += pinOf(workspaceId, parentId, tab, url)
        summary = summary.copy(pinnedTabs = summary.pinnedTabs + 1)
    }

    /**
     * Opens [tab] in [workspaceId], unless the page is open or pinned there already, or is an essential. The snapshots
     * Dejavu exports list the pinned tabs of a workspace among the tabs of its panel, so they come back as such tabs.
     */
    private fun addUnpinned(workspaceId: String, tab: SideberyTab, url: String) {
        val known = openUrls[workspaceId].orEmpty().any { isSamePage(it, url) } || state.pins.any {
            !it.isFolder && (it.essential || it.workspaceId == workspaceId) && it.url != null && isSamePage(it.url, url)
        }
        if (known) return
        val tabId = newId()
        newTabs += SideberyImport.NewTab(tabId, url, tab.title.ifBlank { url }, contextIdOf(tab.containerId))
        assignments[tabId] = workspaceId
        tab.customTitle?.let { tabTitles[tabId] = it }
        summary = summary.copy(unpinnedTabs = summary.unpinnedTabs + 1)
    }

    private fun pinOf(workspaceId: String?, parentId: String?, tab: SideberyTab, url: String) = PinnedItem(
        id = newId(),
        workspaceId = workspaceId,
        parentId = parentId,
        kind = PinKind.TAB,
        title = tab.customTitle ?: tab.title.ifBlank { url },
        url = url,
        containerId = contextIdOf(tab.containerId),
        staticLabel = tab.customTitle != null,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * The folder of group tab [tab] inside [parentId]: the folder of the same name already there, or a new one. `null`
     * when [parentId] is nested as deep as folders go, so the group's tabs go into [parentId] itself.
     */
    private fun folderFor(workspaceId: String, parentId: String?, tab: SideberyTab): String? {
        if (parentId != null && depthOf(parentId) >= MAX_FOLDER_DEPTH) return null
        val name = SideberyFile.groupName(tab.url) ?: tab.customTitle ?: tab.title.trim().ifEmpty { FOLDER_NAME }
        state.pins.firstOrNull {
            it.isFolder && it.workspaceId == workspaceId && it.parentId == parentId &&
                it.title.trim().equals(name, ignoreCase = true)
        }?.let { return it.id }
        val folder = PinnedItem(
            id = newId(),
            workspaceId = workspaceId,
            parentId = parentId,
            kind = PinKind.FOLDER,
            title = name,
            collapsed = tab.folded,
            createdAt = now,
            updatedAt = now,
        )
        pins += folder
        summary = summary.copy(folders = summary.folders + 1)
        return folder.id
    }

    /** How many folders [folderId] is nested in, counting itself, like [WorkspaceState.folderDepth]. */
    private fun depthOf(folderId: String): Int {
        var depth = 0
        var current: String? = folderId
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            depth++
            val id = current
            current = pins.firstOrNull { it.id == id }?.parentId
        }
        return depth
    }

    private fun contextIdOf(sideberyId: String?): String? =
        sideberyId?.let { id -> contextIds.getOrPut(id) { containerFor(id) } }

    /** The container for Sidebery's container [sideberyId]: Dejavu's container of the same name, or a new one. */
    private fun containerFor(sideberyId: String): String? {
        val container = snapshot.containers.firstOrNull { it.id == sideberyId } ?: return null
        val name = container.name.trim().ifEmpty { return null }
        permanent.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }?.let { return it.contextId }
        newContainers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.contextId }
        val color = ContainerColor.fromKey(container.color)?.takeIf { it != ContainerColor.WHITE }
            ?: ContainerColor.GRAY
        val icon = ContainerState.Icon.entries.firstOrNull { it.icon == container.icon }
            ?: ContainerState.Icon.FINGERPRINT
        return SideberyImport.NewContainer(newId(), name, color, icon).also { newContainers += it }.contextId
    }

    /** The theme of a new workspace for a panel of Sidebery's [color], none for gray. */
    private fun themeOf(color: String): WorkspaceTheme? = ContainerColor.fromKey(color)
        ?.takeIf { it != ContainerColor.GRAY && it != ContainerColor.WHITE }
        ?.let { WorkspaceTheme(colors = listOf(it.argb.toInt())) }

    /** Which essentials an essential in container [contextId] counts with, see [WorkspaceState.essentialsGroupOf]. */
    private fun essentialsGroupOf(contextId: String?): String? =
        contextId?.takeIf { id -> perContainerEssentials && workspaces.any { it.containerId == id } }

    /**
     * Where the tabs of one panel of a window go as they go deeper in the panel's tree: inside the folder of the group
     * above them, or among the unpinned tabs outside any group.
     */
    private inner class Tree(private val workspaceId: String) {
        // For each level of the tabs above, the folder the children of the tab at that level go in, or null.
        private val folders = mutableListOf<String?>()

        /** Keeps the place of [tab], which is not brought in, so the tabs below it stay where they belong. */
        fun skip(tab: SideberyTab) {
            folders += parentFolderOf(tab)
        }

        fun add(tab: SideberyTab) {
            val folder = parentFolderOf(tab)
            folders += if (SideberyFile.isGroupUrl(tab.url)) {
                folderFor(workspaceId, folder, tab) ?: folder
            } else {
                val url = SideberyFile.webAddress(tab.url)
                when {
                    url == null -> summary = summary.copy(leftOut = summary.leftOut + 1)
                    folder != null -> addPin(workspaceId, folder, tab, url)
                    else -> addUnpinned(workspaceId, tab, url)
                }
                folder
            }
        }

        /** The folder [tab] goes in, after leaving the levels of the tabs above that are not its parents. */
        private fun parentFolderOf(tab: SideberyTab): String? {
            val level = tab.level.coerceIn(0, folders.size)
            while (folders.size > level) folders.removeAt(folders.lastIndex)
            return folders.lastOrNull()
        }
    }

    private companion object {
        const val FOLDER_NAME = "…"
    }
}
