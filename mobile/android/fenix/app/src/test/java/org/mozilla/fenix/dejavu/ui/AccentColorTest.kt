/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AccentColorTest {
    @Test
    fun `an accent is kept by its key, and an unknown key means the neutral one`() {
        AccentColor.entries.forEach { assertEquals(it, AccentColor.of(it.key)) }
        assertEquals(AccentColor.NEUTRAL, AccentColor.of(null))
        assertEquals(AccentColor.NEUTRAL, AccentColor.of("magenta"))
    }

    @Test
    fun `only the neutral accent leaves the colors and the screens made of views as they are`() {
        assertNull(AccentColor.NEUTRAL.colors)
        assertNull(AccentColor.NEUTRAL.overlay)
        AccentColor.entries.filter { it != AccentColor.NEUTRAL }.forEach { accent ->
            assertNotNull(accent.colors)
            assertNotNull(accent.overlay)
        }
    }

    @Test
    fun `every accent has colors of its own`() {
        val colors = AccentColor.entries.mapNotNull { it.colors }
        assertEquals(colors.size, colors.map { it.light }.toSet().size)
        assertEquals(colors.size, colors.map { it.dark }.toSet().size)
    }
}
