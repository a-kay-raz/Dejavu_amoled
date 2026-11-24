/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingButtonPlaceTest {
    private val room = Size(600f, 1000f)

    @Test
    fun `the button starts at the end of the screen, somewhat below the middle`() {
        val place = FloatingButtonPlace()

        assertEquals(Offset(600f, 600f), place.offsetIn(room, rtl = false))
        // From right to left, the end of the screen is on its left.
        assertEquals(Offset(0f, 600f), place.offsetIn(room, rtl = true))
    }

    @Test
    fun `a button dragged anywhere goes to the nearer side, as far down as it was let go`() {
        val leftHigh = Offset(120f, 250f)

        assertEquals(FloatingButtonPlace(atEnd = false, height = 0.25f), nearest(leftHigh, rtl = false))
        assertEquals(FloatingButtonPlace(atEnd = true, height = 0.9f), nearest(Offset(480f, 900f), rtl = false))
        // From right to left, the left side is the end of the screen.
        assertEquals(FloatingButtonPlace(atEnd = true, height = 0.25f), nearest(leftHigh, rtl = true))
    }

    @Test
    fun `a place stays within the room, also when the room shrank`() {
        val place = FloatingButtonPlace(atEnd = true, height = 1.4f)

        assertEquals(Offset(600f, 1000f), place.offsetIn(room, rtl = false))
        assertEquals(1f, nearest(Offset(600f, 1300f), rtl = false).height)
        assertEquals(0f, nearest(Offset(600f, -40f), rtl = false).height)
    }

    @Test
    fun `without room to move up and down, the button keeps the usual height`() {
        val flat = Size(600f, 0f)

        assertEquals(
            FloatingButtonPlace.DEFAULT_HEIGHT,
            FloatingButtonPlace.nearest(Offset(600f, 0f), flat, rtl = false).height,
        )
    }

    private fun nearest(offset: Offset, rtl: Boolean) = FloatingButtonPlace.nearest(offset, room, rtl)
}
