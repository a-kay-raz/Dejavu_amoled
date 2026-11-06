/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.containers

import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.TabListAction.RestoreAction.RestoreLocation
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.state.recover.RecoverableTab
import mozilla.components.browser.state.state.recover.TabState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TemporaryContainersTest {
    @Test
    fun `open tabs keep their containers, recently closed ones do not`() {
        val state = BrowserState(
            tabs = listOf(createTab("https://open.example", contextId = "open"), createTab("https://none.example")),
            closedTabs = listOf(
                TabState(id = "closed", url = "https://closed.example", contextId = "closed"),
                TabState(id = "plain", url = "https://plain.example"),
            ),
        )
        assertEquals(setOf("open"), TemporaryContainerMiddleware.usedContextIds(state))
    }

    @Test
    fun `a closed tab brought back after its container was destroyed opens in a new temporary container`() = runTest {
        val storage = DejavuContainerStorage.get(testContext)
        storage.saveContainer("kept-container", "Kept", ContainerColor.BLUE, ContainerState.Icon.BRIEFCASE)
        val store = BrowserStore(middleware = listOf(TemporaryContainerMiddleware(testContext)))

        store.dispatch(
            TabListAction.RestoreAction(
                listOf(
                    RecoverableTab(null, TabState(id = "a", url = "https://a.example", contextId = "destroyed")),
                    RecoverableTab(null, TabState(id = "b", url = "https://b.example", contextId = "destroyed")),
                    RecoverableTab(null, TabState(id = "k", url = "https://k.example", contextId = "kept-container")),
                ),
                restoreLocation = RestoreLocation.BEGINNING,
            ),
        )

        val replacement = store.state.findTab("a")?.contextId
        assertNotEquals("destroyed", replacement)
        assertTrue(storage.isTemporary(replacement!!))
        assertEquals(replacement, store.state.findTab("b")?.contextId)
        assertEquals("kept-container", store.state.findTab("k")?.contextId)
    }
}
