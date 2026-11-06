/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.mockk.every
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.utils.Settings
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DejavuDefaultsTest {
    private lateinit var settings: Settings

    @Before
    fun setup() {
        settings = Settings(testContext)
        every { testContext.components.settings } returns settings
    }

    @Test
    fun `remote debugging is off again at every start, and so is its switch`() {
        val key = testContext.getString(R.string.pref_key_remote_debugging)
        val switchPreferences = PreferenceManager.getDefaultSharedPreferences(testContext)
        settings.preferences.edit { putBoolean(key, true) }
        switchPreferences.edit { putBoolean(key, true) }
        assertTrue(settings.isRemoteDebuggingEnabled)

        DejavuDefaults.applyEveryStart(testContext)

        assertFalse(settings.isRemoteDebuggingEnabled)
        assertFalse(switchPreferences.getBoolean(key, true))
    }
}
