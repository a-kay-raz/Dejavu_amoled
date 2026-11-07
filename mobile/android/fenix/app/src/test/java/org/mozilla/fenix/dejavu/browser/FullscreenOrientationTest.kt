/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.pm.ActivityInfo
import androidx.fragment.app.FragmentActivity
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FullscreenOrientationTest {
    private val activity =
        Robolectric.buildActivity(FragmentActivity::class.java).setup().get().apply {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        }
    private val store =
        BrowserStore(
            BrowserState(tabs = listOf(createTab("https://m.youtube.com/", id = "video")), selectedTabId = "video"),
        )
    private val dispatcher = StandardTestDispatcher()
    private val feature =
        FullscreenOrientationFeature(mockk(relaxed = true), activity, store, dispatcher).apply {
            start()
            settle()
        }

    private fun settle() = dispatcher.scheduler.advanceUntilIdle()

    private fun fullscreen(enabled: Boolean) {
        store.dispatch(ContentAction.FullScreenChangedAction("video", enabled))
        settle()
    }

    private fun lockLandscape() = feature.onOrientationLock(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

    @Test
    fun `a lock the tab kept from a fullscreen request that failed does not turn the screen`() {
        lockLandscape()
        settle()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, activity.requestedOrientation)
    }

    @Test
    fun `a fullscreen page turns the screen`() {
        fullscreen(true)

        lockLandscape()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
    }

    @Test
    fun `a lock that comes just before the store hears of the fullscreen still turns the screen`() {
        lockLandscape()

        fullscreen(true)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
    }

    @Test
    fun `a lock Firefox did not give back is given back when the page leaves fullscreen`() {
        fullscreen(true)
        lockLandscape()

        fullscreen(false)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test
    fun `leaving fullscreen without a lock leaves the screen to the other features`() {
        fullscreen(true)

        fullscreen(false)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, activity.requestedOrientation)
    }
}
