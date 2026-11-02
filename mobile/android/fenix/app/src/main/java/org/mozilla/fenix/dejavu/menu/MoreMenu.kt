/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import mozilla.components.ui.icons.R as iconsR
import org.json.JSONArray
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.actions.ActionPlace
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.browser.BarsLayout
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation

/**
 * A built-in item of the Dejavu "More" menu of the browser.
 *
 * @property key Stable identifier used to persist the menu layout.
 * @property label Short label shown under the icon.
 * @property icon Icon of the item.
 */
enum class MoreMenuItem(
    val key: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    BACK("back", R.string.dejavu_menu_back, iconsR.drawable.mozac_ic_back_24),
    FORWARD("forward", R.string.dejavu_menu_forward, iconsR.drawable.mozac_ic_forward_24),
    SHARE("share", R.string.dejavu_menu_share, R.drawable.dejavu_ic_share_24),
    REFRESH("refresh", R.string.dejavu_menu_refresh, R.drawable.dejavu_ic_refresh_24),
    READER_VIEW("reader_view", R.string.dejavu_menu_reader_view, iconsR.drawable.mozac_ic_reader_view_24),
    NEW_TAB("new_tab", R.string.dejavu_menu_new_tab, R.drawable.dejavu_ic_new_tab_24),
    FIND_IN_PAGE("find_in_page", R.string.dejavu_menu_find_in_page, R.drawable.dejavu_ic_find_in_page_24),
    BOOKMARK_PAGE("bookmark_page", R.string.dejavu_menu_bookmark_page, R.drawable.dejavu_ic_bookmark_add_24),
    DESKTOP_SITE("desktop_site", R.string.dejavu_menu_desktop_site, iconsR.drawable.mozac_ic_device_desktop_24),
    AMOLED_PAGES("amoled_pages", R.string.dejavu_menu_amoled_pages, iconsR.drawable.mozac_ic_themes_24),
    EXTENSIONS("extensions", R.string.dejavu_menu_extensions, R.drawable.dejavu_ic_extensions_24),
    PASSWORDS("passwords", R.string.dejavu_menu_passwords, R.drawable.dejavu_ic_passwords_24),
    BOOKMARKS("bookmarks", R.string.dejavu_menu_bookmarks, R.drawable.dejavu_ic_bookmarks_24),
    DOWNLOADS("downloads", R.string.dejavu_menu_downloads, R.drawable.dejavu_ic_downloads_24),
    HISTORY("history", R.string.dejavu_menu_history, R.drawable.dejavu_ic_history_24),
    TRANSLATE("translate", R.string.dejavu_menu_translate, iconsR.drawable.mozac_ic_translate_24),
    PIN_TAB("pin_tab", R.string.dejavu_menu_pin_tab, iconsR.drawable.mozac_ic_pin_24),
    ESSENTIAL_TAB("essential_tab", R.string.dejavu_menu_essential_tab, iconsR.drawable.mozac_ic_grid_add_24),
    SAVE_PAGE("save_page", R.string.dejavu_menu_save_page, R.drawable.dejavu_ic_save_page_24),
    SAVE_AS_PDF("save_as_pdf", R.string.dejavu_menu_save_as_pdf, iconsR.drawable.mozac_ic_save_file_24),
    PRINT("print", R.string.dejavu_menu_print, iconsR.drawable.mozac_ic_print_24),
    OPEN_IN_APP("open_in_app", R.string.dejavu_menu_open_in_app, iconsR.drawable.mozac_ic_external_link_24),
    RESET_PINNED_URL("reset_pinned_url", R.string.dejavu_menu_reset_pinned_url, R.drawable.dejavu_ic_reset_pin_24),
    REPLACE_PINNED_URL("replace_pinned_url", R.string.dejavu_menu_replace_pinned_url, iconsR.drawable.mozac_ic_pin_fill_24),
    SPLIT_VIEW("split_view", R.string.dejavu_menu_split_view, R.drawable.dejavu_ic_split_24),

    // What the split view entry turns into on screens with room for both ways of splitting; never put by the user.
    SPLIT_TOP_AND_BOTTOM("split_top_and_bottom", R.string.dejavu_split_top_and_bottom, R.drawable.dejavu_ic_split_24),
    SPLIT_SIDE_BY_SIDE("split_side_by_side", R.string.dejavu_split_side_by_side, R.drawable.dejavu_ic_split_side_24),
    CLEAR_DATA("clear_data", R.string.dejavu_menu_clear_data, iconsR.drawable.mozac_ic_data_clearance_24),
    CLEAR_DATA_AND_QUIT(
        "clear_data_and_quit",
        R.string.dejavu_menu_clear_data_and_quit,
        iconsR.drawable.mozac_ic_cross_circle_fill_24,
    ),
    SETTINGS("settings", R.string.dejavu_menu_settings, R.drawable.dejavu_ic_settings_24),
    ;

    companion object {
        fun fromKey(key: String): MoreMenuItem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * An item of the "More" menu or of the actions bar: a built-in one, a tab action or one of the user's custom actions,
 * run on the shown tab.
 */
sealed interface MoreMenuEntry {
    val key: String

    /** A [MoreMenuItem]. */
    data class BuiltIn(val item: MoreMenuItem) : MoreMenuEntry {
        override val key: String get() = item.key
    }

    /** A [TabAction] that has no [MoreMenuItem] doing the same, see [isMenuEntry]. */
    data class Tab(val action: TabAction) : MoreMenuEntry {
        override val key: String get() = action.key
    }

    /** A [CustomAction]. */
    data class Custom(val action: CustomAction) : MoreMenuEntry {
        override val key: String get() = RowAction.keyOf(action.id)
    }

    /** Goes to the home screen. Only the actions bar has it. */
    data object Home : MoreMenuEntry {
        override val key: String get() = "home"
    }

    /** Starts a search. Only the actions bar has it. */
    data object Search : MoreMenuEntry {
        override val key: String get() = "search"
    }
}

/** The [MoreMenuItem] that does to the shown tab what this action does, for the actions the menu already had. */
val TabAction.menuItem: MoreMenuItem?
    get() = when (this) {
        TabAction.SHARE -> MoreMenuItem.SHARE
        TabAction.BOOKMARK -> MoreMenuItem.BOOKMARK_PAGE
        TabAction.PIN, TabAction.UNPIN -> MoreMenuItem.PIN_TAB
        TabAction.ADD_TO_ESSENTIALS, TabAction.REMOVE_FROM_ESSENTIALS -> MoreMenuItem.ESSENTIAL_TAB
        TabAction.RESET_PIN -> MoreMenuItem.RESET_PINNED_URL
        TabAction.REPLACE_PIN -> MoreMenuItem.REPLACE_PINNED_URL
        TabAction.SPLIT_VIEW, TabAction.UNSPLIT -> MoreMenuItem.SPLIT_VIEW
        TabAction.SPLIT_TOP_AND_BOTTOM -> MoreMenuItem.SPLIT_TOP_AND_BOTTOM
        TabAction.SPLIT_SIDE_BY_SIDE -> MoreMenuItem.SPLIT_SIDE_BY_SIDE
        else -> null
    }

/** Whether this action is an entry of its own in the "More" menu, rather than one of its [MoreMenuItem]s. */
val TabAction.isMenuEntry: Boolean
    get() = ActionPlace.MORE_MENU in places && menuItem == null

/** The key this action has in the "More" menu and the actions bar, or `null` when it cannot be put there. */
val RowAction.menuKey: String?
    get() = when (this) {
        is RowAction.BuiltIn -> if (ActionPlace.MORE_MENU in action.places) action.menuItem?.key ?: action.key else null
        is RowAction.Custom -> key
    }

/**
 * The rows of the "More" menu, as lists of entry keys. A row holds up to [MAX_PER_ROW] items, except the
 * extensions item, which fills a row of its own.
 */
object MoreMenuLayout {
    const val MAX_PER_ROW = 4

    val DEFAULT: List<List<String>> = listOf(
        listOf(MoreMenuItem.BACK.key, MoreMenuItem.FORWARD.key, MoreMenuItem.REFRESH.key, TabAction.CLOSE.key),
        listOf(
            MoreMenuItem.READER_VIEW.key,
            MoreMenuItem.FIND_IN_PAGE.key,
            MoreMenuItem.DESKTOP_SITE.key,
            MoreMenuItem.TRANSLATE.key,
        ),
        listOf(MoreMenuItem.EXTENSIONS.key),
        listOf(
            MoreMenuItem.BOOKMARK_PAGE.key,
            MoreMenuItem.PIN_TAB.key,
            MoreMenuItem.ESSENTIAL_TAB.key,
            MoreMenuItem.SAVE_AS_PDF.key,
        ),
        listOf(TabAction.SLEEP.key, TabAction.COPY_LINK.key, MoreMenuItem.OPEN_IN_APP.key, MoreMenuItem.SPLIT_VIEW.key),
        listOf(MoreMenuItem.AMOLED_PAGES.key),
    )

    /** Whether the entry with [key] fills a row on its own. */
    fun isFullRow(key: String): Boolean = key == MoreMenuItem.EXTENSIONS.key

    /** The entry with [key], or `null` if it does not exist (anymore). */
    fun entryOf(key: String, customActions: List<CustomAction>): MoreMenuEntry? =
        MoreMenuItem.fromKey(key)?.let { MoreMenuEntry.BuiltIn(it) }
            ?: TabAction.fromKey(key)?.takeIf { it.isMenuEntry }?.let { MoreMenuEntry.Tab(it) }
            ?: customActions.firstOrNull { RowAction.keyOf(it.id) == key }?.let { MoreMenuEntry.Custom(it) }

    /** Every entry that can be put in the menu: its own items, then the tab actions, then the custom ones. */
    fun available(customActions: List<CustomAction>): List<MoreMenuEntry> =
        MoreMenuItem.entries.filter { it.splitWay == null }.map { MoreMenuEntry.BuiltIn(it) } +
            TabAction.ordered.filter { it.isMenuEntry }.map { MoreMenuEntry.Tab(it) } +
            customActions.map { MoreMenuEntry.Custom(it) }

    /** The entries of [rows], leaving out the ones that no longer exist and rows left empty. */
    fun resolve(rows: List<List<String>>, customActions: List<CustomAction>): List<List<MoreMenuEntry>> =
        normalized(rows).map { row -> row.mapNotNull { entryOf(it, customActions) } }.filter { it.isNotEmpty() }

    /**
     * [rows] without empty rows, with full row entries on rows of their own and at most [MAX_PER_ROW] entries per row.
     * An entry can be in the menu more than once.
     */
    fun normalized(rows: List<List<String>>): List<List<String>> =
        rows.flatMap { row ->
            val (full, others) = row.partition(::isFullRow)
            full.map { listOf(it) } + others.chunked(MAX_PER_ROW)
        }.filter { it.isNotEmpty() }

    fun toJson(rows: List<List<String>>): String =
        JSONArray().apply { rows.forEach { row -> put(JSONArray(row)) } }.toString()

    fun fromJson(json: String?): List<List<String>>? {
        val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        return (0 until array.length()).map { index ->
            val row = array.optJSONArray(index) ?: JSONArray()
            (0 until row.length()).map { row.optString(it) }.filter { it.isNotBlank() }
        }
    }
}

/**
 * The buttons of the actions bar below pages, as keys of [MoreMenuEntry]s. Home and Search are buttons like the others,
 * so they can be moved or removed too. A button can be in the bar more than once.
 */
object ActionsBarLayout {
    const val MAX_BUTTONS = 5

    /**
     * The bar a new install starts with: five buttons, New tab first and Home in the middle, and no Back, since the
     * system Back does that.
     */
    val DEFAULT: List<String> = listOf(
        MoreMenuItem.NEW_TAB.key,
        MoreMenuItem.SHARE.key,
        MoreMenuEntry.Home.key,
        MoreMenuItem.REFRESH.key,
        MoreMenuEntry.Search.key,
    )

    /** The bar Dejavu 2.1 to 3.0 started with, which had Forward where New tab is now. */
    private val OLD_DEFAULT = listOf(MoreMenuItem.FORWARD.key) + DEFAULT.drop(1)

    /** The [saved] bar, or [DEFAULT] when it is the bar an older Dejavu started with, which the user never changed. */
    fun upgraded(saved: List<String>): List<String> = if (saved == OLD_DEFAULT) DEFAULT else saved

    /** The first rows the menu's default had before: with Back, then with Reader view in its place. */
    private val OLD_FIRST_ROWS = listOf(MoreMenuItem.BACK, MoreMenuItem.READER_VIEW).map { first ->
        listOf(first.key, MoreMenuItem.FORWARD.key, MoreMenuItem.SHARE.key, MoreMenuItem.REFRESH.key)
    }

    /**
     * The bar Dejavu showed before it could be changed on its own: the first row of the menu laid out as [menuRows]
     * and Search, around Home in the middle. A menu whose first row was never changed gives [DEFAULT] instead.
     */
    fun fromMenu(menuRows: List<List<String>>): List<String> {
        val firstRow = menuRows.firstOrNull().orEmpty()
        if (firstRow == MoreMenuLayout.DEFAULT.first() || firstRow in OLD_FIRST_ROWS) {
            return DEFAULT
        }
        val buttons = firstRow + MoreMenuEntry.Search.key
        return buttons.toMutableList().apply { add(size / 2, MoreMenuEntry.Home.key) }.distinct().take(MAX_BUTTONS)
    }

    /** The entry with [key], or `null` if it does not exist (anymore). */
    fun entryOf(key: String, customActions: List<CustomAction>): MoreMenuEntry? = when (key) {
        MoreMenuEntry.Home.key -> MoreMenuEntry.Home
        MoreMenuEntry.Search.key -> MoreMenuEntry.Search
        else -> MoreMenuLayout.entryOf(key, customActions)
    }

    /** Whether the entry with [key] can only be in the bar, not in the menu: Home and Search. */
    fun isBarOnly(key: String): Boolean = key == MoreMenuEntry.Home.key || key == MoreMenuEntry.Search.key

    /** Every entry that can be put in the bar. */
    fun available(customActions: List<CustomAction>): List<MoreMenuEntry> =
        listOf(MoreMenuEntry.Home, MoreMenuEntry.Search) + MoreMenuLayout.available(customActions)

    /** The entries of [keys], leaving out the ones that no longer exist. */
    fun resolve(keys: List<String>, customActions: List<CustomAction>): List<MoreMenuEntry> =
        keys.mapNotNull { entryOf(it, customActions) }.take(MAX_BUTTONS)
}

/** A side of the address bar, each with room for one button. */
enum class BarSide { START, END }

/**
 * The buttons beside the address bar, one on each side, as keys of the [MoreMenuEntry]s the actions bar can hold, or
 * `null` for a side that leaves its room to the address bar.
 */
data class AddressBarButtons(val start: String? = null, val end: String? = null) {
    operator fun get(side: BarSide): String? = when (side) {
        BarSide.START -> start
        BarSide.END -> end
    }

    /** These buttons with [key] on [side] instead. */
    fun with(side: BarSide, key: String?): AddressBarButtons = when (side) {
        BarSide.START -> copy(start = key)
        BarSide.END -> copy(end = key)
    }

    companion object {
        /** New tab at the start and Home at the end, for the layouts where the address bar is the only bar. */
        val DEFAULT = AddressBarButtons(start = MoreMenuItem.NEW_TAB.key, end = MoreMenuEntry.Home.key)

        /** The buttons the address bar starts with in [layout]: none next to the actions bar, which has its own. */
        fun defaultFor(layout: BarsLayout): AddressBarButtons =
            if (layout.hasActionsBar) AddressBarButtons() else DEFAULT
    }
}

/** The way this entry splits the browser, for the entries the split view entry turns into. */
val MoreMenuItem.splitWay: SplitOrientation?
    get() = when (this) {
        MoreMenuItem.SPLIT_TOP_AND_BOTTOM -> SplitOrientation.TOP_AND_BOTTOM
        MoreMenuItem.SPLIT_SIDE_BY_SIDE -> SplitOrientation.SIDE_BY_SIDE
        else -> null
    }

/** The entry that splits the browser this way. */
val SplitOrientation.menuItem: MoreMenuItem
    get() = when (this) {
        SplitOrientation.TOP_AND_BOTTOM -> MoreMenuItem.SPLIT_TOP_AND_BOTTOM
        SplitOrientation.SIDE_BY_SIDE -> MoreMenuItem.SPLIT_SIDE_BY_SIDE
    }

/**
 * These entries as shown for a tab on a screen with room to split the [ways] given: the split view entry becomes one
 * entry for each way when there is room for both, and goes away when there is room for none. A tab shown split keeps
 * the entry, which ends the split.
 */
fun List<MoreMenuEntry>.forSplitWays(ways: List<SplitOrientation>, isSplit: Boolean): List<MoreMenuEntry> =
    flatMap { entry ->
        when {
            entry != MoreMenuEntry.BuiltIn(MoreMenuItem.SPLIT_VIEW) || isSplit -> listOf(entry)
            ways.size > 1 -> ways.map { MoreMenuEntry.BuiltIn(it.menuItem) }
            else -> listOf(entry).takeIf { ways.isNotEmpty() }.orEmpty()
        }
    }
