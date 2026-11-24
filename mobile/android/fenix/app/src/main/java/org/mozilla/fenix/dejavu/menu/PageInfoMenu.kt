/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.TrackingProtectionState
import mozilla.components.compose.browser.toolbar.R as toolbarR
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.ktx.kotlin.getOrigin
import mozilla.components.support.ktx.kotlin.isContentUrl
import mozilla.components.support.utils.CertificateUtils
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.menu.store.MenuAction
import org.mozilla.fenix.components.menu.store.MenuStore
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.browser.currentBarsLayout
import org.mozilla.fenix.dejavu.browser.domainOf
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.NoContainerIcon
import org.mozilla.fenix.dejavu.containers.color
import org.mozilla.fenix.dejavu.containers.drawable
import org.mozilla.fenix.dejavu.home.SiteIcon
import org.mozilla.fenix.dejavu.home.WorkspaceMark
import org.mozilla.fenix.dejavu.home.titleOf
import org.mozilla.fenix.dejavu.ui.iconPainter
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

/**
 * What the page's details show about the shown tab.
 *
 * @property title The tab's title, as the home screen shows it.
 * @property url The whole address of the page.
 * @property isWebPage Whether the page came from the web, which the connection and tracker rows are about.
 * @property isSecure Whether the connection is secure, or `null` while that is not known yet.
 * @property verifier Who verified the site's certificate, for a secure connection.
 * @property trackersBlocked How many trackers were blocked on the page, or `null` when trackers are not blocked on it.
 * @property isPrivate Whether the tab is a private one, which has neither a container nor a workspace of its own.
 * @property container The tab's container, or `null` for a tab without one or a private tab, which takes none.
 * @property workspace The workspace the tab is in, or `null` for a private tab.
 * @property workspaceContainer The default container of [workspace], whose color marks a workspace without an icon.
 * @property canChangePlace Whether the tab can move to another workspace or container: private tabs and the tabs of
 *   other devices on the account cannot.
 * @property marks What else is special about the tab.
 */
internal data class PageInfo(
    val title: String,
    val url: String,
    val isWebPage: Boolean,
    val isSecure: Boolean?,
    val verifier: String?,
    val trackersBlocked: Int?,
    val isPrivate: Boolean,
    val container: PageContainer?,
    val workspace: Workspace?,
    val workspaceContainer: ContainerRecord?,
    val canChangePlace: Boolean,
    val marks: List<PageMark>,
)

/** The container of the shown tab, from what Dejavu keeps about it or else from what the browser knows. */
internal data class PageContainer(
    val name: String,
    val color: ContainerColor,
    val icon: ContainerState.Icon,
    val temporary: Boolean,
)

/** Something special about the shown tab, shown as a label below the page's details. */
internal enum class PageMark(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    PINNED(R.string.dejavu_page_info_pinned, iconsR.drawable.mozac_ic_pin_24),
    ESSENTIAL(R.string.dejavu_page_info_essential, iconsR.drawable.mozac_ic_grid_add_24),
    SPLIT_VIEW(R.string.dejavu_menu_split_view, R.drawable.dejavu_ic_split_24),
    DESKTOP_SITE(R.string.dejavu_menu_desktop_site, iconsR.drawable.mozac_ic_device_desktop_24),
    READER_VIEW(R.string.dejavu_page_info_reader_view, iconsR.drawable.mozac_ic_reader_view_24),
    TRANSLATED(R.string.dejavu_page_info_translated, iconsR.drawable.mozac_ic_translate_24),
}

/**
 * What the page's details show about [tab], the shown tab, in [workspaces], with the containers Dejavu keeps in
 * [records] and the ones the browser knows in [containers], by context id.
 */
internal fun pageInfoOf(
    tab: TabSessionState,
    workspaces: WorkspaceState,
    records: List<ContainerRecord>,
    containers: Map<String, ContainerState>,
): PageInfo {
    val url = tab.content.url
    val private = tab.content.private
    val isWebPage = url.startsWith("http://") || url.startsWith("https://")
    val security = tab.content.securityInfo
    val workspaceId = workspaces.pinOf(tab.id)?.workspaceId ?: workspaces.workspaceOf(tab.id)
    val workspace = workspaces.workspaces.firstOrNull { it.id == workspaceId }?.takeUnless { private }
    return PageInfo(
        title = workspaces.titleOf(tab),
        url = url,
        isWebPage = isWebPage,
        isSecure = security.isSecure.takeIf { isWebPage && security != SecurityInfo.Unknown },
        verifier = security.verifier()?.takeIf { isWebPage },
        trackersBlocked = tab.trackingProtection.blockedCount(),
        isPrivate = private,
        container = tab.contextId?.takeUnless { private }?.let { containerOf(it, records, containers) },
        workspace = workspace,
        workspaceContainer = workspace?.containerId?.let { id -> records.firstOrNull { it.contextId == id } },
        canChangePlace = !private && !workspaces.isDeviceWorkspace(workspaceId),
        marks = marksOf(tab, workspaces),
    )
}

/** Who verified the certificate of a secure connection, as the panel of the site's security names them. */
private fun SecurityInfo.verifier(): String? =
    (CertificateUtils.issuerOrganization(certificate) ?: issuer).takeIf { isSecure && it.isNotBlank() }

/** How many trackers were blocked, or `null` when tracking protection is off or the site is an exception to it. */
private fun TrackingProtectionState.blockedCount(): Int? =
    blockedTrackers.size.takeIf { enabled && !ignoredOnTrackingProtection }

private fun marksOf(tab: TabSessionState, workspaces: WorkspaceState): List<PageMark> = buildList {
    workspaces.pinOf(tab.id)?.let { pin -> add(if (pin.essential) PageMark.ESSENTIAL else PageMark.PINNED) }
    if (workspaces.splitOf(tab.id) != null) add(PageMark.SPLIT_VIEW)
    if (tab.content.desktopMode) add(PageMark.DESKTOP_SITE)
    if (tab.readerState.active) add(PageMark.READER_VIEW)
    if (tab.translationsState.isTranslated) add(PageMark.TRANSLATED)
}

private fun containerOf(
    contextId: String,
    records: List<ContainerRecord>,
    containers: Map<String, ContainerState>,
): PageContainer {
    records.firstOrNull { it.contextId == contextId }?.let { record ->
        return PageContainer(record.name, record.color, record.icon, record.showsAsTemporary)
    }
    val known = containers[contextId]
    return PageContainer(
        name = known?.name ?: contextId,
        color = known?.let { ContainerColor.fromAcColor(it.color) } ?: ContainerColor.GRAY,
        icon = known?.icon ?: ContainerState.Icon.CIRCLE,
        temporary = false,
    )
}

/**
 * The details of the shown page, which grow out of the address bar: its whole address, how safe the connection is, and
 * where the tab is kept, its container and its workspace, which can be changed from here.
 *
 * @param menuStore Fenix's menu store, which shares the page as the "More" menu does.
 * @param navController Opens the panel of the site's security and permissions.
 * @param onDismiss Removes the panel, once it shrank back into the address bar.
 */
@Composable
internal fun PageInfoMenu(menuStore: MenuStore, navController: NavController, onDismiss: () -> Unit) {
    FloatingPanel(bar = PanelBar.addressBarIn(currentBarsLayout()), swipe = PageInfoSwipe, onDismiss = onDismiss) {
        val context = LocalContext.current
        val fenix = components
        val closer = LocalMenuCloser.current ?: return@FloatingPanel
        val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
        val containerStorage = remember { DejavuContainerStorage.get(context) }
        val tab by fenix.core.store.observeAsComposableState { it.selectedTab }
        val containers by fenix.core.store.observeAsComposableState { it.containers }
        val workspaces by repository.state.collectAsState()
        val records by containerStorage.records.collectAsState()
        var asking by remember { mutableStateOf<TabAction?>(null) }
        val current = tab ?: return@FloatingPanel
        val info = pageInfoOf(current, workspaces, records.orEmpty(), containers)

        // The panel stays open behind the dialog, so it shows the tab's new place once the dialog is done.
        asking?.let { action ->
            ShownTabActionDialog(action, current, navController, onDone = { asking = null })
        }
        PageInfoContent(
            info = info,
            onCopy = {
                runOnShownTab(TabAction.COPY_LINK, context, navController, current)
                closer.close()
            },
            onShare = { closer.closeBy { menuStore.dispatch(MenuAction.Navigate.Share) } },
            onSiteDetails = { closer.closeBy { openSiteDetails(fenix, navController, current) } },
            onContainer = { asking = TabAction.CHANGE_CONTAINER },
            onWorkspace = { asking = TabAction.MOVE_TO_WORKSPACE },
        )
    }
}

/**
 * Opens Fenix's panel of the security, permissions and tracking protection of the site [tab] shows, in place of the
 * page's details, as tapping the security icon of Fenix's address bar does.
 */
private fun openSiteDetails(components: Components, navController: NavController, tab: TabSessionState) {
    val scope = CoroutineScope(Dispatchers.Main)
    scope.launch {
        val permissions = withContext(Dispatchers.IO) {
            tab.content.url.getOrigin()?.let { origin ->
                components.core.geckoSitePermissionsStorage.findSitePermissionsBy(origin, private = tab.content.private)
            }
        }
        components.useCases.trackingProtectionUseCases.containsException(tab.id) { hasException ->
            scope.launch {
                if (navController.currentDestination?.id !in SiteDetailsOrigins) return@launch
                navController.navigate(
                    NavGraphDirections.actionGlobalTrustPanelFragment(
                        sessionId = tab.id,
                        title = tab.content.title,
                        url = tab.content.url,
                        isSecured = tab.content.securityInfo.isSecure,
                        sitePermissions = permissions,
                        certificate = tab.content.securityInfo.certificate,
                        permissionHighlights = tab.content.permissionHighlights,
                        isTrackingProtectionEnabled = tab.trackingProtection.enabled && !hasException,
                        isLocalPdf = tab.content.url.isContentUrl(),
                    ),
                    NavOptions.Builder().setPopUpTo(R.id.browserFragment, false).build(),
                )
            }
        }
    }
}

/** Where the panel of the site's security can open from: the page's details, or the page once they went away. */
private val SiteDetailsOrigins = setOf(R.id.menuDialogFragment, R.id.browserFragment)

@Suppress("LongParameterList")
@Composable
private fun PageInfoContent(
    info: PageInfo,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSiteDetails: () -> Unit,
    onContainer: () -> Unit,
    onWorkspace: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        PageHeader(info)
        AddressCard(url = info.url, onCopy = onCopy, onShare = onShare.takeIf { info.isWebPage })
        if (info.isWebPage) SecurityCard(info, onSiteDetails)
        PlaceCard(
            info = info,
            onContainer = onContainer.takeIf { info.canChangePlace },
            onWorkspace = onWorkspace.takeIf { info.canChangePlace },
        )
        if (info.marks.isNotEmpty()) MarkLabels(info.marks)
    }
}

@Composable
private fun PageHeader(info: PageInfo) {
    // The title already says the domain when it is the address itself.
    val domain = domainOf(info.url)?.takeUnless { info.title.contains(it, ignoreCase = true) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        SiteIcon(url = info.url, size = SITE_ICON_SIZE)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            domain?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The whole address, which can be selected, with buttons to copy and share it. */
@Composable
private fun AddressCard(url: String, onCopy: () -> Unit, onShare: (() -> Unit)?) {
    Column(modifier = Modifier.card().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
        SelectionContainer {
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = URL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            CardButton(
                icon = iconsR.drawable.mozac_ic_copy_24,
                label = stringResource(toolbarR.string.mozac_browser_toolbar_long_press_popup_copy),
                onClick = onCopy,
            )
            onShare?.let {
                CardButton(R.drawable.dejavu_ic_share_24, stringResource(R.string.dejavu_menu_share), it)
            }
        }
    }
}

@Composable
private fun CardButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(
            painter = iconPainter(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** How safe the connection is and the trackers blocked; tapping it opens the site's security and permissions. */
@Composable
private fun SecurityCard(info: PageInfo, onSiteDetails: () -> Unit) {
    Column(
        modifier = Modifier
            .card()
            .clickable(onClickLabel = stringResource(R.string.dejavu_page_info_site_details), onClick = onSiteDetails),
    ) {
        info.isSecure?.let { secure -> ConnectionRow(secure, info.verifier) }
        TrackersRow(blocked = info.trackersBlocked, showsMore = info.isSecure == null)
    }
}

@Composable
private fun ConnectionRow(secure: Boolean, verifier: String?) {
    if (secure) {
        InfoRow(
            icon = iconsR.drawable.mozac_ic_lock_24,
            text = stringResource(R.string.connection_security_panel_secure),
            detail = verifier?.let { stringResource(R.string.connection_security_panel_verified_by, it) },
            showsMore = true,
        )
    } else {
        InfoRow(
            icon = iconsR.drawable.mozac_ic_lock_slash_24,
            iconTint = MaterialTheme.colorScheme.error,
            text = stringResource(R.string.connection_security_panel_not_secure),
            showsMore = true,
        )
    }
}

@Composable
private fun TrackersRow(blocked: Int?, showsMore: Boolean) {
    if (blocked != null) {
        InfoRow(
            icon = iconsR.drawable.mozac_ic_shield_checkmark_24,
            text = stringResource(R.string.protection_panel_num_trackers_blocked, blocked),
            showsMore = showsMore,
        )
    } else {
        InfoRow(
            icon = iconsR.drawable.mozac_ic_shield_slash_24,
            text = stringResource(R.string.protection_panel_etp_disabled_no_trackers_blocked),
            showsMore = showsMore,
        )
    }
}

/** The tab's container and workspace, each of which can be changed by tapping it when the tab allows. */
@Composable
private fun PlaceCard(info: PageInfo, onContainer: (() -> Unit)?, onWorkspace: (() -> Unit)?) {
    Column(modifier = Modifier.card()) {
        if (!info.isPrivate) {
            ContainerRow(info.container, onContainer)
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_ALPHA),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        WorkspaceRow(info.workspace, info.workspaceContainer, onWorkspace)
    }
}

/** The tab's [container], or "No container" when `null`. */
@Composable
private fun ContainerRow(container: PageContainer?, onClick: (() -> Unit)?) {
    InfoRow(
        leading = {
            if (container != null) {
                Icon(
                    painter = iconPainter(container.icon.drawable),
                    contentDescription = null,
                    tint = container.color.color,
                    modifier = Modifier.size(ROW_ICON_SIZE),
                )
            } else {
                NoContainerIcon(size = ROW_ICON_SIZE)
            }
        },
        text = container?.name ?: stringResource(R.string.dejavu_workspace_no_container),
        detail = if (container?.temporary == true) {
            stringResource(R.string.dejavu_temporary_container)
        } else {
            stringResource(R.string.dejavu_page_info_container)
        },
        onClick = onClick,
    )
}

/** The tab's [workspace], marked by its icon or the color of its default [container], or private when `null`. */
@Composable
private fun WorkspaceRow(workspace: Workspace?, container: ContainerRecord?, onClick: (() -> Unit)?) {
    InfoRow(
        leading = {
            if (workspace != null) {
                WorkspaceMark(workspace, container)
            } else {
                Icon(
                    painter = iconPainter(R.drawable.dejavu_ic_private_tab_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(ROW_ICON_SIZE),
                )
            }
        },
        text = workspace?.name ?: stringResource(R.string.dejavu_private_workspace),
        detail = stringResource(R.string.dejavu_page_info_workspace),
        onClick = onClick,
    )
}

@Composable
private fun InfoRow(
    @DrawableRes icon: Int,
    text: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    detail: String? = null,
    showsMore: Boolean = false,
) {
    InfoRow(
        leading = {
            Icon(
                painter = iconPainter(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(ROW_ICON_SIZE),
            )
        },
        text = text,
        detail = detail,
        showsMore = showsMore,
    )
}

/**
 * A row of the page's details: the [leading] icon, a line of [text] and a smaller [detail] below it. A row with
 * [onClick] can be tapped by itself; [showsMore] adds a chevron saying that tapping leads further, which such a row
 * always has.
 */
@Composable
private fun InfoRow(
    leading: @Composable () -> Unit,
    text: String,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    showsMore: Boolean = onClick != null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ROW_ICON_SIZE)) { leading() }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showsMore) {
            Icon(
                painter = iconPainter(iconsR.drawable.mozac_ic_chevron_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun MarkLabels(marks: List<PageMark>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        marks.forEach { mark ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Icon(
                    painter = iconPainter(mark.icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(mark.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun Modifier.card(): Modifier =
    fillMaxWidth().clip(RoundedCornerShape(CARD_CORNER)).background(MaterialTheme.colorScheme.surfaceContainerHigh)

private val SITE_ICON_SIZE = 36.dp
private val ROW_ICON_SIZE = 24.dp
private val ROW_MIN_HEIGHT = 56.dp
private val CARD_CORNER = 20.dp
private const val URL_MAX_LINES = 4
private const val DIVIDER_ALPHA = 0.5f
