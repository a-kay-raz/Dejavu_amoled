/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.graphics.Bitmap
import io.mockk.mockk
import mozilla.components.browser.state.state.createTab
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsSameOnHomeTest {
    private val first = createTab(url = "https://first.example/", id = "first", title = "First")
    private val second = createTab(url = "https://second.example/", id = "second", title = "Second")
    private val tabs = listOf(first, second)

    @Test
    fun `load progress does not count`() {
        val loading = first.copy(content = first.content.copy(progress = 50, loading = true))

        assertTrue(isSameOnHome(tabs, listOf(loading, second)))
    }

    @Test
    fun `a new title counts`() {
        val renamed = first.copy(content = first.content.copy(title = "Renamed"))

        assertFalse(isSameOnHome(tabs, listOf(renamed, second)))
    }

    @Test
    fun `an icon that arrived counts`() {
        val withIcon = first.copy(content = first.content.copy(icon = mockk<Bitmap>()))

        assertFalse(isSameOnHome(tabs, listOf(withIcon, second)))
    }

    @Test
    fun `waking a tab counts`() {
        val awake = first.copy(engineState = first.engineState.copy(engineSession = mockk(relaxed = true)))

        assertFalse(isSameOnHome(tabs, listOf(awake, second)))
    }

    @Test
    fun `a new order counts`() {
        assertFalse(isSameOnHome(tabs, listOf(second, first)))
    }
}
