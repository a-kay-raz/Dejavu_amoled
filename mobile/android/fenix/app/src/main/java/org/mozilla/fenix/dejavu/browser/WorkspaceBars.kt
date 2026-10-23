/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.app.Activity
import android.content.res.Configuration
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.StrictMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.lib.state.ext.flow
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.base.feature.LifecycleAwareFeature
import org.mozilla.fenix.dejavu.home.GRAIN_STRENGTH
import org.mozilla.fenix.dejavu.home.drawWorkspaceTheme
import org.mozilla.fenix.dejavu.home.gradientColors
import org.mozilla.fenix.dejavu.home.grainBitmap
import org.mozilla.fenix.dejavu.home.rememberGrainBrush
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.ext.components

/** The theme of the workspace of the shown tab, or `null` for a private tab or a workspace without one. */
@Composable
internal fun shownWorkspaceTheme(): WorkspaceTheme? {
    val context = LocalContext.current
    val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    if (isNight) return null
    val tabId by context.components.core.store.observeAsComposableState { state ->
        state.selectedTab?.takeUnless { it.content.private }?.id
    }
    val repository = remember(context) { WorkspaceRepository.get(context) }
    val workspaces by repository.state.collectAsState()
    return tabId?.let { workspaces.themeOfTab(it) }
}

/** The theme of the workspace tab [tabId] is in, if that workspace has one. */
internal fun WorkspaceState.themeOfTab(tabId: String): WorkspaceTheme? {
    val workspaceId = workspaceOf(tabId)
    return workspaces.firstOrNull { it.id == workspaceId }?.theme
}

/**
 * Draws [theme] over the background, the way the home screen shows the workspace: as one gradient across the whole
 * window, so the bars match each other and the [DejavuWindowTheme] behind the system bars. The bar becomes solid,
 * since a see-through one would show the theme twice over the window.
 */
@Composable
internal fun Modifier.workspaceTheme(theme: WorkspaceTheme?): Modifier {
    if (theme == null) return this
    val grain = rememberGrainBrush()
    val root = LocalView.current.rootView
    val base = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInWindow() }
        .drawBehind {
            val window = ComposeRect(-origin, Size(root.width.toFloat(), root.height.toFloat()))
            drawRect(base)
            drawWorkspaceTheme(theme, next = null, fraction = 0f, grain = grain, area = window)
        }
}

/**
 * Paints the theme of the shown tab's workspace on the window behind the browser, where the status bar and the
 * navigation bar are, so they blend with the address bar and the actions bar. The window gets its own background back
 * when the browser stops.
 */
class DejavuWindowTheme(private val activity: Activity) : LifecycleAwareFeature {
    private var scope: CoroutineScope? = null
    private var original: Drawable? = null

    override fun start() {
        val window = activity.window ?: return
        val components = activity.components
        val repository = components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
            WorkspaceRepository.get(activity)
        }
        val base = window.decorView.background
        original = base
        scope = MainScope().apply {
            launch {
                components.core.store.flow()
                    .map { state -> state.selectedTab?.takeUnless { it.content.private }?.id }
                    .distinctUntilChanged()
                    .combine(repository.state) { tabId, workspaces -> tabId?.let { workspaces.themeOfTab(it) } }
                    .distinctUntilChanged()
                    .collect { theme ->
                        val isNight = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                        window.setBackgroundDrawable(if (isNight) base else theme?.let { WorkspaceBackdrop(base, it) } ?: base)
                    }
            }
        }
    }

    override fun stop() {
        val running = scope ?: return
        running.cancel()
        scope = null
        activity.window?.setBackgroundDrawable(original)
        original = null
    }
}

/** [base] with [theme] over it, as one gradient from the top left of the window to its bottom right. */
private class WorkspaceBackdrop(private val base: Drawable?, theme: WorkspaceTheme) : Drawable() {
    private val colors = theme.gradientColors().map { it.toArgb() }.toIntArray()
    private val gradient = Paint()
    private val hasGrain = theme.texture > 0f
    private val grain = Paint().apply {
        shader = BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        alpha = (theme.texture * GRAIN_STRENGTH * MAX_ALPHA).roundToInt()
    }

    override fun onBoundsChange(bounds: Rect) {
        base?.bounds = bounds
        gradient.shader = LinearGradient(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            colors,
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        base?.draw(canvas)
        canvas.drawRect(bounds, gradient)
        if (hasGrain) canvas.drawRect(bounds, grain)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val MAX_ALPHA = 255
    }
}
