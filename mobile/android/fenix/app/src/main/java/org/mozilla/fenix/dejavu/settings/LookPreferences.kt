/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.annotation.ColorRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.discover.NewThing
import org.mozilla.fenix.dejavu.discover.newDot
import org.mozilla.fenix.dejavu.discover.rememberUnseenNewThings
import org.mozilla.fenix.dejavu.ui.AccentColor
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.IconStyle
import org.mozilla.fenix.dejavu.ui.iconIn
import org.mozilla.fenix.settings.ComposePreference

/**
 * The accent colors of Customize's Theme (issue #218), as a row of colored circles. Picking one starts the screen
 * again, as the screens made of views take an accent as they start.
 */
class AccentColorPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ComposePreference(context, attrs) {
    @Composable
    override fun Content() {
        val settings = remember { DejavuSettings.get(context) }
        val picked by settings.accentColor.collectAsState()
        val activity = LocalActivity.current
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.dejavu_settings_accent_color),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(end = NEW_DOT_ROOM)
                    .newDot(NewThing.ACCENT_COLOR in rememberUnseenNewThings(), inset = 0.dp),
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP),
                verticalArrangement = Arrangement.spacedBy(SWATCH_GAP),
            ) {
                AccentColor.available().forEach { accent ->
                    AccentSwatch(accent = accent, selected = accent == picked) {
                        if (accent != picked) {
                            settings.setAccentColor(accent)
                            activity?.recreate()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentSwatch(accent: AccentColor, selected: Boolean, onClick: () -> Unit) {
    val color = colorResource(accent.swatch())
    val label = stringResource(accent.label)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(SWATCH_SIZE)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        val mark = when {
            selected -> iconsR.drawable.mozac_ic_checkmark_24
            accent == AccentColor.SYSTEM -> iconsR.drawable.mozac_ic_wallpaper_24
            else -> null
        }
        if (mark != null) {
            Icon(
                painter = painterResource(mark),
                contentDescription = null,
                tint = if (color.luminance() > ON_SWATCH_LUMINANCE) Color.Black else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The color an accent's circle shows: its own in the theme shown. */
@ColorRes
private fun AccentColor.swatch(): Int = when (this) {
    AccentColor.NEUTRAL -> R.color.fx_mobile_primary
    AccentColor.SYSTEM -> R.color.dejavu_accent_system
    AccentColor.YELLOW -> R.color.dejavu_accent_yellow
    AccentColor.ORANGE -> R.color.dejavu_accent_orange
    AccentColor.RED -> R.color.dejavu_accent_red
    AccentColor.PINK -> R.color.dejavu_accent_pink
    AccentColor.PURPLE -> R.color.dejavu_accent_purple
    AccentColor.BLUE -> R.color.dejavu_accent_blue
    AccentColor.CYAN -> R.color.dejavu_accent_cyan
    AccentColor.GREEN -> R.color.dejavu_accent_green
}

/**
 * The styles of icons of Customize (issue #233), side by side, each showing a few icons drawn in it. The icons shown
 * take a style at once.
 */
class IconStylePreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ComposePreference(context, attrs) {
    @Composable
    override fun Content() {
        val settings = remember { DejavuSettings.get(context) }
        val picked by settings.iconStyle.collectAsState()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            IconStyle.entries.forEach { style ->
                IconStyleChoice(
                    style = style,
                    selected = style == picked,
                    onClick = { settings.setIconStyle(style) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun IconStyleChoice(style: IconStyle, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChoiceCard(label = stringResource(style.label), selected = selected, onClick = onClick, modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PREVIEW_ICONS.forEach { icon ->
                Icon(
                    painter = painterResource(iconIn(style, icon)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The app icons of Customize, side by side as launchers show them. Picking one asks first, as Android may close
 * Dejavu to change it.
 */
class AppIconPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ComposePreference(context, attrs) {
    @Composable
    override fun Content() {
        val activity = LocalActivity.current
        var shown by remember { mutableStateOf(DejavuAppIcon.current(context)) }
        var asked by remember { mutableStateOf<DejavuAppIcon?>(null) }
        // The new icons have the orange dot until Customize is left; Classic is the icon Dejavu had before.
        val iconsNew = NewThing.APP_ICON in rememberUnseenNewThings()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            DejavuAppIcon.entries.forEach { icon ->
                ChoiceCard(
                    label = stringResource(icon.label),
                    selected = icon == shown,
                    onClick = { if (icon != shown) asked = icon },
                    modifier = Modifier.weight(1f),
                ) {
                    AppIconPicture(icon, isNew = iconsNew && icon != DejavuAppIcon.CLASSIC)
                }
            }
        }
        asked?.let { icon ->
            ChangeAppIconDialog(
                onDismiss = { asked = null },
                onChange = {
                    asked = null
                    if (activity != null && DejavuAppIcon.change(activity, from = shown, to = icon)) {
                        shown = icon
                    } else {
                        Toast.makeText(context, R.string.dejavu_app_icon_change_failed, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }
}

/** The picture of [icon] as launchers show it, with the orange dot of what is new while [isNew]. */
@Composable
private fun AppIconPicture(icon: DejavuAppIcon, isNew: Boolean) {
    val context = LocalContext.current
    val size = with(LocalDensity.current) { APP_ICON_SIZE.roundToPx() }
    val picture = remember(icon, size) { icon.drawable(context)?.toBitmap(size, size)?.asImageBitmap() }
    Box(modifier = Modifier.size(APP_ICON_SIZE).newDot(isNew, inset = 0.dp)) {
        picture?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun ChangeAppIconDialog(onDismiss: () -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    val removesShortcuts = remember { DejavuAppIcon.removesShortcuts(context) }
    DejavuDialog(
        title = stringResource(R.string.dejavu_app_icon_change_title),
        onDismissRequest = onDismiss,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_app_icon_change),
                onClick = onChange,
                style = DejavuButtonStyle.Primary,
            )
        },
    ) {
        val message = if (removesShortcuts) {
            R.string.dejavu_app_icon_change_message_shortcuts
        } else {
            R.string.dejavu_app_icon_change_message
        }
        Text(stringResource(message, stringResource(R.string.app_name)))
    }
}

/** A choice of Customize: [picture] over its [label], on a card outlined in the accent color while [selected]. */
@Composable
private fun ChoiceCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    picture: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(shape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        picture()
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

private val PREVIEW_ICONS = listOf(
    R.drawable.dejavu_ic_home_24,
    R.drawable.dejavu_ic_bookmarks_24,
    R.drawable.dejavu_ic_settings_24,
)
private val SWATCH_SIZE = 44.dp
private val APP_ICON_SIZE = 56.dp
private val NEW_DOT_ROOM = 12.dp
private val SWATCH_GAP = 8.dp
private const val ON_SWATCH_LUMINANCE = 0.5f
