/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sidebery

import kotlin.random.Random
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

/**
 * Makes the Sidebery snapshot of Dejavu's workspaces. Each workspace is a tab panel, which lists its pinned section,
 * its folders as group tabs with what is inside them as their children, then its unpinned tabs. The essentials are the
 * pinned tabs every panel shows. The workspaces of devices on the account, private tabs and tabs a page opened by
 * itself are left out, like sync leaves them out.
 */
internal object SideberyExport {
    /**
     * @property tabs How many tabs the snapshot has, not counting the group tabs of folders.
     * @property workspaces How many workspaces it has.
     */
    data class Result(val snapshot: SideberySnapshot, val tabs: Int, val workspaces: Int)

    private const val PANEL_ID_LENGTH = 12
    private const val ID_RADIX = 36
    private const val RANDOM_ID_LENGTH = 11

    /**
     * @param tabs The open tabs, in their order.
     * @param iconImage The icon of a workspace as a `data:` address of an image, for its panel.
     */
    @Suppress("LongParameterList")
    fun export(
        state: WorkspaceState,
        tabs: List<TabSessionState>,
        containers: List<ContainerRecord>,
        iconImage: (Workspace) -> String? = { null },
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
    ): Result {
        val permanent = containers.filterNot { it.showsAsTemporary }.associateBy { it.contextId }
        val shown = state.workspaces.filter { it.device == null }
        val panelIds = panelIdsOf(shown)
        val usedContainers = LinkedHashSet<String>()
        fun containerOf(contextId: String?) = contextId?.takeIf { it in permanent }?.also { usedContainers += it }

        fun pinTab(pin: PinnedItem, panelId: String?, level: Int, pinned: Boolean): SideberyTab? {
            val url = pin.url?.let(SideberyFile::webAddress) ?: return null
            return SideberyTab(
                url = url,
                title = pin.title.ifBlank { url },
                panelId = panelId,
                level = level,
                pinned = pinned,
                containerId = containerOf(pin.containerId),
                customTitle = pin.title.takeIf { pin.staticLabel && it.isNotBlank() },
            )
        }

        val exported = mutableListOf<SideberyTab>()
        state.essentials.forEach { pin -> pinTab(pin, panelId = null, level = 0, pinned = true)?.let(exported::add) }
        shown.forEach { workspace ->
            val panelId = panelIds.getValue(workspace.id)
            val seen = mutableSetOf<String>()
            fun addItems(parentId: String?, level: Int) {
                state.children(workspace.id, parentId).forEach { item ->
                    if (!seen.add(item.id)) return@forEach
                    if (item.isFolder) {
                        exported += SideberyTab(
                            url = SideberyFile.groupUrl(item.title),
                            title = item.title,
                            panelId = panelId,
                            level = level,
                            folded = item.collapsed,
                        )
                        addItems(item.id, level + 1)
                    } else {
                        pinTab(item, panelId, level, pinned = false)?.let(exported::add)
                    }
                }
            }
            addItems(parentId = null, level = 0)
            tabs.filter { tab ->
                !tab.content.private && state.workspaceOf(tab.id) == workspace.id &&
                    state.pinOf(tab.id) == null && tab.id !in state.openedByPages
            }.forEach { tab ->
                val url = SideberyFile.webAddress(tab.readerState.activeUrl ?: tab.content.url) ?: return@forEach
                exported += SideberyTab(
                    url = url,
                    title = tab.content.title.ifBlank { url },
                    panelId = panelId,
                    containerId = containerOf(tab.contextId),
                    customTitle = state.tabTitles[tab.id]?.takeIf { it.isNotBlank() },
                )
            }
        }

        // The panels leave out the containers of their workspaces: Sidebery keeps a panel's container as it is, and
        // fails to open new tabs in a container it does not know, which a container of Dejavu always is to it.
        val panels = shown.map { workspace ->
            val container = workspace.containerId?.let(permanent::get)
            SideberyPanel(
                id = panelIds.getValue(workspace.id),
                name = workspace.name,
                color = container?.color?.sideberyName ?: workspace.theme?.colors?.let(::sideberyColorOf)
                    ?: SideberyFile.DEFAULT_COLOR,
                iconImage = iconImage(workspace),
            )
        }
        val snapshot = SideberySnapshot(
            id = now.toString(ID_RADIX) + (1..RANDOM_ID_LENGTH).joinToString("") {
                random.nextInt(ID_RADIX).digitToChar(ID_RADIX).lowercase()
            },
            time = now,
            panels = panels,
            containers = usedContainers.mapNotNull(permanent::get).map { record ->
                SideberyContainer(record.contextId, record.name, record.color.sideberyName, record.icon.icon)
            },
            windows = if (exported.isEmpty()) emptyList() else listOf(SideberyWindow(exported)),
        )
        return Result(snapshot, tabs = exported.count { !SideberyFile.isGroupUrl(it.url) }, workspaces = shown.size)
    }

    /**
     * Ids for the panels of [workspaces], made from the workspaces' ids so that importing the snapshot again finds the
     * same workspaces, even renamed: Sidebery's own ids are 12 letters and digits long.
     */
    private fun panelIdsOf(workspaces: List<Workspace>): Map<String, String> {
        val ids = mutableMapOf<String, String>()
        workspaces.forEach { workspace ->
            val base = panelIdOf(workspace)
            var id = base
            var suffix = 1
            while (id in ids.values) id = base + suffix++
            ids[workspace.id] = id
        }
        return ids
    }

    /** The id of the panel of [workspace] in the snapshots Dejavu exports. */
    fun panelIdOf(workspace: Workspace): String =
        workspace.id.filter { it.isLetterOrDigit() }.take(PANEL_ID_LENGTH).ifEmpty { workspace.id }
}

/**
 * The name Sidebery and older versions of Firefox give this color. Both still know gray and cyan by their old names,
 * toolbar and turquoise, and violet, which they lack, comes closest to purple.
 */
internal val ContainerColor.sideberyName: String
    get() = when (this) {
        ContainerColor.GRAY, ContainerColor.WHITE -> SideberyFile.DEFAULT_COLOR
        ContainerColor.CYAN -> "turquoise"
        ContainerColor.VIOLET -> "purple"
        else -> key
    }

private const val GRAY_SATURATION = 0.15f
private const val HUE_SECTOR = 60f
private const val FULL_CIRCLE = 360f
private const val CHANNEL_MAX = 255f
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 0xFF
private const val GREEN_SECTOR = 2
private const val BLUE_SECTOR = 4
private const val SECTORS = 6

// Where each of Sidebery's colors ends on the color wheel, in degrees.
private val HUE_ENDS = listOf(
    15f to "red",
    45f to "orange",
    70f to "yellow",
    165f to "green",
    195f to "turquoise",
    255f to "blue",
    290f to "purple",
    345f to "pink",
    FULL_CIRCLE to "red",
)

/** The Sidebery color closest to the most colorful of [colors], ARGB colors like a workspace theme's. */
internal fun sideberyColorOf(colors: List<Int>): String {
    var hue: Float? = null
    var saturation = 0f
    for (color in colors) {
        val r = (color shr RED_SHIFT and CHANNEL_MASK) / CHANNEL_MAX
        val g = (color shr GREEN_SHIFT and CHANNEL_MASK) / CHANNEL_MAX
        val b = (color and CHANNEL_MASK) / CHANNEL_MAX
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        val colorSaturation = if (max == 0f) 0f else delta / max
        if (colorSaturation < GRAY_SATURATION || colorSaturation <= saturation) continue
        saturation = colorSaturation
        val sector = when (max) {
            r -> ((g - b) / delta).mod(SECTORS.toFloat())
            g -> (b - r) / delta + GREEN_SECTOR
            else -> (r - g) / delta + BLUE_SECTOR
        }
        hue = (HUE_SECTOR * sector).mod(FULL_CIRCLE)
    }
    return hue?.let { found -> HUE_ENDS.first { found < it.first }.second } ?: SideberyFile.DEFAULT_COLOR
}
