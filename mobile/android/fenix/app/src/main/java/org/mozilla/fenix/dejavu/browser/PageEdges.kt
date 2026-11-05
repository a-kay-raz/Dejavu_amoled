/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import org.mozilla.fenix.R

// Like Candy's: a light tint at the top of the status bar, fading out a little below it.
private const val SCRIM_TOP_ALPHA = 0.22f
private const val SCRIM_MIDDLE_ALPHA = 0.1f
private const val SCRIM_FADE_DP = 8
private const val SCRIM_TAG = "dejavu_status_bar_scrim"
private const val MAX_ALPHA = 255

/**
 * Lets pages run from edge to edge, behind the status bar and the navigation bar, keeping [this] browser layout clear
 * only of system bars and display cutouts at its sides. Dejavu's bars keep themselves clear of the status bar and the
 * navigation bar while they show, and of nothing while they are hidden over pages. [onBarsChanged] runs once that room
 * changes, as
 * the place of the page below the address bar and above the actions bar depends on it. A video shown full screen gets
 * the whole screen.
 */
fun View.padForSystemBarsAtSides(window: Window, onBarsChanged: () -> Unit) {
    var bars: Pair<Int, Int>? = null
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val sides = if (window.isFullScreen) {
            Insets.NONE
        } else {
            insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        }
        view.updatePadding(left = sides.left, right = sides.right)
        val heights = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            .top to insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        if (heights != bars) {
            bars = heights
            // Not once the layout is gone, as when a link opened in a private tab rebuilt the screen meanwhile.
            view.post { if (view.isAttachedToWindow) onBarsChanged() }
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * Keeps the content of [this] view, at the bottom of the browser, above the navigation bar the page runs behind, while
 * it shows. Above the keyboard, the navigation bar is out of the way. A view [contentHeight] tall,
 * like the find in page bar, grows by the room it keeps, so its content keeps its height.
 */
fun View.padForNavigationBar(contentHeight: Int? = null) {
    val ownPadding = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bar = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
            0
        } else {
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        }
        view.updatePadding(bottom = ownPadding + bar)
        contentHeight?.let { height ->
            view.layoutParams?.takeIf { it.height != height + ownPadding + bar }?.let {
                view.updateLayoutParams { this.height = height + ownPadding + bar }
            }
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * Adds a light fade from the app's background color behind the status bar to [this] layout, over the page and right
 * above [above], like Candy's, so the status bar's icons stand out a little more over pages scrolled behind it.
 */
fun CoordinatorLayout.addStatusBarScrim(above: View, window: Window) {
    if (findViewWithTag<View>(SCRIM_TAG) != null) return
    val scrim = View(context).apply {
        tag = SCRIM_TAG
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = scrimFade(ContextCompat.getColor(context, R.color.fx_mobile_surface))
    }
    val layoutParams = CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
        gravity = Gravity.TOP
    }
    addView(scrim, indexOfChild(above) + 1, layoutParams)
    val fade = (SCRIM_FADE_DP * resources.displayMetrics.density).toInt()
    ViewCompat.setOnApplyWindowInsetsListener(scrim) { view, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        view.updateLayoutParams { height = if (window.isFullScreen || top == 0) 0 else top + fade }
        insets
    }
}

/** Turns the fade behind the status bar added by [addStatusBarScrim] to [color]. */
internal fun CoordinatorLayout.tintStatusBarScrim(color: Int) {
    findViewWithTag<View>(SCRIM_TAG)?.background = scrimFade(color)
}

private fun scrimFade(color: Int) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(
        ColorUtils.setAlphaComponent(color, (SCRIM_TOP_ALPHA * MAX_ALPHA).toInt()),
        ColorUtils.setAlphaComponent(color, (SCRIM_MIDDLE_ALPHA * MAX_ALPHA).toInt()),
        ColorUtils.setAlphaComponent(color, 0),
    ),
)

/**
 * The height of the status bar over [this] view, or of a camera cutout, which Dejavu's bars keep below. 0 while the
 * status bar is hidden over pages, or before it is known.
 */
internal val View.statusBarHeight: Int
    get() = ViewCompat.getRootWindowInsets(this)
        ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0

/**
 * The height of the navigation bar at the bottom of [this] view, which Dejavu's bars keep above. 0 while it is hidden
 * over pages, or before it is known.
 */
internal val View.navigationBarHeight: Int
    get() = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

/** The height of the navigation bar shown at the bottom of [this] view, or 0 while it is hidden or not known. */
internal val View.shownNavigationBarHeight: Int
    get() = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

/** Whether a video or page is shown full screen, the system bars hidden over it. */
internal val Window.isFullScreen: Boolean
    get() = attributes.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0
