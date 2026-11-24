/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.compose.base.Switch
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.dejavu.discover.NewThing
import org.mozilla.fenix.dejavu.discover.NewThings
import org.mozilla.fenix.dejavu.home.SiteIcon
import org.mozilla.fenix.dejavu.home.label
import org.mozilla.fenix.dejavu.home.pageUrl
import org.mozilla.fenix.ext.requireComponents

/**
 * Shows or hides the essentials on this phone (issue #278): all of them, or some, like the ones of a computer that are
 * no use on a phone. Sync and the other devices keep them, and they come back when shown again.
 */
class DejavuEssentialsFragment : DejavuComposeFragment(R.string.dejavu_settings_essentials) {
    @Composable
    override fun DejavuScreen() {
        val settings = remember { dejavuSettings() }
        val repository = remember { workspaceRepository() }
        val state by repository.state.collectAsState()
        val shown by settings.essentialsShown.collectAsState()
        val hidden by settings.hiddenEssentials.collectAsState()
        val tabs by requireComponents.core.store.observeAsComposableState { it.normalTabs }
        val tabsById = remember(tabs) { tabs.associateBy { it.id } }
        val essentials = state.essentials
        val context = LocalContext.current
        LaunchedEffect(Unit) { NewThings.seen(context, NewThing.ESSENTIALS) }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SettingsHint(stringResource(R.string.dejavu_essentials_hint))
            }
            item {
                SwitchListItem(
                    label = stringResource(R.string.dejavu_essentials_show),
                    maxLabelLines = Int.MAX_VALUE,
                    checked = shown,
                    showSwitchAfter = true,
                    modifier = Modifier.settingsCard(index = 0, count = 1),
                    onClick = settings::setEssentialsShown,
                )
            }
            if (essentials.isEmpty()) {
                item { SettingsHint(stringResource(R.string.dejavu_essentials_none)) }
            }
            itemsIndexed(essentials, key = { _, pin -> pin.id }) { index, pin ->
                val tab = pin.tabId?.let { tabsById[it] }
                EssentialSwitchRow(
                    label = pin.label(tab),
                    url = tab?.content?.url ?: pin.pageUrl,
                    checked = pin.id !in hidden,
                    enabled = shown,
                    modifier = Modifier.settingsCard(index, essentials.size),
                    onChange = { settings.setEssentialHidden(pin.id, hidden = !it) },
                )
            }
        }
    }
}

/** An essential with its site's icon and a switch that shows it on this phone, greyed out while [enabled] is off. */
@Composable
private fun EssentialSwitchRow(
    label: String,
    url: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clickable(enabled = enabled) { onChange(!checked) }
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                selected = checked
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SiteIcon(url = url, size = ICON_SIZE, modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA))
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_ALPHA),
        )
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked && enabled,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

private val ROW_HEIGHT = 56.dp
private val ICON_SIZE = 24.dp
private const val DISABLED_ALPHA = 0.38f
