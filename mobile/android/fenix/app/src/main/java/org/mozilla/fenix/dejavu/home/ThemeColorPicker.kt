/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuTextField
import org.mozilla.fenix.dejavu.ui.iconPainter
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme

/**
 * The last choice of workspace theme, after the ready-made gradients: a gradient of the user's own [colors], or a
 * rainbow with a plus while the workspace has none, which opens [ThemeColorDialog].
 */
@Composable
internal fun CustomThemeChoice(colors: List<Int>?, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.dejavu_workspace_theme_custom)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(
                if (colors == null) Brush.sweepGradient(HueColors) else themeBrush(colors.map { Color(it) }, opacity = 1f),
            )
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
    ) {
        val icon = if (colors == null) iconsR.drawable.mozac_ic_plus_24 else iconsR.drawable.mozac_ic_edit_24
        Icon(
            painter = iconPainter(icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(18.dp)
                .background(Color.Black.copy(alpha = ICON_BACKDROP_ALPHA), CircleShape)
                .padding(2.dp),
        )
    }
}

/**
 * Picks the colors of a workspace theme of the user's own: one color, or a gradient of up to three, each picked on a
 * field of shades under a bar of hues, or typed as a color code.
 *
 * @param initial The colors to start from, like the workspace's current theme.
 * @param onPick Takes the picked colors, as ARGB.
 */
@Suppress("LongMethod")
@Composable
internal fun ThemeColorDialog(initial: List<Int>, onPick: (List<Int>) -> Unit, onDismiss: () -> Unit) {
    val stops = remember {
        mutableStateListOf<Int>().apply { addAll(initial.take(MAX_STOPS).ifEmpty { listOf(DEFAULT_COLOR) }) }
    }
    var editing by remember { mutableIntStateOf(0) }
    // The hue is kept apart from the color, so it stays put while the color goes through white, gray or black.
    var hsv by remember { mutableStateOf(Hsv.of(stops[0])) }
    fun edit(index: Int) {
        editing = index
        hsv = Hsv.of(stops[index])
    }
    fun change(next: Hsv) {
        hsv = next
        stops[editing] = next.toArgb()
    }

    DejavuDialog(
        title = stringResource(R.string.dejavu_workspace_theme_custom),
        onDismissRequest = onDismiss,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_save),
                onClick = { onPick(stops.toList()) },
                style = DejavuButtonStyle.Primary,
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PreviewHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(themeBrush(stops.map { Color(it) }, opacity = 1f)),
        )
        Spacer(Modifier.size(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            stops.forEachIndexed { index, color ->
                StopChip(
                    color = Color(color),
                    selected = index == editing,
                    onClick = { edit(index) },
                    onRemove = if (stops.size > 1 && index == editing) {
                        {
                            stops.removeAt(index)
                            edit((index - 1).coerceAtLeast(0))
                        }
                    } else {
                        null
                    },
                )
            }
            if (stops.size < MAX_STOPS) {
                AddStopChip {
                    // A new color starts a little further round the hues than the last one, for a gentle gradient.
                    stops.add(Hsv.of(stops.last()).let { it.copy(hue = (it.hue + NEW_STOP_HUE_STEP) % FULL_TURN) }.toArgb())
                    edit(stops.lastIndex)
                }
            }
        }
        Spacer(Modifier.size(14.dp))
        ShadeField(hsv = hsv, onChange = ::change)
        Spacer(Modifier.size(14.dp))
        HueBar(hue = hsv.hue, onChange = { change(hsv.copy(hue = it)) })
        Spacer(Modifier.size(14.dp))
        HexField(color = stops[editing], onColor = { color -> change(Hsv.of(color).keepHueOf(hsv)) })
    }
}

/** One color of the theme being picked; the one being edited has a ring, and a cross to take it out when it can go. */
@Composable
private fun StopChip(color: Color, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    Box {
        Box(
            modifier = Modifier
                .size(StopSize)
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = EDGE_ALPHA), CircleShape)
                .clickable(onClick = onClick)
                .semantics { this.selected = selected },
        )
        if (onRemove != null) {
            Icon(
                painter = iconPainter(iconsR.drawable.mozac_ic_cross_24),
                contentDescription = stringResource(R.string.dejavu_workspace_theme_remove_color),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onRemove)
                    .padding(3.dp),
            )
        }
    }
}

/** Adds a color to the theme being picked, for a gradient. */
@Composable
private fun AddStopChip(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(StopSize)
            .padding(4.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = EDGE_ALPHA), CircleShape)
            .clickable(onClick = onClick),
    ) {
        Icon(
            painter = iconPainter(iconsR.drawable.mozac_ic_plus_24),
            contentDescription = stringResource(R.string.dejavu_workspace_theme_add_color),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The shades of [hsv]'s hue: paler to the start, darker to the bottom. Dragging on it picks one. */
@Composable
private fun ShadeField(hsv: Hsv, onChange: (Hsv) -> Unit) {
    val latest by rememberUpdatedState(hsv)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(SHADE_FIELD_RATIO)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                fun pick(at: Offset, field: IntSize) = latest.copy(
                    saturation = (at.x / field.width).coerceIn(0f, 1f),
                    value = 1f - (at.y / field.height).coerceIn(0f, 1f),
                )
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onChange(pick(down.position, size))
                    drag(down.id) { change ->
                        change.consume()
                        onChange(pick(change.position, size))
                    }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Hsv(hsv.hue, 1f, 1f).toColor())))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawThumb(Offset(hsv.saturation * size.width, (1f - hsv.value) * size.height), hsv.toColor())
    }
}

/** All the hues, from red round to red again. Dragging on it picks one. */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(HueBarHeight)
            .pointerInput(Unit) {
                fun pick(at: Offset) = FULL_TURN * (at.x / size.width).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onChange(pick(down.position))
                    drag(down.id) { change ->
                        change.consume()
                        onChange(pick(change.position))
                    }
                }
            },
    ) {
        val radius = size.height / 2
        drawRoundRect(Brush.horizontalGradient(HueColors), cornerRadius = CornerRadius(radius, radius))
        val x = (hue / FULL_TURN * size.width).coerceIn(radius, size.width - radius)
        drawThumb(Offset(x, radius), Hsv(hue, 1f, 1f).toColor())
    }
}

/** The ring that marks the picked spot on [ShadeField] and [HueBar], filled with the color there. */
private fun DrawScope.drawThumb(center: Offset, color: Color) {
    val radius = ThumbRadius.toPx()
    drawCircle(color, radius, center)
    drawCircle(Color.White, radius, center, style = Stroke(width = ThumbStroke.toPx()))
    drawCircle(Color.Black.copy(alpha = THUMB_SHADOW_ALPHA), radius + ThumbStroke.toPx(), center, style = Stroke(1.dp.toPx()))
}

/** The edited color as a code like #F2B84B, which can also be typed in. */
@Composable
private fun HexField(color: Int, onColor: (Int) -> Unit) {
    var text by remember { mutableStateOf(hexOf(color)) }
    // A color picked on the field or the bar shows its code, unless the code typed in already means that color.
    LaunchedEffect(color) {
        if (parseHex(text) != color) text = hexOf(color)
    }
    DejavuTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.uppercase().filter { it == '#' || it.isDigit() || it in 'A'..'F' }.take(HEX_FIELD_LENGTH)
            parseHex(text)?.let(onColor)
        },
        label = stringResource(R.string.dejavu_workspace_theme_code),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun hexOf(color: Int) = "#%06X".format(color and RGB_MASK)

/** The opaque color of a code like #F2B84B or F2B84B, or `null` when the code is not complete. */
internal fun parseHex(text: String): Int? {
    val digits = text.removePrefix("#")
    if (digits.length != HEX_DIGITS) return null
    return digits.toIntOrNull(radix = 16)?.let { it or OPAQUE }
}

/** A color as hue, from 0 to 360, and saturation and value, from 0 to 1. */
internal data class Hsv(val hue: Float, val saturation: Float, val value: Float) {
    fun toArgb(): Int = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))

    fun toColor() = Color(toArgb())

    /** This color with the hue of [other] when this one has none to speak of, being white, gray or black. */
    fun keepHueOf(other: Hsv) = if (saturation == 0f || value == 0f) copy(hue = other.hue) else this

    companion object {
        fun of(color: Int): Hsv {
            val values = FloatArray(3)
            android.graphics.Color.colorToHSV(color, values)
            return Hsv(values[0], values[1], values[2])
        }
    }
}

private val HueColors = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
)

private val PreviewHeight = 56.dp
private val StopSize = 44.dp
private val HueBarHeight = 28.dp
private val ThumbRadius = 11.dp
private val ThumbStroke = 3.dp

private const val MAX_STOPS = 3
private const val FULL_TURN = 360f
private const val NEW_STOP_HUE_STEP = 30f
private const val SHADE_FIELD_RATIO = 1.6f
private const val EDGE_ALPHA = 0.2f
private const val ICON_BACKDROP_ALPHA = 0.25f
private const val THUMB_SHADOW_ALPHA = 0.3f
private const val HEX_DIGITS = 6
private const val HEX_FIELD_LENGTH = 7
private const val RGB_MASK = 0xFFFFFF
private const val OPAQUE = 0xFF000000.toInt()

/** Dejavu's gold, where the picker starts for a workspace without a theme. */
private val DEFAULT_COLOR = WorkspaceTheme.Gold.colors[1]
