/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.DeviceCommandOutgoing
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.concept.sync.TabPrivacy
import mozilla.components.lib.state.ext.flow
import mozilla.components.service.fxa.manager.SCOPE_SYNC
import mozilla.components.service.fxa.sync.SyncStatusObserver
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.SilentlyClosedTabs
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRemoval
import org.mozilla.fenix.dejavu.containers.ContainerRemover
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.TemporaryContainers
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.withoutStarters
import org.mozilla.fenix.ext.components

/** Why workspace sync is not working, as shown in its settings. */
enum class DejavuSyncProblem {
    /** Firefox Sync has not set up the account's storage yet. It is done by the first sync of the browser. */
    NOT_SET_UP,

    /** Spaces sync is not turned on for the account, see [DejavuSync.turnOn]. */
    NOT_TURNED_ON,

    /** Another device uses a newer version of spaces sync. */
    NEEDS_UPDATE,

    /** The account has to be signed in again. */
    SIGN_IN_AGAIN,

    /** The servers could not be reached. */
    OFFLINE,

    /** The servers failed or asked to wait. */
    SERVER,

    /** Sync waits until the user chooses which browser workspaces follow, see [DejavuSync.chooseSource]. */
    CHOOSE_SOURCE,

    /** Open tabs are not synced for the account, so Firefox's tabs cannot be shown. */
    TABS_OFF,

    /** No Firefox on a computer syncs its open tabs to the account. */
    NO_FIREFOX,
}

/** Which browser Dejavu's workspaces sync with. */
enum class DejavuSyncSource(internal val key: String) {
    /** Zen's spaces, both ways, see [SpacesSyncEngine]. Dejavu on other devices uses them too. */
    ZEN("zen"),

    /** The open tabs of Firefox on the account's computers, see [FirefoxTabsEngine]. */
    FIREFOX("firefox"),
    ;

    internal companion object {
        fun fromKey(key: String?): DejavuSyncSource? = entries.firstOrNull { it.key == key }
    }
}

/** What happens to this device's own workspaces and tabs when workspaces start syncing with a browser. */
enum class SyncStart {
    /** They stay, next to what is synced. With Zen, they sync to it too. */
    MERGE,

    /** They are removed first, so that this device gets only what is synced. */
    FRESH,
}

/**
 * State of workspace sync.
 *
 * @property enabled Whether syncing with Zen is turned on in Dejavu. Firefox's workspaces do not depend on it.
 * @property normalTabs Whether tabs that are not pinned are synced with Zen too, like Zen's "Include unpinned tabs".
 *   Off like in Zen, until the user turns it on, or synced data shows that another device syncs them. Firefox's
 *   workspaces always show every tab.
 * @property signedIn Whether a Mozilla account is signed in.
 * @property syncing Whether a sync is running.
 * @property lastSynced Time of the last complete sync in milliseconds, or 0.
 * @property problem What stopped the last sync, if anything.
 * @property source The browser workspaces sync with, or `null` until the user chose one. Sync waits until then.
 * @property found What the account syncs from Firefox and Zen, once Dejavu looked.
 * @property askSource Whether to ask the user which browser to sync with: none is chosen, something was found, and the
 *   user did not put the question off.
 */
data class DejavuSyncStatus(
    val enabled: Boolean = true,
    val normalTabs: Boolean = false,
    val signedIn: Boolean = false,
    val syncing: Boolean = false,
    val lastSynced: Long = 0L,
    val problem: DejavuSyncProblem? = null,
    val source: DejavuSyncSource? = null,
    val found: FoundSyncData? = null,
    val askSource: Boolean = false,
) {
    /** Whether workspaces sync on this device: with Firefox always, otherwise while [enabled] is on. */
    val isOn: Boolean
        get() = enabled || source == DejavuSyncSource.FIREFOX

    /** Whether workspaces sync with Zen on this device. */
    val syncsZen: Boolean
        get() = enabled && source == DejavuSyncSource.ZEN
}

/**
 * Syncs Dejavu's workspaces through the Mozilla account, with Zen ([SpacesSyncEngine]) or with Firefox
 * ([FirefoxTabsEngine]), whichever the user chose after Dejavu showed what the account has of each
 * ([SyncSourceScanner]). Until the user chooses, workspaces are not synced.
 *
 * Syncs run one at a time: after Firefox's own syncs, when the app comes to the foreground and every few minutes
 * while it is shown, a few seconds after local changes, and when the app goes to the background with changes not
 * uploaded yet.
 */
object DejavuSync {
    // Kept from before the rename to Dejavu, like STORE_FILE, so saved data still loads.
    private const val PREFS_NAME = "kaizen_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NORMAL_TABS = "normal_tabs"
    private const val KEY_SOURCE = "source"
    private const val KEY_SOURCE_PUT_OFF = "source_put_off"
    private const val KEY_FORGOTTEN = "forgotten_tabs"
    private const val STORE_FILE = "kaizen_spaces_sync.json"
    private const val FIREFOX_STORE_FILE = "dejavu_firefox_sync.json"
    private const val LOCAL_CHANGE_DELAY_MS = 3_000L
    private const val FOREGROUND_POLL_MS = 5 * 60_000L
    private const val MIN_INTERVAL_MS = 10_000L
    private const val CONFLICT_RETRY_MS = 5_000L
    private const val MAX_CONFLICT_RETRIES = 3
    private const val DEVICES_REFRESH_MS = 60_000L
    private const val UNREACHABLE_RETRY_MS = 30_000L
    private const val MAX_RETRY_DOUBLINGS = 4

    private val logger = Logger("DejavuSync")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val forgottenLock = Any()
    private val requests = Channel<Boolean>(Channel.CONFLATED)
    private val _status = MutableStateFlow(DejavuSyncStatus())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engine: SpacesSyncEngine? = null

    @Volatile
    private var firefoxEngine: FirefoxTabsEngine? = null

    @Volatile
    private var scanner: SyncSourceScanner? = null

    @Volatile
    private var local: DejavuSpacesData? = null
    private var pollJob: Job? = null

    /** How many switches to another browser are getting ready, which syncs wait for. */
    private val switching = AtomicInteger()

    @Volatile
    private var turnOnRequested = false

    /** When the account's devices were last asked for, in [SystemClock.elapsedRealtime] milliseconds, or 0. */
    @Volatile
    private var devicesRefreshedAt = 0L

    /** Devices the account did not have when it was last asked, though sync records of them were still there. */
    @Volatile
    private var stillUnknownDevices = emptySet<String>()

    @Volatile
    private var scanRequested = false

    @Volatile
    private var sourcePutOff = false

    @Volatile
    private var accountManagerReady = false
    private var lastRun = 0L

    /** Syncs in a row that could not reach the servers, which space out the next tries. */
    private var failedToReach = 0

    /** Syncs in a row that another device's changes cut short, each retried soon until there are too many. */
    private var conflictsInARow = 0

    /** Which tabs here are tabs of the Firefox computers Dejavu shows. */
    @Volatile
    private var computerTabs = ComputerTabs.NONE

    /** State of workspace sync, for its settings. */
    val status: StateFlow<DejavuSyncStatus> = _status.asStateFlow()

    /** Starts workspace sync. Only the first call does anything. */
    fun install(context: Context) {
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val store = SpacesSyncStore(File(app.filesDir, STORE_FILE))
        val firefoxStore = FirefoxSyncStore(File(app.filesDir, FIREFOX_STORE_FILE))
        computerTabs = firefoxStore.load().computerTabs()
        // Dejavu synced with Zen before it could sync with Firefox; those who did keep doing it without being asked.
        val source = DejavuSyncSource.fromKey(prefs.getString(KEY_SOURCE, null))
            ?: DejavuSyncSource.ZEN.takeIf { store.load().syncId != null }?.also { zen ->
                prefs.edit { putString(KEY_SOURCE, zen.key) }
            }
        sourcePutOff = prefs.getBoolean(KEY_SOURCE_PUT_OFF, false)
        _status.update {
            it.copy(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                normalTabs = prefs.getBoolean(KEY_NORMAL_TABS, false),
                source = source,
                lastSynced = when (source) {
                    DejavuSyncSource.ZEN -> store.load().lastSynced
                    DejavuSyncSource.FIREFOX -> firefoxStore.load().lastSynced
                    null -> 0L
                },
            )
        }

        scope.launch(Dispatchers.Main) {
            // The fetch client comes with the Gecko runtime, which is created on the main thread.
            val http = FetchSyncHttp(app.components.core.client)
            val data = DejavuSpacesData(app)
            local = data
            engine = SpacesSyncEngine(http = http, store = store, local = data, log = { logger.info(it) })
            firefoxEngine = FirefoxTabsEngine(http = http, store = firefoxStore, local = data, log = { logger.info(it) })
            scanner = SyncSourceScanner(http)
            scope.launch { for (manual in requests) runRequest(manual) }
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            val services = app.components.backgroundServices
            services.accountManagerAvailableQueue.runIfReadyOrQueue {
                val manager = services.accountManager
                manager.register(accountObserver)
                manager.registerForSyncEvents(syncObserver, ProcessLifecycleOwner.get(), autoPause = false)
                val signedIn = manager.authenticatedAccount() != null
                accountManagerReady = true
                _status.update { it.copy(signedIn = signedIn) }
                if (signedIn) request() else stopForgetting(app)
            }
        }
        watchLocalChanges(app)
    }

    /**
     * Whether open tab [tabId], opened at [createdAt], is a tab of a Firefox computer that Dejavu follows: one of its
     * tabs, or one opened in its workspace, which is sent to it. The other devices list those on the computer, not on
     * this one.
     */
    fun isFirefoxComputerTab(tabId: String, createdAt: Long): Boolean {
        val status = _status.value
        if (status.source != DejavuSyncSource.FIREFOX) return false
        val tabs = computerTabs
        if (tabs.workspaces.isEmpty()) return false
        val state = WorkspaceRepository.peek()?.state?.value ?: return false
        return tabs.has(state, tabId, createdAt)
    }

    /** Syncs now, as asked by the user. */
    fun syncNow() {
        requests.trySend(true)
    }

    /** Turns spaces sync on for the account, which also turns it on in Zen, and syncs. */
    fun turnOn() {
        turnOnRequested = true
        syncNow()
    }

    /** Turns syncing with Zen on or off on this device only; other devices keep syncing. */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        _status.update { it.copy(enabled = enabled, problem = null) }
        if (enabled) syncNow()
    }

    /** Syncs tabs that are not pinned with Zen too, or only pinned tabs, folders and essentials. */
    fun setNormalTabs(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_NORMAL_TABS, enabled) }
        _status.update { it.copy(normalTabs = enabled) }
        syncNow()
    }

    /**
     * Turns on syncing the tabs that are not pinned once synced data shows another device syncs them, like Zen with
     * its own "Include unpinned tabs" on, as long as the user did not turn it on or off on this device.
     */
    private fun followUnpinnedTabs(context: Context, result: SpacesSyncResult) {
        if (result !is SpacesSyncResult.Synced || !result.unpinnedTabs) return
        if (_status.value.normalTabs || prefs(context).contains(KEY_NORMAL_TABS)) return
        logger.info("Another device syncs unpinned tabs, syncing them here too")
        setNormalTabs(context, true)
    }

    /**
     * Makes sync forget the tabs that synced under [syncIds], which were removed all at once rather than closed by the
     * user, like by Delete browsing data, or by Firefox for being unused too long. They are not closed on the other
     * devices, and come back from there instead. Kept until a sync took them into account.
     */
    fun forgetTabs(context: Context, syncIds: Collection<String>) {
        if (syncIds.isEmpty()) return
        synchronized(forgottenLock) {
            val prefs = prefs(context)
            prefs.edit { putStringSet(KEY_FORGOTTEN, prefs.getStringSet(KEY_FORGOTTEN, null).orEmpty() + syncIds) }
        }
    }

    /** The ids of the tabs sync forgets, see [forgetTabs]. */
    internal fun forgottenTabs(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FORGOTTEN, null).orEmpty().toSet()

    /** Stops forgetting [syncIds], which a sync took into account, or every tab when [syncIds] is `null`. */
    private fun stopForgetting(context: Context, syncIds: Set<String>? = null) {
        synchronized(forgottenLock) {
            val prefs = prefs(context)
            if (!prefs.contains(KEY_FORGOTTEN)) return
            val left = syncIds?.let { prefs.getStringSet(KEY_FORGOTTEN, null).orEmpty() - it }.orEmpty()
            prefs.edit { if (left.isEmpty()) remove(KEY_FORGOTTEN) else putStringSet(KEY_FORGOTTEN, left) }
        }
    }

    /**
     * Makes workspaces sync with [source] from now on, and syncs. Leaving Firefox takes the workspaces and tabs of its
     * computers out of Dejavu, as Firefox keeps them; leaving Zen keeps its workspaces here, no longer synced. [start]
     * tells what happens to this device's own workspaces and tabs. Starting to sync with Zen merges with what Zen has
     * without removing anything there, even after syncing with it before: what was removed here meanwhile stays there.
     */
    fun chooseSource(context: Context, source: DejavuSyncSource, start: SyncStart = SyncStart.MERGE) {
        val previous = _status.value.source
        prefs(context).edit {
            putString(KEY_SOURCE, source.key)
            remove(KEY_SOURCE_PUT_OFF)
        }
        sourcePutOff = false
        // Until this device is ready for the new browser, syncs wait, so that none of them runs with it too early.
        switching.incrementAndGet()
        _status.update { it.copy(source = source, askSource = false, problem = null) }
        scope.launch {
            try {
                mutex.withLock { getReady(previous, source, start) }
            } finally {
                switching.decrementAndGet()
            }
            val lastSynced = when (source) {
                DejavuSyncSource.ZEN -> engine?.lastSynced
                DejavuSyncSource.FIREFOX -> firefoxEngine?.lastSynced
            } ?: 0L
            _status.update { if (it.source == source) it.copy(lastSynced = lastSynced) else it }
            syncNow()
        }
    }

    /** Gets this device ready to sync with [source] instead of [previous], as [start] asks. */
    private suspend fun getReady(previous: DejavuSyncSource?, source: DejavuSyncSource, start: SyncStart) {
        if (previous == source) return
        if (previous == DejavuSyncSource.FIREFOX) {
            runCatching { firefoxEngine?.removeAll() }.onFailure { logger.warn("Could not remove Firefox", it) }
            computerTabs = ComputerTabs.NONE
        }
        // What Dejavu knew of Zen's records from an earlier time would make the first sync delete there what is gone
        // here, so it starts over. The examples a fresh install started with are not sent there as they came.
        if (source == DejavuSyncSource.ZEN) {
            engine?.reset()
            local?.dropStarters()
        }
        if (start == SyncStart.FRESH) {
            logger.info("Starting fresh: removing this device's workspaces and tabs")
            runCatching { local?.startFresh() }.onFailure { logger.warn("Could not start fresh", it) }
        }
    }

    /**
     * Leaves the choice of a browser for later: it waits in the sync settings, and workspaces are not synced until the
     * user chooses.
     */
    fun putOffSourceChoice(context: Context) {
        prefs(context).edit { putBoolean(KEY_SOURCE_PUT_OFF, true) }
        sourcePutOff = true
        _status.update { it.copy(askSource = false) }
    }

    /** Looks again at what the account syncs from Firefox and Zen, to choose between them. */
    fun findSources() {
        scanRequested = true
        syncNow()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun request() {
        requests.trySend(false)
    }

    @OptIn(FlowPreview::class)
    private fun watchLocalChanges(context: Context) = scope.launch {
        val repository = WorkspaceRepository.get(context)
        val containers = DejavuContainerStorage.get(context)
        // Only while this device syncs: looking at every tab on every change of the browser costs work and memory.
        _status.map { it.isOn && it.signedIn }.distinctUntilChanged().collectLatest { syncing ->
            if (!syncing) return@collectLatest
            // Tabs that are not pinned count by what their records hold: address, title and container. A tab opened in the
            // workspace of a Firefox computer is sent to it once its page loaded, so loading counts too.
            // Compared field by field rather than mapped to a list per tab, as the browser changes often while
            // pages load.
            val tabs = context.components.core.store.flow()
                .map { state -> state.normalTabs }
                .distinctUntilChanged { old, new ->
                    old.size == new.size && old.indices.all { index ->
                        val a = old[index]
                        val b = new[index]
                        a.id == b.id && a.content.url == b.content.url && a.content.title == b.content.title &&
                            a.contextId == b.contextId && a.content.loading == b.content.loading
                    }
                }
            combine(repository.state, containers.records, tabs) { _, _, _ -> }
                .drop(1)
                .debounce(LOCAL_CHANGE_DELAY_MS)
                .collect { if (hasLocalChanges()) request() }
        }
    }

    private suspend fun hasLocalChanges(): Boolean {
        val current = _status.value
        if (!current.isOn || !current.signedIn) return false
        return mutex.withLock {
            runCatching {
                when (current.source) {
                    DejavuSyncSource.ZEN -> engine?.hasLocalChanges() == true
                    DejavuSyncSource.FIREFOX -> firefoxEngine?.hasLocalChanges() == true
                    null -> false
                }
            }.getOrDefault(false)
        }
    }

    private suspend fun runRequest(manual: Boolean) {
        if (!manual) {
            val wait = lastRun + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
        }
        runSync(manual)
        lastRun = System.currentTimeMillis()
    }

    private suspend fun runSync(manual: Boolean) {
        val context = appContext ?: return
        val engines = Engines(engine ?: return, firefoxEngine ?: return, scanner ?: return)
        if (!accountManagerReady || !_status.value.isOn) return
        if (!manual && engines.backoffUntil > System.currentTimeMillis()) return
        val account = context.components.backgroundServices.accountManager.authenticatedAccount()
        if (account == null) {
            _status.update { it.copy(signedIn = false) }
            stopForgetting(context)
            return
        }
        val turnOn = turnOnRequested && manual
        turnOnRequested = false
        val scan = scanRequested
        scanRequested = false
        mutex.withLock { syncLocked(engines, account, scan, turnOn) }
    }

    /**
     * Syncs once while [mutex] is held, unless this device is getting ready for another browser: the sync that follows
     * then does what this one was asked to.
     */
    private suspend fun syncLocked(engines: Engines, account: OAuthAccount, scan: Boolean, turnOn: Boolean) {
        if (switching.get() > 0) {
            scanRequested = scanRequested || scan
            turnOnRequested = turnOnRequested || turnOn
            return
        }
        val source = _status.value.source
        _status.update { it.copy(signedIn = true, syncing = true) }
        val outcome = sync(engines, account, source, scan = scan || source == null, turnOn = turnOn)
        retrySoonIfUnreachable(outcome.problem)
        _status.update { current ->
            val found = outcome.found ?: current.found
            current.copy(
                syncing = false,
                // The user may have chosen a browser during the sync, which syncs again with it next.
                problem = if (current.source == source) outcome.problem else null,
                lastSynced = if (outcome.problem == null) System.currentTimeMillis() else current.lastSynced,
                found = found,
                askSource = current.source == null && !sourcePutOff && found?.isEmpty == false,
            )
        }
    }

    /**
     * Tries again soon when the servers could not be reached while the app is shown, so that a short loss of network
     * does not leave the problem showing until the next regular sync: after 30 seconds, then twice as long each time.
     */
    private fun retrySoonIfUnreachable(problem: DejavuSyncProblem?) {
        if (problem != DejavuSyncProblem.OFFLINE) {
            failedToReach = 0
            return
        }
        if (pollJob?.isActive != true) return
        val wait = minOf(UNREACHABLE_RETRY_MS shl minOf(failedToReach, MAX_RETRY_DOUBLINGS), FOREGROUND_POLL_MS)
        failedToReach++
        scope.launch {
            delay(wait)
            if (pollJob?.isActive == true) request()
        }
    }

    /** The engines of each browser, and the scanner that finds what the account has of them. */
    private class Engines(val zen: SpacesSyncEngine, val firefox: FirefoxTabsEngine, val scanner: SyncSourceScanner) {
        val backoffUntil: Long
            get() = maxOf(zen.backoffUntil, firefox.backoffUntil, scanner.backoffUntil)
    }

    /** How a sync went: what stopped it, if anything, and what the account has of Firefox and Zen when it looked. */
    private class Outcome(val problem: DejavuSyncProblem?, val found: FoundSyncData? = null)

    /**
     * Syncs once with [source], after looking at what the account has of Firefox and Zen when [scan] is set. Without a
     * source, it only looks. The tabs to forget, see [forgetTabs], are forgotten once a sync took them into account.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun sync(
        engines: Engines,
        account: OAuthAccount,
        source: DejavuSyncSource?,
        scan: Boolean,
        turnOn: Boolean,
    ): Outcome {
        val context = appContext ?: return Outcome(null)
        val forgotten = forgottenTabs(context)
        val conflicts = conflictsInARow
        conflictsInARow = 0
        return try {
            val auth = authOf(account) ?: throw SyncAuthException("The account has no sync key")
            val connected = connectedDevices(account)
            // The account's device list comes over the network a while after the app starts; until then, the account
            // still knows this device's id, without which this device would show as another phone.
            val currentDevice = account.deviceConstellation().state()?.currentDevice
            val localDeviceId = currentDevice?.id ?: account.getCurrentDeviceId()
            val localName = currentDevice?.displayName
            val refreshDevices: suspend (Set<String>) -> Set<String>? = { unknown -> refreshedDevices(account, unknown) }
            val found = if (scan) {
                when (val result = engines.scanner.scan(auth, connected, localDeviceId, localName, refreshDevices)) {
                    ScanResult.NotSetUp -> return Outcome(DejavuSyncProblem.NOT_SET_UP)
                    is ScanResult.Found -> result.data.also { logScan(it) }
                }
            } else {
                null
            }
            val problem = when (source) {
                null -> {
                    stopForgetting(context)
                    DejavuSyncProblem.CHOOSE_SOURCE
                }
                DejavuSyncSource.ZEN -> {
                    val result = engines.zen.sync(auth, turnOn)
                    if (result is SpacesSyncResult.Synced) stopForgetting(context, forgotten)
                    followUnpinnedTabs(context, result)
                    problemOf(result)
                }
                DejavuSyncSource.FIREFOX -> {
                    val result = engines.firefox.sync(auth, connected, localDeviceId, localName, refreshDevices)
                    computerTabs = engines.firefox.computerTabs()
                    if (result is FirefoxSyncResult.Synced) stopForgetting(context, forgotten)
                    problemOf(result)
                }
            }
            Outcome(problem, found)
        } catch (e: SyncConflictException) {
            conflictsInARow = conflicts + 1
            if (conflictsInARow <= MAX_CONFLICT_RETRIES) {
                logger.info("Changed elsewhere during the sync, syncing again", e)
                scope.launch {
                    delay(CONFLICT_RETRY_MS)
                    request()
                }
            } else {
                // Leaves it to the next regular sync rather than asking the servers every few seconds.
                logger.warn("Changed elsewhere during $conflictsInARow syncs in a row, waiting for the next sync", e)
            }
            Outcome(null)
        } catch (e: SyncAuthException) {
            logger.warn("Sync refused the account", e)
            Outcome(DejavuSyncProblem.SIGN_IN_AGAIN)
        } catch (e: SyncServerException) {
            logger.warn("Sync server error ${e.status}", e)
            Outcome(DejavuSyncProblem.SERVER)
        } catch (e: IOException) {
            logger.warn("Sync servers not reachable", e)
            Outcome(DejavuSyncProblem.OFFLINE)
        } catch (e: Exception) {
            logger.error("Workspace sync failed", e)
            Outcome(DejavuSyncProblem.SERVER)
        }
    }

    private fun logScan(found: FoundSyncData) {
        val zen = found.zen?.let { "${it.spaces} spaces, sidebar sync ${if (it.spacesSyncOn) "on" else "off"}" } ?: "none"
        logger.info("Found ${found.firefox.size} Firefox computers, Zen: $zen")
    }

    /** Ids of the account's devices, or `null` while they are not known. */
    private fun connectedDevices(account: OAuthAccount): Set<String>? =
        account.deviceConstellation().state()?.otherDevices?.map { it.id }?.toSet()?.takeIf { it.isNotEmpty() }

    /**
     * Asks the account for its devices again, because of the [unknown] ones, and returns their ids, or `null` when it
     * could not, at most once a minute. The account tells about a device that joined it only by push messages, which
     * Dejavu may not get. Devices still unknown after that left the account; their records stay a while, and do not
     * make Dejavu ask again.
     */
    private suspend fun refreshedDevices(account: OAuthAccount, unknown: Set<String>): Set<String>? {
        if (stillUnknownDevices.containsAll(unknown)) return null
        val now = SystemClock.elapsedRealtime()
        if (devicesRefreshedAt != 0L && now - devicesRefreshedAt < DEVICES_REFRESH_MS) return null
        devicesRefreshedAt = now
        logger.info("A device is not among the account's devices, asking the account again")
        if (!account.deviceConstellation().refreshDevices()) return null
        val devices = connectedDevices(account)
        stillUnknownDevices = unknown - devices.orEmpty()
        return devices
    }

    private fun problemOf(result: SpacesSyncResult): DejavuSyncProblem? = when (result) {
        is SpacesSyncResult.Synced -> {
            logger.info("Synced: ${result.received} in, ${result.sent} out, ${result.pending} waiting")
            null
        }
        SpacesSyncResult.NotSetUp -> DejavuSyncProblem.NOT_SET_UP
        SpacesSyncResult.NotTurnedOn -> DejavuSyncProblem.NOT_TURNED_ON
        is SpacesSyncResult.NeedsUpdate -> DejavuSyncProblem.NEEDS_UPDATE
    }

    private fun problemOf(result: FirefoxSyncResult): DejavuSyncProblem? = when (result) {
        is FirefoxSyncResult.Synced -> DejavuSyncProblem.NO_FIREFOX.takeIf { result.computers.isEmpty() }
        FirefoxSyncResult.NotSetUp -> DejavuSyncProblem.NOT_SET_UP
        FirefoxSyncResult.TabsOff -> DejavuSyncProblem.TABS_OFF
    }

    private suspend fun authOf(account: OAuthAccount): SyncAuth? {
        val token = account.getAccessToken(SCOPE_SYNC) ?: return null
        val key = token.key ?: return null
        val tokenServer = account.getTokenServerEndpointURL() ?: return null
        return SyncAuth(kid = key.kid, accessToken = token.token, syncKey = key.k, tokenServerUrl = tokenServer)
    }

    private val accountObserver = object : AccountObserver {
        override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
            _status.update { it.copy(signedIn = true, problem = null) }
            request()
        }

        override fun onLoggedOut() {
            // The next account may sync other browsers, so it is asked again which one to follow.
            appContext?.let { context ->
                prefs(context).edit {
                    remove(KEY_SOURCE)
                    remove(KEY_SOURCE_PUT_OFF)
                }
                stopForgetting(context)
            }
            sourcePutOff = false
            _status.update {
                it.copy(signedIn = false, problem = null, lastSynced = 0L, source = null, found = null, askSource = false)
            }
            scope.launch {
                mutex.withLock {
                    engine?.reset()
                    firefoxEngine?.let { firefox ->
                        runCatching { firefox.removeAll() }.onFailure { logger.warn("Could not remove Firefox", it) }
                        firefox.reset()
                    }
                    computerTabs = ComputerTabs.NONE
                    scanner?.reset()
                }
            }
        }

        override fun onAuthenticationProblems() {
            _status.update { it.copy(problem = DejavuSyncProblem.SIGN_IN_AGAIN) }
        }
    }

    private val syncObserver = object : SyncStatusObserver {
        override fun onStarted() = Unit

        override fun onIdle() = request()

        override fun onError(error: Exception?) = request()
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            pollJob?.cancel()
            pollJob = scope.launch {
                while (isActive) {
                    request()
                    delay(FOREGROUND_POLL_MS)
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            pollJob?.cancel()
            pollJob = null
            scope.launch { if (hasLocalChanges()) request() }
        }
    }
}

/** Dejavu's workspaces, containers and open tabs, as [SpacesSyncEngine] and [FirefoxTabsEngine] read and change them. */
private class DejavuSpacesData(private val context: Context) : SpacesLocalData, FirefoxLocalData {
    private val repository: WorkspaceRepository
        get() = WorkspaceRepository.get(context)

    private val containers: DejavuContainerStorage
        get() = DejavuContainerStorage.get(context)

    override val defaultWorkspaceName: String = context.getString(R.string.dejavu_workspace_default_name)

    override val unnamedGroup: String = context.getString(R.string.dejavu_sync_unnamed_group)

    override suspend fun closeRemoteTabs(deviceId: String, urls: List<String>) {
        context.components.backgroundServices.syncedTabsCommands.add(deviceId, DeviceCommandOutgoing.CloseTab(urls))
    }

    override suspend fun sendTab(deviceId: String, url: String, title: String): Boolean {
        val account = context.components.backgroundServices.accountManager.authenticatedAccount() ?: return false
        return account.deviceConstellation().sendCommandToDevice(
            deviceId,
            DeviceCommandOutgoing.SendTab(title.ifBlank { url }, url, TabPrivacy.Normal),
        )
    }

    override suspend fun read(): LocalSpaces {
        containers.load()
        val browser = context.components.core.store.state
        val tabs = browser.takeIf { it.restoreComplete }?.normalTabs?.map { tab ->
            LocalTab(
                id = tab.id,
                url = tab.content.url,
                title = tab.content.title,
                contextId = tab.contextId,
                awake = tab.engineState.engineSession != null,
                createdAt = tab.createdAt,
                loading = tab.content.loading,
            )
        }
        return LocalSpaces(
            state = repository.state.value,
            containers = containers.records.value.orEmpty(),
            normalTabs = DejavuSync.status.value.normalTabs,
            tabs = tabs,
            forgotten = DejavuSync.forgottenTabs(context),
        )
    }

    /** Removes the examples a fresh install started with that are still as they came, see [withoutStarters]. */
    fun dropStarters() = repository.dropStarters()

    /**
     * Closes the open tabs and removes the workspaces with their pinned tabs and folders, and the essentials, see
     * [WorkspaceRepository.startFresh]. Private tabs stay, and so do the workspaces and tabs of devices on the account,
     * as closing those would close them on the devices. Returns once the browser closed the tabs.
     */
    suspend fun startFresh() {
        val store = context.components.core.store
        val state = repository.state.value
        val closing = store.state.normalTabs.map { it.id }.filterNot { state.isDeviceWorkspace(state.workspaceOf(it)) }
        repository.startFresh()
        if (closing.isEmpty()) return
        withContext(Dispatchers.Main) { context.components.useCases.tabsUseCases.removeTabs(closing) }
        // The first sync must not find them open still, or it would sync them.
        withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
            store.stateFlow.first { browser -> closing.none { browser.findTab(it) != null } }
        }
    }

    override suspend fun runTabOps(ops: List<TabOp>) = withContext(Dispatchers.Main) {
        val store = context.components.core.store
        for (op in ops) {
            when (op) {
                is TabOp.Open -> {
                    if (store.state.findTab(op.id) != null) continue
                    repository.expectTab(op.id)
                    val contextId = if (op.containerRemoved) {
                        val temporary = DejavuSettings.get(context).temporaryContainersByDefault.value
                        TemporaryContainers.orReplacement(op.contextId, store, containers, temporary)
                    } else {
                        op.contextId
                    }
                    store.dispatch(
                        TabListAction.AddTabAction(
                            createTab(url = op.url, id = op.id, title = op.title, contextId = contextId),
                            select = false,
                        ),
                    )
                    repository.assignTab(op.id, op.workspaceId)
                }
                is TabOp.Retarget -> retarget(store, op)
                is TabOp.Close -> if (store.state.findTab(op.id) != null) {
                    SilentlyClosedTabs.add(op.id)
                    store.dispatch(TabListAction.RemoveTabAction(op.id, selectParentIfExists = false))
                }
            }
        }
    }

    /**
     * Replaces tab [TabOp.Retarget.id], whose page is not loaded, with one at the new address under the same id and in
     * the same place, as a tab keeps the history it was restored with. A tab that is shown or in a split view stays.
     */
    private fun retarget(store: BrowserStore, op: TabOp.Retarget) {
        val state = store.state
        val tab = state.findTab(op.id) ?: return
        val splits = repository.state.value
        if (tab.engineState.engineSession != null || state.selectedTabId == op.id || splits.splitOf(op.id) != null) return
        val index = state.tabs.indexOf(tab)
        val before = state.tabs.getOrNull(index - 1)
        val after = state.tabs.getOrNull(index + 1)
        repository.expectTab(op.id)
        SilentlyClosedTabs.add(op.id)
        store.dispatch(TabListAction.RemoveTabAction(op.id, selectParentIfExists = false))
        store.dispatch(
            TabListAction.AddTabAction(
                createTab(
                    url = op.url,
                    id = op.id,
                    title = op.title,
                    contextId = tab.contextId,
                    lastAccess = tab.lastAccess,
                    createdAt = tab.createdAt,
                ),
                select = false,
            ),
        )
        when {
            before != null -> store.dispatch(TabListAction.MoveTabsAction(listOf(op.id), before.id, placeAfter = true))
            after != null -> store.dispatch(TabListAction.MoveTabsAction(listOf(op.id), after.id, placeAfter = false))
        }
    }

    override fun <T> update(transform: (WorkspaceState) -> Pair<WorkspaceState, T>): T = repository.update(transform)

    override suspend fun saveContainer(
        contextId: String,
        name: String,
        color: ContainerColor,
        icon: ContainerState.Icon,
    ) = containers.saveContainer(contextId, name, color, icon)

    override suspend fun removeContainer(contextId: String) {
        val record = containers.records.value?.firstOrNull { it.contextId == contextId } ?: return
        withContext(Dispatchers.Main) {
            // Forgotten rather than closed by the user, so nothing closes on the other devices for them: the tabs the
            // other device keeps come back with a later sync.
            val state = repository.state.value
            val unpinned = context.components.core.store.state.tabs.filter { tab ->
                tab.contextId == contextId && !tab.content.private && state.pinOf(tab.id) == null
            }
            DejavuSync.forgetTabs(context, unpinned.map { state.syncIdOf(it.id) })
            ContainerRemover(context.components, repository).remove(record, ContainerRemoval.FollowSync)
        }
    }

    override fun useEssentialsPerContainer(enabled: Boolean) =
        DejavuSettings.get(context).setEssentialsPerContainerFromSync(enabled)

    override fun builtinName(container: BuiltinContainer): String = context.getString(container.label)

    private companion object {
        const val CLOSE_TIMEOUT_MS = 5_000L
    }
}
