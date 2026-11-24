/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Where the floating button of full screen view floats, as the user last dragged it: at the end of the screen or at
 * its start, [height] of the way down the room it has there, from 0 at the top to 1 at the bottom.
 */
data class FloatingButtonPlace(val atEnd: Boolean = true, val height: Float = DEFAULT_HEIGHT) {
    /** Where the button's top left corner goes in [room], the space it moves in, from the left unless [rtl]. */
    fun offsetIn(room: Size, rtl: Boolean): Offset =
        Offset(if (atEnd != rtl) room.width else 0f, height.coerceIn(0f, 1f) * room.height)

    companion object {
        /** Somewhat below the middle, out of the way of the menus and the text fields pages put at their ends. */
        const val DEFAULT_HEIGHT = 0.6f

        /** Where the button dragged to [offset] in [room] rests: at the nearer side, as far down as it was let go. */
        fun nearest(offset: Offset, room: Size, rtl: Boolean): FloatingButtonPlace = FloatingButtonPlace(
            atEnd = (offset.x > room.width / 2) != rtl,
            height = if (room.height > 0f) (offset.y / room.height).coerceIn(0f, 1f) else DEFAULT_HEIGHT,
        )
    }
}
