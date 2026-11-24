/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.history

import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.action.RecentlyClosedAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.state.recover.RecoverableTab
import mozilla.components.browser.state.state.recover.TabState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.storage.PageVisit
import mozilla.components.concept.storage.VisitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspaceHistoryTest {
    private val state = WorkspaceState(
        workspaces = listOf(
            Workspace("home", "Home", createdAt = 0L, updatedAt = 0L),
            Workspace("secret", "Secret", createdAt = 0L, updatedAt = 0L, savesHistory = false),
        ),
        pins = emptyList(),
        activeWorkspaceId = "home",
        assignments = mapOf("hidden" to "secret", "kept" to "home"),
    )

    private fun recoverable(id: String) = RecoverableTab(null, TabState(id = id, url = "https://$id.example/"))

    /** Records what reaches history. */
    private class RecordingHistory : HistoryTrackingDelegate {
        val visited = mutableListOf<String>()

        override suspend fun onVisited(uri: String, visit: PageVisit) {
            visited += uri
        }

        override suspend fun onTitleChanged(uri: String, title: String) = Unit

        override suspend fun onPreviewImageChange(uri: String, previewImageUrl: String) = Unit

        override suspend fun getVisited(uris: List<String>): List<Boolean> = uris.map { it in visited }

        override suspend fun getVisited(): List<String> = visited

        override fun shouldStoreUri(uri: String): Boolean = true
    }

    @Test
    fun `the tabs of a workspace that saves no history keep out of it, new ones as the workspace shown says`() {
        assertTrue(WorkspaceHistory.isOff(state, "hidden"))
        assertFalse(WorkspaceHistory.isOff(state, "kept"))
        assertFalse(WorkspaceHistory.isOff(state, "new"))
        assertTrue(WorkspaceHistory.isOff(state.copy(activeWorkspaceId = "secret"), "new"))
    }

    @Test
    fun `visits of those tabs are not saved, the visits of others are`() = runTest {
        val history = RecordingHistory()
        val isOff = { tabId: String -> WorkspaceHistory.isOff(state, tabId) }
        val hidden = TabHistoryDelegate(history, "hidden", isOff)
        val kept = TabHistoryDelegate(history, "kept", isOff)

        assertFalse(hidden.shouldStoreUri("https://hidden.example/"))
        hidden.onVisited("https://hidden.example/", PageVisit(VisitType.LINK))
        assertTrue(kept.shouldStoreUri("https://kept.example/"))
        kept.onVisited("https://kept.example/", PageVisit(VisitType.LINK))

        assertEquals(listOf("https://kept.example/"), history.visited)
    }

    @Test
    fun `closed tabs of those workspaces stay out of the recently closed tabs`() {
        val store = BrowserStore(
            BrowserState(tabs = listOf(createTab("https://hidden.example/", id = "hidden"), createTab("https://kept.example/", id = "kept"))),
            middleware = listOf(NoHistoryClosedTabsMiddleware { state }),
        )

        store.dispatch(UndoAction.AddRecoverableTabs("tag", listOf(recoverable("hidden"), recoverable("kept")), null))
        store.dispatch(RecentlyClosedAction.AddClosedTabsAction(listOf(recoverable("hidden"), recoverable("kept"))))

        assertEquals(listOf("kept"), store.state.closedTabs.map { it.id })
    }
}
