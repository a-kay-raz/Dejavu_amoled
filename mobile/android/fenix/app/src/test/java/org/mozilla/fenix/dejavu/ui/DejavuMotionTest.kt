/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DejavuMotionTest {
    @Test
    fun `animations go as fast as the system has them while they are on`() {
        assertEquals(1f, motionScale(systemScale = 1f, animationsOn = true))
        assertEquals(0.5f, motionScale(systemScale = 0.5f, animationsOn = true))
        assertEquals(0f, motionScale(systemScale = 0f, animationsOn = true))
    }

    @Test
    fun `turned off in Customize, nothing moves whatever the system has`() {
        assertEquals(0f, motionScale(systemScale = 1f, animationsOn = false))
        assertEquals(0f, motionScale(systemScale = 10f, animationsOn = false))
    }
}
