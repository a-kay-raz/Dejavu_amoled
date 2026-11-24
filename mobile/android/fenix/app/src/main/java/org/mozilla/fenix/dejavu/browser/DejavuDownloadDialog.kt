/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import mozilla.components.feature.downloads.R as downloadsR
import mozilla.components.support.utils.ext.withExtension
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuDialogCard
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.ui.DejavuTextField
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.iconPainter
import org.mozilla.fenix.downloads.enableConfirmButton

/**
 * Dejavu's prompt before a download, on a card in the colors of the shown tab's workspace: the name the file is saved
 * under, its extension kept as it is, and the folder it is saved in, which opens a folder picker.
 *
 * @param title What is downloaded, with its size when it is known.
 * @param fileName The name the page gave the file.
 * @param friendlyPath The folder the file is saved in, shortened for showing.
 * @param onFileNameChange Takes the whole new name, extension included.
 */
@Suppress("LongParameterList")
@Composable
internal fun DejavuDownloadDialog(
    title: String,
    fileName: String,
    friendlyPath: String,
    onFileNameChange: (String) -> Unit,
    onDirectorySelect: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    // Only the name before the extension is edited, and it is all picked to type over it, like Firefox's prompt.
    val (initialBaseName, extension) = remember {
        val file = File(fileName)
        file.nameWithoutExtension to file.extension
    }
    var name by remember {
        mutableStateOf(TextFieldValue(text = initialBaseName, selection = TextRange(0, initialBaseName.length)))
    }
    var isFocused by remember { mutableStateOf(false) }
    LaunchedEffect(isFocused) {
        if (isFocused) name = name.copy(selection = TextRange(0, File(name.text).nameWithoutExtension.length))
    }
    val isInvalid = name.text.contains("/")
    val canDownload = enableConfirmButton(fileName = name.text.trim().withExtension(extension))

    CompositionLocalProvider(LocalPopupTheme provides shownPopupTheme()) {
        DejavuDialogCard(
            title = title,
            icon = R.drawable.dejavu_ic_downloads_24,
            buttons = {
                DejavuDialogButton(
                    text = stringResource(downloadsR.string.mozac_feature_downloads_dialog_cancel),
                    onClick = onCancel,
                )
                DejavuDialogButton(
                    text = stringResource(downloadsR.string.mozac_feature_downloads_dialog_download),
                    onClick = onConfirm,
                    style = DejavuButtonStyle.Primary,
                    enabled = canDownload,
                )
            },
        ) {
            DejavuTextField(
                value = name,
                onValueChange = {
                    name = it
                    onFileNameChange(it.text.withExtension(extension))
                },
                label = stringResource(R.string.download_rename_dialog_label),
                suffix = extension.takeIf { it.isNotEmpty() }?.let { ".$it" },
                isError = isInvalid,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused },
            )
            if (isInvalid) {
                Text(
                    text = stringResource(R.string.download_rename_error_invalid_name_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }
            FolderRow(friendlyPath = friendlyPath, onClick = onDirectorySelect)
        }
    }
}

/** The folder a download is saved in, which can be tapped to pick another one. */
@Composable
private fun FolderRow(friendlyPath: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(DejavuPopup.ItemShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Icon(
            painter = iconPainter(iconsR.drawable.mozac_ic_folder_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = friendlyPath,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = iconPainter(iconsR.drawable.mozac_ic_chevron_right_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
