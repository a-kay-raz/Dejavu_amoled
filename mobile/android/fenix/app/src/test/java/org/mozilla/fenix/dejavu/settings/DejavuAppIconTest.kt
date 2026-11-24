/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.iconpicker.AppIcon
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class DejavuAppIconTest {
    // Robolectric has no getActivityInfo with Android 13's flags yet.
    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    fun `every icon has its alias in the manifest, which draws it`() {
        DejavuAppIcon.entries.forEach { assertNotNull(it.name, it.drawable(testContext)) }
    }

    @Test
    fun `every icon has its own alias, name and splash screen`() {
        val entries = DejavuAppIcon.entries
        assertEquals(entries.size, entries.map { it.appIcon }.toSet().size)
        assertEquals(entries.size, entries.map { it.label }.toSet().size)
        assertEquals(entries.size, entries.map { it.splash }.toSet().size)
        assertEquals(AppIcon.AppDefault, DejavuAppIcon.YELLOW.appIcon)
    }

    @Test
    fun `an install starts with the yellow icon, and the icon shown is the one whose alias is enabled`() {
        val packageManager = testContext.packageManager
        assertEquals(DejavuAppIcon.YELLOW, DejavuAppIcon.current(testContext))

        packageManager.setComponentEnabledSetting(
            DejavuAppIcon.YELLOW.component(testContext),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            DejavuAppIcon.CLASSIC.component(testContext),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )

        assertEquals(DejavuAppIcon.CLASSIC, DejavuAppIcon.current(testContext))
    }
}
