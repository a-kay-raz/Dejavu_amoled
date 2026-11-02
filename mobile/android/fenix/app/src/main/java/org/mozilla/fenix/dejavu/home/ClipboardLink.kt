/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.ViewTreeObserver
import android.view.textclassifier.TextClassifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import mozilla.components.support.ktx.kotlin.isUrl
import mozilla.components.support.utils.ClipboardHandler

/**
 * Whether the clipboard holds a link, looked at again whenever Dejavu comes back to the front or the clipboard changes.
 * On Android 12 and newer it only looks at what the system found in the clipboard, without reading it, which would
 * make Android tell the user that Dejavu pasted from the clipboard.
 */
@Composable
internal fun rememberClipboardHasLink(): Boolean {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The clipboard listener is held by a native system stub, so it is
    // registered only while Home is resumed and removed on pause, before the
    // activity can be destroyed, not only when composition is disposed.
    val clipboard = remember(context) { context.getSystemService<ClipboardManager>() }
    var hasLink by remember { mutableStateOf(false) }
    var checks by remember { mutableIntStateOf(0) }

    DisposableEffect(clipboard, lifecycle, view) {
        val treeObserver = view.viewTreeObserver
        val onClipChanged = ClipboardManager.OnPrimaryClipChangedListener { checks++ }
        var isRegistered = false
        val register = {
            if (!isRegistered) {
                clipboard?.addPrimaryClipChangedListener(onClipChanged)
                isRegistered = true
            }
        }
        val unregister = {
            if (isRegistered) {
                clipboard?.removePrimaryClipChangedListener(onClipChanged)
                isRegistered = false
            }
        }
        val onLifecycle = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    register()
                    checks++
                }
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> {}
            }
        }
        val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) checks++ }
        lifecycle.addObserver(onLifecycle)
        treeObserver.addOnWindowFocusChangeListener(onFocus)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }
        onDispose {
            unregister()
            lifecycle.removeObserver(onLifecycle)
            treeObserver.removeOnWindowFocusChangeListener(onFocus)
        }
    }

    // The system finds the links in a new clip a moment after it was copied.
    LaunchedEffect(checks) {
        repeat(CLASSIFICATION_TRIES) {
            val found = clipboard?.linkState(context)
            if (found != null) {
                hasLink = found
                return@LaunchedEffect
            }
            delay(CLASSIFICATION_WAIT_MS)
        }
        hasLink = false
    }
    return hasLink
}

/** Whether the clip holds a link, or `null` while the system is still looking at it. */
private fun ClipboardManager.linkState(context: Context): Boolean? {
    val description = primaryClipDescription ?: return false
    if (TEXT_TYPES.none { description.hasMimeType(it) }) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return ClipboardHandler(context).extractURL()?.isUrl() == true
    }
    return when (description.classificationStatus) {
        ClipDescription.CLASSIFICATION_COMPLETE ->
            runCatching { description.getConfidenceScore(TextClassifier.TYPE_URL) >= URL_CONFIDENCE }.getOrDefault(false)
        ClipDescription.CLASSIFICATION_NOT_COMPLETE -> null
        // A clip the system does not look at, like a very long text, keeps the button usable.
        else -> true
    }
}

private val TEXT_TYPES = listOf(
    ClipDescription.MIMETYPE_TEXT_PLAIN,
    ClipDescription.MIMETYPE_TEXT_HTML,
    ClipDescription.MIMETYPE_TEXT_URILIST,
    "text/x-moz-url",
)
private const val URL_CONFIDENCE = 0.7f
private const val CLASSIFICATION_TRIES = 10
private const val CLASSIFICATION_WAIT_MS = 300L
