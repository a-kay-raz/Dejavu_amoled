/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import android.os.StrictMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.R
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.menu.BORDER_ALPHA
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.ui.iconPainter
import org.mozilla.fenix.ext.components as contextComponents
import org.mozilla.fenix.theme.FirefoxTheme

/** Whether pages fill the whole screen in full screen view right now, see [installFullScreenView]. */
internal val Context.isFullScreenView: Boolean
    get() = contextComponents.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
        DejavuSettings.get(this)
    }.fullScreenView.value

/**
 * Dejavu's full screen view (issue #277): the Full screen entry of the More menu or the bars hides the address bar, the
 * actions bar, the status bar and the navigation bar, so the page fills the screen, until the button floating over it,
 * or the entry again, shows them again. It stays on while switching tabs and apps and after Dejavu restarts. A video in
 * full screen hides the button.
 *
 * @param window The layout of the browser screen's page and bars, which the button floats over.
 * @param settings Where full screen view is kept.
 * @param apply Hides the bars when called with `true`, and shows them again when called with `false`.
 */
fun Fragment.installFullScreenView(window: ConstraintLayout, settings: DejavuSettings, apply: (Boolean) -> Unit) {
    val button = ComposeView(requireContext()).apply {
        isVisible = false
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            FirefoxTheme {
                val place by settings.fullScreenButton.collectAsState()
                FullScreenButton(place = place, onMoved = settings::setFullScreenButton) {
                    settings.setFullScreenView(false)
                }
            }
        }
    }
    val wholeWindow = ConstraintLayout.LayoutParams(0, 0).apply {
        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
    }
    window.addView(button, wholeWindow)
    viewLifecycleOwner.lifecycleScope.launch {
        settings.fullScreenView.collect { on ->
            apply(on)
            button.isVisible = on
        }
    }
}

/**
 * The round button that ends full screen view, floating at [place] over the page. Dragged, it follows the finger, then
 * goes to the nearer side of the screen, where [onMoved] keeps it. Touches beside it reach the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullScreenButton(place: FloatingButtonPlace, onMoved: (FloatingButtonPlace) -> Unit, onClick: () -> Unit) {
    val videoFullScreen by components.core.store.observeAsComposableState {
        it.selectedTab?.content?.fullScreen == true
    }
    if (videoFullScreen) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Clear of the status bar and the navigation bar, which a swipe in from the edge shows for a moment.
            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Vertical))
            .padding(BUTTON_MARGIN),
    ) {
        val buttonSize = with(LocalDensity.current) { BUTTON_SIZE.toPx() }
        val room = Size(
            (constraints.maxWidth - buttonSize).coerceAtLeast(0f),
            (constraints.maxHeight - buttonSize).coerceAtLeast(0f),
        )
        val rest = place.offsetIn(room, rtl)
        val offset = remember { Animatable(rest, Offset.VectorConverter) }
        var dragging by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(rest) {
            if (!dragging) offset.snapTo(rest)
        }
        val settle = {
            val moved = FloatingButtonPlace.nearest(offset.value, room, rtl)
            onMoved(moved)
            scope.launch {
                offset.animateTo(moved.offsetIn(room, rtl))
                dragging = false
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .absoluteOffset { IntOffset(offset.value.x.roundToInt(), offset.value.y.roundToInt()) }
                .size(BUTTON_SIZE)
                // At the edge of the screen, dragging the button does not go back instead.
                .systemGestureExclusion()
                .pointerInput(room, rtl) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                    ) { change, amount ->
                        change.consume()
                        scope.launch {
                            val moved = offset.value + amount
                            offset.snapTo(Offset(moved.x.coerceIn(0f, room.width), moved.y.coerceIn(0f, room.height)))
                        }
                    }
                }
                .shadow(BUTTON_ELEVATION, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .workspaceTheme(shownPopupTheme())
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = BORDER_ALPHA), CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
        ) {
            Icon(
                painter = iconPainter(R.drawable.dejavu_ic_full_screen_exit_24),
                contentDescription = stringResource(R.string.dejavu_menu_exit_full_screen),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
    }
}

private val BUTTON_SIZE = 48.dp
private val BUTTON_MARGIN = 12.dp
private val BUTTON_ELEVATION = 3.dp
private val ICON_SIZE = 22.dp
