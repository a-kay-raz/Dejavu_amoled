/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.ContainerState
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerIcon
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.ContainerRemoval
import org.mozilla.fenix.dejavu.containers.NoContainerIcon
import org.mozilla.fenix.dejavu.containers.color
import org.mozilla.fenix.dejavu.containers.drawable
import org.mozilla.fenix.dejavu.ui.DejavuButtonGap
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.ui.DejavuTextField
import org.mozilla.fenix.dejavu.ui.iconPainter

private const val ITEMS_PER_ROW = 5

/**
 * Creates a container, or edits [record] when it is not `null`.
 */
@Composable
fun ContainerEditorDialog(
    record: ContainerRecord?,
    onSave: (name: String, color: ContainerColor, icon: ContainerState.Icon) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(record?.name.orEmpty()) }
    var color by remember { mutableStateOf(record?.color ?: ContainerColor.BLUE) }
    var icon by remember { mutableStateOf(record?.icon ?: ContainerState.Icon.FINGERPRINT) }

    DejavuDialog(
        title = stringResource(if (record == null) R.string.dejavu_container_add else R.string.dejavu_container_edit),
        onDismissRequest = onDismiss,
        buttons = {
            if (record != null) {
                DejavuDialogButton(
                    text = stringResource(R.string.dejavu_delete),
                    onClick = onDelete,
                    style = DejavuButtonStyle.DangerText,
                )
                DejavuButtonGap()
            }
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_save),
                onClick = { onSave(name.trim(), color, icon) },
                style = DejavuButtonStyle.Primary,
                enabled = name.isNotBlank(),
            )
        },
    ) {
        DejavuTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.dejavu_container_name),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.dejavu_container_color), style = MaterialTheme.typography.labelLarge)
        ContainerColor.pickable.chunked(ITEMS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    ChoiceCircle(selected = option == color, onClick = { color = option }) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(option.color))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.dejavu_container_icon), style = MaterialTheme.typography.labelLarge)
        ContainerState.Icon.entries.chunked(ITEMS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    ChoiceCircle(selected = option == icon, onClick = { icon = option }) {
                        Icon(
                            painter = iconPainter(option.drawable),
                            contentDescription = option.icon,
                            tint = color.color,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                repeat(ITEMS_PER_ROW - row.size) { Spacer(Modifier.size(44.dp)) }
            }
        }
    }
}

/**
 * Asks what happens to the tabs of a container before deleting it: close them, pinned tabs included, or reopen them
 * in another container or without one. Its cookies and site data are always deleted.
 */
@Suppress("LongMethod")
@Composable
fun DeleteContainerDialog(
    record: ContainerRecord,
    otherContainers: List<ContainerRecord>,
    onConfirm: (ContainerRemoval) -> Unit,
    onDismiss: () -> Unit,
) {
    var removal by remember { mutableStateOf<ContainerRemoval>(ContainerRemoval.CloseTabs) }

    DejavuDialog(
        title = stringResource(R.string.dejavu_container_delete_title, record.name),
        onDismissRequest = onDismiss,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_delete),
                onClick = { onConfirm(removal) },
                style = DejavuButtonStyle.Danger,
            )
        },
    ) {
        Text(stringResource(R.string.dejavu_container_delete_message))
        Spacer(Modifier.height(12.dp))
        RemovalOption(
            label = stringResource(R.string.dejavu_container_delete_close_tabs),
            selected = removal == ContainerRemoval.CloseTabs,
            onClick = { removal = ContainerRemoval.CloseTabs },
        )
        Text(
            text = stringResource(R.string.dejavu_container_delete_move_tabs),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        RemovalOption(
            label = stringResource(R.string.dejavu_workspace_no_container),
            selected = removal == ContainerRemoval.MoveTabs(null),
            onClick = { removal = ContainerRemoval.MoveTabs(null) },
            icon = { NoContainerIcon() },
        )
        if (otherContainers.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = DejavuPopup.edgeColor)
        }
        otherContainers.forEach { container ->
            RemovalOption(
                label = container.name,
                selected = removal == ContainerRemoval.MoveTabs(container.contextId),
                onClick = { removal = ContainerRemoval.MoveTabs(container.contextId) },
                icon = { ContainerIcon(container) },
            )
        }
    }
}

@Composable
private fun RemovalOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        if (icon != null) {
            icon()
            Spacer(Modifier.width(10.dp))
        }
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChoiceCircle(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
    ) {
        content()
    }
}
