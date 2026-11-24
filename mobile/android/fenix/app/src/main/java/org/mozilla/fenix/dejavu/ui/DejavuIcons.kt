/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.DejavuSettings

/**
 * The styles of icons Customize offers for Dejavu's buttons and menus, and the large pictures of empty pages and error
 * pages (issue #233).
 *
 * @property key How the style is kept in the settings.
 * @property label Its name in Customize.
 */
enum class IconStyle(val key: String, @param:StringRes val label: Int) {
    /** Dejavu's own two-tone icons, which move a little when tapped, see [IconMotions]. */
    DEJAVU("dejavu", R.string.dejavu_icons_dejavu),

    /** Firefox's line icons, which Dejavu had before its own. */
    FIREFOX("firefox", R.string.dejavu_icons_firefox),

    /** Rounded icons like Android's, Google's Material Symbols. */
    ROUNDED("rounded", R.string.dejavu_icons_rounded),
    ;

    companion object {
        /** The style kept as [key], or Dejavu's own for a key no style has. */
        fun of(key: String?): IconStyle = entries.firstOrNull { it.key == key } ?: DEJAVU
    }
}

/** The icons of the [IconStyle] picked in Customize, which [of] and [iconPainter] give in place of Dejavu's own. */
object DejavuIcons {
    /** The style picked, which redraws the icons shown as it changes. */
    var style by mutableStateOf(IconStyle.DEJAVU)
        private set

    /** Follows the style picked in [settings] from now on. */
    fun install(settings: DejavuSettings, scope: CoroutineScope) {
        style = settings.iconStyle.value
        scope.launch { settings.iconStyle.collect { style = it } }
    }

    /** The drawable that stands for [icon] in the style picked: [icon] itself when the style has none of its own. */
    @DrawableRes
    fun of(@DrawableRes icon: Int): Int = iconIn(style, icon)
}

/** The drawable that stands for [icon] in [style], or [icon] itself. */
@DrawableRes
internal fun iconIn(style: IconStyle, @DrawableRes icon: Int): Int = when (style) {
    IconStyle.DEJAVU -> icon
    IconStyle.FIREFOX -> firefoxIcons[icon] ?: icon
    IconStyle.ROUNDED -> roundedIcons[icon] ?: icon
}

/** [painterResource] of [icon] in the style picked in Customize, see [DejavuIcons]. Other pictures stay as they are. */
@Composable
fun iconPainter(@DrawableRes icon: Int): Painter = painterResource(DejavuIcons.of(icon))

// The icons Dejavu's own took the place of, and the ones it had then for the rest.
private val firefoxIcons = mapOf(
    R.drawable.dejavu_ic_accounts_24 to iconsR.drawable.mozac_ic_avatar_circle_24,
    R.drawable.dejavu_ic_bookmark_add_24 to iconsR.drawable.mozac_ic_bookmark_24,
    R.drawable.dejavu_ic_bookmarked_24 to iconsR.drawable.mozac_ic_bookmark_fill_24,
    R.drawable.dejavu_ic_bookmarks_24 to iconsR.drawable.mozac_ic_bookmark_tray_24,
    R.drawable.dejavu_ic_clipboard_24 to iconsR.drawable.mozac_ic_clipboard_24,
    R.drawable.dejavu_ic_create_folder_24 to iconsR.drawable.mozac_ic_folder_add_24,
    R.drawable.dejavu_ic_downloads_24 to iconsR.drawable.mozac_ic_download_24,
    R.drawable.dejavu_ic_extensions_24 to iconsR.drawable.mozac_ic_extension_24,
    R.drawable.dejavu_ic_find_in_page_24 to iconsR.drawable.mozac_ic_find_in_page_24,
    R.drawable.dejavu_ic_folder_empty_24 to iconsR.drawable.mozac_ic_folder_24,
    R.drawable.dejavu_ic_history_24 to iconsR.drawable.mozac_ic_history_24,
    R.drawable.dejavu_ic_home_24 to iconsR.drawable.mozac_ic_home_24,
    R.drawable.dejavu_ic_new_tab_24 to iconsR.drawable.mozac_ic_plus_24,
    R.drawable.dejavu_ic_no_container_24 to R.drawable.dejavu_ic_classic_no_container_24,
    R.drawable.dejavu_ic_passwords_24 to iconsR.drawable.mozac_ic_login_24,
    R.drawable.dejavu_ic_private_tab_24 to iconsR.drawable.mozac_ic_private_mode_24,
    R.drawable.dejavu_ic_profile_24 to iconsR.drawable.mozac_ic_avatar_circle_24,
    R.drawable.dejavu_ic_refresh_24 to iconsR.drawable.mozac_ic_arrow_clockwise_24,
    R.drawable.dejavu_ic_reopen_tab_24 to iconsR.drawable.mozac_ic_arrow_counter_clockwise_24,
    R.drawable.dejavu_ic_save_page_24 to R.drawable.dejavu_ic_classic_save_page_24,
    R.drawable.dejavu_ic_search_24 to iconsR.drawable.mozac_ic_search_24,
    R.drawable.dejavu_ic_settings_24 to iconsR.drawable.mozac_ic_settings_24,
    R.drawable.dejavu_ic_share_24 to iconsR.drawable.mozac_ic_share_android_24,
    R.drawable.dejavu_ic_tabs_24 to iconsR.drawable.mozac_ic_tab_24,
    R.drawable.dejavu_ic_temporary_container_24 to R.drawable.dejavu_ic_classic_temporary_container_24,
    // The large pictures of empty pages, made by scripts/make-media-illustrations.py. The others already show
    // Firefox's icons.
    R.drawable.dejavu_illustration_account to R.drawable.dejavu_illustration_classic_account,
    R.drawable.dejavu_illustration_bookmarks to R.drawable.dejavu_illustration_classic_bookmarks,
    R.drawable.dejavu_illustration_downloads to R.drawable.dejavu_illustration_classic_downloads,
    R.drawable.dejavu_illustration_history to R.drawable.dejavu_illustration_classic_history,
    R.drawable.dejavu_illustration_no_results to R.drawable.dejavu_illustration_classic_no_results,
)

// Made by scripts/make-media-rounded-icons.py, for Dejavu's icons and the Firefox ones its menus and actions show.
private val roundedIcons = mapOf(
    R.drawable.dejavu_ic_accounts_24 to R.drawable.dejavu_ic_rounded_group_24,
    R.drawable.dejavu_ic_bookmark_add_24 to R.drawable.dejavu_ic_rounded_bookmark_add_24,
    R.drawable.dejavu_ic_bookmarked_24 to R.drawable.dejavu_ic_rounded_bookmark_fill_24,
    R.drawable.dejavu_ic_bookmarks_24 to R.drawable.dejavu_ic_rounded_bookmarks_24,
    R.drawable.dejavu_ic_clipboard_24 to R.drawable.dejavu_ic_rounded_content_paste_24,
    R.drawable.dejavu_ic_container_24 to R.drawable.dejavu_ic_rounded_inventory_2_24,
    R.drawable.dejavu_ic_create_folder_24 to R.drawable.dejavu_ic_rounded_create_new_folder_24,
    R.drawable.dejavu_ic_downloads_24 to R.drawable.dejavu_ic_rounded_download_24,
    R.drawable.dejavu_ic_extensions_24 to R.drawable.dejavu_ic_rounded_extension_24,
    R.drawable.dejavu_ic_find_in_page_24 to R.drawable.dejavu_ic_rounded_find_in_page_24,
    R.drawable.dejavu_ic_folder_empty_24 to R.drawable.dejavu_ic_rounded_folder_open_24,
    R.drawable.dejavu_ic_full_screen_24 to R.drawable.dejavu_ic_rounded_fullscreen_24,
    R.drawable.dejavu_ic_full_screen_exit_24 to R.drawable.dejavu_ic_rounded_fullscreen_exit_24,
    R.drawable.dejavu_ic_history_24 to R.drawable.dejavu_ic_rounded_history_24,
    R.drawable.dejavu_ic_home_24 to R.drawable.dejavu_ic_rounded_home_24,
    R.drawable.dejavu_ic_new_tab_24 to R.drawable.dejavu_ic_rounded_add_circle_24,
    R.drawable.dejavu_ic_no_container_24 to R.drawable.dejavu_ic_rounded_block_24,
    R.drawable.dejavu_ic_passwords_24 to R.drawable.dejavu_ic_rounded_key_24,
    R.drawable.dejavu_ic_private_tab_24 to R.drawable.dejavu_ic_rounded_domino_mask_24,
    R.drawable.dejavu_ic_profile_24 to R.drawable.dejavu_ic_rounded_account_circle_24,
    R.drawable.dejavu_ic_refresh_24 to R.drawable.dejavu_ic_rounded_refresh_24,
    R.drawable.dejavu_ic_reopen_tab_24 to R.drawable.dejavu_ic_rounded_undo_24,
    R.drawable.dejavu_ic_reset_pin_24 to R.drawable.dejavu_ic_rounded_restart_alt_24,
    R.drawable.dejavu_ic_save_page_24 to R.drawable.dejavu_ic_rounded_download_for_offline_24,
    R.drawable.dejavu_ic_search_24 to R.drawable.dejavu_ic_rounded_search_24,
    R.drawable.dejavu_ic_settings_24 to R.drawable.dejavu_ic_rounded_settings_24,
    R.drawable.dejavu_ic_share_24 to R.drawable.dejavu_ic_rounded_share_24,
    R.drawable.dejavu_ic_sleep_24 to R.drawable.dejavu_ic_rounded_bedtime_24,
    R.drawable.dejavu_ic_split_24 to R.drawable.dejavu_ic_rounded_splitscreen_24,
    R.drawable.dejavu_ic_split_side_24 to R.drawable.dejavu_ic_rounded_view_column_2_24,
    R.drawable.dejavu_ic_tabs_24 to R.drawable.dejavu_ic_rounded_tab_24,
    R.drawable.dejavu_ic_temporary_container_24 to R.drawable.dejavu_ic_rounded_auto_delete_24,
    R.drawable.dejavu_ic_unsplit_24 to R.drawable.dejavu_ic_rounded_crop_portrait_24,
    iconsR.drawable.mozac_ic_back_24 to R.drawable.dejavu_ic_rounded_arrow_back_24,
    iconsR.drawable.mozac_ic_copy_24 to R.drawable.dejavu_ic_rounded_content_copy_24,
    iconsR.drawable.mozac_ic_cross_24 to R.drawable.dejavu_ic_rounded_close_24,
    iconsR.drawable.mozac_ic_cross_circle_fill_24 to R.drawable.dejavu_ic_rounded_cancel_fill_24,
    iconsR.drawable.mozac_ic_data_clearance_24 to R.drawable.dejavu_ic_rounded_cleaning_services_24,
    iconsR.drawable.mozac_ic_delete_24 to R.drawable.dejavu_ic_rounded_delete_24,
    iconsR.drawable.mozac_ic_device_desktop_24 to R.drawable.dejavu_ic_rounded_desktop_windows_24,
    iconsR.drawable.mozac_ic_edit_24 to R.drawable.dejavu_ic_rounded_edit_24,
    iconsR.drawable.mozac_ic_external_link_24 to R.drawable.dejavu_ic_rounded_open_in_new_24,
    iconsR.drawable.mozac_ic_folder_24 to R.drawable.dejavu_ic_rounded_folder_24,
    iconsR.drawable.mozac_ic_folder_arrow_right_24 to R.drawable.dejavu_ic_rounded_drive_file_move_24,
    iconsR.drawable.mozac_ic_forward_24 to R.drawable.dejavu_ic_rounded_arrow_forward_24,
    iconsR.drawable.mozac_ic_grid_add_24 to R.drawable.dejavu_ic_rounded_dashboard_customize_24,
    iconsR.drawable.mozac_ic_link_24 to R.drawable.dejavu_ic_rounded_link_24,
    iconsR.drawable.mozac_ic_pin_24 to R.drawable.dejavu_ic_rounded_keep_24,
    iconsR.drawable.mozac_ic_pin_fill_24 to R.drawable.dejavu_ic_rounded_keep_fill_24,
    iconsR.drawable.mozac_ic_pin_slash_24 to R.drawable.dejavu_ic_rounded_keep_off_24,
    iconsR.drawable.mozac_ic_plus_24 to R.drawable.dejavu_ic_rounded_add_24,
    iconsR.drawable.mozac_ic_print_24 to R.drawable.dejavu_ic_rounded_print_24,
    iconsR.drawable.mozac_ic_reader_view_24 to R.drawable.dejavu_ic_rounded_chrome_reader_mode_24,
    iconsR.drawable.mozac_ic_reader_view_fill_24 to R.drawable.dejavu_ic_rounded_chrome_reader_mode_fill_24,
    iconsR.drawable.mozac_ic_save_file_24 to R.drawable.dejavu_ic_rounded_picture_as_pdf_24,
    iconsR.drawable.mozac_ic_screenshot_24 to R.drawable.dejavu_ic_rounded_screenshot_24,
    iconsR.drawable.mozac_ic_tab_ungroup_24 to R.drawable.dejavu_ic_rounded_ungroup_24,
    iconsR.drawable.mozac_ic_translate_24 to R.drawable.dejavu_ic_rounded_translate_24,
    // The large pictures of empty pages, made by scripts/make-media-illustrations.py.
    R.drawable.dejavu_illustration_account to R.drawable.dejavu_illustration_rounded_account,
    R.drawable.dejavu_illustration_bookmarks to R.drawable.dejavu_illustration_rounded_bookmarks,
    R.drawable.dejavu_illustration_devices to R.drawable.dejavu_illustration_rounded_devices,
    R.drawable.dejavu_illustration_downloads to R.drawable.dejavu_illustration_rounded_downloads,
    R.drawable.dejavu_illustration_folder to R.drawable.dejavu_illustration_rounded_folder,
    R.drawable.dejavu_illustration_history to R.drawable.dejavu_illustration_rounded_history,
    R.drawable.dejavu_illustration_no_results to R.drawable.dejavu_illustration_rounded_no_results,
    R.drawable.dejavu_illustration_notifications to R.drawable.dejavu_illustration_rounded_notifications,
    R.drawable.dejavu_illustration_offline to R.drawable.dejavu_illustration_rounded_offline,
    R.drawable.dejavu_illustration_problem to R.drawable.dejavu_illustration_rounded_problem,
    R.drawable.dejavu_illustration_shield to R.drawable.dejavu_illustration_rounded_shield,
    R.drawable.dejavu_illustration_shield_off to R.drawable.dejavu_illustration_rounded_shield_off,
    R.drawable.dejavu_illustration_sparkle to R.drawable.dejavu_illustration_rounded_sparkle,
)
