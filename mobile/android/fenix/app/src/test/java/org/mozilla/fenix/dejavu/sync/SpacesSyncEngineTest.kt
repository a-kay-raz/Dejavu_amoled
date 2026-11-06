/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.io.File
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.state.ContainerState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.sync.ZenRecords.CONTAINER
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPACE_PERSONAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPACE_WORK
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_ESSENTIAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_ESSENTIAL_CONTAINER
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_PINNED
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_RENAMED
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpacesSyncEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val auth = SyncAuth(
        kid = "1727000000-abc",
        accessToken = "token",
        syncKey = SyncCryptoTest.syncKey(3),
        tokenServerUrl = "https://token.example",
    )
    private lateinit var server: FakeSyncServer
    private lateinit var local: FakeLocalSpaces
    private lateinit var engine: SpacesSyncEngine

    @Before
    fun setUp() {
        server = FakeSyncServer(auth.syncKey)
        local = FakeLocalSpaces()
        engine = newEngine()
    }

    private fun newEngine(clock: SyncClock = SyncClock()) =
        SpacesSyncEngine(server, SpacesSyncStore(File(folder.root, "spaces.json")), local, clock)

    private fun renameHere(id: String, name: String, at: Long) {
        local.state = local.state.copy(
            workspaces = local.state.workspaces.map { if (it.id == id) it.copy(name = name, updatedAt = at) else it },
        )
    }

    @Test
    fun `pulling Zen's spaces sends nothing back`() = runTest {
        server.upload(ZenRecords.desktop())

        val result = engine.sync(auth)

        assertEquals(SpacesSyncResult.Synced(received = 16, sent = 0, pending = 0, unpinnedTabs = true), result)
        assertEquals(emptyList<List<String>>(), server.uploads)
        assertEquals(listOf(SPACE_PERSONAL, SPACE_WORK), local.state.workspaces.map { it.id })
        assertEquals(setOf(CONTAINER, "builtin-2"), local.containers.map { it.contextId }.toSet())
        assertEquals(FakeSyncServer.ZEN_SYNC_ID, server.meta().getJSONObject("engines").getJSONObject("spaces").getString("syncID"))
    }

    @Test
    fun `a sync without changes on either side only checks the server`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        server.requests.clear()

        assertEquals(SpacesSyncResult.Synced(received = 0, sent = 0, pending = 0, unpinnedTabs = true), engine.sync(auth))

        assertEquals(listOf("GET info/collections"), server.requests)
    }

    @Test
    fun `meta global is read again once it changed on the server`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        server.setMeta(server.meta().getJSONObject("engines"))
        server.requests.clear()

        engine.sync(auth)

        assertEquals(
            listOf("GET info/collections", "GET storage/meta/global", "GET storage/crypto/keys"),
            server.requests,
        )
    }

    @Test
    fun `local changes upload only the records that changed`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        local.state = local.state.copy(
            workspaces = local.state.workspaces.map { if (it.id == SPACE_WORK) it.copy(name = "Office") else it },
        )
        assertTrue(engine.hasLocalChanges())

        engine.sync(auth)

        assertEquals(listOf(listOf(SPACE_WORK)), server.uploads)
        assertEquals("Office", server.record(SPACE_WORK)!!.data!!.string("name"))
        assertEquals(false, engine.hasLocalChanges())
    }

    @Test
    fun `changes from Zen arrive without being sent back`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        server.upload(listOf(ZenRecords.tab(TAB_PINNED, "https://example.com/moved", SPACE_PERSONAL)))

        val result = engine.sync(auth)

        assertEquals(SpacesSyncResult.Synced(received = 1, sent = 0, pending = 0, unpinnedTabs = true), result)
        assertEquals("https://example.com/moved", local.state.pins.single { it.id == TAB_PINNED }.url)
        assertEquals(emptyList<List<String>>(), server.uploads)
    }

    @Test
    fun `a change made here and one made in Zen are both kept, and the newer of two renames wins`() = runTest {
        val device = 1_000_000L
        engine = newEngine(SyncClock { device })
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        val work = { name: String ->
            ZenRecords.space(SPACE_WORK, name, listOf(TAB_RENAMED), icon = ZenRecords.STAR_ICON, containerGuid = "builtin-2")
        }
        server.upload(listOf(work("Zen name")))
        renameHere(SPACE_WORK, "Phone name", at = device + 60_000)

        engine.sync(auth)

        val renamed = local.state.workspaces.single { it.id == SPACE_WORK }
        assertEquals("Phone name" to ZenRecords.STAR_ICON, renamed.name to renamed.icon)
        val uploaded = server.record(SPACE_WORK)!!.data!!
        assertEquals("Phone name" to ZenRecords.STAR_ICON, uploaded.string("name") to uploaded.string("icon"))

        server.upload(listOf(work("Zen again")))
        renameHere(SPACE_WORK, "Older phone name", at = device - 60_000)
        engine.sync(auth)

        assertEquals("Zen again", local.state.workspaces.single { it.id == SPACE_WORK }.name)
        assertEquals("Zen again", server.record(SPACE_WORK)!!.data!!.string("name"))
    }

    @Test
    fun `spaces sync is only turned on for an account when asked`() = runTest {
        server.setMeta(JSONObject(), declined = listOf("spaces"))

        assertEquals(SpacesSyncResult.NotTurnedOn, engine.sync(auth))
        assertEquals(0, server.meta().getJSONObject("engines").length())

        engine.sync(auth, turnOn = true)

        assertEquals(0, server.meta().getJSONArray("declined").length())
        val entry = server.meta().getJSONObject("engines").getJSONObject("spaces")
        assertEquals(3, entry.getInt("version"))
        assertEquals(12, entry.getString("syncID").length)
        val home = local.state.workspaces.single().id
        assertEquals(setOf(home, LAYOUT_RECORD_ID), server.records().keys)
    }

    @Test
    fun `a newer or older version of spaces sync stops syncing`() = runTest {
        server.upload(ZenRecords.desktop())
        server.setMeta(JSONObject().put("spaces", JSONObject().put("version", 4).put("syncID", "newerSyncId1")))
        assertEquals(SpacesSyncResult.NeedsUpdate(4), engine.sync(auth))

        server.setMeta(JSONObject().put("spaces", JSONObject().put("version", 2).put("syncID", "olderSyncId1")))
        assertEquals(SpacesSyncResult.NotTurnedOn, engine.sync(auth))

        assertEquals(ZenRecords.freshDejavu(), local.state)
        assertEquals(emptyList<List<String>>(), server.uploads)
    }

    @Test
    fun `a change on the server during the sync is downloaded before uploading again`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        local.state = local.state.copy(
            workspaces = local.state.workspaces.map { if (it.id == SPACE_WORK) it.copy(name = "Office") else it },
        )
        server.conflictOnNextUpload = true

        assertTrue(runCatching { engine.sync(auth) }.exceptionOrNull() is SyncConflictException)
        engine.sync(auth)

        assertEquals("Office", server.record(SPACE_WORK)!!.data!!.string("name"))
    }

    @Test
    fun `local data that looks reset does not delete what other devices have`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        local.state = ZenRecords.freshDejavu()

        engine.sync(auth)

        assertEquals(emptyList<List<String>>(), server.uploads)
        assertEquals(listOf(SPACE_PERSONAL, SPACE_WORK), local.state.workspaces.map { it.id })
        assertEquals(ZenRecords.desktop().size, server.records().size)
    }

    @Test
    fun `records that need a missing space wait for it`() = runTest {
        val tab = ZenRecords.tab("waiting", "https://waiting.example/", "{later-space}")
        server.upload(listOf(tab))

        val first = engine.sync(auth) as SpacesSyncResult.Synced
        assertEquals(1, first.received)
        assertEquals(1, first.pending)
        assertTrue(local.state.pins.none { it.id == "waiting" })

        server.upload(listOf(ZenRecords.space("{later-space}", "Later", listOf("waiting"))))
        engine.sync(auth)

        assertEquals("{later-space}", local.state.pins.single { it.id == "waiting" }.workspaceId)
        assertTrue(server.uploads.none { "waiting" in it })
    }

    @Test
    fun `the sync state survives a restart`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        server.requests.clear()

        engine = newEngine()
        engine.sync(auth)

        assertTrue(server.requests.none { it.startsWith("GET storage/spaces") || it.startsWith("POST") })
    }

    /** A Zen profile with container-specific essentials: one essential for the work space, one for the others. */
    private fun essentialsPerSpace() = listOf(
        ZenRecords.space(SPACE_PERSONAL, "Personal", children = emptyList()),
        ZenRecords.space(SPACE_WORK, "Work", children = emptyList(), containerGuid = "builtin-2"),
        ZenRecords.tab(TAB_ESSENTIAL, "https://mail.example/", null, essential = true),
        ZenRecords.tab(
            TAB_ESSENTIAL_CONTAINER,
            "https://chat.example/",
            null,
            essential = true,
            containerGuid = "builtin-2",
        ),
        ZenRecords.layout(
            spaces = listOf(SPACE_PERSONAL, SPACE_WORK),
            essentials = mapOf("default" to listOf(TAB_ESSENTIAL), "builtin-2" to listOf(TAB_ESSENTIAL_CONTAINER)),
        ),
    )

    @Test
    fun `essentials Zen keeps per container turn essentials per container on once`() = runTest {
        server.upload(essentialsPerSpace())

        engine.sync(auth)
        server.upload(listOf(ZenRecords.tab(TAB_PINNED, "https://example.com/", SPACE_WORK)))
        engine.sync(auth)

        assertEquals(listOf(true), local.essentialsPerContainer)
    }

    @Test
    fun `an essential in a container no space opens tabs in keeps essentials shared`() = runTest {
        server.upload(ZenRecords.desktop())

        engine.sync(auth)

        assertEquals(listOf(false), local.essentialsPerContainer)
    }

    @Test
    fun `essentials are only checked until they show it, and again for the next account`() = runTest {
        val (later, first) = essentialsPerSpace().partition { it.id == SPACE_WORK || it.id == TAB_ESSENTIAL_CONTAINER }
        server.upload(first)
        engine.sync(auth)
        assertEquals(emptyList<Boolean>(), local.essentialsPerContainer)

        server.upload(later)
        engine.sync(auth)
        engine = newEngine()
        engine.sync(auth)
        assertEquals(listOf(true), local.essentialsPerContainer)

        engine.reset()
        engine.sync(auth)
        assertEquals(listOf(true, true), local.essentialsPerContainer)
    }
}

/** Dejavu's data in memory. */
internal class FakeLocalSpaces(
    var state: WorkspaceState = ZenRecords.freshDejavu(),
    var containers: List<ContainerRecord> = emptyList(),
) : SpacesLocalData {
    override val defaultWorkspaceName = "Home"

    /** What syncs found of essentials per container, in order. */
    val essentialsPerContainer = mutableListOf<Boolean>()

    override suspend fun read() = LocalSpaces(state, containers)

    override fun useEssentialsPerContainer(enabled: Boolean) {
        essentialsPerContainer += enabled
    }

    override fun <T> update(transform: (WorkspaceState) -> Pair<WorkspaceState, T>): T {
        val (updated, result) = transform(state)
        state = updated
        return result
    }

    override suspend fun saveContainer(
        contextId: String,
        name: String,
        color: ContainerColor,
        icon: ContainerState.Icon,
    ) {
        containers = if (containers.any { it.contextId == contextId }) {
            containers.map { if (it.contextId == contextId) it.copy(name = name, color = color, icon = icon) else it }
        } else {
            containers + ContainerRecord(contextId, name, color, icon, createdAt = 1L, updatedAt = 1L)
        }
    }

    override suspend fun removeContainer(contextId: String) {
        containers = containers.filterNot { it.contextId == contextId }
    }

    override fun builtinName(container: BuiltinContainer) = container.name.lowercase().replaceFirstChar { it.uppercase() }
}
