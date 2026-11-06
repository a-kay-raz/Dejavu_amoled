/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import mozilla.components.browser.state.state.ContainerState
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

/** Dejavu's data as the sync engine reads and changes it. */
internal interface SpacesLocalData {
    /** Name of the workspace a fresh Dejavu starts with. */
    val defaultWorkspaceName: String

    /** Current workspaces and containers, containers read from disk if needed. */
    suspend fun read(): LocalSpaces

    /** Applies [transform] to the workspaces as one change and returns its second value. It may run more than once. */
    fun <T> update(transform: (WorkspaceState) -> Pair<WorkspaceState, T>): T

    /** Creates container [contextId], or updates its name, color and icon. */
    suspend fun saveContainer(contextId: String, name: String, color: ContainerColor, icon: ContainerState.Icon)

    /**
     * Deletes container [contextId], which another device deleted: its open tabs close, as they did there, and its
     * pinned tabs stay, without the container.
     */
    suspend fun removeContainer(contextId: String)

    fun builtinName(container: BuiltinContainer): String

    /** Opens, points elsewhere or closes open tabs as applied records ask. */
    suspend fun runTabOps(ops: List<TabOp>) = Unit

    /** Turns essentials per container on or off, as synced data shows Zen does, unless the user chose it. */
    fun useEssentialsPerContainer(enabled: Boolean) = Unit
}

/** How a sync ended when it did not throw. */
internal sealed interface SpacesSyncResult {
    /**
     * Synced; counts of records received, sent and still waiting to apply, and whether the server has tabs that are
     * not pinned, which only a device that syncs them puts there.
     */
    data class Synced(
        val received: Int,
        val sent: Int,
        val pending: Int,
        val unpinnedTabs: Boolean = false,
    ) : SpacesSyncResult

    /** Firefox Sync has not set up this account's storage yet; Firefox's own sync does it. */
    data object NotSetUp : SpacesSyncResult

    /**
     * Spaces sync is not turned on for the account: no device set up the spaces collection, or one turned it off.
     * Syncing with `turnOn` turns it on, which also turns it on in Zen.
     */
    data object NotTurnedOn : SpacesSyncResult

    /** Another device uses a newer version of the spaces collection. */
    data class NeedsUpdate(val serverVersion: Int) : SpacesSyncResult
}

/**
 * Syncs Dejavu's workspaces with the "spaces" collection of Firefox Sync, which Zen's Spaces sync engine
 * (ZenSpacesSync.sys.mjs) reads and writes, so Dejavu and Zen share spaces, their looks, containers, pinned tabs,
 * essentials, folders and their order.
 *
 * Like Zen, it never tracks individual changes. It compares what Dejavu's data maps to ([SpacesProjector]) with the
 * server's copy of each record, and uploads the difference. Incoming records apply first, keeping the changes made here
 * that they would undo ([keepLocalChanges]), and a record Dejavu applied maps back to itself, so a sync never sends
 * back what it just received, only the changes it kept.
 */
internal class SpacesSyncEngine(
    private val http: SyncHttp,
    private val store: SpacesSyncStore,
    private val local: SpacesLocalData,
    private val clock: SyncClock = SyncClock(),
    private val log: (String) -> Unit = {},
) {
    @Volatile
    private var token: SyncToken? = null
    private val setup = StorageSetupCache()

    /** Server time before which the servers asked not to sync again, in local milliseconds. */
    @Volatile
    var backoffUntil: Long = 0L
        private set

    /** Time of the last complete sync in milliseconds, or 0. */
    val lastSynced: Long
        get() = store.load().lastSynced

    /** Forgets everything about the server, for a sign out. */
    fun reset() {
        token = null
        setup.forget()
        store.clear()
    }

    /** Whether local data differs from the server's copy, without touching the network. */
    suspend fun hasLocalChanges(): Boolean {
        val data = store.load()
        if (data.syncId == null) return false
        return SpacesProjector(local.read(), data.server, data.seen).project().changesAgainst(data.server).isNotEmpty()
    }

    /**
     * Syncs. When spaces sync is not turned on for the account, it only turns it on with [turnOn], since that turns it
     * on for every device of the account, Zen included.
     */
    suspend fun sync(auth: SyncAuth, turnOn: Boolean = false): SpacesSyncResult = try {
        run(auth, turnOn, allowWipeCheck = true)
    } catch (e: SyncAuthException) {
        // Tokens expire before their stated end sometimes; one retry with a new one is enough.
        token = null
        log("Token refused, retrying once: ${e.message}")
        run(auth, turnOn, allowWipeCheck = true)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "ReturnCount")
    private suspend fun run(auth: SyncAuth, turnOn: Boolean, allowWipeCheck: Boolean): SpacesSyncResult {
        val syncKeys = KeyBundle.fromSyncKey(auth.syncKey)
        val token = currentToken(auth)
        val client = StorageClient(http, token, clock) { seconds -> backoffUntil = clock.millis() + seconds * MILLIS }
        val data = store.load()
        if (data.account != token.uid) {
            log("New storage user, forgetting the previous one")
            data.resetAll()
            data.account = token.uid
        }

        val info = JSONObject(client.expect("GET", "info/collections").body)
        if (!info.has("meta") || !info.has("crypto")) return SpacesSyncResult.NotSetUp
        setup.check(token, info)
        checkMetaGlobal(client, data, turnOn)?.let { return it }
        val keys = collectionKeys(client, syncKeys, data) ?: return SpacesSyncResult.NotSetUp

        val serverModified = timestampOf(info.opt(COLLECTION))
        if (data.lastModified != null && (serverModified == null || isNewerTimestamp(data.lastModified, serverModified))) {
            log("The spaces collection was emptied on the server, syncing everything again")
            data.resetCollection()
        }
        var collectionModified = data.lastModified
        val incoming = LinkedHashMap<String, SpacesRecord>()
        val incomingTimes = HashMap<String, Double>()
        if (serverModified != null && (data.lastModified == null || isNewerTimestamp(serverModified, data.lastModified))) {
            collectionModified = download(client, keys, data.lastModified, incoming, incomingTimes) ?: serverModified
        }
        log("Downloaded ${incoming.size} records")

        val batch = LinkedHashMap<String, SpacesRecord>()
        data.failed.values.forEach { batch[it.id] = it }
        val beforeApplying = local.read()
        data.seen.keys.removeAll(beforeApplying.forgotten)
        unopenedTabs(beforeApplying, data).forEach { batch[it.id] = it }
        batch.putAll(incoming)
        if (batch.isNotEmpty()) {
            applyIncoming(batch.values.toList(), data, keptHere(batch.values, beforeApplying, data, incomingTimes))
        }
        data.lastModified = collectionModified
        store.save(data)

        val current = local.read()
        val changes = SpacesProjector(current, data.server, data.seen).project().changesAgainst(data.server)
        if (allowWipeCheck && looksWiped(current, changes, data)) {
            // Data that looks freshly reset must not delete everything other devices have; take it back instead.
            log("Local data looks reset, downloading everything again instead of deleting it on the server")
            data.resetCollection()
            store.save(data)
            return run(auth, turnOn = false, allowWipeCheck = false)
        }

        var sent = 0
        if (changes.isNotEmpty()) {
            log("Uploading ${changes.size} records: ${changes.joinToString { if (it.deleted) "-${it.id}" else it.id }}")
            // Asked only before uploading, which is rare, so that a sync where nothing changed makes one request.
            val limits = UploadLimits.from(
                client.request("GET", "info/configuration").takeIf { it.isSuccess }?.body?.let(SyncJson::parseObject),
            )
            val uploaded = upload(client, keys, changes, collectionModified, limits)
            changes.filter { it.id in uploaded.succeeded }.forEach { data.server[it.id] = it }
            sent = uploaded.succeeded.size
            data.lastModified = uploaded.lastModified ?: data.lastModified
        }
        // Zen's split views of pinned tabs show as one row here, as the server has them now.
        local.update { state ->
            val splits = pinnedSplitsOf(data.server.values, state.pins)
            (if (splits == state.pinnedSplits) state else state.copy(pinnedSplits = splits)) to Unit
        }
        rememberTabs(current, data)
        if (!data.essentialsChecked) {
            current.separatesEssentials()?.let { separate ->
                log("Synced essentials are ${if (separate) "" else "not "}kept per container")
                local.useEssentialsPerContainer(separate)
                data.essentialsChecked = true
            }
        }
        data.lastSynced = clock.millis()
        store.save(data)
        return SpacesSyncResult.Synced(
            received = incoming.size,
            sent = sent,
            pending = data.failed.size,
            unpinnedTabs = data.server.values.any { it.isNormalTab },
        )
    }

    /**
     * The server's tabs that are not pinned and were never open here, like Zen's unpinned tabs from before they were
     * synced here too. Applying them again opens them, where the projection would otherwise leave them out.
     */
    private fun unopenedTabs(current: LocalSpaces, data: SpacesSyncData): List<SpacesRecord> {
        val tabs = current.tabs?.takeIf { current.normalTabs } ?: return emptyList()
        val here = tabs.map { current.state.syncIdOf(it.id) }.toSet() + current.state.pins.map { it.id }
        return data.server.values.filter { it.isNormalTab && it.id !in here && it.id !in data.seen }
    }

    /** Remembers the tabs that are not pinned as they are now, to find later which of them changed or closed here. */
    private fun rememberTabs(current: LocalSpaces, data: SpacesSyncData) {
        val tabs = current.tabs?.takeIf { current.normalTabs } ?: return
        val pinned = current.state.pins.mapNotNull { it.tabId }.toSet()
        val now = tabs.filterNot { it.id in pinned }.associate { current.state.syncIdOf(it.id) to it.fingerprint }
        // Tabs this sync opened may not show in the browser yet; they count as seen so closing them deletes them.
        val opening = data.seen.filterKeys { it !in now && data.server[it]?.isNormalTab == true && it in data.opened }
        data.seen.clear()
        data.seen.putAll(now + opening)
        data.opened.clear()
    }

    private fun currentToken(auth: SyncAuth): SyncToken =
        token?.takeIf { it.expiresAt > clock.millis() } ?: TokenServerClient(http, clock).fetch(auth).also { token = it }

    /**
     * Checks the collection's entry in meta/global, like Firefox's `SyncEngine._syncStartup`. A newer version stops
     * the sync, and a changed sync id means the collection was reset elsewhere, so everything is synced again. A
     * missing, declined or older entry means spaces sync is off for the account; with [turnOn] it is replaced by this
     * version with a new sync id, which other devices, Zen included, then follow.
     */
    @Suppress("ReturnCount")
    private fun checkMetaGlobal(client: StorageClient, data: SpacesSyncData, turnOn: Boolean): SpacesSyncResult? {
        val response = setup.get(client, META_GLOBAL)
        if (response.status == HTTP_NOT_FOUND) return SpacesSyncResult.NotSetUp
        val modified = response.header("X-Last-Modified")
        val payload = JSONObject(JSONObject(response.body).getString("payload"))
        if (payload.optInt("storageVersion") != STORAGE_VERSION) return SpacesSyncResult.NotSetUp

        val engines = payload.optJSONObject("engines") ?: JSONObject()
        val entry = engines.optJSONObject(COLLECTION)
        val version = entry?.optInt("version") ?: 0
        val syncId = entry?.string("syncID")
        if (version > ENGINE_VERSION) return SpacesSyncResult.NeedsUpdate(version)
        if (version == ENGINE_VERSION && syncId != null) {
            if (syncId != data.syncId) {
                log("The spaces collection was set up or reset elsewhere, syncing everything")
                data.syncId = syncId
                data.resetCollection()
            }
            return null
        }
        if (!turnOn) return SpacesSyncResult.NotTurnedOn

        val newSyncId = newSyncId()
        log("Turning spaces sync on in meta/global (was version $version)")
        engines.put(COLLECTION, JSONObject().put("version", ENGINE_VERSION).put("syncID", newSyncId))
        payload.put("engines", engines)
        payload.strings("declined")?.let { declined -> payload.put("declined", JSONArray(declined - COLLECTION)) }
        val body = JSONObject().put("id", "global").put("payload", SyncJson.stringify(payload))
        client.expect("PUT", META_GLOBAL, SyncJson.stringify(body), ifUnmodifiedSince = modified)
        setup.forget()
        data.syncId = newSyncId
        data.resetCollection()
        return null
    }

    /** The keys of the spaces collection, read from crypto/keys with the account's sync key. */
    private fun collectionKeys(client: StorageClient, syncKeys: KeyBundle, data: SpacesSyncData): KeyBundle? {
        val response = setup.get(client, CRYPTO_KEYS)
        if (response.status == HTTP_NOT_FOUND) return null
        val keys = JSONObject(decryptKeys(response, syncKeys, setup))
        val bundle = KeyBundle.fromPair(keys.optJSONObject("collections")?.optJSONArray(COLLECTION))
            ?: KeyBundle.fromPair(keys.optJSONArray("default"))
            ?: throw SyncCryptoException("crypto/keys has no default key")
        val modified = response.header("X-Last-Modified")
        if (data.keysModified != null && modified != data.keysModified) {
            log("The collection keys changed, syncing everything again")
            data.resetCollection()
        }
        data.keysModified = modified
        return bundle
    }

    /**
     * Downloads the records changed since [since] into [into], with the server time each was written at into [times],
     * and returns the collection's server time.
     */
    private fun download(
        client: StorageClient,
        keys: KeyBundle,
        since: String?,
        into: MutableMap<String, SpacesRecord>,
        times: MutableMap<String, Double>,
    ): String? {
        var modified: String? = null
        var offset: String? = null
        var skipped = 0
        do {
            val query = buildString {
                append("full=1&sort=oldest&limit=").append(PAGE_SIZE)
                since?.let { append("&newer=").append(it) }
                offset?.let { append("&offset=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val response = client.expect(
                "GET",
                "storage/$COLLECTION?$query",
                ifUnmodifiedSince = modified.takeIf { offset != null },
                allowed = setOf(HTTP_NOT_FOUND),
            )
            if (response.status == HTTP_NOT_FOUND) break
            if (modified == null) modified = response.header("X-Last-Modified")
            val items = JSONArray(response.body)
            for (index in 0 until items.length()) {
                val bso = items.optJSONObject(index) ?: continue
                val id = bso.string("id") ?: continue
                val record = runCatching {
                    val cleartext = JSONObject(keys.decrypt(JSONObject(bso.getString("payload"))))
                    cleartext.string("id")?.let { check(it == id) { "Record id mismatch" } }
                    SpacesRecord.fromCleartext(id, cleartext)
                }.getOrNull()
                if (record == null) {
                    skipped++
                } else {
                    into[id] = record
                    bso.optDouble("modified").takeUnless { it.isNaN() }?.let { times[id] = it }
                }
            }
            offset = response.header("X-Weave-Next-Offset")
        } while (offset != null)
        if (skipped > 0) log("Skipped $skipped records that could not be read")
        return modified
    }

    /**
     * The records to apply in place of some of [records], by id: those that keep changes made here, see
     * [keepLocalChanges]. [times] has the server time of the downloaded records.
     */
    private fun keptHere(
        records: Collection<SpacesRecord>,
        current: LocalSpaces,
        data: SpacesSyncData,
        times: Map<String, Double>,
    ): Map<String, SpacesRecord> {
        val projected = SpacesProjector(current, data.server, data.seen).project().records
        val changedAt = HashMap<String, Long>()
        current.containers.forEach { changedAt[it.contextId] = it.updatedAt }
        current.state.workspaces.forEach { changedAt[it.id] = it.updatedAt }
        current.state.pins.forEach { changedAt[it.id] = it.updatedAt }
        val kept = LinkedHashMap<String, SpacesRecord>()
        for (record in records) {
            val localTime = changedAt[record.id]?.let(clock::serverSecondsAt)
            val merged = keepLocalChanges(record, data.server[record.id], projected[record.id], times[record.id], localTime)
            if (merged !== record) kept[record.id] = merged
        }
        if (kept.isNotEmpty()) log("Keeping changes made here in ${kept.keys.joinToString()}")
        return kept
    }

    /**
     * Applies [records]: containers first, including Firefox's default containers the records use, then everything
     * else, then deleted containers once nothing relies on them. The records of [keptHere] are applied in place of
     * theirs, which the server keeps.
     */
    private suspend fun applyIncoming(
        records: List<SpacesRecord>,
        data: SpacesSyncData,
        keptHere: Map<String, SpacesRecord> = emptyMap(),
    ) {
        val received = records.associateBy { it.id }
        val toApply = records.map { keptHere[it.id] ?: it }
        val before = local.read()
        val localContainers = before.containers.filterNot { it.temporary }.associateBy { it.contextId }
        val (containerRecords, others) = toApply.partition { record ->
            record.kind == RecordKind.CONTAINER ||
                (record.deleted && (record.id in localContainers || data.server[record.id]?.kind == RecordKind.CONTAINER))
        }
        val applied = mutableListOf<SpacesRecord>()
        val failed = mutableListOf<SpacesRecord>()

        containerRecords.filterNot { it.deleted }.forEach { record ->
            saveContainer(record, localContainers[record.id])
            applied += record
        }
        addBuiltinContainers(others)
        val containerIds = local.read().containers.filterNot { it.temporary }.map { it.contextId }.toSet()
        val removedContainers = data.server.values.filter { it.deleted }.map { it.id }.toSet() +
            containerRecords.filter { it.deleted }.map { it.id }

        val applier = SpacesApplier(
            server = data.server.toMap(),
            containerIds = containerIds,
            firstSync = data.server.isEmpty(),
            defaultName = local.defaultWorkspaceName,
            now = clock.millis(),
            normalTabs = before.normalTabs,
            tabs = before.tabs?.associateBy { it.id },
            removedContainers = removedContainers,
        )
        val result = local.update { state -> applier.apply(state, others).let { it.state to it } }
        applied += result.applied
        failed += result.failed
        if (result.tabOps.isNotEmpty()) {
            local.runTabOps(result.tabOps)
            result.tabOps.filterIsInstance<TabOp.Open>().forEach { op ->
                data.seen[op.id] = LocalTab(op.id, op.url, op.title, op.contextId, awake = false).fingerprint
                data.opened += op.id
            }
        }

        for (record in containerRecords.filter { it.deleted }) {
            if (record.id in containerIds) local.removeContainer(record.id)
            applied += record
        }

        applied.forEach {
            data.server[it.id] = received[it.id] ?: it
            data.failed.remove(it.id)
        }
        failed.forEach { data.failed[it.id] = received[it.id] ?: it }
        toApply.filter { it !in applied && it !in failed }.forEach { data.failed.remove(it.id) }
        log("Applied ${applied.size} records, ${failed.size} waiting for records they need")
    }

    /** Creates or updates the container of an incoming container [record]; Zen skips containers without a name. */
    private suspend fun saveContainer(record: SpacesRecord, existing: ContainerRecord?) {
        val fields = record.data ?: return
        val name = fields.string("name")
        if (name.isNullOrEmpty()) return
        val color = containerColorOf(fields.opt("color"))
        val icon = containerIconOf(fields.opt("icon"))
        if (existing?.let { Triple(it.name, it.color, it.icon) } != Triple(name, color, icon)) {
            local.saveContainer(record.id, name, color, icon)
        }
    }

    /** Creates the Firefox default containers that [records] use and Dejavu does not have yet. */
    private suspend fun addBuiltinContainers(records: List<SpacesRecord>) {
        val present = local.read().containers.map { it.contextId }.toSet()
        records.asSequence()
            .mapNotNull { it.data?.string("containerGuid") }
            .filter { it !in present }
            .mapNotNull { BuiltinContainer.of(it) }
            .distinct()
            .forEach { local.saveContainer(it.guid, local.builtinName(it), it.color, it.icon) }
    }

    /**
     * Whether local data looks like it was lost rather than deleted: nothing but a new default workspace is left, while
     * the server has spaces and pins Dejavu had synced.
     */
    private fun looksWiped(current: LocalSpaces, changes: List<SpacesRecord>, data: SpacesSyncData): Boolean {
        val deletions = changes.count { change ->
            val known = data.server[change.id]
            change.deleted && known != null && !known.isNormalTab &&
                known.kind in setOf(RecordKind.SPACE, RecordKind.TAB, RecordKind.FOLDER)
        }
        val workspace = current.state.workspaces.singleOrNull() ?: return false
        return deletions >= WIPE_DELETIONS && current.state.pins.isEmpty() && data.server[workspace.id] == null
    }

    private class Uploaded(val succeeded: Set<String>, val lastModified: String?)

    /**
     * Uploads [records] in as few requests as the server allows. Several requests use the server's batch mode, so
     * other devices see all of them or none.
     */
    private fun upload(
        client: StorageClient,
        keys: KeyBundle,
        records: List<SpacesRecord>,
        since: String?,
        limits: UploadLimits,
    ): Uploaded {
        val chunks = chunks(encrypt(keys, records, limits), limits)
        val succeeded = mutableSetOf<String>()
        var lastModified: String? = null
        var ifUnmodifiedSince = since
        var batchId: String? = null
        var batched = chunks.size > 1
        chunks.forEachIndexed { index, chunk ->
            val body = chunk.joinToString(",", prefix = "[", postfix = "]") { it.second }
            val path = when {
                !batched -> "storage/$COLLECTION"
                batchId == null -> "storage/$COLLECTION?batch=true"
                else -> "storage/$COLLECTION?batch=${URLEncoder.encode(batchId, "UTF-8")}" +
                    if (index == chunks.lastIndex) "&commit=true" else ""
            }
            val response = client.expect("POST", path, body, ifUnmodifiedSince)
            val result = SyncJson.parseObject(response.body) ?: JSONObject()
            succeeded += result.strings("success").orEmpty()
            result.optJSONObject("failed")?.keys()?.forEach { log("Server refused $it") }
            if (batched && response.status == HTTP_ACCEPTED) {
                batchId = batchId ?: result.string("batch")
            } else {
                // Committed, or a server without batches: each request is applied on its own.
                batched = false
                lastModified = response.header("X-Last-Modified") ?: timestampOf(result.opt("modified"))
                ifUnmodifiedSince = lastModified
            }
        }
        return Uploaded(succeeded, lastModified)
    }

    /** The upload JSON of each record by id, without records too large for the server. */
    private fun encrypt(keys: KeyBundle, records: List<SpacesRecord>, limits: UploadLimits): List<Pair<String, String>> =
        records.mapNotNull { record ->
            val payload = SyncJson.stringify(keys.encrypt(SyncJson.stringify(record.toCleartext())))
            if (payload.length > limits.maxRecordPayloadBytes) {
                log("Record ${record.id} is too large to upload")
                null
            } else {
                record.id to SyncJson.stringify(JSONObject().put("id", record.id).put("payload", payload))
            }
        }

    /** Splits [items] into requests within the server's limits. */
    private fun chunks(items: List<Pair<String, String>>, limits: UploadLimits): List<List<Pair<String, String>>> {
        val chunks = mutableListOf<MutableList<Pair<String, String>>>()
        var bytes = 0
        for (item in items) {
            val current = chunks.lastOrNull()
            val size = item.second.length
            if (current == null || current.size >= limits.maxPostRecords || bytes + size > limits.maxPostBytes) {
                chunks += mutableListOf(item)
                bytes = size
            } else {
                current += item
                bytes += size
            }
        }
        return chunks
    }

    /** Upload limits from info/configuration. */
    private class UploadLimits(val maxPostRecords: Int, val maxPostBytes: Int, val maxRecordPayloadBytes: Int) {
        companion object {
            private const val DEFAULT_POST_RECORDS = 100
            private const val DEFAULT_POST_BYTES = 2 * 1024 * 1024
            private const val DEFAULT_RECORD_BYTES = 256 * 1024

            fun from(config: JSONObject?) = UploadLimits(
                maxPostRecords = config?.optInt("max_post_records", DEFAULT_POST_RECORDS) ?: DEFAULT_POST_RECORDS,
                maxPostBytes = minOf(
                    config?.optInt("max_post_bytes", DEFAULT_POST_BYTES) ?: DEFAULT_POST_BYTES,
                    config?.optInt("max_request_bytes", DEFAULT_POST_BYTES) ?: DEFAULT_POST_BYTES,
                ),
                maxRecordPayloadBytes = config?.optInt("max_record_payload_bytes", DEFAULT_RECORD_BYTES)
                    ?: DEFAULT_RECORD_BYTES,
            )
        }
    }

    companion object {
        /** Name of the collection, and of Zen's sync engine in meta/global. */
        const val COLLECTION = "spaces"

        /** Version of the records, matching Zen's `ZenSpacesSyncEngine.version`. */
        const val ENGINE_VERSION = 3

        private const val STORAGE_VERSION = 5
        private const val PAGE_SIZE = 1000
        private const val WIPE_DELETIONS = 2
        private const val SYNC_ID_BYTES = 9
        private const val MILLIS = 1000L
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_NOT_FOUND = 404
        private val random = SecureRandom()

        /** A 12 character sync id, like Firefox's `Utils.makeGUID`. */
        fun newSyncId(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(SYNC_ID_BYTES).also { random.nextBytes(it) })
    }
}

/**
 * Whether the synced essentials look like Zen keeps them per container, as Zen does not sync that setting itself, or
 * `null` while the setting would not change which essentials any space shows. With the setting on, Zen only lets a
 * space get essentials in its own default container, so an essential in a container no space has as its default means
 * it is off. Otherwise spaces that would show different essentials with it mean it is on.
 */
internal fun LocalSpaces.separatesEssentials(): Boolean? {
    val synced = containers.filterNot { it.temporary }.map { it.contextId }.toSet()
    fun syncedContainer(containerId: String?) = containerId?.takeIf { it in synced }
    val essentials = state.essentials.map { syncedContainer(it.containerId) }
    val spaces = state.workspaces.map { syncedContainer(it.containerId) }.toSet()
    if (essentials.isEmpty() || spaces.all { it == null }) return null
    if (essentials.any { it != null && it !in spaces }) return false
    val shown = spaces.map { container -> essentials.count { it == container } }
    return when {
        shown.count { it > 0 } > 1 -> true
        shown.singleOrNull() == essentials.size -> null
        else -> false
    }
}
