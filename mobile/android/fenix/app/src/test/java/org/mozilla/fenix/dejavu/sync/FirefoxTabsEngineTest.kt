/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.LAPTOP
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.LAPTOP_DEVICE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.MAIL
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.PHONE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.ZEN
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.ZEN_DEVICE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.client
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tab
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tabsRecord
import org.mozilla.fenix.dejavu.workspaces.SyncedDevice
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.robolectric.RobolectricTestRunner

private const val OPENED = "https://opened.example/"

@RunWith(RobolectricTestRunner::class)
class FirefoxTabsEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val auth = SyncAuth(
        kid = "1727000000-abc",
        accessToken = "token",
        syncKey = SyncCryptoTest.syncKey(3),
        tokenServerUrl = "https://token.example",
    )
    private val laptopWorkspace = FirefoxMirror.workspaceIdOf(LAPTOP)
    private lateinit var server: FakeSyncServer
    private lateinit var local: FakeFirefoxLocal
    private lateinit var engine: FirefoxTabsEngine

    @Before
    fun setUp() {
        server = FakeSyncServer(auth.syncKey)
        local = FakeFirefoxLocal()
        engine = newEngine()
        server.put("clients", LAPTOP, client("Laptop", fxaDeviceId = LAPTOP_DEVICE))
        server.put("clients", ZEN, client("Zen desktop", application = "Zen", fxaDeviceId = ZEN_DEVICE))
        server.put("clients", PHONE, client("Pixel", application = null, type = "mobile", fxaDeviceId = PHONE))
        server.put("tabs", LAPTOP, FirefoxFixtures.laptopTabs())
        server.put("tabs", ZEN, tabsRecord("Zen desktop", listOf(tab("https://zen.example/", pinned = true))))
        server.put("tabs", PHONE, tabsRecord("Pixel", listOf(tab("https://phone.example/", window = "")), windows = emptyList()))
    }

    private fun newEngine() = FirefoxTabsEngine(server, FirefoxSyncStore(File(folder.root, "firefox.json")), local)

    /** Opens tab [id] on [url] here, in the workspace of the laptop. */
    private fun openHere(id: String, url: String, createdAt: Long = System.currentTimeMillis()) {
        local.tabs = local.tabs!! + LocalTab(id, url, "Opened here", null, awake = true, createdAt = createdAt)
        local.state = local.state.copy(assignments = local.state.assignments + (id to laptopWorkspace))
    }

    /** The laptop's tabs, with a tab on [url] after the others. */
    private fun laptopTabsWith(url: String) =
        FirefoxFixtures.laptopTabs().also { it.getJSONArray("tabs").put(tab(url, index = 4)) }

    @Test
    fun `only Firefox on computers is shown, and nothing is uploaded`() = runTest {
        val result = engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(FirefoxSyncResult.Synced(listOf("Laptop")), result)
        assertEquals(listOf("Home", "Laptop"), local.state.workspaces.map { it.name })
        assertEquals(listOf(MAIL, null), local.state.pins.filter { it.parentId == null }.map { it.url })
        assertEquals(listOf(FirefoxFixtures.NEWS), local.tabs!!.map { it.url })
        assertTrue(server.requests.none { it.startsWith("POST") || it.startsWith("PUT") })
    }

    @Test
    fun `nothing is downloaded again while Firefox's tabs stay the same`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        server.requests.clear()

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(listOf("GET info/collections"), server.requests)
    }

    @Test
    fun `changes in Firefox arrive`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        server.put("tabs", LAPTOP, FirefoxFixtures.laptopTabs(mail = "https://mail.example/inbox"))

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertTrue(local.state.pins.any { it.url == "https://mail.example/inbox" })
        assertTrue(local.state.pins.none { it.url == MAIL })
    }

    @Test
    fun `turning off open tabs for the account stops following Firefox`() = runTest {
        server.setMeta(JSONObject(), declined = listOf("tabs"))

        assertEquals(FirefoxSyncResult.TabsOff, engine.sync(auth, connected = null, localDeviceId = PHONE))
        assertEquals(listOf("Home"), local.state.workspaces.map { it.name })
    }

    @Test
    fun `a tab closed here asks its computer to close it`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        local.state = local.state.copy(pins = local.state.pins.filterNot { it.url == MAIL })
        assertTrue(engine.hasLocalChanges())

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(listOf(LAPTOP_DEVICE to listOf(MAIL)), local.closeCommands)
        assertFalse(engine.hasLocalChanges())
        assertTrue(local.state.pins.none { it.url == MAIL })
    }

    @Test
    fun `a tab closed right after it was shown asks its computer to close it`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        local.state = local.state.copy(pins = local.state.pins.filterNot { it.url == MAIL })
        assertTrue(engine.hasLocalChanges())

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(listOf(LAPTOP_DEVICE to listOf(MAIL)), local.closeCommands)
        assertTrue(local.state.pins.none { it.url == MAIL })
    }

    @Test
    fun `tabs lost before they were kept here come back instead of being closed in Firefox`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        // The app stopped before the tabs were kept.
        engine = newEngine()
        local.state = local.state.copy(pins = local.state.pins.filterNot { it.url == MAIL })
        local.tabs = emptyList()
        assertFalse(engine.hasLocalChanges())

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertTrue(local.closeCommands.isEmpty())
        assertEquals(1, local.state.pins.count { it.url == MAIL })
        assertEquals(listOf(FirefoxFixtures.NEWS), local.tabs!!.map { it.url })
    }

    @Test
    fun `tabs removed all at once come back from their computer instead of being closed there`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        local.forgotten = local.tabs!!.map { it.id }.toSet()
        local.tabs = emptyList()
        assertFalse(engine.hasLocalChanges())

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertTrue(local.closeCommands.isEmpty())
        assertEquals(listOf(FirefoxFixtures.NEWS), local.tabs!!.map { it.url })
    }

    @Test
    fun `a sent tab removed here all at once stays open on its computer, and comes back from there`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        openHere("opened-here", OPENED)
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        local.tabs = local.tabs!!.filterNot { it.id == "opened-here" }
        local.forgotten = setOf("opened-here")
        assertFalse(engine.hasLocalChanges())

        engine.sync(auth, connected = null, localDeviceId = PHONE)
        server.put("tabs", LAPTOP, laptopTabsWith(OPENED))
        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertTrue(local.closeCommands.isEmpty())
        assertEquals(1, local.tabs!!.count { it.url == OPENED })
    }

    @Test
    fun `a tab a page opened in the workspace of a computer stays here, as one of this device's tabs`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        openHere("pop-up", OPENED)
        local.state = local.state.copy(openedByPages = setOf("pop-up"))
        assertFalse(engine.hasLocalChanges())

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertTrue(local.sentTabs.isEmpty())
        assertFalse(engine.computerTabs().has(local.state, "pop-up", System.currentTimeMillis()))
    }

    @Test
    fun `a tab opened here in the workspace of a computer opens there and becomes its tab once it shows it`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        openHere("opened-here", OPENED)
        assertTrue(engine.hasLocalChanges())

        engine.sync(auth, connected = null, localDeviceId = PHONE)
        assertEquals(listOf(LAPTOP_DEVICE to OPENED), local.sentTabs)
        server.put("tabs", LAPTOP, laptopTabsWith(OPENED))
        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(listOf("opened-here"), local.tabs!!.filter { it.url == OPENED }.map { it.id })
        assertEquals(1, local.sentTabs.size)
        local.tabs = local.tabs!!.filterNot { it.id == "opened-here" }
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        assertEquals(listOf(LAPTOP_DEVICE to listOf(OPENED)), local.closeCommands)
    }

    @Test
    fun `tabs opened before Dejavu sent tabs to computers stay here`() = runTest {
        openHere("opened-before", OPENED, createdAt = 1L)

        engine.sync(auth, connected = null, localDeviceId = PHONE)
        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertTrue(local.sentTabs.isEmpty())
        assertFalse(engine.hasLocalChanges())
    }

    @Test
    fun `a tab closed here before its computer showed it is closed there too`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        openHere("opened-here", OPENED)
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        local.tabs = local.tabs!!.filterNot { it.id == "opened-here" }
        assertTrue(engine.hasLocalChanges())

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(listOf(LAPTOP_DEVICE to listOf(OPENED)), local.closeCommands)
        assertFalse(engine.hasLocalChanges())
    }

    @Test
    fun `a tab that could not be sent is sent at the next sync`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        openHere("opened-here", OPENED)
        local.canSend = false
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        assertTrue(local.sentTabs.isEmpty())

        local.canSend = true
        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(listOf(LAPTOP_DEVICE to OPENED), local.sentTabs)
    }

    @Test
    fun `a sent tab is found again after its page redirected`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        openHere("opened-here", "http://opened.example/")
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        local.tabs = local.tabs!!.map { if (it.id == "opened-here") it.copy(url = OPENED) else it }
        server.put("tabs", LAPTOP, laptopTabsWith(OPENED))

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(listOf("opened-here"), local.tabs!!.filter { it.url == OPENED }.map { it.id })
        assertEquals(listOf(LAPTOP_DEVICE to "http://opened.example/"), local.sentTabs)
    }

    @Test
    fun `the computers' tabs and the ones opened in their workspaces since then are not this device's`() = runTest {
        openHere("opened-before", "https://before.example/", createdAt = 1L)
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        openHere("opened-after", OPENED)
        val tabs = engine.computerTabs()
        val news = local.tabs!!.first { it.url == FirefoxFixtures.NEWS }

        assertTrue(tabs.has(local.state, news.id, news.createdAt))
        assertTrue(tabs.has(local.state, "opened-after", System.currentTimeMillis()))
        assertFalse(tabs.has(local.state, "opened-before", 1L))
    }

    @Test
    fun `a computer that left the account leaves Dejavu`() = runTest {
        engine.sync(auth, connected = setOf(LAPTOP_DEVICE, ZEN_DEVICE), localDeviceId = PHONE)
        assertTrue(local.state.workspaces.any { it.id == laptopWorkspace })

        assertEquals(FirefoxSyncResult.Synced(emptyList()), engine.sync(auth, connected = setOf(ZEN_DEVICE), localDeviceId = PHONE))

        assertEquals(listOf("Home"), local.state.workspaces.map { it.name })
        assertTrue(local.tabs!!.isEmpty())
    }

    @Test
    fun `a computer that joined the account after its devices were read is shown once they are read again`() = runTest {
        val result = engine.sync(auth, connected = setOf(ZEN_DEVICE), localDeviceId = PHONE) { setOf(LAPTOP_DEVICE, ZEN_DEVICE) }

        assertEquals(FirefoxSyncResult.Synced(listOf("Laptop")), result)
        assertTrue(local.state.workspaces.any { it.id == laptopWorkspace })
    }

    @Test
    fun `another phone gets a workspace of its own, and this phone does not`() = runTest {
        val tablet = "fxa-device-tablet"
        server.put("clients", tablet, client("Tablet", application = null, type = "tablet", fxaDeviceId = tablet))
        server.put("tabs", tablet, tabsRecord("Tablet", listOf(tab("https://tablet.example/", window = "")), windows = emptyList()))

        val result = engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(FirefoxSyncResult.Synced(listOf("Laptop", "Tablet")), result)
        assertEquals(listOf("Home", "Laptop", "Tablet"), local.state.workspaces.map { it.name })
        assertEquals(SyncedDevice.PHONE, local.state.workspaces.last().device)
        assertTrue(local.tabs!!.any { it.url == "https://tablet.example/" })
        assertTrue(local.tabs!!.none { it.url == "https://phone.example/" })
    }

    @Test
    fun `a computer that signed out of Firefox Sync leaves Dejavu`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        server.delete("tabs", LAPTOP)
        server.delete("clients", LAPTOP)

        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(listOf("Home"), local.state.workspaces.map { it.name })
    }

    @Test
    fun `stopping to follow Firefox removes its workspaces`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)

        engine.removeAll()

        assertEquals(ZenRecords.freshDejavu().workspaces, local.state.workspaces)
        assertTrue(local.state.pins.isEmpty())
        assertTrue(local.tabs!!.isEmpty())
    }

    @Test
    fun `what was shown survives a restart`() = runTest {
        engine.sync(auth, connected = null, localDeviceId = PHONE)
        val shown = local.state
        server.requests.clear()

        engine = newEngine()
        engine.sync(auth, connected = null, localDeviceId = PHONE)

        assertEquals(shown, local.state)
        assertTrue(server.requests.none { it.startsWith("GET storage/tabs") || it.startsWith("GET storage/clients") })
    }
}

/** Dejavu's workspaces and open tabs in memory. */
internal class FakeFirefoxLocal(
    var state: WorkspaceState = ZenRecords.freshDejavu(),
    var tabs: List<LocalTab>? = emptyList(),
    var normalTabs: Boolean = true,
) : FirefoxLocalData {
    override val unnamedGroup = "Tab group"

    /** The close commands sent, by device. */
    val closeCommands = mutableListOf<Pair<String, List<String>>>()

    /** Ids of the tabs removed all at once, which sync forgets. */
    var forgotten: Set<String> = emptySet()

    override suspend fun read() = LocalSpaces(state, emptyList(), normalTabs, tabs, forgotten)

    override fun <T> update(transform: (WorkspaceState) -> Pair<WorkspaceState, T>): T {
        val (updated, result) = transform(state)
        state = updated
        return result
    }

    override suspend fun runTabOps(ops: List<TabOp>) {
        val current = tabs ?: return
        val closed = ops.filterIsInstance<TabOp.Close>().map { it.id }.toSet()
        val opened = ops.filterIsInstance<TabOp.Open>().map { LocalTab(it.id, it.url, it.title, it.contextId, awake = false) }
        tabs = current.filterNot { it.id in closed } + opened
    }

    override suspend fun closeRemoteTabs(deviceId: String, urls: List<String>) {
        closeCommands += deviceId to urls
    }

    /** The tabs sent, by device. */
    val sentTabs = mutableListOf<Pair<String, String>>()

    /** Whether sending a tab works, as it does not without the network. */
    var canSend = true

    override suspend fun sendTab(deviceId: String, url: String, title: String): Boolean {
        if (canSend) sentTabs += deviceId to url
        return canSend
    }
}
