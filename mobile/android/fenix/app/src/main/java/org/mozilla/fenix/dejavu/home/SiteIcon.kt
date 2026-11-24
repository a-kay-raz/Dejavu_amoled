/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import mozilla.components.browser.icons.Icon.Source
import mozilla.components.browser.icons.compose.Placeholder
import mozilla.components.browser.icons.compose.WithIcon
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.ui.iconPainter

/**
 * The icon of the site at [url], round. Until the site's own icon is known, as for tabs synced from other devices
 * that never loaded here, a globe stands in for it instead of a made up letter. The icon of a private tab is its own
 * [icon] when it has one, or else loaded like private browsing does ([isPrivate]): never with the cookies of normal
 * browsing, and never kept on disk.
 */
@Composable
internal fun SiteIcon(
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
    isPrivate: Boolean = false,
    icon: Bitmap? = null,
) {
    if (icon != null) {
        val bitmap = remember(icon) { icon.asImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
        return
    }
    components.core.icons.LoadableImage(url = url, isPrivate = isPrivate) {
        Placeholder { DefaultSiteIcon(size, modifier) }
        WithIcon { loaded ->
            if (loaded.source == Source.GENERATOR) {
                DefaultSiteIcon(size, modifier)
            } else {
                Image(
                    painter = loaded.painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier.size(size).clip(CircleShape),
                )
            }
        }
    }
}

@Composable
private fun DefaultSiteIcon(size: Dp, modifier: Modifier) {
    Icon(
        painter = iconPainter(iconsR.drawable.mozac_ic_globe_24),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    )
}
