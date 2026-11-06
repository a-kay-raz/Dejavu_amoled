/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.InitAction
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository

/**
 * Puts tabs to sleep that were not looked at for a while, so their pages stop taking memory; they load again when
 * opened. Firefox for Android never unloads tabs by itself, so every tab opened since the browser
 * started otherwise stays loaded until Android runs out of memory and kills the whole browser. When more tabs are awake
 * than [DejavuSettings.awakeTabLimit], the ones used longest ago sleep. When Dejavu is closed for good ([closed]), or
 * Android runs low on memory, all tabs but the most recent ones sleep at once. Going to the home screen or another app
 * leaves them awake.
 *
 * The shown tab, the tab next to it in split view, tabs still loading and tabs playing media or using the camera or
 * microphone never sleep. [DejavuSettings.tabSleepMinutes] sets the wait, or turns sleeping after it off.
 */
internal class TabSleepMiddleware : Middleware<BrowserState, BrowserAction> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null
    private var observing = false

    // When each tab stopped being the shown one. A tab's own last access is when it was last selected, so a tab read
    // for an hour would otherwise count as unused from the moment it is left.
    private val lastShown = ConcurrentHashMap<String, Long>()

    init {
        current = this
    }

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        val shownBefore = store.state.selectedTabId
        next(action)
        val shownAfter = store.state.selectedTabId
        if (shownBefore != null && shownBefore != shownAfter) lastShown[shownBefore] = System.currentTimeMillis()

        when (action) {
            is InitAction -> startTicker(store)
            is SystemAction.LowMemoryAction -> if (isLowOnMemory(action.level)) sleepAllButRecent(store)
            // A tab woke up, or one that could not sleep before now can.
            is EngineAction.LinkEngineSessionAction -> sleepOverLimit(store, alsoKeep = action.tabId)
            is TabListAction.SelectTabAction -> sleepOverLimit(store)
            is ContentAction.UpdateLoadingStateAction -> if (!action.loading) sleepOverLimit(store)
            else -> Unit
        }
    }

    /**
     * Puts the tabs used longest ago to sleep while more are awake than the limit allows. [alsoKeep] stays awake too:
     * a tab that just woke up, which something other than the user may have woken.
     */
    private fun sleepOverLimit(store: Store<BrowserState, BrowserAction>, alsoKeep: String? = null) {
        val limit = awakeLimit()
        if (limit <= 0) return
        val keep = keptAwake(store.state) + setOfNotNull(alsoKeep)
        sleep(store, TabSleep.tabsOverLimit(store.state, lastShown, keep, limit))
    }

    /**
     * Checks for idle tabs once a minute, only while the app is shown. In the background nothing is opened, and low
     * memory handling above does not need the ticker.
     */
    private fun startTicker(store: Store<BrowserState, BrowserAction>) {
        if (observing) return
        observing = true
        // addObserver must run on the main thread. The callbacks run there too, so [ticker] is only used there.
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        ticker?.cancel()
                        ticker = tick(store)
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        ticker?.cancel()
                        ticker = null
                    }
                },
            )
        }
    }

    private fun tick(store: Store<BrowserState, BrowserAction>): Job =
        scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                val minutes = sleepMinutes()
                lastShown.keys.retainAll(store.state.tabs.mapTo(HashSet()) { it.id })
                val idle = if (minutes > 0) {
                    TabSleep.idleTabs(
                        state = store.state,
                        lastShown = lastShown,
                        keep = keptAwake(store.state),
                        now = System.currentTimeMillis(),
                        idleMillis = minutes * MILLIS_PER_MINUTE,
                    )
                } else {
                    emptyList()
                }
                // The limit is checked here too, for a tab that stopped playing, and after the limit was lowered.
                if (idle.isNotEmpty() || awakeLimit() > 0) {
                    withContext(Dispatchers.Main) {
                        sleep(store, idle)
                        sleepOverLimit(store)
                    }
                }
            }
        }

    /**
     * Whether Android runs low on memory, which only Android 13 and older say.
     * [ComponentCallbacks2.TRIM_MEMORY_BACKGROUND] is not enough: Android 14 and newer send it every time Dejavu goes
     * to the home screen or to another app.
     */
    @Suppress("DEPRECATION")
    private fun isLowOnMemory(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL

    private fun sleepAllButRecent(store: Store<BrowserState, BrowserAction>) {
        if (sleepMinutes() > 0 || awakeLimit() > 0) {
            sleep(store, TabSleep.tabsForMemory(store.state, lastShown, keptAwake(store.state)))
        }
    }

    private fun sleep(store: Store<BrowserState, BrowserAction>, tabIds: List<String>) {
        tabIds.forEach { store.dispatch(EngineAction.SuspendEngineSessionAction(it)) }
    }

    private fun sleepMinutes(): Int =
        DejavuSettings.peek()?.tabSleepMinutes?.value ?: DejavuSettings.DEFAULT_TAB_SLEEP_MINUTES

    private fun awakeLimit(): Int =
        DejavuSettings.peek()?.awakeTabLimit?.value ?: DejavuSettings.DEFAULT_AWAKE_TAB_LIMIT

    /** The shown tab and the tab shown next to it in split view. */
    private fun keptAwake(state: BrowserState): Set<String> {
        val shown = state.selectedTabId ?: return emptySet()
        val split = WorkspaceRepository.peek()?.state?.value?.splitOf(shown)?.tabIds.orEmpty()
        return split.toSet() + shown
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val MILLIS_PER_MINUTE = 60_000L

        private var current: TabSleepMiddleware? = null

        /**
         * Dejavu was closed for good: swiped away from the recent apps while something kept it running, or left in a
         * way that ends it. All tabs but the most recent ones sleep, as when memory runs low.
         */
        fun closed(store: Store<BrowserState, BrowserAction>) {
            current?.sleepAllButRecent(store)
        }
    }
}

/** Which tabs [TabSleepMiddleware] puts to sleep. */
internal object TabSleep {
    /** How many tabs stay awake, besides the shown one, when Dejavu closes or memory runs low. */
    const val KEEP_RECENT_FOR_MEMORY = 2

    /** Awake tabs not looked at for [idleMillis], leaving out [keep] and tabs that must stay awake. */
    fun idleTabs(
        state: BrowserState,
        lastShown: Map<String, Long>,
        keep: Set<String>,
        now: Long,
        idleMillis: Long,
    ): List<String> = sleepable(state, keep)
        .filter { now - lastUsed(it, lastShown) >= idleMillis }
        .map { it.id }

    /**
     * The awake tabs used longest ago, as many as are awake beyond [limit], leaving out [keep] and tabs that must stay
     * awake, which still count.
     */
    fun tabsOverLimit(state: BrowserState, lastShown: Map<String, Long>, keep: Set<String>, limit: Int): List<String> {
        val over = state.tabs.count { it.engineState.engineSession != null } - limit
        if (over <= 0) return emptyList()
        return sleepable(state, keep).sortedBy { lastUsed(it, lastShown) }.take(over).map { it.id }
    }

    /** Every awake tab but the [KEEP_RECENT_FOR_MEMORY] most recently looked at, leaving out [keep]. */
    fun tabsForMemory(state: BrowserState, lastShown: Map<String, Long>, keep: Set<String>): List<String> =
        sleepable(state, keep)
            .sortedByDescending { lastUsed(it, lastShown) }
            .drop(KEEP_RECENT_FOR_MEMORY)
            .map { it.id }

    private fun sleepable(state: BrowserState, keep: Set<String>): List<TabSessionState> = state.tabs.filter { tab ->
        tab.engineState.engineSession != null &&
            tab.id !in keep &&
            !tab.content.loading &&
            tab.content.recordingDevices.isEmpty() &&
            tab.mediaSessionState?.playbackState != MediaSession.PlaybackState.PLAYING
    }

    private fun lastUsed(tab: TabSessionState, lastShown: Map<String, Long>): Long =
        maxOf(tab.lastAccess, tab.createdAt, lastShown[tab.id] ?: 0L)
}
