/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.containers

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.ContainerState
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.IconTaps
import org.mozilla.fenix.dejavu.ui.TapIcon
import org.mozilla.fenix.dejavu.ui.iconPainter
import org.mozilla.fenix.dejavu.ui.rememberIconTaps

/** Icon of a container, taken from Firefox desktop. */
@get:DrawableRes
val ContainerState.Icon.drawable: Int
    get() = when (this) {
        ContainerState.Icon.FINGERPRINT -> R.drawable.dejavu_container_fingerprint
        ContainerState.Icon.BRIEFCASE -> R.drawable.dejavu_container_briefcase
        ContainerState.Icon.DOLLAR -> R.drawable.dejavu_container_dollar
        ContainerState.Icon.CART -> R.drawable.dejavu_container_cart
        ContainerState.Icon.CIRCLE -> R.drawable.dejavu_container_circle
        ContainerState.Icon.GIFT -> R.drawable.dejavu_container_gift
        ContainerState.Icon.VACATION -> R.drawable.dejavu_container_vacation
        ContainerState.Icon.FOOD -> R.drawable.dejavu_container_food
        ContainerState.Icon.FRUIT -> R.drawable.dejavu_container_fruit
        ContainerState.Icon.PET -> R.drawable.dejavu_container_pet
        ContainerState.Icon.TREE -> R.drawable.dejavu_container_tree
        ContainerState.Icon.CHILL -> R.drawable.dejavu_container_chill
        ContainerState.Icon.FENCE -> R.drawable.dejavu_container_fence
    }

val ContainerColor.color: Color
    get() = Color(argb)

/** Draws [record]'s icon in its color. */
@Composable
fun ContainerIcon(
    record: ContainerRecord,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Icon(
        painter = iconPainter(record.icon.drawable),
        contentDescription = record.name,
        tint = record.color.color,
        modifier = modifier.size(size),
    )
}

/**
 * Stands for "no container" wherever containers can be picked, so it never looks like one of them. The same crossed
 * circle stands for no icon or no colors, which say so with [description].
 */
@Composable
fun NoContainerIcon(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    @StringRes description: Int = R.string.dejavu_workspace_no_container,
) {
    Icon(
        painter = iconPainter(R.drawable.dejavu_ic_no_container_24),
        contentDescription = stringResource(description),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    )
}

/** Stands for a new temporary container. With [taps], it moves when its button is tapped. */
@Composable
fun TemporaryContainerIcon(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    taps: IconTaps = rememberIconTaps(),
) {
    TapIcon(
        icon = R.drawable.dejavu_ic_temporary_container_24,
        taps = taps,
        contentDescription = stringResource(R.string.dejavu_temporary_container),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    )
}
