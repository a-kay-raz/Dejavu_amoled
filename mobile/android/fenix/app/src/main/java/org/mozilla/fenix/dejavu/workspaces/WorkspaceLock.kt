/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.lib.state.ext.flow
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.ext.components

/**
 * When the locked workspaces the user unlocked lock again, chosen in the privacy settings.
 *
 * @property key How the choice is saved.
 * @property awayMillis How long Dejavu has to be left before they lock, 0 for as soon as it is left, or `null` for
 *   only once it closes.
 */
enum class WorkspaceLockTime(val key: String, val awayMillis: Long?) {
    /** When the user switches to another workspace, and as soon as they leave Dejavu. */
    SWITCH("switch", 0L),

    /** As soon as the user leaves Dejavu. */
    LEAVE("leave", 0L),
    ONE_MINUTE("1m", 60_000L),
    FIVE_MINUTES("5m", 5 * 60_000L),
    THIRTY_MINUTES("30m", 30 * 60_000L),

    /** Only once Dejavu closes, as the unlocked workspaces are only kept in memory. */
    CLOSE("close", null),
    ;

    companion object {
        val DEFAULT = LEAVE

        fun fromKey(key: String?): WorkspaceLockTime = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Which locked workspaces, see [Workspace.locked], the user unlocked, and when they lock again, as [WorkspaceLockTime]
 * says. Only kept in memory, so every locked workspace is locked again whenever Dejavu starts.
 */
object WorkspaceLock {
    private val _unlocked = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of the locked workspaces the user unlocked, which show until they lock again. */
    val unlocked: StateFlow<Set<String>> = _unlocked.asStateFlow()

    private val _privateHidden = MutableStateFlow(false)

    /**
     * Whether the private tabs show blurred until the user shows them again, which asks for nothing: they hide when the
     * unlocked workspaces lock, after Dejavu was left, and as the user turns to another workspace, while Firefox's lock
     * of private tabs is off. With it on, they lock instead.
     */
    val privateHidden: StateFlow<Boolean> = _privateHidden.asStateFlow()

    /** Whether leaving Dejavu hides the private tabs: some are open, and Firefox's lock of private tabs is off. */
    @VisibleForTesting
    internal var privateTabsHide: () -> Boolean = { false }

    /** Whether a prompt to unlock shows, which can leave Dejavu for the screen lock for a while. */
    @Volatile
    var prompting = false

    // When Dejavu was left, while the unlocked workspaces wait for WorkspaceLockTime.awayMillis to pass.
    @Volatile
    private var leftAt: Long? = null

    // The workspaces asked about by themselves since they last locked or were left, see askOnce. Main thread only.
    private val asked = mutableSetOf<String>()
    private var watching = false

    /** Whether [workspace] is locked and not unlocked, so its page shows blurred and its tabs stay covered. */
    fun hides(workspace: Workspace?, unlocked: Set<String> = this.unlocked.value): Boolean =
        workspace != null && workspace.locked && workspace.id !in unlocked

    /**
     * The locked workspace tab [tabId] is in, unless it is unlocked: the workspace of its pin, the one it was put in, or
     * for a new tab not put in one yet, the workspace shown, which the home screen puts it in. Essentials show in every
     * workspace, so their tabs are never hidden.
     */
    fun hiddenWorkspaceOf(state: WorkspaceState, tabId: String, unlocked: Set<String> = this.unlocked.value): Workspace? {
        val pin = state.pinOf(tabId)
        if (pin?.essential == true) return null
        val workspaceId = pin?.workspaceId ?: state.workspaceOf(tabId)
        return state.workspaces.firstOrNull { it.id == workspaceId }?.takeIf { hides(it, unlocked) }
    }

    fun unlock(workspaceId: String) = _unlocked.update { it + workspaceId }

    fun lock(workspaceId: String) {
        _unlocked.update { it - workspaceId }
        asked.remove(workspaceId)
    }

    fun lockAll() {
        _unlocked.value = emptySet()
        asked.clear()
        hidePrivate()
    }

    /** Hides the private tabs, see [privateHidden], when some are open and Firefox's lock of private tabs is off. */
    fun hidePrivate() {
        if (privateTabsHide()) _privateHidden.value = true
    }

    /** Shows the private tabs again, see [privateHidden]. */
    fun showPrivate() {
        _privateHidden.value = false
    }

    /**
     * Whether to ask to unlock [key], a workspace or the private tabs, by itself as it shows: only once until it locks
     * again, the user leaves it, see [left], or the user leaves Dejavu, and never while a prompt already shows.
     */
    fun askOnce(key: String): Boolean = !prompting && asked.add(key)

    /** The user left [key], so it is asked about by itself again when it shows next. */
    fun left(key: String) {
        asked.remove(key)
    }

    /**
     * Locks the unlocked workspaces when [WorkspaceLockTime] says, and keeps [activity] out of the recent apps view
     * while any is unlocked or private tabs are open, unless screenshots are allowed in private browsing, from Android
     * 13 on, which leaves screenshots alone. The secure window of private browsing comes and goes with what shows, so
     * the recent apps view could catch a private tab as Dejavu was left. Called as [activity] is created.
     */
    fun watch(activity: ComponentActivity) {
        val context = activity.applicationContext
        if (!watching) {
            watching = true
            val process = ProcessLifecycleOwner.get()
            process.lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) = onLeftDejavu(lockTime())
                    override fun onStart(owner: LifecycleOwner) = onBackInDejavu(lockTime())
                },
            )
            process.lifecycleScope.launch { lockOnSwitch(context) }
        }
        val components = activity.components
        privateTabsHide = {
            components.core.store.state.privateTabs.isNotEmpty() && !components.settings.privateBrowsingModeLocked
        }
        // Private tabs opened after the last ones closed show as they are.
        activity.lifecycleScope.launch {
            components.core.store.flow().map { it.privateTabs.isEmpty() }.distinctUntilChanged().collect { none ->
                if (none) showPrivate()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val privateTabsOpen = components.core.store.flow().map { it.privateTabs.isNotEmpty() }.distinctUntilChanged()
            activity.lifecycleScope.launch {
                combine(unlocked, privateTabsOpen) { unlockedIds, hasPrivateTabs ->
                    unlockedIds.isEmpty() && (!hasPrivateTabs || components.settings.shouldSecureModeBeOverridden)
                }.distinctUntilChanged().collect { activity.setRecentsScreenshotEnabled(it) }
            }
        }
    }

    private fun lockTime(): WorkspaceLockTime = DejavuSettings.peek()?.workspaceLockTime?.value ?: WorkspaceLockTime.DEFAULT

    private fun onLeftDejavu(time: WorkspaceLockTime) {
        // The screen lock asked for to unlock is a screen of its own, which leaves Dejavu until the user is back.
        if (prompting) return
        // Coming back to a locked workspace asks again.
        asked.clear()
        when (time.awayMillis) {
            null -> Unit
            0L -> lockAll()
            else -> leftAt = SystemClock.elapsedRealtime()
        }
    }

    private fun onBackInDejavu(time: WorkspaceLockTime) {
        val since = leftAt ?: return
        leftAt = null
        val away = time.awayMillis ?: return
        if (SystemClock.elapsedRealtime() - since >= away) lockAll()
    }

    /** Locks a workspace once another one is shown, while [WorkspaceLockTime.SWITCH] is chosen. */
    private suspend fun lockOnSwitch(context: Context) {
        val repository = withContext(Dispatchers.IO) { WorkspaceRepository.get(context) }
        var previous: String? = null
        repository.state.map { it.activeWorkspaceId }.distinctUntilChanged().collect { active ->
            previous?.takeIf { it != active && lockTime() == WorkspaceLockTime.SWITCH }?.let(::lock)
            previous = active
        }
    }
}
