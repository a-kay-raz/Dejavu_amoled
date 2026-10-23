/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import android.content.res.Configuration
import android.view.Window
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.mozilla.fenix.dejavu.home.drawWorkspaceTheme
import org.mozilla.fenix.dejavu.home.rememberGrainBrush
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme

/**
 * Dejavu's look for dialogs and menus: a warm card lit by a little gold from its top corner, like the logo, with a
 * hairline edge. [DejavuTextField] is the text field that goes with it, and `dejavu_styles.xml` gives the dialogs
 * made of views the same shape, colors and buttons.
 */
object DejavuPopup {
    val DialogShape: Shape = RoundedCornerShape(28.dp)
    val MenuShape: Shape = RoundedCornerShape(20.dp)
    val ItemShape: Shape = RoundedCornerShape(12.dp)

    /** The fill of dialogs and menus. */
    val containerColor: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHigh

    /** The hairline around dialogs, menus and text fields. */
    val edgeColor: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface.copy(alpha = EDGE_ALPHA)

    /** The gold light in the top corner of dialogs and menus. */
    val glowColor: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary.copy(alpha = GLOW_ALPHA)
}

/**
 * The theme of the workspace that dialogs and menus open over, so they take on its colors. Screens that show a
 * workspace provide it; without one, popups keep their plain fill.
 */
val LocalPopupTheme = compositionLocalOf<WorkspaceTheme?> { null }

/**
 * Paints [DejavuPopup]'s light, which fades out from the top start corner, behind the content, over the colors of
 * [LocalPopupTheme]'s workspace, a little lighter than the workspace so the text stays easy to read.
 */
@Composable
fun Modifier.popupGlow(): Modifier {
    val isNight = (LocalView.current.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val glow = DejavuPopup.glowColor
    val grain = rememberGrainBrush()
    val tint = if (isNight) null else LocalPopupTheme.current?.let { theme ->
        theme.copy(
            opacity = (theme.opacity * TINT_STRENGTH).coerceAtMost(MAX_TINT),
            texture = theme.texture * TINT_STRENGTH,
        )
    }
    return drawBehind {
        tint?.let { drawWorkspaceTheme(it, next = null, fraction = 0f, grain = grain) }
        drawRect(
            Brush.radialGradient(
                colors = listOf(glow, Color.Transparent),
                center = Offset.Zero,
                radius = size.maxDimension * GLOW_REACH,
            ),
        )
    }
}

/** How a [DejavuDialogButton] looks: the main choice, a quiet one, or one that deletes something. */
enum class DejavuButtonStyle { Primary, Tonal, Danger, DangerText }

/**
 * A dialog in Dejavu's look: [title], with [icon] in a gold circle before it, then [content], which scrolls when it
 * is too tall, and [buttons], laid out from the end by [DejavuButtonRow]. Buttons are usually [DejavuDialogButton]s;
 * a [DejavuButtonGap] pushes the ones before it to the start.
 */
@Composable
fun DejavuDialog(
    title: String,
    onDismissRequest: () -> Unit,
    buttons: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.resizeForKeyboard() }
        Column(
            modifier = modifier
                .padding(horizontal = DIALOG_MARGIN, vertical = DIALOG_MARGIN)
                .widthIn(max = DIALOG_MAX_WIDTH)
                .fillMaxWidth()
                .shadow(DIALOG_SHADOW, DejavuPopup.DialogShape)
                .clip(DejavuPopup.DialogShape)
                .background(DejavuPopup.containerColor)
                .popupGlow()
                .border(1.dp, DejavuPopup.edgeColor, DejavuPopup.DialogShape)
                .padding(DIALOG_PADDING),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                ) {
                    content()
                }
            }
            DejavuButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), content = buttons)
        }
    }
}

/**
 * Buttons in a row from the end, 8dp apart. When they do not fit, each gets room in proportion to its text, whose
 * [DejavuFittedText] gets smaller to fit; when the texts would get too small, every button goes on a line of its own,
 * the last one on top. [padding] is the room a button takes besides its text.
 */
@Composable
fun DejavuButtonRow(
    modifier: Modifier = Modifier,
    padding: Dp = BUTTON_PADDING * 2,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val spacing = BUTTON_SPACING.roundToPx()
        val fixed = padding.roundToPx()
        val gap = measurables.indexOfFirst { it.layoutId == BUTTON_GAP }
        val buttons = measurables.filterIndexed { index, _ -> index != gap }
        val natural = buttons.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val room = constraints.maxWidth - spacing * (buttons.size - 1).coerceAtLeast(0)
        val texts = (natural.sum() - fixed * buttons.size).coerceAtLeast(1)
        val scale = ((room - fixed * buttons.size).toFloat() / texts).coerceAtMost(1f)
        if (scale >= MIN_ROW_SCALE) {
            val placeables = buttons.mapIndexed { index, button ->
                val width = if (scale < 1f) fixed + ((natural[index] - fixed) * scale).toInt() else natural[index]
                button.measure(Constraints(maxWidth = width.coerceIn(0, constraints.maxWidth)))
            }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(constraints.maxWidth, height) {
                val leading = if (gap < 0) 0 else gap
                var x = 0
                placeables.take(leading).forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + spacing
                }
                x = constraints.maxWidth
                placeables.drop(leading).asReversed().forEach {
                    x -= it.width
                    it.placeRelative(x, (height - it.height) / 2)
                    x -= spacing
                }
            }
        } else {
            val placeables = buttons.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }.asReversed()
            val height = placeables.sumOf { it.height } + spacing * (placeables.size - 1).coerceAtLeast(0)
            layout(constraints.maxWidth, height) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(constraints.maxWidth - it.width, y)
                    y += it.height + spacing
                }
            }
        }
    }
}

/** Space in a [DejavuButtonRow] that pushes the buttons before it to the start. */
@Composable
fun DejavuButtonGap() {
    Spacer(Modifier.layoutId(BUTTON_GAP))
}

/** [text] on one line of a button, getting smaller when it is too long for the button, and cut off only after that. */
@Composable
fun DejavuFittedText(text: String, style: TextStyle, color: Color = Color.Unspecified) {
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * MIN_TEXT_SCALE, maxFontSize = style.fontSize),
    )
}

/** Lets the keyboard shrink the dialog, so its buttons stay above it while typing. */
@Suppress("DEPRECATION")
private fun Window.resizeForKeyboard() = setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

/** A pill shaped button for the bottom of a [DejavuDialog]. */
@Composable
fun DejavuDialogButton(
    text: String,
    onClick: () -> Unit,
    style: DejavuButtonStyle = DejavuButtonStyle.Tonal,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = when (style) {
        DejavuButtonStyle.Primary -> ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
        )
        DejavuButtonStyle.Tonal -> ButtonDefaults.buttonColors(
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
        )
        DejavuButtonStyle.Danger -> ButtonDefaults.buttonColors(
            containerColor = scheme.error,
            contentColor = scheme.onError,
        )
        DejavuButtonStyle.DangerText -> ButtonDefaults.textButtonColors(contentColor = scheme.error)
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = colors,
        elevation = null,
        contentPadding = PaddingValues(horizontal = BUTTON_PADDING, vertical = 10.dp),
    ) {
        DejavuFittedText(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A dropdown menu in Dejavu's look. Its items are [DejavuMenuItem]s, with [DejavuMenuLabel]s and [DejavuMenuDivider]s. */
@Composable
fun DejavuDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.popupGlow(),
        offset = offset,
        shape = DejavuPopup.MenuShape,
        containerColor = DejavuPopup.containerColor,
        tonalElevation = 0.dp,
        shadowElevation = MENU_SHADOW,
        border = BorderStroke(1.dp, DejavuPopup.edgeColor),
        content = content,
    )
}

/** An item of a [DejavuDropdownMenu], highlighted in a rounded box when pressed. */
@Composable
fun DejavuMenuItem(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    DropdownMenuItem(
        text = { Text(text = text, style = MaterialTheme.typography.bodyLarge) },
        onClick = onClick,
        modifier = Modifier.padding(horizontal = ITEM_INSET).clip(DejavuPopup.ItemShape),
        leadingIcon = leadingIcon,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = MaterialTheme.colorScheme.onSurface,
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}

/** A small heading over a group of items in a [DejavuDropdownMenu]. */
@Composable
fun DejavuMenuLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ITEM_INSET + 12.dp, vertical = 8.dp),
    )
}

/** A line between groups of items in a [DejavuDropdownMenu]. */
@Composable
fun DejavuMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = ITEM_INSET + 6.dp, vertical = 4.dp),
        color = DejavuPopup.edgeColor,
    )
}

private const val EDGE_ALPHA = 0.1f
private const val GLOW_ALPHA = 0.14f
private const val GLOW_REACH = 0.9f
private const val TINT_STRENGTH = 0.8f
private const val MAX_TINT = 0.45f
private val DIALOG_MARGIN = 24.dp
private val DIALOG_MAX_WIDTH = 560.dp
private val DIALOG_PADDING = 24.dp
private val DIALOG_SHADOW = 12.dp
private val MENU_SHADOW = 8.dp
private val BUTTON_PADDING = 18.dp
private val BUTTON_SPACING = 8.dp
private const val BUTTON_GAP = "gap"

/** How much smaller than its style a button's text can get. */
private const val MIN_TEXT_SCALE = 0.75f

/** How much smaller the texts of buttons in a row get before the buttons go on lines of their own. */
private const val MIN_ROW_SCALE = 0.8f
private val ITEM_INSET = 6.dp
