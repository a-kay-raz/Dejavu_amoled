/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.iconpicker.AppIcon
import org.mozilla.fenix.iconpicker.DefaultAppIconRepository
import org.mozilla.fenix.iconpicker.DefaultPackageManagerWrapper
import org.mozilla.fenix.utils.ShortcutManagerWrapperDefault
import org.mozilla.fenix.utils.ShortcutsUpdaterDefault
import org.mozilla.fenix.utils.changeAppLauncherIcon
import org.mozilla.gecko.search.SearchWidgetProvider

/**
 * The icons Customize offers for Dejavu on the home screen and in the list of apps. Each is an activity alias of the
 * manifest, of which one is enabled at a time: the yellow icon is the App alias that every install starts with.
 *
 * @property appIcon The alias, as Firefox's icon picker knows it.
 * @property label What Customize calls the icon.
 * @property splash The theme of the splash screen Dejavu starts with while the icon is picked, from Android 13 on;
 * none for the manifest's, which shows the yellow logo.
 */
internal enum class DejavuAppIcon(
    val appIcon: AppIcon,
    @param:StringRes val label: Int,
    @param:StyleRes val splash: Int,
) {
    YELLOW(AppIcon.AppDefault, R.string.dejavu_app_icon_yellow, Resources.ID_NULL),
    BLUE(AppIcon.AppDejavuBlue, R.string.dejavu_app_icon_blue, R.style.DejavuSplashBlue),
    CLASSIC(AppIcon.AppDejavuClassic, R.string.dejavu_app_icon_classic, R.style.DejavuSplashClassic),
    ;

    /** The activity alias that shows this icon. */
    fun component(context: Context) = ComponentName(context, "${context.packageName}.${appIcon.aliasSuffix}")

    /** The icon as launchers show it, Dejavu Test's with its band; null when the manifest has no such alias. */
    fun drawable(context: Context): Drawable? {
        val packageManager = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getActivityInfo(
                    component(context),
                    PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(component(context), PackageManager.MATCH_DISABLED_COMPONENTS)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return ContextCompat.getDrawable(context, info.iconResource)
    }

    companion object {
        /** The icon launchers show now. */
        fun current(context: Context): DejavuAppIcon {
            val shown = DefaultAppIconRepository(
                packageManager = DefaultPackageManagerWrapper(context.packageManager),
                packageName = context.packageName,
            ).selectedAppIcon
            return entries.firstOrNull { it.appIcon == shown } ?: YELLOW
        }

        /**
         * Shows [to] in place of [from] on the home screen and in the list of apps, moving the sites and shortcuts
         * added to the home screen over to it, and starts Dejavu with its splash screen. False when Android could not
         * move the shortcuts, which leaves the icon as it was.
         */
        fun change(activity: Activity, from: DejavuAppIcon, to: DejavuAppIcon): Boolean {
            val changed = changeAppLauncherIcon(
                packageManager = activity.packageManager,
                shortcutManager = ShortcutManagerWrapperDefault(activity),
                shortcutInfo = ShortcutsUpdaterDefault(activity),
                appAlias = from.component(activity),
                newAppAlias = to.component(activity),
                crashReporter = activity.components.analytics.crashReporter,
            )
            if (changed) {
                SearchWidgetProvider.updateAllWidgets(activity)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.splashScreen.setSplashScreenTheme(to.splash)
                }
            }
            return changed
        }

        /** Whether changing the icon removes the sites and shortcuts added to the home screen, as before Android 10. */
        fun removesShortcuts(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                runCatching { ShortcutManagerWrapperDefault(context).getPinnedShortcuts().isNotEmpty() }
                    .getOrDefault(false)
    }
}
