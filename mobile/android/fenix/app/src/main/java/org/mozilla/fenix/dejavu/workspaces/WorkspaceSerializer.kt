/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes [WorkspaceState] as JSON. Version 1 (workspaces with nested pinned tabs, no folders) is migrated
 * to version 2 (flat pinned items with parent folders) on read.
 */
internal object WorkspaceSerializer {
    private const val VERSION = 2
    private const val MAX_THEME_COLORS = 3
    private const val SPLIT_SIZE = 2

    fun read(json: String?, defaultName: String, now: Long): WorkspaceState {
        val root = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val workspaceArray = root?.optJSONArray("workspaces")

        val workspaces = workspaceArray.objects().map { item ->
            Workspace(
                id = item.getString("id"),
                name = item.getString("name"),
                containerId = item.optStringOrNull("containerId"),
                icon = item.optStringOrNull("icon"),
                theme = item.optJSONObject("theme")?.toTheme(),
                createdAt = item.optLong("createdAt", now),
                updatedAt = item.optLong("updatedAt", now),
                device = SyncedDevice.fromKey(item.optStringOrNull("device")),
                locked = item.optBoolean("locked"),
                savesHistory = item.optBoolean("savesHistory", true),
            )
        }.ifEmpty {
            listOf(
                Workspace(
                    id = newWorkspaceId(),
                    name = defaultName,
                    icon = Workspace.HomeIcon,
                    theme = WorkspaceTheme.Gold,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        val workspaceIds = workspaces.map { it.id }.toSet()

        val pins = if (root?.optInt("version") == VERSION) {
            root.optJSONArray("pins").objects().map { it.toPinnedItem(now) }
        } else {
            workspaceArray.objects().flatMap { ws -> ws.optJSONArray("pinned").objects().map { it.toV1Pin(ws.getString("id"), now) } }
        }.filter { it.essential || it.workspaceId in workspaceIds }

        val assignments = root?.optJSONObject("assignments")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.orEmpty().filterValues { it in workspaceIds }

        val activeId = root?.optStringOrNull("active")?.takeIf { it in workspaceIds } ?: workspaces.first().id

        val splits = root?.optJSONArray("splits").objects().mapNotNull { item ->
            val tabIds = item.optJSONArray("tabIds")?.let { ids -> (0 until ids.length()).map { ids.getString(it) } }
            val orientation =
                SplitOrientation.fromKey(item.optStringOrNull("orientation")) ?: SplitOrientation.TOP_AND_BOTTOM
            tabIds?.takeIf { it.size == SPLIT_SIZE }?.let { SplitView(item.getString("id"), it, orientation) }
        }

        val tabTitles = root?.optJSONObject("tabTitles")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.orEmpty()

        val tabSyncIds = root?.optJSONObject("tabSyncIds")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.orEmpty()

        val pinnedSplits = root?.optJSONArray("pinnedSplits").objects().mapNotNull { item ->
            val pinIds = item.optJSONArray("pinIds")?.let { ids -> (0 until ids.length()).map { ids.getString(it) } }
            val orientation = SplitOrientation.fromKey(item.optStringOrNull("orientation")) ?: return@mapNotNull null
            pinIds?.takeIf { it.size == SPLIT_SIZE }?.let { PinnedSplit(item.getString("id"), it, orientation) }
        }

        val openedByPages = root?.optJSONArray("openedByPages")?.let { ids ->
            (0 until ids.length()).mapNotNull { ids.optString(it).ifEmpty { null } }.toSet()
        }.orEmpty()

        val starters = root?.optJSONArray("starters")?.let { ids ->
            (0 until ids.length()).mapNotNull { ids.optString(it).ifEmpty { null } }.toSet()
        }.orEmpty()

        return WorkspaceState(
            workspaces,
            pins,
            activeId,
            assignments,
            splits,
            tabTitles,
            tabSyncIds,
            pinnedSplits,
            openedByPages,
            starters,
        )
    }

    fun write(state: WorkspaceState): String = JSONObject().apply {
        put("version", VERSION)
        put("active", state.activeWorkspaceId)
        put(
            "workspaces",
            JSONArray().apply {
                state.workspaces.forEach { ws ->
                    put(
                        JSONObject()
                            .put("id", ws.id)
                            .put("name", ws.name)
                            .putOpt("containerId", ws.containerId)
                            .putOpt("icon", ws.icon)
                            .putOpt("theme", ws.theme?.toJson())
                            .put("createdAt", ws.createdAt)
                            .put("updatedAt", ws.updatedAt)
                            .putOpt("device", ws.device?.key)
                            .apply {
                                if (ws.locked) put("locked", true)
                                if (!ws.savesHistory) put("savesHistory", false)
                            },
                    )
                }
            },
        )
        put(
            "pins",
            JSONArray().apply {
                state.pins.forEach { pin ->
                    put(
                        JSONObject()
                            .put("id", pin.id)
                            .putOpt("workspaceId", pin.workspaceId)
                            .putOpt("parentId", pin.parentId)
                            .put("kind", pin.kind.key)
                            .put("title", pin.title)
                            .putOpt("url", pin.url)
                            .putOpt("containerId", pin.containerId)
                            .put("collapsed", pin.collapsed)
                            .putOpt("icon", pin.icon)
                            .put("essential", pin.essential)
                            .put("staticLabel", pin.staticLabel)
                            .putOpt("tabId", pin.tabId)
                            .putOpt("openUrl", pin.openUrl)
                            .putOpt("openTitle", pin.openTitle)
                            .put("createdAt", pin.createdAt)
                            .put("updatedAt", pin.updatedAt)
                            .putOpt("color", pin.color),
                    )
                }
            },
        )
        put("assignments", JSONObject(state.assignments))
        put("tabTitles", JSONObject(state.tabTitles))
        put("tabSyncIds", JSONObject(state.tabSyncIds))
        put("openedByPages", JSONArray(state.openedByPages))
        if (state.starters.isNotEmpty()) put("starters", JSONArray(state.starters))
        put(
            "splits",
            JSONArray().apply {
                state.splits.forEach { split ->
                    put(
                        JSONObject()
                            .put("id", split.id)
                            .put("tabIds", JSONArray(split.tabIds))
                            .put("orientation", split.orientation.key),
                    )
                }
            },
        )
        put(
            "pinnedSplits",
            JSONArray().apply {
                state.pinnedSplits.forEach { split ->
                    put(
                        JSONObject()
                            .put("id", split.id)
                            .put("pinIds", JSONArray(split.pinIds))
                            .put("orientation", split.orientation.key),
                    )
                }
            },
        )
    }.toString()

    private fun JSONObject.toPinnedItem(now: Long) = PinnedItem(
        id = getString("id"),
        workspaceId = optStringOrNull("workspaceId"),
        parentId = optStringOrNull("parentId"),
        kind = PinKind.entries.firstOrNull { it.key == optString("kind") } ?: PinKind.TAB,
        title = optString("title"),
        url = optStringOrNull("url"),
        containerId = optStringOrNull("containerId"),
        collapsed = optBoolean("collapsed"),
        icon = optStringOrNull("icon"),
        essential = optBoolean("essential"),
        staticLabel = optBoolean("staticLabel"),
        tabId = optStringOrNull("tabId"),
        openUrl = optStringOrNull("openUrl"),
        openTitle = optStringOrNull("openTitle"),
        createdAt = optLong("createdAt", now),
        updatedAt = optLong("updatedAt", now),
        color = optStringOrNull("color"),
    )

    private fun WorkspaceTheme.toJson() = JSONObject()
        .put("colors", JSONArray(colors))
        .put("opacity", opacity.toDouble())
        .put("texture", texture.toDouble())

    private fun JSONObject.toTheme(): WorkspaceTheme? {
        val colors = optJSONArray("colors") ?: return null
        val values = (0 until colors.length()).map { colors.getInt(it) }.take(MAX_THEME_COLORS)
        if (values.isEmpty()) return null
        return WorkspaceTheme(
            colors = values,
            opacity = optDouble("opacity", WorkspaceTheme.DEFAULT_OPACITY.toDouble()).toFloat(),
            texture = optDouble("texture", 0.0).toFloat(),
        )
    }

    private fun JSONObject.toV1Pin(workspaceId: String, now: Long) = PinnedItem(
        id = getString("id"),
        workspaceId = workspaceId,
        parentId = null,
        kind = PinKind.TAB,
        title = optString("title"),
        url = optStringOrNull("url"),
        tabId = optStringOrNull("tabId"),
        createdAt = now,
        updatedAt = now,
    )

    /** Zen identifies spaces by braced UUIDs; Dejavu uses the same form so ids can be shared when syncing. */
    fun newWorkspaceId(): String = "{${UUID.randomUUID()}}"

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}
