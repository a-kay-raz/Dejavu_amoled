/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DejavuMouseTest {
    @Test
    fun `opens web links only`() {
        assertTrue(isOpenableLink("https://example.com/a?b#c"))
        assertTrue(isOpenableLink("HTTP://example.com"))
        listOf(
            "",
            "https://",
            "https:example.com",
            "file:///sdcard/a.html",
            "about:config",
            "about:blank",
            "chrome://browser/content/a.xhtml",
            "resource://android/assets/a.js",
            "intent://scan#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "data:text/html,<b>a</b>",
            " https://example.com",
        ).forEach { assertFalse(it, isOpenableLink(it)) }
    }

    @Test
    fun `a stopped owner keeps the value of a newer owner`() {
        val held = OwnedValue<String>()
        val first = Any()
        val second = Any()
        held.set(first, "first")
        held.set(second, "second")
        held.clear(first)
        assertEquals("second", held.value)
        held.clear(second)
        assertNull(held.value)
    }

    @Test
    fun `registers a session once`() {
        val registrations = Registrations<Any>()
        val session = Any()
        assertTrue(registrations.add(session))
        assertFalse(registrations.add(session))
        assertTrue(registrations.add(Any()))
    }
}
