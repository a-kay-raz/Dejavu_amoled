/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.app.Activity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.activity.OrientationDelegate
import mozilla.components.concept.engine.activity.OrientationDelegate.LockResult
import mozilla.components.feature.session.ScreenOrientationFeature
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature

/**
 * Turns the screen as pages ask with `screen.orientation.lock()`, like [ScreenOrientationFeature], but only while a tab
 * is fullscreen: a page may lock the screen only then, and Firefox gives the lock back when the page leaves fullscreen.
 *
 * Firefox can also keep a lock on a tab that never took effect. m.youtube.com asks for landscape right after asking
 * for fullscreen, before the fullscreen request went through; when that request fails (two requests at once, the
 * second one refused), the tab keeps the landscape lock, and Firefox locks the screen again each time the tab becomes
 * active. Dejavu then came back sideways without fullscreen, and stayed sideways until it was restarted (issue #290).
 * Such locks no longer turn the screen, and a lock still held when no tab is fullscreen any more is given back.
 */
class FullscreenOrientationFeature(
    private val engine: Engine,
    activity: Activity,
    private val store: BrowserStore,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : LifecycleAwareFeature, OrientationDelegate {
    private val screenOrientation = ScreenOrientationFeature(engine, activity)
    private var scope: CoroutineScope? = null
    private var pendingLock: Job? = null
    private var locked = false

    override fun start() {
        engine.registerScreenOrientationDelegate(this)
        scope = store.flowScoped(dispatcher = mainDispatcher) { flow ->
            flow.map { it.hasFullscreenTab() }
                .distinctUntilChanged()
                .collect { fullscreen -> if (!fullscreen && locked) onOrientationUnlock() }
        }
    }

    override fun stop() {
        engine.unregisterScreenOrientationDelegate()
        scope?.cancel()
    }

    override fun onOrientationLock(requestedOrientation: Int): LockResult {
        pendingLock?.cancel()
        if (store.state.hasFullscreenTab()) return lock(requestedOrientation)
        // The engine hands the tab's fullscreen to the store on a later turn of the main thread, so a lock can come
        // first: it is looked at again once the store has it.
        pendingLock = scope?.launch { if (store.state.hasFullscreenTab()) lock(requestedOrientation) }
        return LockResult.SUCCESS
    }

    override fun onOrientationUnlock() {
        pendingLock?.cancel()
        locked = false
        screenOrientation.onOrientationUnlock()
    }

    private fun lock(requestedOrientation: Int) =
        screenOrientation.onOrientationLock(requestedOrientation).also { locked = it == LockResult.SUCCESS }
}

private fun BrowserState.hasFullscreenTab() =
    tabs.any { it.content.fullScreen } || customTabs.any { it.content.fullScreen }
