/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenEntryTest {
    @Test
    fun `full screen can be put in the menu and on the bars, but is not in the default menu or bar`() {
        val entry = MoreMenuEntry.BuiltIn(MoreMenuItem.FULL_SCREEN)

        assertEquals(entry, MoreMenuLayout.entryOf("full_screen", emptyList()))
        assertEquals(entry, ActionsBarLayout.entryOf("full_screen", emptyList()))
        assertTrue(entry in MoreMenuLayout.available(emptyList()))
        assertTrue(entry in ActionsBarLayout.available(emptyList()))
        assertFalse("full_screen" in MoreMenuLayout.DEFAULT.flatten())
        assertFalse("full_screen" in ActionsBarLayout.DEFAULT)
    }

    @Test
    fun `it sits right after desktop site in the list of entries`() {
        val items = MoreMenuItem.entries

        assertEquals(items.indexOf(MoreMenuItem.DESKTOP_SITE) + 1, items.indexOf(MoreMenuItem.FULL_SCREEN))
    }
}
