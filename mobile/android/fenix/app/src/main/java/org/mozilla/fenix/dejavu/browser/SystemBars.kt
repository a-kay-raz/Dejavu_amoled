/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.app.Activity
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.view.Window
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.EngineView

private const val HIDE_AGAIN_LISTENER = "dejavu_system_bars"

/** Whether [hideSystemBarsOverPages] keeps the status bar and the navigation bar hidden right now. */
private var hiddenOverPages = false

/**
 * Hides the status bar and the navigation bar over the pages of [this] browser screen while it shows and [hidden] is
 * set; swiping in from an edge of the screen shows them for a moment. When something shows them again, they are hidden
 * again, while a video shown full screen hides them its own way. Dejavu's bars keep clear of the system bars only while
 * they show, see [statusBarHeight], so no room stays empty for a hidden bar.
 */
fun Fragment.hideSystemBarsOverPages(engineView: EngineView, hidden: StateFlow<Boolean>) {
    val window = requireActivity().window
    var barsShown = false
    val hideAgain = OnApplyWindowInsetsListener { _, insets ->
        // Only once each time they come back, in case the system keeps them, as in split screen.
        val shown = insets.showsSystemBars()
        if (shown && !barsShown && !window.isFullScreen) window.hideSystemBars()
        barsShown = shown
        insets
    }
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                hidden.collect { hide ->
                    if (hide == hiddenOverPages) return@collect
                    hiddenOverPages = hide
                    if (hide) engineView.addWindowInsetsListener(HIDE_AGAIN_LISTENER, hideAgain)
                    if (!hide) engineView.removeWindowInsetsListener(HIDE_AGAIN_LISTENER)
                    if (window.isFullScreen) return@collect
                    if (hide) window.hideSystemBars() else window.showSystemBars()
                }
            } finally {
                if (hiddenOverPages) {
                    hiddenOverPages = false
                    engineView.removeWindowInsetsListener(HIDE_AGAIN_LISTENER)
                    if (!window.isFullScreen) window.showSystemBars()
                }
            }
        }
    }
}

/**
 * Leaves a video's full screen with [exitImmersiveMode], which shows the system bars. While they are hidden over
 * pages, they are hidden again at once, and left alone when no video was full screen, as on every tab switch.
 */
fun Activity.exitFullScreenKeepingBarsHidden(exitImmersiveMode: () -> Unit) {
    // Before Android 9 a video's full screen leaves no mark on the window to tell it from a tab switch.
    if (!hiddenOverPages || window.isFullScreen || SDK_INT < VERSION_CODES.P) exitImmersiveMode()
    if (hiddenOverPages) window.hideSystemBars()
}

/** Keeps [this] below the status bar while it shows, and below a camera cutout. */
fun Modifier.statusBarRoomPadding(): Modifier = composed {
    windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
}

/** Keeps [this] above the navigation bar while it shows, leaving no room for it while it is hidden over pages. */
fun Modifier.navigationBarRoomPadding(): Modifier = composed {
    windowInsetsPadding(WindowInsets.navigationBars)
}

private fun Window.hideSystemBars() = WindowCompat.getInsetsController(this, decorView).run {
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    hide(WindowInsetsCompat.Type.systemBars())
}

private fun Window.showSystemBars() =
    WindowCompat.getInsetsController(this, decorView).show(WindowInsetsCompat.Type.systemBars())

/** Whether the status bar or the navigation bar shows for good, not only along with the keyboard. */
private fun WindowInsetsCompat.showsSystemBars(): Boolean =
    !isVisible(WindowInsetsCompat.Type.ime()) &&
        (isVisible(WindowInsetsCompat.Type.statusBars()) || isVisible(WindowInsetsCompat.Type.navigationBars()))
