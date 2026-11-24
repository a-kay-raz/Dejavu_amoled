/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspaceLockTest {
    private fun workspace(id: String, locked: Boolean = false, savesHistory: Boolean = true) =
        Workspace(id, id, createdAt = 0L, updatedAt = 0L, locked = locked, savesHistory = savesHistory)

    private fun pin(id: String, workspaceId: String?, tabId: String, essential: Boolean = false) =
        PinnedItem(
            id = id,
            workspaceId = workspaceId,
            parentId = null,
            kind = PinKind.TAB,
            title = id,
            url = "https://$id.example/",
            essential = essential,
            tabId = tabId,
            createdAt = 0L,
            updatedAt = 0L,
        )

    private val state = WorkspaceState(
        workspaces = listOf(workspace("home"), workspace("bank", locked = true)),
        pins = listOf(pin("account", "bank", tabId = "pinned"), pin("mail", null, tabId = "essential", essential = true)),
        activeWorkspaceId = "bank",
        assignments = mapOf("statement" to "bank", "news" to "home", "essential" to "bank", "pinned" to "bank"),
    )

    @After
    fun lockAgain() {
        WorkspaceLock.prompting = false
        WorkspaceLock.privateTabsHide = { false }
        WorkspaceLock.lockAll()
        WorkspaceLock.showPrivate()
    }

    @Test
    fun `private tabs open as Dejavu is left show hidden until the user shows them`() {
        WorkspaceLock.privateTabsHide = { true }
        WorkspaceLock.lockAll()
        assertTrue(WorkspaceLock.privateHidden.value)

        WorkspaceLock.showPrivate()
        assertFalse(WorkspaceLock.privateHidden.value)
    }

    @Test
    fun `turning to another workspace hides the private tabs too, without locking the workspaces`() {
        WorkspaceLock.privateTabsHide = { true }
        WorkspaceLock.unlock("bank")
        WorkspaceLock.hidePrivate()
        assertTrue(WorkspaceLock.privateHidden.value)
        assertFalse(WorkspaceLock.hides(state.workspaces[1]))
    }

    @Test
    fun `private tabs stay as they are when none are open or Firefox's lock of private tabs locks them`() {
        WorkspaceLock.privateTabsHide = { false }
        WorkspaceLock.lockAll()
        assertFalse(WorkspaceLock.privateHidden.value)
    }

    @Test
    fun `the tabs of a locked workspace stay hidden until it is unlocked, and again once it locks`() {
        assertEquals("bank", WorkspaceLock.hiddenWorkspaceOf(state, "statement")?.id)
        assertEquals("bank", WorkspaceLock.hiddenWorkspaceOf(state, "pinned")?.id)
        assertNull(WorkspaceLock.hiddenWorkspaceOf(state, "news"))

        WorkspaceLock.unlock("bank")
        assertNull(WorkspaceLock.hiddenWorkspaceOf(state, "statement"))
        assertFalse(WorkspaceLock.hides(state.workspaces[1]))

        WorkspaceLock.lockAll()
        assertEquals("bank", WorkspaceLock.hiddenWorkspaceOf(state, "statement")?.id)
    }

    @Test
    fun `essentials are never hidden, and a tab not put in a workspace yet is in the one shown`() {
        assertNull(WorkspaceLock.hiddenWorkspaceOf(state, "essential"))
        assertEquals("bank", WorkspaceLock.hiddenWorkspaceOf(state, "new")?.id)
        assertNull(WorkspaceLock.hiddenWorkspaceOf(state.copy(activeWorkspaceId = "home"), "new"))
    }

    @Test
    fun `a locked workspace asks to be unlocked once, until the user leaves it, and never while a prompt shows`() {
        assertTrue(WorkspaceLock.askOnce("bank"))
        assertFalse(WorkspaceLock.askOnce("bank"))

        WorkspaceLock.left("bank")
        WorkspaceLock.prompting = true
        assertFalse(WorkspaceLock.askOnce("bank"))

        WorkspaceLock.prompting = false
        assertTrue(WorkspaceLock.askOnce("bank"))
    }

    @Test
    fun `whether a workspace is locked and saves history is kept with it`() {
        val saved = state.copy(workspaces = listOf(workspace("home", savesHistory = false), workspace("bank", locked = true)))

        val read = WorkspaceSerializer.read(WorkspaceSerializer.write(saved), defaultName = "Home", now = 1L)

        assertEquals(listOf(false, true), read.workspaces.map { it.locked })
        assertEquals(listOf(false, true), read.workspaces.map { it.savesHistory })
    }

    @Test
    fun `workspaces saved before the lock existed are unlocked and save history`() {
        val read = WorkspaceSerializer.read(
            """{"version":2,"workspaces":[{"id":"home","name":"Home"}],"pins":[]}""",
            defaultName = "Home",
            now = 1L,
        )

        assertFalse(read.workspaces.single().locked)
        assertTrue(read.workspaces.single().savesHistory)
    }

    @Test
    fun `an unknown lock time is the default one`() {
        assertEquals(WorkspaceLockTime.LEAVE, WorkspaceLockTime.fromKey(null))
        assertEquals(WorkspaceLockTime.LEAVE, WorkspaceLockTime.fromKey("someday"))
        assertEquals(WorkspaceLockTime.ONE_MINUTE, WorkspaceLockTime.fromKey("1m"))
    }
}
