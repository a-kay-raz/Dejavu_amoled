/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sidebery

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.state.createTab
import mozilla.components.compose.base.utils.toLocaleString
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.settings.DejavuComposeFragment
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.settings.SettingsHint
import org.mozilla.fenix.dejavu.settings.settingsCard
import org.mozilla.fenix.dejavu.sync.workspaceIconText
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.ext.components

/** Exports the workspaces and tabs to Sidebery, and imports Sidebery's, see [SideberyExport] and [SideberyImport]. */
class DejavuTabsImportExportFragment : DejavuComposeFragment(R.string.dejavu_settings_tabs_import_export) {
    @Composable
    override fun DejavuScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var pending by remember { mutableStateOf<Pair<SideberySnapshot, SideberyImport.Summary>?>(null) }
        val createFile = remember { ActivityResultContracts.CreateDocument(SideberyFile.MIME_TYPE) }
        val exporter = rememberLauncherForActivityResult(createFile) { uri ->
            if (uri != null) {
                scope.launch {
                    val message = if (exportTo(context, uri)) {
                        R.string.dejavu_sidebery_exported
                    } else {
                        R.string.dejavu_sidebery_export_failed
                    }
                    toast(context, message)
                }
            }
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val snapshot = readFrom(context, uri)
                    if (snapshot == null) {
                        toast(context, R.string.dejavu_sidebery_bad_file)
                    } else {
                        pending = snapshot to SideberyImporter(context).preview(snapshot)
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHint(stringResource(R.string.dejavu_sidebery_hint))
            IconListItem(
                label = stringResource(R.string.dejavu_sidebery_export),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_save_file_24),
                modifier = Modifier.settingsCard(index = 0, count = 2),
                onClick = { launchSafely { exporter.launch(SideberyFile.name(System.currentTimeMillis())) } },
            )
            IconListItem(
                label = stringResource(R.string.dejavu_sidebery_import),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_import_data_24),
                modifier = Modifier.settingsCard(index = 1, count = 2),
                onClick = { launchSafely { importer.launch(SideberyFile.OPEN_TYPES) } },
            )
            SettingsHint(stringResource(R.string.dejavu_sidebery_steps))
        }

        pending?.let { (snapshot, summary) ->
            ImportDialog(
                summary = summary,
                onImport = {
                    pending = null
                    scope.launch {
                        SideberyImporter(context).import(snapshot)
                        toast(context, R.string.dejavu_sidebery_imported)
                    }
                },
                onDismiss = { pending = null },
            )
        }
    }
}

/** What importing a Sidebery snapshot adds, for the user to confirm it. */
@Composable
private fun ImportDialog(summary: SideberyImport.Summary, onImport: () -> Unit, onDismiss: () -> Unit) {
    DejavuDialog(
        title = stringResource(R.string.dejavu_sidebery_import),
        onDismissRequest = onDismiss,
        icon = iconsR.drawable.mozac_ic_import_data_24,
        buttons = {
            if (summary.isEmpty) {
                DejavuDialogButton(text = stringResource(R.string.dejavu_done), onClick = onDismiss)
            } else {
                DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
                DejavuDialogButton(
                    text = stringResource(R.string.dejavu_sidebery_import_button),
                    onClick = onImport,
                    style = DejavuButtonStyle.Primary,
                )
            }
        },
    ) {
        if (summary.isEmpty) Text(stringResource(R.string.dejavu_sidebery_nothing_new))
        listOf(
            R.string.dejavu_sidebery_new_workspaces to summary.newWorkspaces,
            R.string.dejavu_settings_folders to summary.folders,
            R.string.dejavu_settings_pinned_tabs to summary.pinnedTabs,
            R.string.dejavu_sidebery_essentials to summary.essentials,
            R.string.dejavu_settings_unpinned_tabs to summary.unpinnedTabs,
            R.string.dejavu_sidebery_new_containers to summary.newContainers,
            R.string.dejavu_sidebery_left_out to summary.leftOut,
        ).filter { (_, count) -> count > 0 }.forEach { (label, count) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(count.toLocaleString(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (summary.leftOut > 0) {
            Text(
                text = stringResource(R.string.dejavu_sidebery_left_out_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Imports a Sidebery snapshot into Dejavu's containers, workspaces and tabs, as [SideberyImport] plans it. */
private class SideberyImporter(private val context: Context) {
    private val repository = WorkspaceRepository.get(context)
    private val storage = DejavuContainerStorage.get(context)
    private val store = context.components.core.store

    /** What importing [snapshot] would add now. */
    suspend fun preview(snapshot: SideberySnapshot): SideberyImport.Summary {
        storage.load()
        return withContext(Dispatchers.Main) { plan(snapshot, repository.state.value).summary }
    }

    /**
     * Adds the containers [snapshot] needs, then its workspaces, pinned tabs and folders, and opens its unpinned tabs
     * without loading them. The workspaces change and the tabs open together on the main thread, so nothing sees the
     * tabs in between.
     */
    suspend fun import(snapshot: SideberySnapshot) {
        storage.load()
        val containers = withContext(Dispatchers.Main) { plan(snapshot, repository.state.value).containers }
        containers.forEach { storage.saveContainer(it.contextId, it.name, it.color, it.icon) }
        withContext(Dispatchers.Main) {
            val plan = repository.update { state -> plan(snapshot, state).let { it.state to it } }
            if (plan.tabs.isEmpty()) return@withContext
            val now = System.currentTimeMillis()
            plan.tabs.forEach { repository.expectTab(it.id) }
            store.dispatch(
                TabListAction.AddMultipleTabsAction(
                    plan.tabs.map { tab ->
                        createTab(
                            url = tab.url,
                            id = tab.id,
                            title = tab.title,
                            contextId = tab.contextId,
                            lastAccess = now,
                            createdAt = now,
                        )
                    },
                ),
            )
        }
    }

    private fun plan(snapshot: SideberySnapshot, state: WorkspaceState) = SideberyImport.plan(
        snapshot = snapshot,
        state = state,
        containers = storage.records.value.orEmpty(),
        openTabs = store.state.normalTabs.map { it.id to (it.readerState.activeUrl ?: it.content.url) },
        perContainerEssentials = DejavuSettings.get(context).essentialsPerContainer.value,
        defaultName = context.getString(R.string.dejavu_workspace_default_name),
    )
}

/** Writes the Sidebery snapshot of the workspaces to [uri]. Returns whether it was written. */
private suspend fun exportTo(context: Context, uri: Uri): Boolean {
    val storage = DejavuContainerStorage.get(context)
    storage.load()
    val snapshot = withContext(Dispatchers.Main) {
        SideberyExport.export(
            state = WorkspaceRepository.get(context).state.value,
            tabs = context.components.core.store.state.normalTabs,
            containers = storage.records.value.orEmpty(),
            iconImage = { workspace -> workspaceIconText(workspace.icon)?.let(::emojiImage) },
        ).snapshot
    }
    return withContext(Dispatchers.IO) {
        runCatching {
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { writer ->
                writer.write(SideberyFile.write(snapshot))
            }
        }.isSuccess
    }
}

/** Reads the Sidebery snapshot or backup at [uri], or returns `null` when it is neither or too large. */
private suspend fun readFrom(context: Context, uri: Uri): SideberySnapshot? = withContext(Dispatchers.IO) {
    runCatching {
        checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                bytes.write(buffer, 0, read)
                if (bytes.size() > SideberyFile.MAX_SIZE) return@runCatching null
            }
            SideberyFile.read(bytes.toString(Charsets.UTF_8.name()))
        }
    }.getOrNull()
}

/** [emoji] drawn as a small PNG image, as a `data:` address, for the icon of a panel in Sidebery. */
private fun emojiImage(emoji: String): String? = runCatching {
    val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ICON_SIZE * EMOJI_SCALE
        textAlign = Paint.Align.CENTER
    }
    val baseline = ICON_SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
    Canvas(bitmap).drawText(emoji, ICON_SIZE / 2f, baseline, paint)
    val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }.toByteArray()
    bitmap.recycle()
    "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
}.getOrNull()

/** Runs [launch], which opens the system's file picker, unless the phone has none. */
private inline fun launchSafely(launch: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        Logger.warn("No file picker to save to or open from", e)
    }
}

private fun toast(context: Context, @StringRes text: Int) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

private const val READ_BUFFER = 64 * 1024
private const val ICON_SIZE = 64
private const val EMOJI_SCALE = 0.75f
private const val PNG_QUALITY = 100
