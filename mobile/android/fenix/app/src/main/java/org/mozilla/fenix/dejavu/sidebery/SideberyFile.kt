/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sidebery

import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A snapshot of Sidebery, the tab manager for Firefox on computers: its tab panels, its containers and the tabs of its
 * windows, as [SideberyFile] reads and writes it.
 *
 * @property panels The tab panels, in the order of Sidebery's navigation bar.
 */
internal data class SideberySnapshot(
    val id: String,
    val time: Long,
    val panels: List<SideberyPanel>,
    val containers: List<SideberyContainer>,
    val windows: List<SideberyWindow>,
)

/**
 * A tab panel of Sidebery, a list of tabs like a workspace.
 *
 * @property color One of Sidebery's color names: blue, turquoise, green, yellow, orange, red, pink, purple or toolbar.
 * @property containerId The container new tabs of the panel open in, or `null`.
 * @property iconImage The panel's own icon as a `data:` address of an image, or `null` for Sidebery's tabs icon.
 */
internal data class SideberyPanel(
    val id: String,
    val name: String,
    val color: String = SideberyFile.DEFAULT_COLOR,
    val containerId: String? = null,
    val iconImage: String? = null,
)

/** A container of Firefox, with Firefox's names of its [color] and [icon]. */
internal data class SideberyContainer(val id: String, val name: String, val color: String, val icon: String)

/** A window of a snapshot and its tabs, in order. */
internal data class SideberyWindow(val tabs: List<SideberyTab>, val private: Boolean = false)

/**
 * A tab of a snapshot.
 *
 * @property panelId The panel the tab is in, or `null` for a pinned tab every panel shows.
 * @property level How deep the tab is in its panel's tree: 0 at the top, 1 for a child of the tab above it, and so on.
 * @property folded Whether the tab's children are hidden.
 * @property customTitle The title the user gave the tab, or `null`.
 */
internal data class SideberyTab(
    val url: String,
    val title: String,
    val panelId: String?,
    val level: Int = 0,
    val pinned: Boolean = false,
    val folded: Boolean = false,
    val containerId: String? = null,
    val customTitle: String? = null,
)

/**
 * Sidebery's JSON files. Dejavu writes the file Sidebery exports for one snapshot, which Sidebery imports again with
 * "Import snapshot". It reads that file, and the backup Sidebery exports of all its data, taking the newest of the
 * snapshots in it, or only the panels when it has none.
 */
internal object SideberyFile {
    const val MIME_TYPE = "application/json"

    /** The types a file to import may have, as file managers do not always know a JSON file. */
    val OPEN_TYPES = arrayOf(MIME_TYPE, "text/plain", "application/octet-stream")

    const val DEFAULT_COLOR = "toolbar"

    /** Files larger than this are not read. A backup with thousands of tabs and their icons stays well below it. */
    const val MAX_SIZE = 64L * 1024 * 1024

    // The address of Sidebery's group page, a tab whose children are the tabs of the group. Sidebery replaces its first
    // part, which differs on every computer, with its own as it opens the snapshot, so it only needs the same length.
    private const val GROUP_URL = "moz-extension://00000000-0000-0000-0000-000000000000/sidebery/group.html"
    private const val GROUP_PATH = "/sidebery/group.html"
    private const val EXTENSION_SCHEME = "moz-extension://"
    private const val READER_PREFIX = "about:reader?url="
    private val WEB_SCHEMES = listOf("https://", "http://")
    private val EMPTY_PAGES = setOf("about:blank", "about:newtab", "about:home", "about:privatebrowsing")
    private const val TABS_PANEL = 2
    private const val NO_PANEL = "-1"
    private const val GLOBAL_PINNED = "global_pinned"
    private val GROUP_NAME_ENDS = listOf(":id:", "%3Aid%3A", "~!")

    private val json = Json { ignoreUnknownKeys = true }

    /** The name Sidebery gives a snapshot it exports, like sidebery-snapshot-2026.11.07-14.05.09.json. */
    fun name(time: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy.MM.dd-HH.mm.ss", Locale.ROOT).apply { timeZone = zone }
            .format(Date(time))
            .let { "sidebery-snapshot-$it.json" }

    /** Reads a snapshot file or a backup file of Sidebery, or returns `null` when [text] is neither. */
    fun read(text: String): SideberySnapshot? =
        runCatching { parse(json.decodeFromString(FileShape.serializer(), text)) }.getOrNull()
            ?.takeIf { it.panels.isNotEmpty() || it.windows.isNotEmpty() }

    fun write(snapshot: SideberySnapshot): String = buildJsonObject {
        put("id", snapshot.id)
        put("time", snapshot.time)
        putJsonObject("containers") {
            snapshot.containers.forEach { container -> put(container.id, containerJson(container)) }
        }
        putJsonObject("sidebar") {
            putJsonObject("panels") { snapshot.panels.forEach { panel -> put(panel.id, panelJson(panel)) } }
            putJsonArray("nav") { snapshot.panels.forEach { add(it.id) } }
        }
        putJsonArray("tabs") {
            snapshot.windows.forEach { window ->
                addJsonArray {
                    groupsOf(window.tabs).forEachIndexed { groupIndex, group ->
                        addJsonArray {
                            group.forEachIndexed { index, tab ->
                                add(tabJson(tab, private = window.private && groupIndex == 0 && index == 0))
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    /** The address of a group tab named [name], which Sidebery shows as a folder of the tabs below it. */
    fun groupUrl(name: String): String = "$GROUP_URL#${encodeComponent(name)}"

    fun isGroupUrl(url: String): Boolean = url.startsWith(EXTENSION_SCHEME) && url.contains(GROUP_PATH)

    /** Whether [url] is an empty page, like a new tab, which is not worth bringing over. */
    fun isEmptyPage(url: String): Boolean = url.isBlank() || url in EMPTY_PAGES

    /** The name of the group tab at [url], or `null` when it has none. */
    fun groupName(url: String): String? {
        val hash = url.substringAfter('#', "").takeIf { it.isNotEmpty() } ?: return null
        val end = GROUP_NAME_ENDS.mapNotNull { mark -> hash.indexOf(mark).takeIf { it >= 0 } }.minOrNull()
            ?: hash.length
        return decodeComponent(hash.substring(0, end)).trim().ifEmpty { null }
    }

    /**
     * The web page at [url] that Dejavu can open, or `null` for an address only a computer opens, like a local file.
     * Reader view addresses of Firefox give the page they show.
     */
    fun webAddress(url: String): String? {
        val address = if (url.startsWith(READER_PREFIX)) decodeComponent(url.removePrefix(READER_PREFIX)) else url
        return address.takeIf { WEB_SCHEMES.any { scheme -> it.startsWith(scheme, ignoreCase = true) } }
    }

    private fun parse(shape: FileShape): SideberySnapshot? {
        val snapshots = shape.snapshots.orEmpty()
        return when {
            shape.tabs != null -> snapshotOf(
                id = shape.id.idOrNull(),
                time = shape.time.asLong(),
                containers = shape.containers as? JsonObject,
                sidebar = shape.sidebar as? JsonObject,
                tabs = shape.tabs as? JsonArray,
            )
            snapshots.isNotEmpty() -> newestOf(snapshots, shape)
            shape.sidebar is JsonObject -> snapshotOf(null, null, shape.containers as? JsonObject, shape.sidebar, null)
            else -> null
        }
    }

    /**
     * The newest snapshot of a backup, merged with the panels and containers the backup has besides. Sidebery stores
     * every part of a snapshot that has not changed since the one before as -1, which is looked up in the older ones.
     */
    private fun newestOf(snapshots: List<JsonElement>, backup: FileShape): SideberySnapshot? {
        val index = snapshots.lastIndex
        val newest = snapshots[index] as? JsonObject ?: return null
        val containers = newest.objectOrEarlier(snapshots, index, "containers")
        val sidebar = newest.objectOrEarlier(snapshots, index, "sidebar")
        val tabs = (newest["tabs"] as? JsonArray)?.let { windows ->
            JsonArray(
                windows.mapIndexed { w, window ->
                    JsonArray(
                        (window as? JsonArray).orEmpty().mapIndexed { p, panel ->
                            JsonArray(
                                (panel as? JsonArray).orEmpty().mapIndexedNotNull { t, tab ->
                                    tab as? JsonObject ?: earlier(snapshots, index) { snapshot ->
                                        snapshot.arrayAt("tabs", w, p)?.getOrNull(t) as? JsonObject
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }
        val snapshot = snapshotOf(newest["id"].idOrNull(), newest["time"].asLong(), containers, sidebar, tabs)
        val extraPanels = panelsOf(backup.sidebar as? JsonObject)
            .filter { panel -> snapshot.panels.none { it.id == panel.id } }
        val extraContainers = containersOf(backup.containers as? JsonObject)
            .filter { container -> snapshot.containers.none { it.id == container.id } }
        return snapshot.copy(panels = snapshot.panels + extraPanels, containers = snapshot.containers + extraContainers)
    }

    private fun JsonObject.objectOrEarlier(snapshots: List<JsonElement>, index: Int, key: String): JsonObject? =
        this[key] as? JsonObject ?: earlier(snapshots, index) { it[key] as? JsonObject }

    private fun <T> earlier(snapshots: List<JsonElement>, index: Int, pick: (JsonObject) -> T?): T? {
        for (i in index - 1 downTo 0) {
            val snapshot = snapshots[i] as? JsonObject ?: continue
            pick(snapshot)?.let { return it }
        }
        return null
    }

    private fun JsonObject.arrayAt(key: String, window: Int, panel: Int): JsonArray? =
        ((this[key] as? JsonArray)?.getOrNull(window) as? JsonArray)?.getOrNull(panel) as? JsonArray

    private fun snapshotOf(
        id: String?,
        time: Long?,
        containers: JsonObject?,
        sidebar: JsonObject?,
        tabs: JsonArray?,
    ): SideberySnapshot {
        val containerList = containersOf(containers)
        val windows = tabs.orEmpty().mapNotNull { window ->
            val groups = (window as? JsonArray).orEmpty().map { (it as? JsonArray).orEmpty() }
            val windowTabs = groups.flatten().mapNotNull { (it as? JsonObject)?.let(::tabOf) }
            val private = (groups.firstOrNull()?.firstOrNull() as? JsonObject)?.bool("priv") == true
            SideberyWindow(windowTabs, private).takeIf { windowTabs.isNotEmpty() }
        }
        return SideberySnapshot(
            id = id.orEmpty(),
            time = time ?: 0L,
            panels = panelsOf(sidebar).map { panel ->
                panel.copy(containerId = panel.containerId?.takeIf { id -> containerList.any { it.id == id } })
            },
            containers = containerList,
            windows = windows,
        )
    }

    private fun panelsOf(sidebar: JsonObject?): List<SideberyPanel> {
        val configs = sidebar?.get("panels") as? JsonObject ?: return emptyList()
        val nav = (sidebar["nav"] as? JsonArray).orEmpty().mapNotNull { it.idOrNull() }
        val order = nav.filter { it in configs } + configs.keys.filter { it !in nav }
        return order.distinct().mapNotNull { key ->
            val config = configs[key] as? JsonObject ?: return@mapNotNull null
            if ((config["type"] as? JsonPrimitive)?.intOrNull != TABS_PANEL) return@mapNotNull null
            SideberyPanel(
                id = config["id"].idOrNull() ?: key,
                name = config.string("name").orEmpty(),
                color = config.string("color") ?: DEFAULT_COLOR,
                containerId = config.string("newTabCtx")?.takeIf { it != "none" },
            )
        }
    }

    private fun containersOf(containers: JsonObject?): List<SideberyContainer> =
        containers.orEmpty().mapNotNull { (key, value) ->
            val container = value as? JsonObject ?: return@mapNotNull null
            SideberyContainer(
                id = key,
                name = container.string("name").orEmpty(),
                color = container.string("color") ?: "blue",
                icon = container.string("icon") ?: "fingerprint",
            )
        }

    private fun tabOf(tab: JsonObject) = SideberyTab(
        url = tab.string("url").orEmpty(),
        title = tab.string("title").orEmpty(),
        panelId = tab["panelId"].idOrNull()?.takeUnless { it == NO_PANEL || it == GLOBAL_PINNED },
        level = (tab["lvl"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0,
        pinned = tab.bool("pinned") == true,
        folded = tab.bool("folded") == true,
        containerId = tab.string("containerId"),
        customTitle = tab.string("customTitle")?.takeIf { it.isNotBlank() },
    )

    /** The tabs of a window as Sidebery groups them: the pinned tabs first, then the tabs of each panel. */
    private fun groupsOf(tabs: List<SideberyTab>): List<List<SideberyTab>> {
        val groups = mutableListOf<MutableList<SideberyTab>>()
        var current: String? = null
        tabs.forEach { tab ->
            val key = if (tab.pinned) GLOBAL_PINNED else tab.panelId ?: NO_PANEL
            if (groups.isEmpty() || key != current) groups += mutableListOf<SideberyTab>()
            current = key
            groups.last() += tab
        }
        return groups
    }

    private fun containerJson(container: SideberyContainer) = buildJsonObject {
        put("id", container.id)
        put("cookieStoreId", container.id)
        put("name", container.name)
        put("icon", container.icon)
        put("color", container.color)
        put("colorCode", COLOR_CODES[container.color] ?: COLOR_CODES.getValue(DEFAULT_COLOR))
        put("proxified", false)
        put("proxy", JsonNull)
        put("reopenRulesActive", false)
        putJsonArray("reopenRules") {}
        put("userAgentActive", false)
        put("userAgent", "")
    }

    private fun panelJson(panel: SideberyPanel) = buildJsonObject {
        put("type", TABS_PANEL)
        put("id", panel.id)
        put("name", panel.name)
        put("color", panel.color)
        put("iconSVG", "icon_tabs")
        put("iconIMGSrc", "")
        put("iconIMG", panel.iconImage.orEmpty())
        put("lockedPanel", false)
        put("skipOnSwitching", false)
        put("noEmpty", false)
        put("newTabCtx", panel.containerId ?: "none")
        put("dropTabCtx", "none")
        putJsonArray("moveRules") {}
        put("moveExcludedTo", -1)
        put("bookmarksFolderId", -1)
        putJsonArray("newTabBtns") {}
        put("srcPanelConfig", JsonNull)
    }

    private fun tabJson(tab: SideberyTab, private: Boolean) = buildJsonObject {
        put("url", tab.url)
        put("title", tab.title)
        if (tab.panelId != null) put("panelId", tab.panelId) else put("panelId", -1)
        if (tab.level > 0) put("lvl", tab.level)
        if (tab.pinned) put("pinned", true)
        if (tab.folded) put("folded", true)
        tab.containerId?.let { put("containerId", it) }
        tab.customTitle?.let { put("customTitle", it) }
        if (private) put("priv", true)
    }

    /** Escapes [text] the way JavaScript's encodeURIComponent does, which Sidebery decodes group names with. */
    private fun encodeComponent(text: String): String = buildString {
        text.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and BYTE_MASK
            val char = code.toChar()
            if (code < ASCII_END && char.isLetterOrDigit() || char in UNESCAPED) {
                append(char)
            } else {
                append('%').append(HEX[code shr HALF_BYTE]).append(HEX[code and HALF_MASK])
            }
        }
    }

    private fun decodeComponent(text: String): String =
        runCatching { URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrDefault(text)

    private fun JsonElement?.idOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonElement?.asLong(): Long? =
        (this as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private const val BYTE_MASK = 0xFF
    private const val HALF_BYTE = 4
    private const val HALF_MASK = 0x0F
    private const val ASCII_END = 128
    private const val HEX = "0123456789ABCDEF"
    private const val UNESCAPED = "-_.!~*'()"

    // Sidebery's values of its color names, which Firefox gave its containers before it renamed some colors.
    private val COLOR_CODES = mapOf(
        "blue" to "#37adff",
        "turquoise" to "#00c79a",
        "green" to "#51cd00",
        "yellow" to "#ffcb00",
        "orange" to "#ff9f00",
        "red" to "#ff613d",
        "pink" to "#ff4bda",
        "purple" to "#af51f5",
        "toolbar" to "#686868",
    )

    /** The parts of Sidebery's files Dejavu reads; the rest, like the icons of a backup, is skipped while reading. */
    @Serializable
    private class FileShape(
        val id: JsonElement? = null,
        val time: JsonElement? = null,
        val containers: JsonElement? = null,
        val sidebar: JsonElement? = null,
        val tabs: JsonElement? = null,
        val snapshots: List<JsonElement>? = null,
    )
}
