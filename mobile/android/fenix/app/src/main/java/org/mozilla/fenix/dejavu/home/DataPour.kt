/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.iconPainter

private const val POUR_MS = 900

// Each button sends a few bits of its data, one after the other, each on its way for this share of the pour.
private const val BITS = 5
private const val STAGGER = 0.09f
private const val FLIGHT = 0.6f
private const val FADE_IN = 0.15f
private const val END_SCALE = 0.35f
private const val SWELL = 0.12f
private val BitSize = 20.dp

/**
 * Clear data on the home screen: bits of the data being deleted stream out of the History and Downloads buttons up into
 * the Clear button, which swells a little as it takes them in. [DataPourOverlay] draws them over the home screen, and
 * the Clear button of the page shown is its [target].
 */
@Stable
internal class DataPour {
    private val progress = Animatable(0f)
    private var from by mutableStateOf<List<Pair<PageGenie.Button, Rect>>>(emptyList())
    private var to: Rect? = null

    /** Where the Clear button of the page shown is, in the window. */
    var target: Rect? = null

    /** Where the overlay drawing the bits is in the window. */
    var origin = Offset.Zero

    /** Whether bits are on their way, so the Clear button stays until they are in. */
    val isPouring: Boolean
        get() = from.isNotEmpty()

    /** How much bigger the Clear button is now, as the bits go in. */
    val swell: Float
        get() {
            if (!isPouring) return 0f
            val arriving = ((progress.value - FLIGHT) / (1f - FLIGHT)).coerceIn(0f, 1f)
            return SWELL * sin(PI * arriving).toFloat()
        }

    /** Pours the data of [buttons], from where they are on the screen, into the Clear button. */
    suspend fun play(buttons: List<PageGenie.Button>) {
        val target = target ?: return
        val starts = buttons.mapNotNull { button -> PageGenie.boundsOf(button)?.let { button to it } }
        if (starts.isEmpty()) return
        to = target
        from = starts
        try {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(POUR_MS, easing = LinearEasing))
        } finally {
            from = emptyList()
        }
    }

    /**
     * Draws the bits on their way, each a small copy of its button's icon from [painterOf] in [tint], with the
     * window's [origin] at this drawing's top left. A bit rises from its button, then turns into the Clear button.
     */
    fun DrawScope.drawBits(painterOf: (PageGenie.Button) -> Painter, tint: ColorFilter) {
        // Read before anything else, so the drawing follows them while nothing pours too.
        val starts = from
        val shown = progress.value
        val target = to ?: return
        val end = target.center - origin
        val size = BitSize.toPx()
        starts.forEach { (button, bounds) ->
            val start = bounds.center - origin
            val bend = Offset(start.x, end.y)
            repeat(BITS) { index ->
                val t = ((shown - index * STAGGER) / FLIGHT).coerceIn(0f, 1f)
                if (t <= 0f || t >= 1f) return@repeat
                val eased = FastOutSlowInEasing.transform(t)
                val at = lerp(lerp(start, bend, eased), lerp(bend, end, eased), eased)
                val side = size * (1f + (END_SCALE - 1f) * eased)
                val alpha = min(t / FADE_IN, 1f) * genieAlpha(t)
                translate(at.x - side / 2, at.y - side / 2) {
                    with(painterOf(button)) { draw(Size(side, side), alpha, tint) }
                }
            }
        }
    }
}

/** Draws the bits of [pour] over the home screen. */
@Composable
internal fun DataPourOverlay(pour: DataPour, modifier: Modifier = Modifier) {
    val history = iconPainter(R.drawable.dejavu_ic_history_24)
    val downloads = iconPainter(R.drawable.dejavu_ic_downloads_24)
    val tint = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
    Spacer(
        modifier = modifier
            .onGloballyPositioned { pour.origin = it.positionInWindow() }
            .drawBehind {
                with(pour) {
                    drawBits(
                        painterOf = { if (it == PageGenie.Button.DOWNLOADS) downloads else history },
                        tint = tint,
                    )
                }
            },
    )
}

/** Lets [pour] pour into this, the Clear button, which swells a little as the bits go in. */
internal fun Modifier.dataPourTarget(pour: DataPour?): Modifier {
    if (pour == null) return this
    return onGloballyPositioned { pour.target = Rect(it.positionInWindow(), it.size.toSize()) }
        .graphicsLayer {
            val scale = 1f + pour.swell
            scaleX = scale
            scaleY = scale
        }
}
