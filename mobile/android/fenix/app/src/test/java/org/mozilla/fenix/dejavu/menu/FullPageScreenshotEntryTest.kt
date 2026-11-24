/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullPageScreenshotEntryTest {
    @Test
    fun `the full page screenshot can be put in the menu and on the bars, but is not in the default menu`() {
        val entry = MoreMenuEntry.BuiltIn(MoreMenuItem.SCREENSHOT)

        assertEquals(entry, MoreMenuLayout.entryOf("screenshot", emptyList()))
        assertTrue(entry in MoreMenuLayout.available(emptyList()))
        assertTrue(entry in ActionsBarLayout.available(emptyList()))
        assertFalse("screenshot" in MoreMenuLayout.DEFAULT.flatten())
    }

    @Test
    fun `it sits right after save as PDF in the list of entries`() {
        val items = MoreMenuItem.entries

        assertEquals(items.indexOf(MoreMenuItem.SAVE_AS_PDF) + 1, items.indexOf(MoreMenuItem.SCREENSHOT))
    }

    @Test
    fun `a private tab is only pictured where screenshots are allowed in private browsing`() {
        assertTrue(FullPageScreenshot.isAllowed(private = false, secureModeOverridden = false))
        assertFalse(FullPageScreenshot.isAllowed(private = true, secureModeOverridden = false))
        assertTrue(FullPageScreenshot.isAllowed(private = true, secureModeOverridden = true))
    }
}
