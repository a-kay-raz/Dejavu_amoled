/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.discover

import androidx.preference.Preference
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.menu.MoreMenuEntry
import org.mozilla.fenix.dejavu.menu.MoreMenuItem
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewThingsTest {
    @Test
    fun `the Full screen button has the dot until the user looked at it`() {
        val fullScreen = MoreMenuEntry.BuiltIn(MoreMenuItem.FULL_SCREEN)

        assertTrue(fullScreen.isNewAmong(setOf(NewThing.FULL_SCREEN)))
        assertFalse(fullScreen.isNewAmong(setOf(NewThing.ACCENT_COLOR)))
        assertFalse(MoreMenuEntry.BuiltIn(MoreMenuItem.BACK).isNewAmong(NewThing.entries.toSet()))
    }

    @Test
    fun `the Screenshot button has the dot until the user looked at it`() {
        val screenshot = MoreMenuEntry.BuiltIn(MoreMenuItem.SCREENSHOT)

        assertTrue(screenshot.isNewAmong(setOf(NewThing.SCREENSHOT)))
        assertFalse(screenshot.isNewAmong(setOf(NewThing.FULL_SCREEN)))
        assertTrue(NewThing.SCREENSHOT in NewThing.CUSTOMIZE)
    }

    @Test
    fun `Settings has the dot while anything in the settings is new, except in the menu's editor`() {
        val settings = MoreMenuEntry.BuiltIn(MoreMenuItem.SETTINGS)

        assertTrue(settings.isNewAmong(setOf(NewThing.ESSENTIALS)))
        assertTrue(settings.isNewAmong(setOf(NewThing.PRIVATE_BROWSING)))
        assertFalse(settings.isNewAmong(setOf(NewThing.ESSENTIALS), leadsToSettings = false))
        assertFalse(settings.isNewAmong(emptySet()))
    }

    @Test
    fun `the lock and history of workspaces are found on the home screen, so they lead nobody to the settings`() {
        val settings = MoreMenuEntry.BuiltIn(MoreMenuItem.SETTINGS)

        assertFalse(settings.isNewAmong(setOf(NewThing.WORKSPACE_PRIVACY)))
        assertFalse(setOf(NewThing.WORKSPACE_PRIVACY).inSettings())
    }

    @Test
    fun `the dot after a preference's title comes and goes, and screen readers read it as New`() {
        val preference = Preference(testContext).apply { title = "Customize" }
        val new = testContext.getString(R.string.dejavu_new)

        preference.showNewDot(true)
        preference.showNewDot(true)
        assertEquals("Customize $new", preference.title.toString())

        preference.showNewDot(false)
        assertEquals("Customize", preference.title.toString())
    }
}
