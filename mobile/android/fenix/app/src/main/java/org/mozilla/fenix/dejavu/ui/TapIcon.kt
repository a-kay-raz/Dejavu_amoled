/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.Group
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.RenderVectorGroup
import androidx.compose.ui.graphics.vector.VectorConfig
import androidx.compose.ui.graphics.vector.VectorProperty
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.util.lerp

/** The taps on a button whose icon moves when tapped, see [TapIcon]. The button calls [tap] as it is tapped. */
@Stable
class IconTaps internal constructor() {
    // The icon the button shows, which keeps showing while it moves even when the tap changes the button.
    internal var shown = 0
    internal var last by mutableStateOf<IconTap?>(null)
        private set

    fun tap() {
        last = IconTap(shown, (last?.number ?: 0) + 1)
    }
}

/** The [number]th tap on a button, which showed [icon] then. */
internal data class IconTap(@param:DrawableRes val icon: Int, val number: Int)

@Composable
fun rememberIconTaps(): IconTaps = remember { IconTaps() }

/**
 * [icon], which plays its little motion each time [taps] counts a tap, when it is one of Dejavu's icons that have one,
 * see [IconMotions]. While it moves it keeps showing the icon that was tapped, so that a tap that changes the button,
 * like Reload turning into Stop, changes the icon once the motion is over.
 */
@Composable
fun TapIcon(
    @DrawableRes icon: Int,
    taps: IconTaps,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val tap = taps.last
    val progress = remember { Animatable(0f) }
    var moving by remember { mutableStateOf<IconTap?>(null) }
    LaunchedEffect(tap) {
        val motion = tap?.let { IconMotions[it.icon] } ?: return@LaunchedEffect
        moving = tap
        progress.snapTo(0f)
        progress.animateTo(1f, tween(motion.durationMillis, easing = LinearEasing))
        moving = null
    }
    val shown = moving?.icon ?: icon
    SideEffect { taps.shown = shown }
    val motion = moving?.let { IconMotions[it.icon] }
    val moved = motion?.let { rememberMovingPainter(shown, it, progress) }
    Icon(
        painter = iconPainter(shown),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.drawWithContent {
            if (moved == null) {
                drawContent()
                return@drawWithContent
            }
            // The moving icon has room past the icon's sides, for the parts that move out a little.
            translate(-size.width * MOTION_ROOM, -size.height * MOTION_ROOM) {
                with(moved) { draw(size * (1f + 2 * MOTION_ROOM), colorFilter = ColorFilter.tint(tint)) }
            }
        },
    )
}

/**
 * The vector drawable [icon] with its parts posed as [motion] has them at the time of [progress], in a viewport with
 * [MOTION_ROOM] of room on every side.
 */
@Composable
private fun rememberMovingPainter(
    @DrawableRes icon: Int,
    motion: IconMotion,
    progress: Animatable<Float, AnimationVector1D>,
): Painter {
    val image = ImageVector.vectorResource(icon)
    val configs = remember(motion, progress) {
        motion.parts.mapValues { (_, pose) -> PartConfig(pose) { progress.value } }
    }
    val grown = 1f + 2 * MOTION_ROOM
    return rememberVectorPainter(
        defaultWidth = image.defaultWidth * grown,
        defaultHeight = image.defaultHeight * grown,
        viewportWidth = image.viewportWidth * grown,
        viewportHeight = image.viewportHeight * grown,
        name = image.name,
        tintColor = image.tintColor,
        tintBlendMode = image.tintBlendMode,
        autoMirror = image.autoMirror,
    ) { _, _ ->
        Group(translationX = image.viewportWidth * MOTION_ROOM, translationY = image.viewportHeight * MOTION_ROOM) {
            RenderVectorGroup(group = image.root, configs = configs)
        }
    }
}

// How far the parts of a moving icon can go past its sides, as a share of its size.
private const val MOTION_ROOM = 0.25f

/**
 * How an icon moves when its button is tapped: for [durationMillis], each group of its drawable named in [parts] takes
 * the pose its function gives for the time of the motion, from 0 to 1. A motion starts and ends with the icon as drawn.
 */
internal class IconMotion(val durationMillis: Int, val parts: Map<String, (Float) -> Pose>)

/**
 * How a group of an icon is moved from how its drawable has it: turned by [rotation] degrees, scaled by [scaleX] and
 * [scaleY] and shifted by [x] and [y] in the units of the icon, around its pivot.
 */
internal class Pose(
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
)

/**
 * A value over a motion, which runs from 0 to 1: it passes [keys], pairs of a time and a value in the order of their
 * times, easing out of each and into the next. Two keys at the same time make it jump.
 */
internal class Curve(private vararg val keys: Pair<Float, Float>) {
    operator fun invoke(t: Float): Float {
        var previous = keys.first()
        for (key in keys) {
            if (t <= key.first) {
                val span = key.first - previous.first
                val eased = if (span > 0f) FastOutSlowInEasing.transform((t - previous.first) / span) else 1f
                return lerp(previous.second, key.second, eased)
            }
            previous = key
        }
        return previous.second
    }
}

/** The properties of a group of a moving icon, as its pose at the time [time] gives. */
private class PartConfig(private val pose: (Float) -> Pose, private val time: () -> Float) : VectorConfig {
    private var posedAt = Float.NaN
    private var posed = Pose()

    // Reading the time on every property makes the icon draw again as it changes.
    private fun current(): Pose {
        val t = time()
        if (t != posedAt) {
            posedAt = t
            posed = pose(t)
        }
        return posed
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getOrDefault(property: VectorProperty<T>, defaultValue: T): T {
        val pose = current()
        val value = when (property) {
            VectorProperty.Rotation -> defaultValue as Float + pose.rotation
            VectorProperty.ScaleX -> defaultValue as Float * pose.scaleX
            VectorProperty.ScaleY -> defaultValue as Float * pose.scaleY
            VectorProperty.TranslateX -> defaultValue as Float + pose.x
            VectorProperty.TranslateY -> defaultValue as Float + pose.y
            else -> defaultValue
        }
        return value as T
    }
}
