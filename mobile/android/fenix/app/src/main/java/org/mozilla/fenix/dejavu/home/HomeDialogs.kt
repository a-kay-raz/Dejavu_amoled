/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mozilla.components.ui.icons.R as iconsR
import androidx.biometric.BiometricManager
import org.mozilla.fenix.R
import mozilla.components.browser.state.state.ContainerState
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerIcon
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.NoContainerIcon
import org.mozilla.fenix.dejavu.containers.TemporaryContainerIcon
import org.mozilla.fenix.dejavu.containers.color
import org.mozilla.fenix.dejavu.containers.splitTemporary
import org.mozilla.fenix.dejavu.discover.NewThing
import org.mozilla.fenix.dejavu.discover.NewThings
import org.mozilla.fenix.dejavu.discover.newDotAfterText
import org.mozilla.fenix.dejavu.discover.rememberUnseenNewThings
import org.mozilla.fenix.dejavu.settings.ContainerEditorDialog
import org.mozilla.fenix.dejavu.sync.DejavuSync
import org.mozilla.fenix.dejavu.sync.DejavuSyncSource
import org.mozilla.fenix.dejavu.sync.workspaceIconText
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.ui.DejavuTextField
import org.mozilla.fenix.dejavu.ui.iconPainter
import org.mozilla.fenix.dejavu.workspaces.MAX_FOLDER_DEPTH
import org.mozilla.fenix.dejavu.workspaces.WallpaperArt
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper
import org.mozilla.fenix.settings.biometric.ext.isAuthenticatorAvailable

/**
 * Changes to tabs, pinned tabs and folders that need the user's input first, asked for by [ItemDialogs]: on the home
 * screen, and for the shown tab in the browser.
 */
interface TabEditor {
    fun onCreateFolder(workspaceId: String, parentId: String?, name: String, targets: ActionTargets)
    fun onRenameFolder(folderId: String, name: String)

    /** Gives pinned tab [pinId] or unpinned tab [tabId] the title [name]; a blank name shows the page title again. */
    fun onRenameTab(pinId: String?, tabId: String?, name: String)

    /** Pins [targets] in [workspaceId], inside [folderId] or at the end of the top level when it is `null`. */
    fun onMoveToFolder(workspaceId: String, targets: ActionTargets, folderId: String?)

    /** Removes pinned tabs, essentials and folders with everything inside them, and closes every tab of [targets]. */
    fun onDeleteItems(targets: ActionTargets)
    fun onMoveToWorkspace(targets: ActionTargets, workspaceId: String)

    /** Moves the tabs of [targets] to the container [pick]; all of them share one new temporary container. */
    fun onChangeContainer(targets: ActionTargets, pick: ContainerPick)
    fun onManageContainers()
}

/** Shows [dialog] and forwards the user's choice to [interactor]. */
@Composable
internal fun HomeDialogs(
    dialog: HomeDialog,
    state: WorkspaceState,
    containers: Map<String, ContainerRecord>,
    wallpapers: Map<String, WorkspaceWallpaper>,
    interactor: DejavuHomeInteractor,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is HomeDialog.EditWorkspace -> {
            val workspace = state.workspaces.firstOrNull { it.id == dialog.workspaceId }
            val sync by DejavuSync.status.collectAsState()
            WorkspaceDialog(
                workspace = workspace,
                syncsWithFirefox = sync.signedIn && sync.source == DejavuSyncSource.FIREFOX,
                wallpaper = workspace?.let { wallpapers[it.id] },
                containers = containers.values.filterNot { it.showsAsTemporary },
                onCreateContainer = interactor::onCreateContainer,
                onDrawWallpaper = interactor::onDrawWallpaper,
                onImportWallpaper = interactor::onImportWallpaper,
                onDiscardWallpaper = interactor::onDiscardWallpaper,
                onSave = { name, containerId, icon, theme, wallpaper, locked, savesHistory ->
                    interactor.onSaveWorkspace(
                        workspace?.id,
                        name,
                        containerId,
                        icon,
                        theme,
                        wallpaper,
                        locked,
                        savesHistory,
                    )
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        is HomeDialog.DeleteWorkspace -> {
            val workspace = state.workspaces.firstOrNull { it.id == dialog.workspaceId }
            ConfirmDialog(
                title = stringResource(R.string.dejavu_workspace_delete_title, workspace?.name.orEmpty()),
                message = stringResource(R.string.dejavu_workspace_delete_message),
                confirm = stringResource(R.string.dejavu_delete),
                onConfirm = {
                    workspace?.let { interactor.onDeleteWorkspace(it.id) }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        else -> ItemDialogs(dialog, state, containers, editor = interactor, onDismiss = onDismiss)
    }
}

/** Shows [dialog], one about tabs, pinned tabs or folders, and forwards the user's choice to [editor]. */
@Suppress("LongMethod")
@Composable
internal fun ItemDialogs(
    dialog: HomeDialog,
    state: WorkspaceState,
    containers: Map<String, ContainerRecord>,
    editor: TabEditor,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is HomeDialog.EditWorkspace, is HomeDialog.DeleteWorkspace -> Unit

        is HomeDialog.NewFolder -> NameDialog(
            title = R.string.dejavu_action_new_folder,
            initial = "",
            onConfirm = { name ->
                editor.onCreateFolder(dialog.workspaceId, dialog.parentId, name, dialog.targets)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is HomeDialog.RenameTab -> NameDialog(
            title = R.string.dejavu_action_rename_tab,
            initial = dialog.title,
            allowBlank = true,
            label = R.string.dejavu_rename_tab_hint,
            onConfirm = { name ->
                editor.onRenameTab(dialog.pinId, dialog.tabId, name)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is HomeDialog.RenameFolder -> NameDialog(
            title = R.string.dejavu_folder_rename,
            initial = dialog.folder.title,
            onConfirm = { name ->
                editor.onRenameFolder(dialog.folder.id, name)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is HomeDialog.DeleteItems -> {
            val targets = dialog.targets
            val single = (targets.pins + targets.folders).singleOrNull()?.takeIf { targets.tabs.isEmpty() }
            val count = targets.pins.size + targets.folders.size + targets.tabs.size
            ConfirmDialog(
                title = if (single != null) {
                    stringResource(R.string.dejavu_delete_item_title, single.title)
                } else {
                    pluralStringResource(R.plurals.dejavu_delete_items_title, count, count)
                },
                message = stringResource(R.string.dejavu_delete_items_message),
                confirm = stringResource(R.string.dejavu_delete),
                onConfirm = {
                    editor.onDeleteItems(targets)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        is HomeDialog.MoveToFolder -> {
            var naming by remember { mutableStateOf(false) }
            if (naming) {
                NameDialog(
                    title = R.string.dejavu_action_new_folder,
                    initial = "",
                    onConfirm = { name ->
                        editor.onCreateFolder(dialog.workspaceId, null, name, dialog.targets)
                        onDismiss()
                    },
                    onDismiss = onDismiss,
                )
            } else {
                FolderPickerDialog(
                    state = state,
                    workspaceId = dialog.workspaceId,
                    movingFolderIds = dialog.targets.folders.map { it.id }.toSet(),
                    onPick = { folderId ->
                        editor.onMoveToFolder(dialog.workspaceId, dialog.targets, folderId)
                        onDismiss()
                    },
                    onNewFolder = { naming = true },
                    onDismiss = onDismiss,
                )
            }
        }

        is HomeDialog.ChangeContainer -> {
            val current = dialog.targets.containerIds.singleOrNull()
            val allInNoContainer = dialog.targets.containerIds == setOf(null)
            val (permanent, temporary) = containers.values.splitTemporary()
            fun pick(choice: ContainerPick) {
                editor.onChangeContainer(dialog.targets, choice)
                onDismiss()
            }

            @Composable
            fun ContainerRows(records: List<ContainerRecord>) = records.forEach { container ->
                PickerRow(
                    label = container.name,
                    selected = current == container.contextId,
                    leading = { ContainerIcon(container) },
                    onClick = { pick(ContainerPick.Container(container.contextId)) },
                )
            }
            PickerDialog(title = R.string.dejavu_action_change_container, onDismiss = onDismiss) {
                PickerRow(
                    label = stringResource(R.string.dejavu_workspace_no_container),
                    selected = allInNoContainer,
                    leading = { NoContainerIcon() },
                    onClick = { pick(ContainerPick.NoContainer) },
                )
                if (permanent.isNotEmpty()) DialogDivider()
                ContainerRows(permanent)
                PickerRow(
                    label = stringResource(R.string.dejavu_container_add),
                    leading = { PickerIcon(iconsR.drawable.mozac_ic_plus_24) },
                    onClick = {
                        onDismiss()
                        editor.onManageContainers()
                    },
                )
                DialogDivider()
                PickerRow(
                    label = stringResource(R.string.dejavu_new_temporary_container),
                    leading = { TemporaryContainerIcon() },
                    onClick = { pick(ContainerPick.Temporary) },
                )
                ContainerRows(temporary)
            }
        }

        is HomeDialog.MoveToWorkspace -> PickerDialog(
            title = R.string.dejavu_action_move_to_workspace,
            onDismiss = onDismiss,
        ) {
            // Essentials can also go to the workspace they are looked at from, as its pinned tabs.
            val hasEssentials = dialog.targets.pins.any { it.essential }
            state.workspaces.filter { hasEssentials || it.id != dialog.workspaceId }.forEach { workspace ->
                val allowed = workspace.canTake(dialog.targets)
                PickerRow(
                    label = workspace.name,
                    enabled = allowed,
                    leading = {
                        if (allowed) {
                            WorkspaceMark(workspace, workspace.containerId?.let { containers[it] })
                        } else {
                            PickerIcon(R.drawable.dejavu_ic_no_container_24)
                        }
                    },
                    onClick = {
                        editor.onMoveToWorkspace(dialog.targets, workspace.id)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * Creates or edits a workspace: its icon, name, look, default container, and whether it is locked and saves history.
 * The look is either a theme of colors or a wallpaper, which gives the theme the colors taken from its picture, so that
 * Zen, which syncs themes but not pictures, shows the same colors. [onDrawWallpaper] draws a wallpaper Dejavu draws,
 * [onImportWallpaper] copies a picture picked for the wallpaper, and [onDiscardWallpaper] deletes a picture that ends
 * up unused. While workspaces [syncsWithFirefox], the dialog says what Firefox Sync leaves out.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun WorkspaceDialog(
    workspace: Workspace?,
    syncsWithFirefox: Boolean,
    wallpaper: WorkspaceWallpaper?,
    containers: List<ContainerRecord>,
    onCreateContainer: (name: String, color: ContainerColor, icon: ContainerState.Icon) -> String,
    onDrawWallpaper: suspend (WallpaperArt) -> WorkspaceWallpaper?,
    onImportWallpaper: suspend (Uri) -> WorkspaceWallpaper?,
    onDiscardWallpaper: (file: String) -> Unit,
    onSave: (
        name: String,
        containerId: String?,
        icon: String?,
        theme: WorkspaceTheme?,
        wallpaper: WorkspaceWallpaper?,
        locked: Boolean,
        savesHistory: Boolean,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(workspace?.name.orEmpty()) }
    var locked by remember { mutableStateOf(workspace?.locked == true) }
    var savesHistory by remember { mutableStateOf(workspace?.savesHistory != false) }
    var containerId by remember { mutableStateOf(workspace?.containerId) }
    var icon by remember { mutableStateOf(workspace?.icon) }
    var theme by remember { mutableStateOf(workspace?.theme) }
    val savedWallpaper = remember { wallpaper }
    var shownWallpaper by remember { mutableStateOf(wallpaper) }
    var usesWallpaper by remember { mutableStateOf(wallpaper != null) }
    var creatingContainer by remember { mutableStateOf(false) }
    var pickingColors by remember { mutableStateOf(false) }
    val isNew = workspace == null
    // The workspace of a device on the account takes its name and icon from the device, which keeps no container.
    val isDevice = workspace?.device != null

    // A picture picked here is only kept once the workspace is saved with it.
    fun dropPicked(keep: String?) {
        shownWallpaper?.file?.takeIf { it != keep && it != savedWallpaper?.file }?.let(onDiscardWallpaper)
    }
    val cancel = {
        dropPicked(keep = null)
        onDismiss()
    }
    // The colors of a wallpaper saved before wallpapers had colors come once they are taken from its picture.
    LaunchedEffect(wallpaper) {
        val current = shownWallpaper
        if (wallpaper != null && current?.file == wallpaper.file && current.colors.isEmpty()) {
            shownWallpaper = current.copy(colors = wallpaper.colors)
        }
    }
    val save = {
        val keptWallpaper = shownWallpaper?.takeIf { usesWallpaper }
        if (keptWallpaper == null) dropPicked(keep = null)
        val savedTheme = keptWallpaper?.colors?.takeIf { it.isNotEmpty() }?.let { colors ->
            WorkspaceTheme(
                colors = colors,
                opacity = theme?.opacity ?: WorkspaceTheme.DEFAULT_OPACITY,
                texture = theme?.texture ?: 0f,
            )
        } ?: theme
        onSave(name, containerId, icon, savedTheme, keptWallpaper, locked, savesHistory)
    }

    if (creatingContainer) {
        ContainerEditorDialog(
            record = null,
            onSave = { containerName, color, containerIcon ->
                containerId = onCreateContainer(containerName, color, containerIcon)
                creatingContainer = false
            },
            onDelete = {},
            onDismiss = { creatingContainer = false },
        )
    }

    if (pickingColors) {
        ThemeColorDialog(
            initial = theme?.colors.orEmpty(),
            onPick = { colors ->
                theme = WorkspaceTheme(
                    colors = colors,
                    opacity = theme?.opacity ?: WorkspaceTheme.DEFAULT_OPACITY,
                    texture = theme?.texture ?: 0f,
                )
                pickingColors = false
            },
            onDismiss = { pickingColors = false },
        )
    }

    DejavuDialog(
        title = stringResource(if (isNew) R.string.dejavu_add_workspace else R.string.dejavu_workspace_edit),
        onDismissRequest = cancel,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = cancel)
            DejavuDialogButton(
                text = stringResource(if (isNew) R.string.dejavu_create else R.string.dejavu_save),
                onClick = save,
                style = DejavuButtonStyle.Primary,
            )
        },
    ) {
        if (isDevice) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                workspaceIconText(icon)?.let { Text(text = it, style = MaterialTheme.typography.titleLarge) }
                Spacer(Modifier.width(12.dp))
                Text(text = name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.size(12.dp))
            FirefoxSyncNote(R.string.dejavu_workspace_firefox_device)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkspaceIconAvatar(icon = icon, onIconTyped = { icon = it })
                Spacer(Modifier.width(12.dp))
                DejavuTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.dejavu_workspace_name),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.size(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { IconChoice(text = null, selected = icon == null, onClick = { icon = null }) }
                items(iconSuggestions) { suggestion ->
                    IconChoice(text = suggestion, selected = icon == suggestion, onClick = { icon = suggestion })
                }
            }
            if (syncsWithFirefox) {
                Spacer(Modifier.size(12.dp))
                FirefoxSyncNote(R.string.dejavu_workspace_firefox_own)
            }
        }

        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.dejavu_workspace_theme), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.size(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            LookChip(R.string.dejavu_workspace_theme_color, selected = !usesWallpaper) { usesWallpaper = false }
            LookChip(R.string.dejavu_workspace_wallpaper, selected = usesWallpaper) { usesWallpaper = true }
        }
        Spacer(Modifier.size(12.dp))
        if (usesWallpaper) {
            WallpaperSection(
                wallpaper = shownWallpaper,
                syncsWithZen = !isDevice && !syncsWithFirefox,
                onDraw = onDrawWallpaper,
                onImport = onImportWallpaper,
                onPicked = { picked ->
                    dropPicked(keep = picked.file)
                    shownWallpaper = picked.copy(intensity = shownWallpaper?.intensity ?: picked.intensity)
                },
                onIntensityChange = { intensity -> shownWallpaper = shownWallpaper?.copy(intensity = intensity) },
            )
        } else {
            ThemeColors(theme = theme, onChange = { theme = it }, onCustomColors = { pickingColors = true })
        }

        if (!isDevice) {
            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.dejavu_workspace_container), style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(R.string.dejavu_workspace_container_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RadioRow(
                label = stringResource(R.string.dejavu_workspace_no_container),
                selected = containerId == null,
                onClick = { containerId = null },
                leading = { NoContainerIcon() },
            )
            if (containers.isNotEmpty()) DialogDivider()
            containers.forEach { container ->
                RadioRow(
                    label = container.name,
                    selected = containerId == container.contextId,
                    onClick = { containerId = container.contextId },
                    leading = { ContainerIcon(container) },
                )
            }
            PickerRow(
                label = stringResource(R.string.dejavu_container_add),
                leading = { PickerIcon(iconsR.drawable.mozac_ic_plus_24) },
                onClick = { creatingContainer = true },
            )
        }

        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.preferences_category_privacy_security),
            style = MaterialTheme.typography.labelLarge,
        )
        WorkspacePrivacy(
            locked = locked,
            savesHistory = savesHistory,
            onLockedChange = { locked = it },
            onSavesHistoryChange = { savesHistory = it },
        )
    }
}

/**
 * Whether a workspace is [locked] and [savesHistory]. A workspace can only be locked while the phone has a screen lock
 * to unlock it with; without one, a row leads to the phone's settings to set it up.
 */
@Composable
private fun WorkspacePrivacy(
    locked: Boolean,
    savesHistory: Boolean,
    onLockedChange: (Boolean) -> Unit,
    onSavesHistoryChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val canLock = remember { BiometricManager.from(context).isAuthenticatorAvailable() }
    // New in 4.0: the orange dot stays on both switches while the dialog shows, and goes once it closes.
    val privacyNew = NewThing.WORKSPACE_PRIVACY in rememberUnseenNewThings()
    DisposableEffect(Unit) {
        onDispose { NewThings.seen(context, NewThing.WORKSPACE_PRIVACY) }
    }
    SwitchRow(
        label = stringResource(R.string.dejavu_workspace_lock),
        description = stringResource(R.string.dejavu_workspace_lock_summary),
        checked = locked,
        enabled = canLock || locked,
        isNew = privacyNew,
        onChange = onLockedChange,
    )
    if (!canLock) {
        PickerRow(
            label = stringResource(R.string.pbm_authentication_lock_device_feature_disabled),
            leading = { PickerIcon(iconsR.drawable.mozac_ic_lock_24) },
            onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
        )
    }
    SwitchRow(
        label = stringResource(R.string.dejavu_workspace_no_history),
        description = stringResource(R.string.dejavu_workspace_no_history_summary),
        checked = !savesHistory,
        isNew = privacyNew,
        onChange = { onSavesHistoryChange(!it) },
    )
}

/**
 * A row of a dialog with [label], [description] below it, and a switch at its end. [isNew] puts the orange dot of what
 * is new after the label.
 */
@Composable
internal fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    isNew: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .alpha(if (enabled) 1f else DISABLED_ROW_ALPHA)
            .padding(start = 8.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.newDotAfterText(isNew),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private const val MIN_THEME_OPACITY = 0.1f
private const val NOTE_ALPHA = 0.06f
private val PICKER_MAX_HEIGHT = 560.dp

/**
 * What Firefox Sync leaves out of a workspace, [text], since it only shares the open tabs of each device: for the
 * workspace of a device, or one of the user's own while workspaces sync with Firefox.
 */
@Composable
private fun FirefoxSyncNote(@StringRes text: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = NOTE_ALPHA))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(
            painter = iconPainter(iconsR.drawable.mozac_ic_sync_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.dejavu_workspace_firefox_limits),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One of the two looks a workspace can have, colors or a wallpaper, filled while it is the one picked. */
@Composable
internal fun LookChip(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .border(1.dp, if (selected) scheme.primary else Color.Transparent, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The colors of a workspace without a wallpaper: no theme, a preset or colors of the user's own, then the theme's
 * intensity and grain. [onCustomColors] opens the color picker.
 */
@Composable
internal fun ThemeColors(theme: WorkspaceTheme?, onChange: (WorkspaceTheme?) -> Unit, onCustomColors: () -> Unit) {
    // A workspace in colors of the user's own shows them at the end of the row, scrolled into view.
    val customColors = theme?.colors?.takeIf { it !in themePresets }
    val themeRow = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (customColors != null) themeRow.scrollToItem(themePresets.size + 1)
    }
    LazyRow(state = themeRow, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ThemeChoice(colors = null, selected = theme == null, onClick = { onChange(null) }) }
        items(themePresets) { colors ->
            ThemeChoice(
                colors = colors,
                selected = theme?.colors == colors,
                onClick = {
                    onChange(
                        WorkspaceTheme(
                            colors = colors,
                            opacity = theme?.opacity ?: WorkspaceTheme.DEFAULT_OPACITY,
                            texture = theme?.texture ?: 0f,
                        ),
                    )
                },
            )
        }
        item {
            CustomThemeChoice(colors = customColors, selected = customColors != null, onClick = onCustomColors)
        }
    }
    theme?.let { current ->
        SliderRow(R.string.dejavu_workspace_theme_intensity, current.opacity, MIN_THEME_OPACITY..1f) {
            onChange(current.copy(opacity = it))
        }
        SliderRow(R.string.dejavu_workspace_theme_grain, current.texture, 0f..1f) {
            onChange(current.copy(texture = it))
        }
    }
}

/**
 * The workspace's [icon] in a circle, or the no-icon sign without one. Icons are picked from the list below it, but
 * tapping the circle opens the keyboard too, for an emoji that is not in the list.
 */
@Composable
private fun WorkspaceIconAvatar(icon: String?, onIconTyped: (String?) -> Unit) {
    val shownIcon = workspaceIconText(icon).orEmpty()
    val description = stringResource(R.string.dejavu_workspace_icon)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = shownIcon,
        onValueChange = { if (it != shownIcon) lastGrapheme(it)?.let(onIconTyped) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(Color.Transparent),
        interactionSource = interactionSource,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else DejavuPopup.edgeColor,
                shape = CircleShape,
            )
            .semantics { contentDescription = description },
        decorationBox = { field ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                if (shownIcon.isEmpty()) NoContainerIcon(size = 24.dp, description = R.string.dejavu_no_icon)
                field()
            }
        },
    )
}

/** A choice of workspace icon: an emoji, or no icon when [text] is `null`. */
@Composable
private fun IconChoice(text: String?, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
    ) {
        if (text == null) {
            NoContainerIcon(size = 20.dp, description = R.string.dejavu_no_icon)
        } else {
            Text(text = text, fontSize = 20.sp)
        }
    }
}

/** A choice of workspace theme: a gradient of [colors], or no theme when it is `null`. */
@Composable
private fun ThemeChoice(colors: List<Int>?, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .then(
                if (colors == null) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                } else {
                    Modifier.background(themeBrush(colors.map { Color(it) }, opacity = 1f))
                },
            )
            .clickable(onClick = onClick),
    ) {
        if (colors == null) NoContainerIcon(size = 18.dp, description = R.string.dejavu_no_color)
    }
}

@Composable
internal fun SliderRow(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(88.dp),
        )
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@Suppress("LongParameterList")
@Composable
private fun FolderPickerDialog(
    state: WorkspaceState,
    workspaceId: String,
    movingFolderIds: Set<String>,
    onPick: (String?) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val blocked = movingFolderIds + movingFolderIds.flatMap { state.descendantIds(it) }
    val tallest = movingFolderIds.maxOfOrNull { state.folderHeight(it) } ?: 0
    val folders = state.pinnedTree(workspaceId, expandAll = true).filter { entry ->
        entry.item.isFolder && entry.item.id !in blocked && state.folderDepth(entry.item.id) + tallest <= MAX_FOLDER_DEPTH
    }

    PickerDialog(title = R.string.dejavu_action_move_to_folder, onDismiss = onDismiss) {
        PickerRow(
            label = stringResource(R.string.dejavu_folder_top_level),
            leading = { PickerIcon(iconsR.drawable.mozac_ic_pin_24) },
            onClick = { onPick(null) },
        )
        folders.forEach { entry ->
            PickerRow(
                label = entry.item.title,
                depth = entry.depth,
                leading = { PickerIcon(folderIcon(isEmpty = state.pins.none { it.parentId == entry.item.id })) },
                onClick = { onPick(entry.item.id) },
            )
        }
        PickerRow(
            label = stringResource(R.string.dejavu_action_new_folder),
            leading = { PickerIcon(R.drawable.dejavu_ic_create_folder_24) },
            onClick = onNewFolder,
        )
    }
}

@Composable
private fun NameDialog(
    @StringRes title: Int,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    allowBlank: Boolean = false,
    @StringRes label: Int = R.string.dejavu_workspace_name,
) {
    var name by remember { mutableStateOf(initial) }
    DejavuDialog(
        title = stringResource(title),
        onDismissRequest = onDismiss,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_save),
                onClick = { onConfirm(name.trim()) },
                style = DejavuButtonStyle.Primary,
                enabled = allowBlank || name.isNotBlank(),
            )
        },
    ) {
        DejavuTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(label),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DejavuDialog(
        title = title,
        onDismissRequest = onDismiss,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
            DejavuDialogButton(text = confirm, onClick = onConfirm, style = DejavuButtonStyle.Danger)
        },
    ) {
        Text(message)
    }
}

@Composable
private fun PickerDialog(
    @StringRes title: Int,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    DejavuDialog(
        title = stringResource(title),
        onDismissRequest = onDismiss,
        buttons = { DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss) },
        modifier = Modifier.heightIn(max = PICKER_MAX_HEIGHT),
    ) {
        content()
    }
}

@Suppress("LongParameterList")
@Composable
private fun PickerRow(
    label: String,
    onClick: () -> Unit,
    depth: Int = 0,
    selected: Boolean = false,
    enabled: Boolean = true,
    leading: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ROW_ALPHA)
            .padding(start = 8.dp + 16.dp * depth, top = 12.dp, bottom = 12.dp, end = 8.dp),
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = iconPainter(iconsR.drawable.mozac_ic_checkmark_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A workspace as the workspace bar shows it: its icon, or a dot in its container's color. */
@Composable
internal fun WorkspaceMark(workspace: Workspace, container: ContainerRecord?) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
        val icon = workspaceIconText(workspace.icon)
        if (icon != null) {
            Text(text = icon, fontSize = 16.sp)
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(container?.color?.color ?: MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

private const val DISABLED_ROW_ALPHA = 0.45f

@Composable
private fun DialogDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = DejavuPopup.edgeColor)
}

@Composable
private fun PickerIcon(icon: Int) {
    Icon(
        painter = iconPainter(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        leading()
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
