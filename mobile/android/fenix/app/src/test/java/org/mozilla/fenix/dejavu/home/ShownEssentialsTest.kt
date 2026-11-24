/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem

class ShownEssentialsTest {
    private val essentials = listOf("a", "b", "c", "d").map { essential(it) }

    @Test
    fun `the essentials show unless they are hidden, all of them or one by one`() {
        assertEquals(essentials, shownEssentials(essentials, shown = true, hidden = emptySet()))
        val someHidden = shownEssentials(essentials, shown = true, hidden = setOf("b", "d"))
        assertEquals(listOf("a", "c"), someHidden.map { it.id })
        assertEquals(emptyList<PinnedItem>(), shownEssentials(essentials, shown = false, hidden = emptySet()))
    }

    @Test
    fun `with none hidden, an essential goes where it was dropped`() {
        val all = listOf("a", "b", "c", "d")
        assertEquals(0, groupIndexOfShown(all, all, pinId = "c", index = 0))
        assertEquals(3, groupIndexOfShown(all, all, pinId = "a", index = 3))
    }

    @Test
    fun `dropped among the shown ones, an essential goes in front of the one it was dropped in front of`() {
        // b is hidden: a c d show. Dropping d in front of c puts it in front of c among all of them, after b.
        val all = listOf("a", "b", "c", "d")
        val shown = listOf("a", "c", "d")
        assertEquals(2, groupIndexOfShown(all, shown, pinId = "d", index = 1))
        // Dropping a last puts it after d, the last shown one.
        assertEquals(3, groupIndexOfShown(all, shown, pinId = "a", index = 2))
        // Dropping d first puts it in front of a.
        assertEquals(0, groupIndexOfShown(all, shown, pinId = "d", index = 0))
    }

    @Test
    fun `the only essential shown keeps its place`() {
        assertEquals(2, groupIndexOfShown(listOf("a", "b", "c", "d"), listOf("c"), pinId = "c", index = 0))
    }

    private fun essential(id: String) = PinnedItem(
        id = id,
        workspaceId = null,
        parentId = null,
        kind = PinKind.TAB,
        title = id,
        url = "https://$id.example/",
        essential = true,
        createdAt = 0,
        updatedAt = 0,
    )
}
