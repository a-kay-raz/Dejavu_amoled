/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

/** Dejavu's data as the Firefox engine reads and changes it. */
internal interface FirefoxLocalData {
    /** Name of the folder of a tab group without a name. */
    val unnamedGroup: String

    /** Current workspaces and open tabs. */
    suspend fun read(): LocalSpaces

    /** Applies [transform] to the workspaces as one change and returns its second value. It may run more than once. */
    fun <T> update(transform: (WorkspaceState) -> Pair<WorkspaceState, T>): T

    /** Opens, points elsewhere or closes open tabs. */
    suspend fun runTabOps(ops: List<TabOp>)

    /** Asks the account's device [deviceId] to close its tabs showing [urls]. */
    suspend fun closeRemoteTabs(deviceId: String, urls: List<String>)

    /** Sends the account's device [deviceId] a tab showing [url], which it opens. Returns whether it was sent. */
    suspend fun sendTab(deviceId: String, url: String, title: String): Boolean
}

/** How a sync with Firefox ended when it did not throw. */
internal sealed interface FirefoxSyncResult {
    /** Synced; [computers] are the names of the Firefox computers shown. */
    data class Synced(val computers: List<String>) : FirefoxSyncResult

    /** Firefox Sync has not set up this account's storage yet; Firefox's own sync does it. */
    data object NotSetUp : FirefoxSyncResult

    /** Open tabs are not synced for the account: the user turned them off. */
    data object TabsOff : FirefoxSyncResult
}

/**
 * Shows the open tabs of Firefox on the account's computers in Dejavu, as [FirefoxMirror] lays them out, from the
 * "tabs" collection where every Firefox keeps the tabs it has open, and the "clients" collection, which tells which
 * device is a Firefox on a computer. Dejavu never writes either: tabs opened here in the workspace of a computer reach
 * it as sent tabs, and tabs closed here as close commands.
 */
internal class FirefoxTabsEngine(
    private val http: SyncHttp,
    private val store: FirefoxSyncStore,
    private val local: FirefoxLocalData,
    private val clock: SyncClock = SyncClock(),
    private val log: (String) -> Unit = {},
) {
    private val tokens = SyncTokens(http, clock)
    private val setup = StorageSetupCache()

    /**
     * Ids of the tabs of computers shown during this run of the app. One of them that is gone was closed by the user,
     * even before a sync confirmed it, since the app did not stop in between.
     */
    private val shownHere: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Server time before which the servers asked not to sync again, in local milliseconds. */
    @Volatile
    var backoffUntil: Long = 0L
        private set

    /** Time of the last complete sync in milliseconds, or 0. */
    val lastSynced: Long
        get() = store.load().lastSynced

    /** Which tabs here are the computers' tabs. */
    fun computerTabs(): ComputerTabs = store.load().computerTabs()

    /** Forgets everything about the server and the computers shown, for a sign out. */
    fun reset() {
        tokens.forget()
        setup.forget()
        store.clear()
        shownHere.clear()
    }

    /**
     * Whether tabs of Firefox computers were closed or removed here since the last sync, or tabs were opened in their
     * workspaces, without the network.
     */
    suspend fun hasLocalChanges(): Boolean {
        val data = store.load()
        if (data.devices.isEmpty()) return false
        val current = local.read()
        val mirror = mirror(current)
        if (mirror.findClosedHere(current.state, data.devices, shownHere).marked > 0) return true
        if (data.sendingSince == 0L) return false
        val opened = mirror.findOpenedHere(current.state, data.devices, data.sent, data.sendingSince)
        return opened.toSend.isNotEmpty() || opened.gone.isNotEmpty()
    }

    /**
     * Syncs. [connected] are the ids of the account's other devices when they are known, so that devices that left the
     * account are not shown anymore, and [localDeviceId] is this device's, which is never shown, like old records with
     * its name [localName]. [refreshDevices] asks the account for its devices again, for devices not among them.
     */
    suspend fun sync(
        auth: SyncAuth,
        connected: Set<String>?,
        localDeviceId: String? = null,
        localName: String? = null,
        refreshDevices: suspend (unknown: Set<String>) -> Set<String>? = { null },
    ): FirefoxSyncResult {
        val data = store.load()
        closeTabsClosedHere(data)
        sendTabsOpenedHere(data)
        return tokens.retryingOnce { run(auth, connected, localDeviceId, localName, refreshDevices, data) }
    }

    /**
     * Removes the workspaces, pinned tabs and open tabs of the Firefox computers from Dejavu, and forgets them, for when
     * Dejavu stops following Firefox. Firefox keeps its tabs.
     */
    suspend fun removeAll() {
        val data = store.load()
        if (data.devices.isNotEmpty()) {
            val current = local.read()
            val mirror = mirror(current)
            val ops = local.update { state -> mirror.removeAll(state, data.devices).let { it.state to it.tabOps } }
            local.runTabOps(ops)
            log("Removed ${data.devices.size} Firefox computers")
        }
        store.clear()
        shownHere.clear()
    }

    /** Asks Firefox to close the tabs closed or removed here, before going to the network, so it also works offline. */
    private suspend fun closeTabsClosedHere(data: FirefoxSyncData) {
        if (data.devices.isEmpty()) return
        val current = local.read()
        val closed = mirror(current).findClosedHere(current.state, data.devices, shownHere)
        if (!closed.changed) return
        data.devices = closed.devices
        store.save(data)
        closed.commands.forEach { (deviceId, urls) ->
            log("Asking a Firefox computer to close ${urls.size} tabs closed here")
            runCatching { local.closeRemoteTabs(deviceId, urls) }.onFailure { log("Could not ask to close tabs: $it") }
        }
    }

    /**
     * Sends the tabs opened here in the workspace of a computer to it, which opens them too, and asks it to close the
     * tabs sent before that were closed here before it showed them. Before going to the network, like closing.
     */
    private suspend fun sendTabsOpenedHere(data: FirefoxSyncData) {
        if (data.devices.isEmpty() || data.sendingSince == 0L) return
        val current = local.read()
        val opened = mirror(current).findOpenedHere(current.state, data.devices, data.sent, data.sendingSince)
        if (opened.toSend.isEmpty() && opened.gone.isEmpty() && opened.forgotten.isEmpty()) return
        val deviceIds = data.devices.associate { it.clientId to it.fxaDeviceId }
        opened.gone.groupBy { it.clientId }.forEach { (clientId, tabs) ->
            val deviceId = deviceIds[clientId] ?: return@forEach
            log("Asking a Firefox computer to close ${tabs.size} tabs closed here before it showed them")
            runCatching { local.closeRemoteTabs(deviceId, tabs.map { it.url }) }
                .onFailure { log("Could not ask to close tabs: $it") }
        }
        val sent = (data.sent - (opened.gone + opened.forgotten).toSet()).toMutableList()
        var count = 0
        for ((tab, title) in opened.toSend) {
            val deviceId = deviceIds[tab.clientId] ?: continue
            if (runCatching { local.sendTab(deviceId, tab.url, title) }.getOrDefault(false)) {
                sent += tab
                count++
            } else {
                log("Could not send a tab opened here to its Firefox computer")
            }
        }
        if (count > 0) log("Sent $count tabs opened here to their Firefox computer")
        data.sent = sent
        store.save(data)
    }

    @Suppress("ReturnCount")
    private suspend fun run(
        auth: SyncAuth,
        connected: Set<String>?,
        localDeviceId: String?,
        localName: String?,
        refreshDevices: suspend (unknown: Set<String>) -> Set<String>?,
        data: FirefoxSyncData,
    ): FirefoxSyncResult {
        val token = tokens.get(auth)
        val client = StorageClient(http, token, clock) { seconds -> backoffUntil = clock.millis() + seconds * MILLIS }
        if (data.account != token.uid) {
            log("New storage user, downloading the records again")
            data.forgetRecords()
            data.account = token.uid
        }
        val info = JSONObject(client.expect("GET", "info/collections").body)
        if (!info.has("meta") || !info.has("crypto")) return FirefoxSyncResult.NotSetUp
        setup.check(token, info)
        val meta = client.metaGlobal(setup) ?: return FirefoxSyncResult.NotSetUp
        if (TABS in meta.declined) return FirefoxSyncResult.TabsOff
        val keys = client.collectionKeys(KeyBundle.fromSyncKey(auth.syncKey), setup)
            ?: return FirefoxSyncResult.NotSetUp
        if (keys.modified != data.keysModified) {
            data.forgetRecords()
            data.keysModified = keys.modified
        }

        val tabsModified = timestampOf(info.opt(TABS))
        if (tabsModified != data.tabsModified) {
            data.tabs = if (tabsModified == null) emptyMap() else client.downloadAll(TABS, keys.of(TABS))
            data.tabsModified = tabsModified
            log("Downloaded the open tabs of ${data.tabs.size} devices")
        }
        val clientsModified = timestampOf(info.opt(CLIENTS))
        // Records kept by an earlier version lack the time they were written, which tells an old device record apart.
        if (clientsModified != data.clientsModified || data.clients.values.any { !it.has(SERVER_MODIFIED) }) {
            data.clients = if (clientsModified == null) {
                emptyMap()
            } else {
                client.downloadAll(CLIENTS, keys.of(CLIENTS), stampModified = true)
            }
            data.clientsModified = clientsModified
        }

        val clients = data.clients.map { (id, cleartext) -> ClientRecord.fromCleartext(id, cleartext) }
        val computers = firefoxComputers(
            clients = clients,
            tabs = data.tabs.mapValues { (id, cleartext) -> TabsRecord.fromCleartext(id, cleartext) },
            connected = devicesFor(clients, connected, localDeviceId, refreshDevices),
            localDeviceId = localDeviceId,
            localName = localName,
            now = clock.serverSeconds().toDouble(),
        )
        val current = local.read()
        val mirror = mirror(current)
        val previous = data.devices
        val (ops, devices) = local.update { state ->
            val matched = computers.map { computer ->
                mirror.match(previous.firstOrNull { it.clientId == computer.client.id }, computer, state, data.sent)
            }
            val result = mirror.apply(state, previous, matched)
            result.state to (result.tabOps to matched)
        }
        local.runTabOps(ops)
        val after = local.read()
        val shown = mirror(after).shownIds(after.state)
        devices.forEach { device -> device.tabs.forEach { if (it.id in shown) shownHere += it.id } }
        // Saved once the tabs are open, so that a sync cut short opens them again rather than taking them as closed.
        data.devices = devices
        // Tabs sent to a computer that shows them now are its tabs; the ones of a computer that is gone stay here.
        data.sent = data.sent.filter { sent ->
            devices.any { device -> device.clientId == sent.clientId && device.tabs.none { it.id == sent.id } }
        }
        if (data.sendingSince == 0L) data.sendingSince = clock.millis()
        data.lastSynced = clock.millis()
        store.save(data)
        log("Showing ${computers.size} Firefox computers, ${ops.size} changes to open tabs")
        return FirefoxSyncResult.Synced(computers.map { it.name })
    }

    // Every tab of a computer shows: Include unpinned tabs is a setting of syncing with Zen.
    private fun mirror(current: LocalSpaces) = FirefoxMirror(
        now = clock.millis(),
        normalTabs = true,
        tabs = current.tabs?.associateBy { it.id },
        unnamedGroup = local.unnamedGroup,
        forgotten = current.forgotten,
    )

    private companion object {
        const val TABS = "tabs"
        const val CLIENTS = "clients"
        const val MILLIS = 1000L
    }
}
