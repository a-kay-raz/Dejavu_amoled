/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.util.lerp
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.navigation.fragment.NavHostFragment
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.PageBackdrop

private const val OPEN_MS = 520L
private const val CLOSE_MS = 480L
private const val REVEAL_MS = 160L
private const val PAGE_WAIT_MS = 1_500L
private const val HOME_WAIT_MS = 1_500L
private const val HOME_FRAMES = 2
private const val PAGE_FRAMES = 3
private const val DIM_ALPHA = 0.3f

// A frame coming later than this after the one before it follows a stall, not a steady pace; the genie then moves on
// by one frame only, so it skips none of its way.
private const val STALL_NANOS = 34_000_000L
private const val FRAME_NANOS = 16_666_667L
private const val NANOS_PER_MS = 1_000_000L

// The share of the genie in which the page's sides bend into a funnel, and the share after which it slides along it.
private const val BEND_END = 0.4f
private const val SLIDE_START = 0.15f

// The share after which the page fades into its button, or out of it.
private const val VANISH_FROM = 0.8f
private const val MESH_COLUMNS = 12
private const val MESH_ROWS = 48

/**
 * Dejavu's genie between the buttons of the home screen and the pages they open, like the bookmarks or the settings. A
 * page pours out of its button: its sides bend into a funnel that widens from the button, and the page slides out
 * along it until it fills the screen. Leaving it for the home screen draws it back into its button the same way. The
 * home screen darkens behind the page.
 */
internal object PageGenie {
    /** The buttons of the home screen that open a page of their own. */
    enum class Button { ACCOUNT, SETTINGS, DOWNLOADS, BOOKMARKS, HISTORY }

    /** A page opened from [button], shown by [fragment]. */
    private class Opened(val button: Button, val fragment: WeakReference<Fragment>)

    /** A picture of a page as it was last left, at half size, and where the page showed in the window. */
    class Kept(val picture: Bitmap, val bounds: Rect)

    private val buttons = HashMap<Button, Rect>()
    private val kept = HashMap<Button, Kept>()
    private val watched = WeakHashMap<FragmentManager, Unit>()
    private var genie: GenieView? = null

    // The opening genie whose page is being created, and the page shown after it.
    private var awaited: GenieView? = null
    private var opened: Opened? = null

    /** [button] is at [bounds] in the window. */
    fun buttonPlaced(button: Button, bounds: Rect) {
        buttons[button] = bounds
    }

    /** Where [button] is now, in the window. */
    fun boundsOf(button: Button): Rect? = buttons[button]?.takeIf { it.isShown }

    /**
     * Opens the page of [button] with [navigate], pouring it out of the button. Without the button on screen, or with
     * animations turned off, it just opens.
     */
    fun open(context: Context, button: Button, navigate: () -> Unit) {
        val activity = context.activity as? FragmentActivity
        val window = activity?.window?.decorView as? ViewGroup
        val host = activity?.supportFragmentManager?.findFragmentById(R.id.container) as? NavHostFragment
        if (window == null || host == null || boundsOf(button) == null || isBusy ||
            !ValueAnimator.areAnimatorsEnabled()
        ) {
            navigate()
            return
        }
        watch(host)
        val view = GenieView(window.context, button, opening = true)
        start(window, view)
        // The home screen stays behind the page as a picture, since the page takes its place once opened.
        pictureWindow(activity, window) { home ->
            if (genie !== view) return@pictureWindow
            if (home == null) {
                finish(view)
                navigate()
                return@pictureWindow
            }
            view.home = home
            // The page shows the home screen blurred behind it.
            PageBackdrop.opensOver(home)
            view.pour(kept[button]) {
                awaited = view
                navigate()
            }
        }
    }

    /** The home screen drew itself, so a page leaving for it can go back into its button. */
    fun homeShown() {
        genie?.homeShown()
    }

    private val isBusy: Boolean
        get() {
            // A genie whose screen went away, like with the app's window, is over.
            genie?.let { if (!it.isAttachedToWindow) finish(it) }
            return genie != null
        }

    private fun start(window: ViewGroup, view: GenieView) {
        genie?.let(::finish)
        genie = view
        window.addView(view, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun finish(view: GenieView) {
        view.stop()
        if (awaited === view) awaited = null
        if (genie === view) genie = null
        view.post { (view.parent as? ViewGroup)?.removeView(view) }
    }

    private fun pageCreated(fragment: Fragment, page: View) {
        val view = awaited ?: return
        awaited = null
        opened = Opened(view.button, WeakReference(fragment))
        view.pageArrived(page)
    }

    private fun pageDestroyed(fragment: Fragment) {
        if (opened?.fragment?.get() === fragment) opened = null
    }

    /** The page of [fragment] leaves for the home screen: pictures it, then draws it back into its button. */
    private fun pageLeaving(fragment: Fragment) {
        val page = opened?.takeIf { it.fragment.get() === fragment } ?: return
        opened = null
        val view = fragment.view?.takeIf { it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 }
        val window = view?.rootView as? ViewGroup ?: return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val genieView = GenieView(window.context, page.button, opening = false)
        // The whole window, with the bars some pages show around them, like the settings' title.
        val picture = picture(window, genieView.surface)
        start(window, genieView)
        val bounds = genieView.leave(window, picture)
        if (picture != null) keep(page.button, picture, bounds)
    }

    /** Keeps a smaller copy of [picture], the page of [button] shown at [bounds], for opening it next time. */
    private fun keep(button: Button, picture: Bitmap, bounds: Rect) {
        val small = runCatching {
            Bitmap.createScaledBitmap(picture, picture.width / 2, picture.height / 2, true)
        }.getOrNull() ?: return
        kept[button] = Kept(small, bounds)
    }

    /** Follows the pages of [host]: the one an opening genie waits for, and the ones that leave for the home screen. */
    private fun watch(host: NavHostFragment) {
        val manager = host.childFragmentManager
        if (manager in watched) return
        watched[manager] = Unit
        manager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedState: Bundle?) =
                    pageCreated(f, v)

                override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) = pageDestroyed(f)
            },
            false,
        )
        manager.addOnBackStackChangedListener(
            object : FragmentManager.OnBackStackChangedListener {
                override fun onBackStackChanged() = Unit

                // Called before the fragments change, while the page still shows.
                override fun onBackStackChangeCommitted(fragment: Fragment, pop: Boolean) {
                    if (pop && host.navController.currentDestination?.id == R.id.homeFragment) pageLeaving(fragment)
                }
            },
        )
    }
}

/** Lets [PageGenie] pour the page of [button] out of this, and draw it back in. */
internal fun Modifier.genieButton(button: PageGenie.Button): Modifier =
    onGloballyPositioned { PageGenie.buttonPlaced(button, it.boundsInWindow()) }

/**
 * Draws a genie over the whole window: during an opening, the page coming out of its button over a picture of the home
 * screen as it was; during a closing, the page going back into it over the home screen. Taps wait for it to end.
 */
@SuppressLint("ViewConstructor")
internal class GenieView(context: Context, val button: PageGenie.Button, private val opening: Boolean) : View(context) {
    /** The color of pages, for a page without a picture. */
    val surface = ContextCompat.getColor(context, R.color.fx_mobile_surface)
    private val plain = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(surface) }
    private val verts = FloatArray((MESH_COLUMNS + 1) * (MESH_ROWS + 1) * 2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var frames: Choreographer.FrameCallback? = null
    private var animator: ValueAnimator? = null

    /** The home screen as it was when an opening started. */
    var home: Bitmap? = null

    // The page, and where it shows and where its button is, in the window. A page without a place fills the window.
    private var page: Bitmap? = null
    private var pageBounds: Rect? = null
    private var buttonBounds: Rect? = null

    // 0 with the page in its place, 1 with the page inside its button.
    private var tau = if (opening) 1f else 0f

    // How much of the genie shows, as it gives way to the page itself at the end of an opening.
    private var shown = 1f
    private var homeArrived = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = true

    /**
     * Pours the page out of its button, as [kept] pictured it the last time, or plain, then opens it with [open] under
     * the genie, which stays until the page drew itself. Setting the page up holds the app a moment, which would stop
     * the genie on its way.
     */
    fun pour(kept: PageGenie.Kept?, open: () -> Unit) {
        page = kept?.picture
        pageBounds = kept?.bounds
        buttonBounds = PageGenie.boundsOf(button)
        play(OPEN_MS, from = 1f, to = 0f) {
            open()
            handler.postDelayed(::reveal, PAGE_WAIT_MS)
        }
    }

    /** The page being opened is [view]: shows it once it is laid out and drew itself a few times, to settle. */
    fun pageArrived(view: View) {
        view.doOnLayout {
            afterFrames(PAGE_FRAMES) {
                if (!isAttachedToWindow) return@afterFrames
                reveal()
            }
        }
    }

    /** Shows the page itself under the genie, which fades away. */
    private fun reveal() {
        if (animator != null) return
        handler.removeCallbacksAndMessages(null)
        home = null
        fade { PageGenie.finish(this) }
    }

    /**
     * The page shown by [view] leaves for the home screen, pictured as [picture] or plain: shows it in its place until
     * the home screen shows its buttons, then draws it back into its own. Gives where the page showed in the window.
     */
    fun leave(view: View, picture: Bitmap?): Rect {
        page = picture
        val bounds = boundsOf(view)
        pageBounds = bounds
        handler.postDelayed({ homeShown() }, HOME_WAIT_MS)
        return bounds
    }

    fun homeShown() {
        if (opening || homeArrived) return
        homeArrived = true
        // The buttons are placed in the frames right after the home screen first drew.
        afterFrames(HOME_FRAMES) {
            if (!isAttachedToWindow) return@afterFrames
            buttonBounds = PageGenie.boundsOf(button)
            if (buttonBounds == null) {
                fade { PageGenie.finish(this) }
            } else {
                play(CLOSE_MS, from = 0f, to = 1f) { PageGenie.finish(this) }
            }
        }
    }

    private fun fade(then: () -> Unit) {
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = REVEAL_MS
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { then() }
            start()
        }
    }

    private fun afterFrames(count: Int, then: () -> Unit) {
        if (count == 0) then() else postOnAnimation { afterFrames(count - 1, then) }
    }

    /**
     * Moves the genie from [from] to [to] in [durationMs], then calls [then]. A frame coming after a stall moves it on
     * by one frame only.
     */
    private fun play(durationMs: Long, from: Float, to: Float, then: () -> Unit) {
        val easing = FastOutSlowInInterpolator()
        val total = durationMs * NANOS_PER_MS
        var played = 0L
        var last = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (frames !== this) return
                if (last != 0L) played = (played + (now - last).let { if (it > STALL_NANOS) FRAME_NANOS else it })
                last = now
                val fraction = (played.toFloat() / total).coerceAtMost(1f)
                tau = lerp(from, to, easing.getInterpolation(fraction))
                invalidate()
                if (fraction >= 1f) {
                    frames = null
                    then()
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        frames = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        frames?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frames = null
        animator?.removeAllListeners()
        animator?.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
        // This view holds the activity context and full-screen pictures, and
        // PageGenie holds this view statically, so leaving it there after the
        // window goes away would keep the destroyed activity alive until the
        // next genie opens. Clearing it here is safe: finish() only nulls the
        // references to this view, and a view removed this way is never used
        // again.
        PageGenie.finish(this)
    }

    override fun onDraw(canvas: Canvas) {
        home?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val darkness = DIM_ALPHA * (1f - tau) * shown
        if (darkness > 0f && (home != null || !opening)) {
            canvas.drawColor(ColorUtils.setAlphaComponent(0, (darkness * 255).roundToInt()))
        }
        // An opening shows nothing until it pictured the home screen and starts pouring.
        if (opening && buttonBounds == null) return
        val origin = IntArray(2).also(::getLocationInWindow)
        val offset = Offset(-origin[0].toFloat(), -origin[1].toFloat())
        val target = (pageBounds ?: Rect(0f, 0f, width.toFloat(), height.toFloat())).translate(offset)
        // Until its button is placed, the page stays in its place.
        val into = buttonBounds?.translate(offset)
        val step = if (into == null) 0f else tau
        genieMesh(target, into ?: target, step, MESH_COLUMNS, MESH_ROWS, verts)
        val alpha = genieAlpha(step) * shown
        if (alpha <= 0f) return
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        canvas.drawBitmapMesh(page ?: plain, MESH_COLUMNS, MESH_ROWS, verts, 0, null, 0, paint)
    }
}

/**
 * Fills [verts] with the points of a mesh of [columns] by [rows] cells that draws [page] [tau] of the way into
 * [button]: at 0 the page is in its place, at 1 inside the button. The sides of the page first bend into a funnel that
 * narrows down to the button, then the page slides along the funnel into it. A button above the page turns the funnel
 * upwards.
 */
internal fun genieMesh(page: Rect, button: Rect, tau: Float, columns: Int, rows: Int, verts: FloatArray) {
    var i = 0
    for (row in 0..rows) {
        val line = genieLine(page, button, tau, row.toFloat() / rows)
        for (column in 0..columns) {
            verts[i++] = lerp(line.start, line.end, column.toFloat() / columns)
            verts[i++] = line.along
        }
    }
}

/**
 * A line across a page in a genie, see [genieMesh]: [along] is where it is down the funnel, and it spans from [start]
 * to [end] across it.
 */
internal class GenieLine(val along: Float, val start: Float, val end: Float)

/**
 * Where the line across [page] [fraction] of the way down it goes in the genie that draws the page [tau] of the way
 * into [button], see [genieMesh]. Unless [downThePage], the funnel runs along the page from its start to its end
 * instead, like for a row going into a button at its end: then [GenieLine.along] is across the screen, and the line
 * spans from top to bottom.
 */
internal fun genieLine(page: Rect, button: Rect, tau: Float, fraction: Float, downThePage: Boolean = true): GenieLine {
    if (!downThePage) return genieLine(page.transposed(), button.transposed(), tau, fraction)
    val bend = smoothstep(0f, BEND_END, tau)
    val slide = smoothstep(SLIDE_START, 1f, tau)
    val downwards = button.center.y >= page.center.y
    val y = lerp(lerp(page.top, button.top, slide), lerp(page.bottom, button.bottom, slide), fraction)
    // The funnel narrows between the page's far edge and the near edge of the button.
    val neckFrom = if (downwards) page.top else page.bottom
    val neckTo = if (downwards) button.top else button.bottom
    val along = if (neckTo == neckFrom) 1f else ((y - neckFrom) / (neckTo - neckFrom)).coerceIn(0f, 1f)
    val narrowing = bend * smoothstep(0f, 1f, along)
    return GenieLine(y, lerp(page.left, button.left, narrowing), lerp(page.right, button.right, narrowing))
}

/** How much of a genie still shows [tau] of the way into its button: it fades as it goes in. */
internal fun genieAlpha(tau: Float): Float = 1f - smoothstep(VANISH_FROM, 1f, tau)

private fun Rect.transposed() = Rect(top, left, bottom, right)

private fun smoothstep(from: Float, to: Float, value: Float): Float {
    val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** A button cut away by the edge of the screen has no room left. */
private val Rect.isShown: Boolean
    get() = width > 1f && height > 1f

private val Context.activity: Activity?
    get() = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

/** Where [view] shows in the window. */
private fun boundsOf(view: View): Rect {
    val at = IntArray(2).also(view::getLocationInWindow)
    return Rect(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
}

/** Pictures what [window], the root of [activity]'s window, shows, or gives `null` when it cannot. */
internal fun pictureWindow(activity: Activity, window: View, done: (Bitmap?) -> Unit) {
    val bitmap = runCatching { Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888) }.getOrNull()
    if (bitmap == null) {
        done(null)
        return
    }
    runCatching {
        PixelCopy.request(
            activity.window,
            android.graphics.Rect(0, 0, window.width, window.height),
            bitmap,
            { result -> done(bitmap.takeIf { result == PixelCopy.SUCCESS }) },
            Handler(Looper.getMainLooper()),
        )
    }.onFailure { done(null) }
}

/** Pictures [view] on a page of [surface] color, or gives `null` when it cannot draw itself into a picture. */
private fun picture(view: View, surface: Int): Bitmap? = runCatching {
    Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(surface)
        view.draw(canvas)
    }
}.getOrNull()
