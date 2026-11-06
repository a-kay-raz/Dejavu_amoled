/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.net.URLEncoder
import java.security.GeneralSecurityException
import org.json.JSONArray
import org.json.JSONObject

/** Storage credentials of the account, fetched from the token server and kept until they are about to expire. */
internal class SyncTokens(private val http: SyncHttp, private val clock: SyncClock) {
    @Volatile
    private var token: SyncToken? = null

    fun get(auth: SyncAuth): SyncToken =
        token?.takeIf { it.expiresAt > clock.millis() } ?: TokenServerClient(http, clock).fetch(auth).also { token = it }

    fun forget() {
        token = null
    }
}

/**
 * Runs [block] once more with a new token when the servers refuse the current one: tokens sometimes expire before
 * their stated end.
 */
internal inline fun <T> SyncTokens.retryingOnce(block: () -> T): T = try {
    block()
} catch (e: SyncAuthException) {
    forget()
    block()
}

/**
 * meta/global and crypto/keys as last downloaded, given again while info/collections shows the same server times for
 * both, so that a sync where nothing changed only asks for info/collections.
 */
internal class StorageSetupCache {
    private var signature: String? = null
    private val responses = HashMap<String, SyncResponse>()

    /** Forgets what it kept unless the storage of [token] and the times of meta and crypto in [info] are the same. */
    @Synchronized
    fun check(token: SyncToken, info: JSONObject) {
        val current = "${token.endpoint} ${timestampOf(info.opt("meta"))} ${timestampOf(info.opt("crypto"))}"
        if (current != signature) {
            responses.clear()
            signature = current
        }
    }

    @Synchronized
    fun forget() {
        signature = null
        responses.clear()
    }

    /** The answer to GET [path], kept from before or downloaded with [client]. Only successes are kept. */
    fun get(client: StorageClient, path: String): SyncResponse {
        synchronized(this) { responses[path] }?.let { return it }
        val response = client.expect("GET", path, allowed = setOf(HTTP_NOT_FOUND))
        if (response.isSuccess) synchronized(this) { responses[path] = response }
        return response
    }
}

/**
 * meta/global, which lists the collections the account syncs.
 *
 * @property engines Each collection's entry, with its version and sync id.
 * @property declined Collections the user turned off for every device.
 */
internal class MetaGlobal(val engines: JSONObject, val declined: Set<String>) {
    fun has(collection: String): Boolean = engines.optJSONObject(collection) != null && collection !in declined
}

/** The keys of crypto/keys: one for every collection, unless a collection has its own. */
internal class CollectionKeys(private val default: KeyBundle, private val own: Map<String, KeyBundle>, val modified: String?) {
    fun of(collection: String): KeyBundle = own[collection] ?: default
}

/**
 * Reads meta/global, from [cache] while it has it; `null` while Firefox Sync has not set up the account's storage.
 */
internal fun StorageClient.metaGlobal(cache: StorageSetupCache? = null): MetaGlobal? {
    val response = cache?.get(this, META_GLOBAL) ?: expect("GET", META_GLOBAL, allowed = setOf(HTTP_NOT_FOUND))
    if (response.status == HTTP_NOT_FOUND) return null
    val payload = JSONObject(JSONObject(response.body).getString("payload"))
    if (payload.optInt("storageVersion") != STORAGE_VERSION) return null
    return MetaGlobal(payload.optJSONObject("engines") ?: JSONObject(), payload.strings("declined").orEmpty().toSet())
}

/**
 * Reads crypto/keys with the account's [syncKeys], from [cache] while it has it; `null` while Firefox Sync has not set
 * up the account's storage.
 */
internal fun StorageClient.collectionKeys(syncKeys: KeyBundle, cache: StorageSetupCache? = null): CollectionKeys? {
    val response = cache?.get(this, CRYPTO_KEYS) ?: expect("GET", CRYPTO_KEYS, allowed = setOf(HTTP_NOT_FOUND))
    if (response.status == HTTP_NOT_FOUND) return null
    val keys = JSONObject(decryptKeys(response, syncKeys, cache))
    val default = KeyBundle.fromPair(keys.optJSONArray("default"))
        ?: throw SyncCryptoException("crypto/keys has no default key")
    val collections = keys.optJSONObject("collections")
    val own = collections?.keys()?.asSequence()
        ?.mapNotNull { name -> KeyBundle.fromPair(collections.optJSONArray(name))?.let { name to it } }
        ?.toMap()
        .orEmpty()
    return CollectionKeys(default, own, response.header("X-Last-Modified"))
}

/** Decrypts the payload of crypto/keys in [response], forgetting what [cache] kept when it cannot. */
internal fun decryptKeys(response: SyncResponse, syncKeys: KeyBundle, cache: StorageSetupCache?): String = try {
    syncKeys.decrypt(JSONObject(JSONObject(response.body).getString("payload")))
} catch (e: GeneralSecurityException) {
    cache?.forget()
    throw e
}

/**
 * Downloads every record of [collection] and decrypts it with [keys], by id. Records that cannot be read are left out.
 * With [stampModified], each record also gets the time the server last changed it, in seconds, as [SERVER_MODIFIED].
 */
internal fun StorageClient.downloadAll(
    collection: String,
    keys: KeyBundle,
    stampModified: Boolean = false,
): Map<String, JSONObject> {
    val records = LinkedHashMap<String, JSONObject>()
    var modified: String? = null
    var offset: String? = null
    do {
        val query = buildString {
            append("full=1&limit=").append(PAGE_SIZE)
            offset?.let { append("&offset=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val response = expect(
            "GET",
            "storage/$collection?$query",
            ifUnmodifiedSince = modified.takeIf { offset != null },
            allowed = setOf(HTTP_NOT_FOUND),
        )
        if (response.status == HTTP_NOT_FOUND) break
        if (modified == null) modified = response.header("X-Last-Modified")
        val items = JSONArray(response.body)
        for (index in 0 until items.length()) {
            val bso = items.optJSONObject(index) ?: continue
            val id = bso.string("id") ?: continue
            runCatching { JSONObject(keys.decrypt(JSONObject(bso.getString("payload")))) }
                .getOrNull()
                ?.takeIf { it.string("id").let { inner -> inner == null || inner == id } }
                ?.let { record ->
                    if (stampModified && bso.has("modified")) record.put(SERVER_MODIFIED, bso.optDouble("modified"))
                    records[id] = record
                }
        }
        offset = response.header("X-Weave-Next-Offset")
    } while (offset != null)
    return records
}

/** Key Dejavu adds to a downloaded record for the time the server last changed it; no browser writes it. */
internal const val SERVER_MODIFIED = "dejavuServerModified"

internal const val META_GLOBAL = "storage/meta/global"
internal const val CRYPTO_KEYS = "storage/crypto/keys"
private const val STORAGE_VERSION = 5
private const val PAGE_SIZE = 1000
private const val HTTP_NOT_FOUND = 404
