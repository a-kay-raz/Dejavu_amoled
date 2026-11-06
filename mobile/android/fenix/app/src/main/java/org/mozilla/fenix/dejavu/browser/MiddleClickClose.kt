/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toSize

/**
 * Like on a computer, a middle-click with a mouse on [this] runs [onClose], which closes the tab it shows, while mouse
 * support is on. The click never reaches what is under it, so it does not also select or open the tab, and a [null]
 * [onClose] makes it do nothing, for a tab with no close button. Put it before the modifier that handles taps: pointer
 * events go from the outer modifiers to the inner ones.
 */
fun Modifier.closeOnMiddleClick(onClose: (() -> Unit)?): Modifier = composed {
    val close by rememberUpdatedState(onClose)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val isMiddle = down.type == PointerType.Mouse &&
                currentEvent.buttons.isTertiaryPressed && !currentEvent.buttons.isPrimaryPressed
            if (!DejavuMouse.isEnabled || !isMiddle) return@awaitEachGesture
            down.consume()
            var end: Offset = down.position
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach {
                    end = it.position
                    it.consume()
                }
            } while (event.changes.any { it.pressed })
            // Releasing away from the tab, like with a tap, does not count.
            if (Rect(Offset.Zero, size.toSize()).contains(end)) close?.invoke()
        }
    }
}
