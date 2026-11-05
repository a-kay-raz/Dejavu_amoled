/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as materialR
import com.google.android.material.color.MaterialColors
import org.mozilla.fenix.R

/**
 * Shows the preferences of every settings screen as rounded cards, one card per category, like the Android settings
 * app: the outer corners of a card are round and its rows are separated by small gaps.
 */
class DejavuPreferenceCards : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
        val fragment = f as? PreferenceFragmentCompat ?: return
        val list = fragment.listView ?: return
        if ((0 until list.itemDecorationCount).any { list.getItemDecorationAt(it) is CardDecoration }) return
        fragment.setDivider(null)
        list.addItemDecoration(CardDecoration(list.context))
    }
}

private class CardDecoration(context: Context) : RecyclerView.ItemDecoration() {
    private val density = context.resources.displayMetrics.density
    private val margin = (MARGIN_DP * density).toInt()
    private val gap = (GAP_DP * density).toInt()
    private val groupSpacing = (GROUP_SPACING_DP * density).toInt()
    private val outerRadius = OUTER_RADIUS_DP * density
    private val innerRadius = INNER_RADIUS_DP * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, materialR.attr.colorSurfaceContainerHigh, 0)
    }
    private val rect = RectF()
    private val path = Path()

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        val row = rowAt(parent, position)
        if (row == null) {
            // A category title lines up with the rows of its card, which sit inside the card margin.
            outRect.left = margin
            return
        }
        outRect.left = margin
        outRect.right = margin
        outRect.top = if (row.first) 0 else gap
        outRect.bottom = if (row.last) groupSpacing else 0
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val row = rowAt(parent, parent.getChildAdapterPosition(child)) ?: continue
            rect.set(
                child.left.toFloat(),
                child.top + child.translationY,
                child.right.toFloat(),
                child.bottom + child.translationY,
            )
            val top = if (row.first) outerRadius else innerRadius
            val bottom = if (row.last) outerRadius else innerRadius
            path.reset()
            path.addRoundRect(
                rect,
                floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom),
                Path.Direction.CW,
            )
            paint.alpha = (child.alpha * OPAQUE).toInt()
            canvas.drawPath(path, paint)
        }
    }

    /** Where the preference at [position] sits in its card, or `null` for category titles and spacers. */
    private fun rowAt(parent: RecyclerView, position: Int): Row? {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return null
        if (position == RecyclerView.NO_POSITION || position >= adapter.itemCount) return null
        if (adapter.getItem(position).isBreak()) return null
        val first = position == 0 || adapter.getItem(position - 1).isBreak()
        val last = position == adapter.itemCount - 1 || adapter.getItem(position + 1).isBreak()
        return Row(first, last)
    }

    // Firefox's empty spacer rows stand between groups, which the cards already keep apart.
    private fun Preference?.isBreak(): Boolean = this is PreferenceCategory || this?.layoutResource == R.layout.preference_spacer

    private data class Row(val first: Boolean, val last: Boolean)

    private companion object {
        const val MARGIN_DP = 16
        const val GAP_DP = 2
        const val GROUP_SPACING_DP = 8
        const val OUTER_RADIUS_DP = 24f
        const val INNER_RADIUS_DP = 6f
        const val OPAQUE = 255
    }
}
