/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import org.json.JSONObject
import org.mozilla.fenix.dejavu.workspaces.SyncedDevice

/**
 * A Firefox computer found on the account.
 *
 * @property name The computer's name.
 * @property tabs How many tabs it has open, pinned and grouped ones included.
 * @property pinned How many of them are pinned.
 * @property groups How many tab groups it has.
 */
data class FoundFirefox(val name: String, val tabs: Int, val pinned: Int, val groups: Int)

/**
 * What Zen syncs on the account.
 *
 * @property devices Names of the Zen devices.
 * @property spacesSyncOn Whether Zen's sidebar sync is on for the account. Without it, Zen syncs no spaces.
 * @property spaces How many spaces it syncs.
 * @property pinned How many pinned tabs, essentials left out.
 * @property essentials How many essentials.
 * @property folders How many folders.
 */
data class FoundZen(
    val devices: List<String>,
    val spacesSyncOn: Boolean,
    val spaces: Int = 0,
    val pinned: Int = 0,
    val essentials: Int = 0,
    val folders: Int = 0,
)

/** What the account syncs from Firefox and from Zen, so that the user can choose which one Dejavu follows. */
data class FoundSyncData(val firefox: List<FoundFirefox>, val zen: FoundZen?) {
    val isEmpty: Boolean
        get() = firefox.isEmpty() && zen == null

    /** The only browser found, or `null` when both or none were. */
    val onlySource: DejavuSyncSource?
        get() = when {
            zen == null && firefox.isNotEmpty() -> DejavuSyncSource.FIREFOX
            zen != null && firefox.isEmpty() -> DejavuSyncSource.ZEN
            else -> null
        }
}

/** How looking at the account ended. */
internal sealed interface ScanResult {
    data class Found(val data: FoundSyncData) : ScanResult

    /** Firefox Sync has not set up this account's storage yet. */
    data object NotSetUp : ScanResult
}

/**
 * Looks at what the account syncs, to tell the user which browsers Dejavu can follow: Firefox computers, from the
 * "clients" and "tabs" collections every Firefox writes, and Zen, from its "spaces" collection and its devices.
 * Nothing is downloaded again while the collections stay the same.
 */
internal class SyncSourceScanner(private val http: SyncHttp, private val clock: SyncClock = SyncClock()) {
    private val tokens = SyncTokens(http, clock)
    private val setup = StorageSetupCache()
    private var cached: Pair<String, FoundSyncData>? = null

    /** Server time before which the servers asked not to sync again, in local milliseconds. */
    @Volatile
    var backoffUntil: Long = 0L
        private set

    fun reset() {
        tokens.forget()
        setup.forget()
        cached = null
    }

    /**
     * Looks at the account. [connected] are the ids of the account's other devices when they are known, [localDeviceId]
     * and [localName] are this device's id and name, and [refreshDevices] asks the account for its devices again, for
     * devices not among them.
     */
    suspend fun scan(
        auth: SyncAuth,
        connected: Set<String>?,
        localDeviceId: String? = null,
        localName: String? = null,
        refreshDevices: suspend (unknown: Set<String>) -> Set<String>? = { null },
    ): ScanResult = tokens.retryingOnce { run(auth, connected, localDeviceId, localName, refreshDevices) }

    @Suppress("ReturnCount")
    private suspend fun run(
        auth: SyncAuth,
        known: Set<String>?,
        localDeviceId: String?,
        localName: String?,
        refreshDevices: suspend (unknown: Set<String>) -> Set<String>?,
    ): ScanResult {
        val token = tokens.get(auth)
        val client = StorageClient(http, token, clock) { seconds -> backoffUntil = clock.millis() + seconds * MILLIS }
        val info = JSONObject(client.expect("GET", "info/collections").body)
        if (!info.has("meta") || !info.has("crypto")) return ScanResult.NotSetUp
        val signature = buildString {
            append(token.uid)
            listOf("meta", "crypto", CLIENTS, TABS, SPACES).forEach { append(' ').append(timestampOf(info.opt(it))) }
            known?.sorted()?.forEach { append(' ').append(it) }
        }
        cached?.takeIf { it.first == signature }?.let { return ScanResult.Found(it.second) }

        setup.check(token, info)
        val meta = client.metaGlobal(setup) ?: return ScanResult.NotSetUp
        val keys = client.collectionKeys(KeyBundle.fromSyncKey(auth.syncKey), setup) ?: return ScanResult.NotSetUp
        fun download(collection: String, stampModified: Boolean = false) =
            if (info.has(collection)) client.downloadAll(collection, keys.of(collection), stampModified) else emptyMap()

        val clients = download(CLIENTS, stampModified = true)
            .map { (id, cleartext) -> ClientRecord.fromCleartext(id, cleartext) }
        val connected = devicesFor(clients, known, localDeviceId, refreshDevices)
        val tabs = if (TABS in meta.declined) emptyMap() else download(TABS)
        val records = tabs.mapValues { (id, cleartext) -> TabsRecord.fromCleartext(id, cleartext) }
        // Phones with Firefox or Dejavu show too once Dejavu follows Firefox, but only computers tell Firefox from Zen.
        val computers = firefoxComputers(
            clients,
            records,
            connected,
            localDeviceId,
            localName,
            now = clock.serverSeconds().toDouble(),
        ).filter { it.kind == SyncedDevice.DESKTOP }
        val spaces = download(SPACES).mapNotNull { (id, cleartext) -> SpacesRecord.fromCleartext(id, cleartext) }
        val found = FoundSyncData(
            firefox = computers.map { computer ->
                FoundFirefox(
                    name = computer.name,
                    tabs = computer.record.tabs.size,
                    pinned = computer.record.tabs.count { it.pinned },
                    groups = computer.record.groups.size,
                )
            },
            zen = zenOf(
                devices = clients.filter { it.isZen && it.isConnected(connected) }.map { it.name },
                spacesSyncOn = meta.has(SPACES),
                records = spaces,
            ),
        )
        cached = signature to found
        return ScanResult.Found(found)
    }

    /** What Zen syncs, from its [devices] and the [records] of the spaces collection; `null` when there is none. */
    private fun zenOf(devices: List<String>, spacesSyncOn: Boolean, records: List<SpacesRecord>): FoundZen? {
        val live = records.filterNot { it.deleted }
        if (devices.isEmpty() && live.isEmpty()) return null
        val tabs = live.filter { it.kind == RecordKind.TAB }
        return FoundZen(
            devices = devices.sortedBy { it.lowercase() },
            spacesSyncOn = spacesSyncOn && live.isNotEmpty(),
            spaces = live.count { it.kind == RecordKind.SPACE },
            pinned = tabs.count { it.data?.opt("pinned") != false && it.data?.opt("essential") != true },
            essentials = tabs.count { it.data?.opt("essential") == true },
            folders = live.count { it.kind == RecordKind.FOLDER },
        )
    }

    private companion object {
        const val CLIENTS = "clients"
        const val TABS = "tabs"
        const val SPACES = SpacesSyncEngine.COLLECTION
        const val MILLIS = 1000L
    }
}
