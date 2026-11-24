/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import androidx.annotation.DrawableRes
import org.mozilla.fenix.R

/**
 * The little motions Dejavu's icons play when their buttons are tapped, see [TapIcon]. Each moves the groups its
 * drawable names: the part of the icon in the lighter shade mostly, which stands for what the button does. Icons
 * whose buttons open a page, a sheet or another screen, or close their menu, like Settings, Home or Share, have none,
 * as that hides them right away.
 */
internal object IconMotions {
    private val motions = mapOf(
        R.drawable.dejavu_ic_bookmark_add_24 to bookmarkAdd,
        R.drawable.dejavu_ic_create_folder_24 to createFolder,
        R.drawable.dejavu_ic_extensions_24 to extensions,
        R.drawable.dejavu_ic_new_tab_24 to newTab,
        R.drawable.dejavu_ic_private_tab_24 to privateTab,
        R.drawable.dejavu_ic_refresh_24 to refresh,
        R.drawable.dejavu_ic_reopen_tab_24 to reopenTab,
        R.drawable.dejavu_ic_save_page_24 to savePage,
        R.drawable.dejavu_ic_temporary_container_24 to temporaryContainer,
    )

    /** The motion of [icon], while Dejavu's own icons are shown: those of the other [IconStyle]s stay still. */
    operator fun get(@DrawableRes icon: Int): IconMotion? =
        motions[icon]?.takeIf { DejavuIcons.style == IconStyle.DEJAVU }
}

private fun motion(durationMillis: Int, vararg parts: Pair<String, (Float) -> Pose>) =
    IconMotion(durationMillis, mapOf(*parts))

private fun part(name: String, pose: (Float) -> Pose) = name to pose

/** A pose squashed to [height] of the part's height around its pivot, widening half as much as it flattens. */
private fun squashed(height: Float) = Pose(scaleX = 1f + (1f - height) / 2f, scaleY = height)

private fun scaled(scale: Float, rotation: Float = 0f) = Pose(rotation = rotation, scaleX = scale, scaleY = scale)

// Curves several icons share: giving a little under a tap and springing back, and popping after shrinking first.
private val give = Curve(0f to 1f, 0.15f to 0.92f, 0.45f to 1.03f, 0.7f to 1f, 1f to 1f)
private val pop = Curve(0f to 1f, 0.15f to 0.6f, 0.45f to 1.3f, 0.7f to 0.95f, 1f to 1f)

// A plus popping with a quarter turn.
private val quarterTurn = Curve(0f to 0f, 0.6f to 90f, 1f to 90f)

// Bookmark page: the bookmark is pressed and springs back as its plus pops, and the bookmark behind it bobs.
private val press = Curve(0f to 1f, 0.12f to 0.9f, 0.4f to 1.06f, 0.65f to 0.98f, 1f to 1f)
private val lift = Curve(0f to 0f, 0.2f to 0f, 0.45f to -1.2f, 0.7f to 0.2f, 1f to 0f)
private val bookmarkAdd = motion(
    650,
    part("front") { t -> squashed(press(t)) },
    part("plus") { t -> scaled(pop(t), rotation = quarterTurn(t)) },
    part("back") { t -> Pose(y = lift(t)) },
)

// New folder: the plus pops with a quarter turn, and the folder gives under it.
private val createFolder = motion(
    650,
    part("plus") { t -> scaled(pop(t), rotation = quarterTurn(t)) },
    part("folder") { t -> squashed(give(t)) },
)

// Extensions: the piece shrinks a little and wiggles into place like a piece of a puzzle.
private val wiggle = Curve(0f to 0f, 0.15f to -14f, 0.35f to 10f, 0.55f to -6f, 0.75f to 3f, 1f to 0f)
private val snapIn = Curve(0f to 1f, 0.15f to 0.9f, 0.4f to 1.06f, 0.7f to 0.99f, 1f to 1f)
private val extensions = motion(700, part("piece") { t -> scaled(snapIn(t), rotation = wiggle(t)) })

// New tab: the plus pops with a quarter turn, a little past and back, as the ring gives.
private val quarterTurnPast = Curve(0f to 0f, 0.65f to 98f, 1f to 90f)
private val newTab = motion(
    600,
    part("plus") { t -> scaled(pop(t), rotation = quarterTurnPast(t)) },
    part("ring") { t -> scaled(give(t)) },
)

// Private tab: the hat is tipped while the glasses blink.
private val tipUp = Curve(0f to 0f, 0.25f to -2.2f, 0.55f to 0.3f, 0.75f to 0f, 1f to 0f)
private val tilt = Curve(0f to 0f, 0.25f to -10f, 0.55f to 3f, 0.8f to 0f, 1f to 0f)
private val blink = Curve(0f to 1f, 0.18f to 1f, 0.28f to 0.12f, 0.4f to 1f, 1f to 1f)
private val privateTab = motion(
    650,
    part("hat") { t -> Pose(rotation = tilt(t), y = tipUp(t)) },
    part("glasses") { t -> Pose(scaleY = blink(t)) },
)

// Reload: the arrow goes round once the way it points, a little past and back.
private val roundBack = Curve(0f to 0f, 0.75f to -380f, 1f to -360f)
private val refresh = motion(650, part("arrow") { t -> Pose(rotation = roundBack(t)) })

// Reopen a closed tab: the arrows go half round the way they point, a little past and back, as the tabs give.
private val halfRound = Curve(0f to 0f, 0.75f to 195f, 1f to 180f)
private val reopenTab = motion(
    650,
    part("arrows") { t -> Pose(rotation = halfRound(t)) },
    part("tabs") { t -> scaled(give(t)) },
)

// Save page: the arrow drops into the page, which gives under it.
private val drop = Curve(0f to 0f, 0.12f to -0.8f, 0.4f to 2.2f, 0.62f to -0.4f, 0.82f to 0.1f, 1f to 0f)
private val pageGives = Curve(0f to 1f, 0.3f to 1f, 0.42f to 0.94f, 0.6f to 1.02f, 0.8f to 1f, 1f to 1f)
private val savePage = motion(
    600,
    part("arrow") { t -> Pose(y = drop(t)) },
    part("page") { t -> squashed(pageGives(t)) },
)

// Temporary container: the hourglass is turned over, a little past and back.
private val turnOver = Curve(0f to 0f, 0.7f to 192f, 1f to 180f)
private val temporaryContainer = motion(700, part("glass") { t -> scaled(give(t), rotation = turnOver(t)) })
