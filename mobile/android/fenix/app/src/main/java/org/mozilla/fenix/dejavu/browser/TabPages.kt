/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.content.HistoryState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.toolbar.R as browserToolbarR
import mozilla.components.compose.base.theme.information
import mozilla.components.compose.base.utils.toLocaleString
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.toolbar.StartPageActions
import org.mozilla.fenix.dejavu.home.SiteIcon
import org.mozilla.fenix.dejavu.ui.DejavuDropdownMenu
import org.mozilla.fenix.dejavu.ui.DejavuMenuItem
import org.mozilla.fenix.dejavu.ui.DejavuMenuLabel
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme

/**
 * A page of a tab's history, as the page counter of the address bar lists it, see [PageCounter].
 *
 * @property index Its place in the tab's history, which going back or forward to it takes.
 * @property title Its title, or its address when it has none.
 * @property url Its address.
 * @property isShown Whether the tab shows it.
 */
internal data class TabPage(val index: Int, val title: String, val url: String, val isShown: Boolean)

/**
 * How many pages of [history] Back goes through before it closes the tab, the shown one included: 1 on the page the
 * tab opened on, one more for each page opened from there, one less for each page gone back to. The homepage a tab
 * opened on does not count, as Back closes the tab there (see [canGoBackToPage]). Until the browser tells the tab's
 * history, the page at [url] counts as the only one, and the homepage as none.
 */
internal fun pageCountOf(history: HistoryState, url: String): Int {
    if (history.items.isEmpty()) return if (isPage(url)) 1 else 0
    return history.items.take(history.currentIndex + 1).count { isPage(it.uri) }
}

/**
 * The pages of [history] without the homepage, newest first: those the tab went back from, the shown one, then those
 * Back goes to. Until the browser tells the tab's history, only the page at [url], with [title].
 */
internal fun pagesOf(history: HistoryState, title: String, url: String): List<TabPage> {
    if (history.items.isEmpty()) {
        return if (isPage(url)) listOf(TabPage(0, title.ifBlank { url }, url, isShown = true)) else emptyList()
    }
    return history.items.mapIndexedNotNull { index, item ->
        TabPage(index, item.title.ifBlank { item.uri }, item.uri, isShown = index == history.currentIndex)
            .takeIf { isPage(item.uri) }
    }.asReversed()
}

private fun isPage(url: String) = url.isNotBlank() && url != ABOUT_HOME_URL

/**
 * What tapping the site's security icon of the address bar does, which holding the page counter does instead: open
 * the site's protections and permissions. `null` where the icon does nothing, as on Firefox's own pages.
 */
internal fun List<Action>.siteInfoClick(): BrowserToolbarEvent? = firstNotNullOfOrNull { action ->
    when (action) {
        is Action.ActionButtonRes -> action.onClick
        is Action.ActionButton -> action.onClick
        is Action.AnimatedPillActionRes -> action.onClick
        else -> null
    } as? StartPageActions.SiteInfoClicked
}

private val TabSessionState.marksSiteInfo: Boolean
    get() = content.permissionHighlights.permissionsChanged || trackingProtection.ignoredOnTrackingProtection

/**
 * Has the browser tell the history of the tab with [tabId] now: on its own, it tells it only every 10 seconds
 * (`browser.sessionstore.interval`). Not for a tab whose page is not loaded, which this would load.
 */
private fun BrowserStore.askForHistory(tabId: String) {
    if (state.findTab(tabId)?.engineState?.engineSession == null) return
    dispatch(EngineAction.FlushEngineSessionStateAction(tabId))
}

private data class ShownCount(
    val tabId: String,
    val count: Int,
    val marked: Boolean,
    val url: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
)

/**
 * The end of the address field, where the site's security icon was: the number of pages of the tab with [tabId], the
 * shown one when `null`, that Back goes through before it closes the tab, in a ring (see [pageCountOf]). Tapping it
 * lists the tab's pages to go back or forward to one, unless [onTap] does something else, and holding it does
 * [onHold], which opens the site's protections the way tapping the security icon did. A dot marks it, as it marked the
 * icon, when the site's permissions changed or its tracking protection is off. Nothing shows on the homepage.
 */
@Composable
internal fun PageCounter(tabId: String?, onHold: (() -> Unit)?, onTap: (() -> Unit)? = null) {
    val store = components.core.store
    val shown by key(tabId) {
        store.observeAsComposableState { state ->
            state.tabOrShown(tabId)?.let { tab ->
                ShownCount(
                    tabId = tab.id,
                    count = pageCountOf(tab.content.history, tab.content.url),
                    marked = tab.marksSiteInfo,
                    url = tab.content.url,
                    canGoBack = tab.content.canGoBack,
                    canGoForward = tab.content.canGoForward,
                )
            }
        }
    }
    val counted = shown?.takeIf { it.count > 0 } ?: return
    // Each step through the tab's pages asks for its history at once, and again a moment later for the steps the
    // browser records after the page changed, as when a page changes its own address.
    LaunchedEffect(counted.tabId, counted.url, counted.canGoBack, counted.canGoForward) {
        store.askForHistory(counted.tabId)
        delay(LATE_HISTORY_MS)
        store.askForHistory(counted.tabId)
    }
    var listing by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onSurface
    val dot = MaterialTheme.colorScheme.information
    val halo = MaterialTheme.colorScheme.surfaceContainerHigh
    val description = stringResource(R.string.dejavu_tab_page_number, counted.count)
    val holdLabel = stringResource(browserToolbarR.string.mozac_browser_toolbar_content_description_site_info)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(COUNTER_SIZE)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = holdLabel.takeIf { onHold != null },
                onLongClick = onHold,
                onClick = { if (onTap != null) onTap() else listing = true },
            )
            .semantics { contentDescription = description },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(RING_SIZE)
                .drawWithContent {
                    drawContent()
                    if (counted.marked) {
                        val center = Offset(size.width - DOT_INSET.toPx(), DOT_INSET.toPx())
                        drawCircle(halo, (DOT_RADIUS + DOT_HALO).toPx(), center)
                        drawCircle(dot, DOT_RADIUS.toPx(), center)
                    }
                }
                .clip(CircleShape)
                .border(RING_WIDTH, color, CircleShape)
                .clearAndSetSemantics {},
        ) {
            // Counting up slides the number up, as a page opens on top of the others; counting down slides it down.
            AnimatedContent(
                targetState = counted.tabId to counted.count,
                transitionSpec = {
                    val step = targetState.second.compareTo(initialState.second)
                    if (targetState.first != initialState.first || step == 0) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (slideInVertically { it * step } + fadeIn()) togetherWith
                            (slideOutVertically { -it * step } + fadeOut())
                    }
                },
                modifier = Modifier.padding(horizontal = RING_TEXT_PADDING),
                label = "pageCount",
            ) { (_, count) ->
                Text(
                    text = count.toLocaleString(),
                    color = color,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = MIN_COUNT_SIZE, maxFontSize = COUNT_SIZE),
                )
            }
        }
        key(counted.tabId) {
            TabPagesMenu(tabId = counted.tabId, expanded = listing, onDismiss = { listing = false })
        }
    }
}

/**
 * The pages of the tab with [tabId], newest first (see [pagesOf]), in a menu growing out of the page counter while
 * [expanded], the shown one checked. Picking another one goes back or forward to it.
 */
@Composable
private fun TabPagesMenu(tabId: String, expanded: Boolean, onDismiss: () -> Unit) {
    CompositionLocalProvider(LocalPopupTheme provides shownPopupTheme(tabId)) {
        DejavuDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            val fenix = components
            val pages by fenix.core.store.observeAsComposableState { state ->
                state.findTab(tabId)?.let { pagesOf(it.content.history, it.content.title, it.content.url) }.orEmpty()
            }
            val isPrivate = remember { fenix.core.store.state.findTab(tabId)?.content?.private == true }
            LaunchedEffect(Unit) { fenix.core.store.askForHistory(tabId) }
            if (pages.isEmpty()) {
                LaunchedEffect(Unit) { onDismiss() }
                return@DejavuDropdownMenu
            }
            DejavuMenuLabel(stringResource(R.string.dejavu_tab_pages))
            pages.forEach { page ->
                DejavuMenuItem(
                    text = page.title,
                    onClick = {
                        onDismiss()
                        if (!page.isShown) fenix.useCases.sessionUseCases.goToHistoryIndex(page.index, tabId)
                    },
                    leadingIcon = { SiteIcon(url = page.url, size = PAGE_ICON_SIZE, isPrivate = isPrivate) },
                    trailingIcon = if (page.isShown) {
                        {
                            Icon(
                                painter = painterResource(iconsR.drawable.mozac_ic_checkmark_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(PAGE_ICON_SIZE),
                            )
                        }
                    } else {
                        null
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

private val COUNTER_SIZE = 48.dp
private val RING_SIZE = 22.dp
private val RING_WIDTH = 1.5.dp
private val RING_TEXT_PADDING = 3.dp
private val COUNT_SIZE = 12.sp
private val MIN_COUNT_SIZE = 7.sp
private val DOT_RADIUS = 4.dp
private val DOT_HALO = 1.5.dp
private val DOT_INSET = 3.dp
private val PAGE_ICON_SIZE = 20.dp
private const val LATE_HISTORY_MS = 1000L
