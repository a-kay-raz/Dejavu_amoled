/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.R as materialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import mozilla.components.browser.state.state.SessionState
import mozilla.components.concept.engine.HitResult
import mozilla.components.feature.contextmenu.ContextMenuCandidate
import mozilla.components.feature.contextmenu.R as contextMenuR
import mozilla.components.ui.widgets.SnackbarDelegate
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.NewTabContainerChoice
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.drawable
import org.mozilla.fenix.dejavu.containers.splitTemporary
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.ui.DejavuIcons
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.ext.components

/** Dejavu's entries in the menu of a long-pressed link: open it in a split view, in a container or in a workspace. */
object DejavuLinkMenu {
    private const val NEW_TAB_ID = "mozac.feature.contextmenu.open_in_new_tab"
    private const val PRIVATE_TAB_ID = "mozac.feature.contextmenu.open_in_private_tab"

    /**
     * Returns [candidates] with Dejavu's entries after the ones opening the link in a new tab. The entries show for
     * the same links as "Open link in new tab", so not in private tabs.
     */
    fun withDejavuEntries(
        context: Context,
        candidates: List<ContextMenuCandidate>,
        snackBarParentView: View,
        snackbarDelegate: SnackbarDelegate,
    ): List<ContextMenuCandidate> {
        val newTab = candidates.firstOrNull { it.id == NEW_TAB_ID } ?: return candidates
        val links = LinkOpener(context, snackBarParentView, snackbarDelegate)
        // One entry per way the screen has room to split, see SplitSpace.
        val ways = { SplitSpace.supported(context) }
        val entries = listOf(
            ContextMenuCandidate(
                id = "dejavu.contextmenu.open_in_split_view",
                label = context.getString(R.string.dejavu_link_open_in_split_view),
                showFor = { tab, hit -> newTab.showFor(tab, hit) && ways().size == 1 },
                action = { tab, hit -> links.openInSplitView(tab, linkOf(hit), SplitSpace.preferred(ways())) },
            ),
            ContextMenuCandidate(
                id = "dejavu.contextmenu.open_beside_in_split_view",
                label = context.getString(R.string.dejavu_link_open_beside_in_split_view),
                showFor = { tab, hit -> newTab.showFor(tab, hit) && ways().size > 1 },
                action = { tab, hit -> links.openInSplitView(tab, linkOf(hit), SplitOrientation.SIDE_BY_SIDE) },
            ),
            ContextMenuCandidate(
                id = "dejavu.contextmenu.open_below_in_split_view",
                label = context.getString(R.string.dejavu_link_open_below_in_split_view),
                showFor = { tab, hit -> newTab.showFor(tab, hit) && ways().size > 1 },
                action = { tab, hit -> links.openInSplitView(tab, linkOf(hit), SplitOrientation.TOP_AND_BOTTOM) },
            ),
            ContextMenuCandidate(
                id = "dejavu.contextmenu.open_in_container",
                label = context.getString(R.string.dejavu_link_open_in_container),
                showFor = newTab.showFor,
                action = { tab, hit -> links.pickContainer(tab, linkOf(hit)) },
            ),
            ContextMenuCandidate(
                id = "dejavu.contextmenu.open_in_workspace",
                label = context.getString(R.string.dejavu_link_open_in_workspace),
                showFor = { tab, hit -> newTab.showFor(tab, hit) && links.otherWorkspaces(tab).isNotEmpty() },
                action = { tab, hit -> links.pickWorkspace(tab, linkOf(hit)) },
            ),
        )
        val index = candidates.indexOfFirst { it.id == PRIVATE_TAB_ID }.takeIf { it >= 0 } ?: candidates.indexOf(newTab)
        return candidates.take(index + 1) + entries + candidates.drop(index + 1)
    }

    private fun linkOf(hit: HitResult): String = when (hit) {
        is HitResult.IMAGE_SRC -> hit.uri
        else -> hit.src
    }
}

private class LinkOpener(
    private val context: Context,
    private val snackBarParentView: View,
    private val snackbarDelegate: SnackbarDelegate,
) {
    private val components = context.components
    private val tabsUseCases = components.useCases.tabsUseCases

    /** Opens [url] in a new tab shown next to [parent] the way [way] has them, in its container and workspace. */
    fun openInSplitView(parent: SessionState, url: String, way: SplitOrientation) {
        val repository = WorkspaceRepository.peek() ?: return
        val tabId = tabsUseCases.addTab(
            url = url,
            selectTab = false,
            startLoading = true,
            parentId = parent.id,
            contextId = parent.contextId,
            textDirectiveUserActivation = true,
        )
        repository.assignTab(tabId, repository.state.value.workspaceOf(parent.id))
        repository.createSplit(parent.id, tabId, way)
    }

    fun pickContainer(parent: SessionState, url: String) {
        val (permanent, temporary) = DejavuContainerStorage.get(context).records.value.orEmpty().splitTemporary()
        fun containerChoice(record: ContainerRecord): Choice<ContainerPick> {
            val drawable = icon(record.icon.drawable)?.apply { setTint(record.color.argb.toInt()) }
            return Choice(record.name, drawable, ContainerPick.Container(record.contextId))
        }
        val choices = buildList<Choice<ContainerPick>> {
            add(
                Choice(
                    context.getString(R.string.dejavu_workspace_no_container),
                    tinted(R.drawable.dejavu_ic_no_container_24),
                    ContainerPick.NoContainer,
                ),
            )
            permanent.forEach { add(containerChoice(it)) }
            add(
                Choice(
                    context.getString(R.string.dejavu_new_temporary_container),
                    tinted(R.drawable.dejavu_ic_temporary_container_24),
                    ContainerPick.Temporary,
                ),
            )
            temporary.forEach { add(containerChoice(it)) }
        }
        showPicker(R.string.dejavu_link_open_in_container, choices) { choice ->
            val repository = WorkspaceRepository.peek() ?: return@showPicker
            val tabId = openInBackground(url, choice.pick)
            repository.assignTab(tabId, repository.state.value.workspaceOf(parent.id))
            showOpened(context.getString(contextMenuR.string.mozac_feature_contextmenu_snackbar_new_tab_opened)) {
                tabsUseCases.selectTab(tabId)
            }
        }
    }

    fun pickWorkspace(parent: SessionState, url: String) {
        val choices = otherWorkspaces(parent).map { workspace ->
            Choice(listOfNotNull(workspace.icon, workspace.name).joinToString("  "), null, workspace)
        }
        showPicker(R.string.dejavu_link_open_in_workspace, choices) { choice ->
            val repository = WorkspaceRepository.peek() ?: return@showPicker
            val workspace = choice.pick
            val tabId = openInBackground(url, containerFor(workspace))
            repository.assignTab(tabId, workspace.id)
            showOpened(context.getString(R.string.dejavu_link_opened_in, workspace.name)) {
                repository.selectWorkspace(workspace.id)
                tabsUseCases.selectTab(tabId)
            }
        }
    }

    /** The workspaces a link in [tab] can be opened in: every one but the tab's own. */
    fun otherWorkspaces(tab: SessionState): List<Workspace> {
        val state = WorkspaceRepository.peek()?.state?.value ?: return emptyList()
        val own = state.workspaceOf(tab.id)
        return state.workspaces.filter { it.id != own }
    }

    /** The container a new tab of [workspace] opens in, as the workspace itself would pick it. */
    private fun containerFor(workspace: Workspace): ContainerPick = when {
        workspace.containerId != null -> ContainerPick.Container(workspace.containerId)
        DejavuSettings.peek()?.temporaryContainersByDefault?.value == true -> ContainerPick.Temporary
        else -> ContainerPick.NoContainer
    }

    /** Opens [url] in a new tab in the background, in the container of [pick]. */
    private fun openInBackground(url: String, pick: ContainerPick): String {
        NewTabContainerChoice.set(pick)
        return tabsUseCases.addTab(url = url, selectTab = false, startLoading = true, textDirectiveUserActivation = true)
    }

    private fun showOpened(text: String, onSwitch: () -> Unit) {
        snackbarDelegate.show(
            snackBarParentView = snackBarParentView,
            text = text,
            duration = Snackbar.LENGTH_LONG,
            action = context.getString(contextMenuR.string.mozac_feature_contextmenu_snackbar_action_switch),
            listener = { onSwitch() },
        )
    }

    private fun <T : Any> showPicker(title: Int, choices: List<Choice<T>>, onPick: (Choice<T>) -> Unit) {
        val adapter = object : ArrayAdapter<Choice<T>>(context, android.R.layout.select_dialog_item, android.R.id.text1, choices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val choice = choices[position]
                view.text = choice.label
                view.compoundDrawablePadding = (ICON_PADDING_DP * context.resources.displayMetrics.density).toInt()
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(choice.icon, null, null, null)
                return view
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setAdapter(adapter) { _, which -> onPick(choices[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun icon(resId: Int): Drawable? = AppCompatResources.getDrawable(context, resId)?.mutate()

    private fun tinted(resId: Int): Drawable? = icon(DejavuIcons.of(resId))?.apply {
        setTint(MaterialColors.getColor(context, materialR.attr.colorOnSurfaceVariant, DEFAULT_ICON_TINT))
    }

    private class Choice<T : Any>(val label: String, val icon: Drawable?, val pick: T)

    private companion object {
        const val ICON_PADDING_DP = 16
        const val DEFAULT_ICON_TINT = 0xFF9E9E9E.toInt()
    }
}
