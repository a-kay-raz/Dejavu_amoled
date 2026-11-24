/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import android.content.Context
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.DejavuSettings

/**
 * The accent colors Customize offers (issue #218) in place of Dejavu's near white or near black accent: switches, check
 * marks, buttons, the shown tab and what is selected take it, on Dejavu's screens and Firefox's alike. Private browsing
 * keeps its purple.
 *
 * @property key How the accent is kept in the settings.
 * @property label Its name, read out by screen readers.
 * @property colors Its colors, see [AccentTones], or `null` for the neutral accent.
 * @property overlay The theme overlay that gives it to the screens made of views, or `null` for the neutral accent.
 */
enum class AccentColor(
    val key: String,
    @param:StringRes val label: Int,
    internal val colors: AccentColors?,
    @param:StyleRes val overlay: Int?,
) {
    NEUTRAL("neutral", R.string.dejavu_accent_neutral, null, null),

    /** The colors Android takes from the wallpaper, from Android 12. */
    SYSTEM(
        "system",
        R.string.dejavu_accent_system,
        AccentColors(
            android.R.color.system_accent1_600,
            android.R.color.system_accent1_100,
            android.R.color.system_accent1_200,
            android.R.color.system_accent1_700,
        ),
        R.style.DejavuAccent_System,
    ),
    YELLOW("yellow", R.string.tab_group_color_yellow, AccentColors.of("yellow"), R.style.DejavuAccent_Yellow),
    ORANGE("orange", R.string.tab_group_color_orange, AccentColors.of("orange"), R.style.DejavuAccent_Orange),
    RED("red", R.string.tab_group_color_red, AccentColors.of("red"), R.style.DejavuAccent_Red),
    PINK("pink", R.string.tab_group_color_pink, AccentColors.of("pink"), R.style.DejavuAccent_Pink),
    PURPLE("purple", R.string.tab_group_color_purple, AccentColors.of("purple"), R.style.DejavuAccent_Purple),
    BLUE("blue", R.string.tab_group_color_blue, AccentColors.of("blue"), R.style.DejavuAccent_Blue),
    CYAN("cyan", R.string.tab_group_color_cyan, AccentColors.of("cyan"), R.style.DejavuAccent_Cyan),
    GREEN("green", R.string.tab_group_color_green, AccentColors.of("green"), R.style.DejavuAccent_Green),
    ;

    companion object {
        /** The accent kept as [key], or the neutral one for a key no accent has. */
        fun of(key: String?): AccentColor = entries.firstOrNull { it.key == key } ?: NEUTRAL

        /** The accents this phone can show: the wallpaper's colors only from Android 12. */
        fun available(): List<AccentColor> = entries.filter { it != SYSTEM || SDK_INT >= VERSION_CODES.S }
    }
}

/**
 * The color resources of an accent: what stands out in the light theme and the faint fill behind what is picked there,
 * then the same two in the dark themes. Each Dejavu accent has them in `dejavu_accents.xml`, as
 * `dejavu_accent_<name>_light`, `_light_container`, `_dark` and `_dark_container`.
 */
internal class AccentColors(
    @param:ColorRes val light: Int,
    @param:ColorRes val lightContainer: Int,
    @param:ColorRes val dark: Int,
    @param:ColorRes val darkContainer: Int,
) {
    companion object {
        fun of(name: String): AccentColors = when (name) {
            "yellow" -> AccentColors(
                R.color.dejavu_accent_yellow_light,
                R.color.dejavu_accent_yellow_light_container,
                R.color.dejavu_accent_yellow_dark,
                R.color.dejavu_accent_yellow_dark_container,
            )
            "orange" -> AccentColors(
                R.color.dejavu_accent_orange_light,
                R.color.dejavu_accent_orange_light_container,
                R.color.dejavu_accent_orange_dark,
                R.color.dejavu_accent_orange_dark_container,
            )
            "red" -> AccentColors(
                R.color.dejavu_accent_red_light,
                R.color.dejavu_accent_red_light_container,
                R.color.dejavu_accent_red_dark,
                R.color.dejavu_accent_red_dark_container,
            )
            "pink" -> AccentColors(
                R.color.dejavu_accent_pink_light,
                R.color.dejavu_accent_pink_light_container,
                R.color.dejavu_accent_pink_dark,
                R.color.dejavu_accent_pink_dark_container,
            )
            "purple" -> AccentColors(
                R.color.dejavu_accent_purple_light,
                R.color.dejavu_accent_purple_light_container,
                R.color.dejavu_accent_purple_dark,
                R.color.dejavu_accent_purple_dark_container,
            )
            "blue" -> AccentColors(
                R.color.dejavu_accent_blue_light,
                R.color.dejavu_accent_blue_light_container,
                R.color.dejavu_accent_blue_dark,
                R.color.dejavu_accent_blue_dark_container,
            )
            "cyan" -> AccentColors(
                R.color.dejavu_accent_cyan_light,
                R.color.dejavu_accent_cyan_light_container,
                R.color.dejavu_accent_cyan_dark,
                R.color.dejavu_accent_cyan_dark_container,
            )
            else -> AccentColors(
                R.color.dejavu_accent_green_light,
                R.color.dejavu_accent_green_light_container,
                R.color.dejavu_accent_green_dark,
                R.color.dejavu_accent_green_dark_container,
            )
        }
    }
}

/** An accent in one theme: [primary] stands out, and [container] is the faint fill behind what is picked. */
data class AccentTone(val primary: Color, val container: Color)

/** An accent's [light] tone, for the light theme, and its [dark] one, for the dark themes. */
data class AccentTones(val light: AccentTone, val dark: AccentTone)

/** The accent picked in Customize, see [AccentColor], which [DejavuColors] and [overlay] give the app. */
object DejavuAccent {
    /** The accent picked. */
    var picked = AccentColor.NEUTRAL
        private set

    /** The tones of the accent picked, or `null` for the neutral one; the screens shown take them as they change. */
    var tones by mutableStateOf<AccentTones?>(null)
        private set

    /** The theme overlay that gives the activities made of views the accent picked, or `null` for the neutral one. */
    val overlay: Int?
        get() = picked.overlay

    /** Follows the accent picked in [settings] from now on. */
    fun install(context: Context, settings: DejavuSettings, scope: CoroutineScope) {
        val app = context.applicationContext
        update(app, settings.accentColor.value)
        scope.launch { settings.accentColor.collect { update(app, it) } }
    }

    /**
     * Reads the tones of the accent picked again, as an activity starts: the wallpaper's colors may have changed, which
     * starts the activities again.
     */
    fun refresh(context: Context) {
        DejavuSettings.peek()?.let { update(context.applicationContext, it.accentColor.value) }
    }

    private fun update(context: Context, accent: AccentColor) {
        picked = accent.takeIf { it in AccentColor.available() } ?: AccentColor.NEUTRAL
        tones = picked.colors?.let { colors ->
            fun color(id: Int) = Color(context.getColor(id))
            AccentTones(
                light = AccentTone(color(colors.light), color(colors.lightContainer)),
                dark = AccentTone(color(colors.dark), color(colors.darkContainer)),
            )
        }
    }
}
