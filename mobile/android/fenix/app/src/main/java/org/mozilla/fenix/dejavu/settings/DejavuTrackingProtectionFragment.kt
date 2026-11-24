/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.net.Uri
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.fragment.findNavController
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.BuildConfig
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.compose.list.TextListItem
import org.mozilla.fenix.dejavu.browser.CallsAndLocation
import org.mozilla.fenix.dejavu.browser.GoogleSafeBrowsing
import org.mozilla.fenix.dejavu.browser.JitAndWebGl
import org.mozilla.fenix.dejavu.browser.ProtectedTabs
import org.mozilla.fenix.dejavu.browser.Protection
import org.mozilla.fenix.dejavu.browser.ProtectionSettings
import org.mozilla.fenix.dejavu.browser.SiteFixes
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuDropdownMenu
import org.mozilla.fenix.dejavu.ui.DejavuMenuItem
import org.mozilla.fenix.dejavu.ui.DejavuTextField
import org.mozilla.fenix.dejavu.ui.iconPainter
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.settings.doh.DefaultDohSettingsProvider
import org.mozilla.fenix.settings.doh.DohSettingsFragment
import org.mozilla.fenix.settings.doh.DohUrlValidator
import org.mozilla.fenix.settings.doh.ProtectionLevel
import org.mozilla.fenix.settings.doh.Provider
import org.mozilla.fenix.settings.doh.UrlValidationException
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Dejavu's tracking protection settings: every protection on a row of its own, in place of Firefox's tracking
 * protection, HTTPS-Only and secure DNS screens. A switch is on when it protects, and the text under it says what it
 * guards against and what may stop working. A protection whose prefs about:config changed is managed there until they
 * are reset, which shows once this screen is back in front.
 */
class DejavuTrackingProtectionFragment : DejavuComposeFragment(R.string.dejavu_settings_tracking_protection) {
    @Composable
    override fun DejavuScreen() {
        val components = requireComponents
        val engine = components.core.engine
        val settings = components.settings
        val protections = remember { ProtectionSettings(requireContext(), settings) }
        // Counts the changes: the screen is drawn again after each one, reading the settings as they are now.
        var changes by remember { mutableIntStateOf(0) }
        var managedPrefs by remember { mutableStateOf(emptySet<String>()) }
        var warning by remember { mutableStateOf<Warning?>(null) }
        var customDnsDialog by remember { mutableStateOf(false) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            Protection.readChangedPrefs(engine) { managedPrefs = it }
            changes++
        }

        fun changed() {
            changes++
        }

        fun applyTrackerPolicy() {
            val policy = components.core.trackingProtectionPolicyFactory.createTrackingProtectionPolicy()
            components.useCases.settingsUseCases.updateTrackingProtection(policy)
            components.useCases.sessionUseCases.reload()
            changed()
        }

        fun managed(protection: Protection) = protection.isManagedBy(managedPrefs)

        val dns = remember(changes) { DefaultDohSettingsProvider(engine, settings) }
        val dnsProvider = dns.getSelectedProvider()
        val strictDns = dnsProvider == null || dns.getSelectedProtectionLevel() == ProtectionLevel.Max
        val providerName = dnsProvider?.let { providerName(it) }

        fun setDns(provider: Provider?, strict: Boolean = strictDns) {
            when {
                provider == null -> dns.setProtectionLevel(ProtectionLevel.Off, null)
                strict -> dns.setProtectionLevel(ProtectionLevel.Max, provider)
                else -> dns.setProtectionLevel(ProtectionLevel.Increased, provider)
            }
            changed()
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHint(stringResource(R.string.dejavu_protection_hint))

            Section(
                title = stringResource(R.string.dejavu_protection_section_connections),
                rows = listOfNotNull(
                    tabsRow(
                        managed = managed(Protection.HTTPS_ONLY),
                        label = stringResource(R.string.preferences_https_only_title),
                        current = protections.httpsOnly,
                        description = when (protections.httpsOnly) {
                            ProtectedTabs.ALL -> R.string.dejavu_protection_https_only_all
                            ProtectedTabs.PRIVATE -> R.string.dejavu_protection_https_only_private
                            ProtectedTabs.OFF -> R.string.dejavu_protection_https_only_off
                        },
                    ) {
                        protections.httpsOnly = it
                        engine.settings.httpsOnlyMode = settings.getHttpsOnlyMode()
                        changed()
                    },
                    if (managed(Protection.SECURE_DNS)) {
                        managedRow(stringResource(R.string.preference_doh_title))
                    } else {
                        val builtIn = dns.getDefaultProviders().filterIsInstance<Provider.BuiltIn>()
                        ProtectionRow.Choice(
                            label = stringResource(R.string.preference_doh_title),
                            value = when (dnsProvider) {
                                null -> stringResource(R.string.preference_doh_off)
                                is Provider.BuiltIn -> dnsProvider.name
                                is Provider.Custom -> stringResource(R.string.preference_doh_provider_custom)
                            },
                            description = if (providerName != null) {
                                stringResource(R.string.dejavu_protection_doh_on, providerName)
                            } else {
                                stringResource(R.string.dejavu_protection_doh_off)
                            },
                            options = builtIn.map { provider ->
                                Option(provider.name, selected = provider == dnsProvider) { setDns(provider) }
                            } + listOf(
                                Option(
                                    stringResource(R.string.preference_doh_provider_custom),
                                    selected = dnsProvider is Provider.Custom,
                                    repeats = true,
                                ) { customDnsDialog = true },
                                Option(
                                    stringResource(R.string.preference_doh_off),
                                    selected = dnsProvider == null,
                                ) { setDns(null) },
                            ),
                        )
                    },
                    if (dnsProvider != null && providerName != null) {
                        switchRow(
                            managed = managed(Protection.STRICT_DNS),
                            label = stringResource(R.string.dejavu_protection_doh_strict),
                            on = strictDns,
                            description = if (strictDns) {
                                stringResource(R.string.dejavu_protection_doh_strict_on, providerName)
                            } else {
                                stringResource(R.string.dejavu_protection_doh_strict_off)
                            },
                        ) { setDns(dnsProvider, strict = it) }
                    } else {
                        null
                    },
                    if (dnsProvider != null) {
                        linkRow(
                            managed = managed(Protection.DNS_EXCEPTIONS),
                            label = stringResource(R.string.preference_doh_exceptions),
                            description = stringResource(
                                R.string.preference_doh_exceptions_summary,
                                stringResource(R.string.app_name),
                            ),
                        ) {
                            findNavController().slideTo(
                                R.id.dohSettingsFragment,
                                Bundle().apply { putBoolean(DohSettingsFragment.START_ON_EXCEPTIONS, true) },
                            )
                        }
                    } else {
                        null
                    },
                ),
            )

            Section(
                title = stringResource(R.string.dejavu_protection_section_trackers),
                rows = listOf(
                    tabsRow(
                        managed = managed(Protection.TRACKING_CONTENT),
                        label = stringResource(R.string.dejavu_protection_tracking_content),
                        current = protections.trackingContent,
                        description = when (protections.trackingContent) {
                            ProtectedTabs.ALL -> R.string.dejavu_protection_tracking_content_all
                            ProtectedTabs.PRIVATE -> R.string.dejavu_protection_tracking_content_private
                            ProtectedTabs.OFF -> R.string.dejavu_protection_tracking_content_off
                        },
                    ) {
                        protections.trackingContent = it
                        applyTrackerPolicy()
                    },
                    switchRow(
                        managed = managed(Protection.CROSS_SITE_COOKIES),
                        label = stringResource(R.string.dejavu_protection_cookies),
                        on = protections.blockCrossSiteCookies,
                        onText = R.string.dejavu_protection_cookies_on,
                        offText = R.string.dejavu_protection_cookies_off,
                    ) {
                        protections.blockCrossSiteCookies = it
                        applyTrackerPolicy()
                    },
                    switchRow(
                        managed = managed(Protection.CRYPTOMINERS),
                        label = stringResource(R.string.dejavu_protection_cryptominers),
                        on = protections.blockCryptominers,
                        onText = R.string.dejavu_protection_cryptominers_on,
                        offText = R.string.dejavu_protection_cryptominers_off,
                    ) {
                        protections.blockCryptominers = it
                        applyTrackerPolicy()
                    },
                    switchRow(
                        managed = managed(Protection.KNOWN_FINGERPRINTERS),
                        label = stringResource(R.string.dejavu_protection_fingerprinters),
                        on = protections.blockKnownFingerprinters,
                        onText = R.string.dejavu_protection_fingerprinters_on,
                        offText = R.string.dejavu_protection_fingerprinters_off,
                    ) {
                        protections.blockKnownFingerprinters = it
                        applyTrackerPolicy()
                    },
                    tabsRow(
                        managed = managed(Protection.SUSPECTED_FINGERPRINTERS),
                        label = stringResource(R.string.dejavu_protection_suspected),
                        current = protections.suspectedFingerprinters,
                        description = when (protections.suspectedFingerprinters) {
                            ProtectedTabs.ALL -> R.string.dejavu_protection_suspected_all
                            ProtectedTabs.PRIVATE -> R.string.dejavu_protection_suspected_private
                            ProtectedTabs.OFF -> R.string.dejavu_protection_suspected_off
                        },
                    ) {
                        protections.suspectedFingerprinters = it
                        applyTrackerPolicy()
                    },
                    switchRow(
                        managed = managed(Protection.REDIRECT_TRACKERS),
                        label = stringResource(R.string.dejavu_protection_redirect),
                        on = protections.clearRedirectTrackers,
                        onText = R.string.dejavu_protection_redirect_on,
                        offText = R.string.dejavu_protection_redirect_off,
                    ) {
                        protections.clearRedirectTrackers = it
                        applyTrackerPolicy()
                    },
                    switchRow(
                        managed = managed(Protection.LINK_TRACKING),
                        label = stringResource(R.string.dejavu_protection_links),
                        on = protections.removeLinkTracking,
                        onText = R.string.dejavu_protection_links_on,
                        offText = R.string.dejavu_protection_links_off,
                    ) {
                        protections.removeLinkTracking = it
                        engine.settings.queryParameterStripping = it
                        engine.settings.queryParameterStrippingPrivateBrowsing = it
                        changed()
                    },
                    switchRow(
                        managed = managed(Protection.GLOBAL_PRIVACY_CONTROL),
                        label = stringResource(
                            R.string.preference_enhanced_tracking_protection_custom_global_privacy_control,
                        ),
                        on = protections.globalPrivacyControl,
                        onText = R.string.dejavu_protection_gpc_on,
                        offText = R.string.dejavu_protection_gpc_off,
                    ) {
                        protections.globalPrivacyControl = it
                        engine.settings.globalPrivacyControlEnabled = it
                        components.useCases.sessionUseCases.reload()
                        changed()
                    },
                    siteFixesRow(
                        managed = managed(Protection.SITE_FIXES),
                        current = protections.siteFixes,
                    ) { fixes ->
                        val save = {
                            protections.siteFixes = fixes
                            applyTrackerPolicy()
                        }
                        if (fixes == SiteFixes.OFF) {
                            warning = fixesOffWarning(onConfirm = save)
                        } else {
                            save()
                        }
                    },
                    linkRow(
                        managed = false,
                        label = stringResource(R.string.preferences_tracking_protection_exceptions),
                        description = stringResource(R.string.dejavu_protection_exceptions_summary),
                    ) {
                        findNavController().slideTo(R.id.action_global_trackingProtectionExceptionsFragment)
                    },
                ),
            )

            Section(
                title = stringResource(R.string.dejavu_protection_section_security),
                rows = listOf(
                    if (BuildConfig.HAS_SAFE_BROWSING_KEY) {
                        switchRow(
                            managed = managed(Protection.SAFE_BROWSING),
                            label = stringResource(R.string.dejavu_settings_safe_browsing),
                            on = protections.safeBrowsing,
                            onText = R.string.dejavu_protection_safe_browsing_on,
                            offText = R.string.dejavu_protection_safe_browsing_off,
                        ) {
                            protections.safeBrowsing = it
                            GoogleSafeBrowsing.apply(engine, components.core.geckoRuntime, it)
                            changed()
                        }
                    } else {
                        ProtectionRow.Switch(
                            label = stringResource(R.string.dejavu_settings_safe_browsing),
                            on = false,
                            description = stringResource(R.string.dejavu_settings_safe_browsing_no_key),
                            enabled = false,
                            onChange = {},
                        )
                    },
                    switchRow(
                        managed = managed(Protection.JIT),
                        label = stringResource(R.string.dejavu_protection_jit),
                        on = protections.blockJit,
                        onText = R.string.dejavu_protection_jit_on,
                        offText = R.string.dejavu_protection_jit_off,
                    ) {
                        protections.blockJit = it
                        JitAndWebGl.apply(engine, settings.jitEnabled, settings.webGlEnabled)
                        changed()
                    },
                    switchRow(
                        managed = managed(Protection.WEBGL),
                        label = stringResource(R.string.dejavu_protection_webgl),
                        on = protections.blockWebGl,
                        onText = R.string.dejavu_protection_webgl_on,
                        offText = R.string.dejavu_protection_webgl_off,
                    ) {
                        protections.blockWebGl = it
                        JitAndWebGl.apply(engine, settings.jitEnabled, settings.webGlEnabled)
                        changed()
                    },
                ),
            )

            Section(
                title = stringResource(R.string.dejavu_protection_section_calls_location),
                rows = listOf(
                    switchRow(
                        managed = managed(Protection.DIRECT_CALLS),
                        label = stringResource(R.string.dejavu_protection_direct_calls),
                        on = protections.blockDirectCalls,
                        onText = R.string.dejavu_protection_direct_calls_on,
                        offText = R.string.dejavu_protection_direct_calls_off,
                    ) { block ->
                        val save = {
                            protections.blockDirectCalls = block
                            CallsAndLocation.applyDirectConnections(engine, !block)
                            changed()
                        }
                        if (block) {
                            save()
                        } else {
                            warning = Warning(
                                title = R.string.dejavu_direct_calls_warning_title,
                                message = R.string.dejavu_direct_calls_warning_message,
                                onConfirm = save,
                            )
                        }
                    },
                    switchRow(
                        managed = false,
                        label = stringResource(R.string.dejavu_protection_gps_only),
                        on = protections.gpsOnlyLocation,
                        onText = R.string.dejavu_protection_gps_only_on,
                        offText = R.string.dejavu_protection_gps_only_off,
                    ) { gpsOnly ->
                        val save = {
                            protections.gpsOnlyLocation = gpsOnly
                            CallsAndLocation.applyNetworkLocation(!gpsOnly)
                            changed()
                        }
                        if (gpsOnly) {
                            save()
                        } else {
                            warning = Warning(
                                title = R.string.dejavu_network_location_warning_title,
                                message = R.string.dejavu_network_location_warning_message,
                                onConfirm = save,
                            )
                        }
                    },
                ),
            )

            Section(
                title = stringResource(R.string.dejavu_protection_section_sites),
                rows = listOf(
                    linkRow(
                        managed = false,
                        label = stringResource(R.string.preferences_site_settings),
                        description = stringResource(R.string.dejavu_protection_site_settings),
                    ) {
                        findNavController().slideTo(R.id.sitePermissionsFragment)
                    },
                    ProtectionRow.Link(
                        label = stringResource(R.string.preferences_data_collection),
                        description = stringResource(R.string.dejavu_protection_data_collection),
                        onClick = null,
                    ),
                ),
            )
            Spacer(Modifier.height(24.dp))
        }

        if (customDnsDialog) {
            CustomDnsDialog(
                current = (dnsProvider as? Provider.Custom)?.url.orEmpty(),
                onDismiss = { customDnsDialog = false },
                onAdd = { url ->
                    customDnsDialog = false
                    setDns(Provider.Custom(url))
                },
            )
        }

        warning?.let { shown ->
            DejavuDialog(
                title = stringResource(shown.title),
                onDismissRequest = { warning = null },
                buttons = {
                    DejavuDialogButton(text = stringResource(shown.cancel), onClick = { warning = null })
                    DejavuDialogButton(
                        text = stringResource(shown.confirm),
                        onClick = {
                            warning = null
                            shown.onConfirm()
                        },
                        style = DejavuButtonStyle.Danger,
                    )
                },
            ) {
                // The app's name fills the messages that name it; the others take no arguments.
                Text(stringResource(shown.message, stringResource(R.string.app_name)))
            }
        }
    }

    private fun fixesOffWarning(onConfirm: () -> Unit) = Warning(
        title = R.string.preference_enhanced_tracking_protection_allow_list_dialog_title,
        message = R.string.preference_enhanced_tracking_protection_allow_list_dialog_message,
        cancel = R.string.preference_enhanced_tracking_protection_allow_list_dialog_cancel,
        confirm = R.string.preference_enhanced_tracking_protection_allow_list_dialog_confirm,
        onConfirm = onConfirm,
    )

    private fun providerName(provider: Provider): String = when (provider) {
        is Provider.BuiltIn -> provider.name
        is Provider.Custom -> Uri.parse(provider.url).host ?: provider.url
    }

    @Composable
    private fun managedRow(label: String) = ProtectionRow.Link(
        label = label,
        description = stringResource(R.string.dejavu_protection_managed),
        onClick = null,
        enabled = false,
    )

    @Composable
    private fun switchRow(
        managed: Boolean,
        label: String,
        on: Boolean,
        @StringRes onText: Int,
        @StringRes offText: Int,
        onChange: (Boolean) -> Unit,
    ): ProtectionRow = switchRow(managed, label, on, stringResource(if (on) onText else offText), onChange)

    @Composable
    private fun switchRow(
        managed: Boolean,
        label: String,
        on: Boolean,
        description: String,
        onChange: (Boolean) -> Unit,
    ): ProtectionRow = if (managed) {
        managedRow(label)
    } else {
        ProtectionRow.Switch(label = label, on = on, description = description, enabled = true, onChange = onChange)
    }

    @Composable
    private fun linkRow(managed: Boolean, label: String, description: String, onClick: () -> Unit): ProtectionRow =
        if (managed) {
            managedRow(label)
        } else {
            ProtectionRow.Link(label = label, description = description, onClick = onClick)
        }

    @Composable
    private fun tabsRow(
        managed: Boolean,
        label: String,
        current: ProtectedTabs,
        @StringRes description: Int,
        onSelect: (ProtectedTabs) -> Unit,
    ): ProtectionRow {
        if (managed) return managedRow(label)
        val names = mapOf(
            ProtectedTabs.ALL to
                stringResource(R.string.preference_enhanced_tracking_protection_custom_tracking_content_1),
            ProtectedTabs.PRIVATE to
                stringResource(R.string.preference_enhanced_tracking_protection_custom_tracking_content_2),
            ProtectedTabs.OFF to stringResource(R.string.preference_doh_off),
        )
        return ProtectionRow.Choice(
            label = label,
            value = names.getValue(current),
            description = stringResource(description),
            options = ProtectedTabs.entries.map { tabs ->
                Option(names.getValue(tabs), selected = tabs == current) { onSelect(tabs) }
            },
        )
    }

    @Composable
    private fun siteFixesRow(managed: Boolean, current: SiteFixes, onSelect: (SiteFixes) -> Unit): ProtectionRow {
        val label = stringResource(R.string.preference_enhanced_tracking_protection_allow_list_subheader)
        if (managed) return managedRow(label)
        val names = mapOf(
            SiteFixes.ALL to stringResource(R.string.dejavu_protection_fixes_all),
            SiteFixes.MAJOR to stringResource(R.string.dejavu_protection_fixes_major),
            SiteFixes.OFF to stringResource(R.string.preference_doh_off),
        )
        return ProtectionRow.Choice(
            label = label,
            value = names.getValue(current),
            description = stringResource(
                when (current) {
                    SiteFixes.ALL -> R.string.dejavu_protection_fixes_all_summary
                    SiteFixes.MAJOR -> R.string.dejavu_protection_fixes_major_summary
                    SiteFixes.OFF -> R.string.dejavu_protection_fixes_off_summary
                },
            ),
            options = SiteFixes.entries.map { fixes ->
                Option(names.getValue(fixes), selected = fixes == current) { onSelect(fixes) }
            },
        )
    }
}

/** A row of the tracking protection settings. */
private sealed interface ProtectionRow {
    val label: String

    class Switch(
        override val label: String,
        val on: Boolean,
        val description: String,
        val enabled: Boolean,
        val onChange: (Boolean) -> Unit,
    ) : ProtectionRow

    /** A choice of [options], the chosen one shown as [value] at the end of the row. */
    class Choice(
        override val label: String,
        val value: String,
        val description: String,
        val options: List<Option>,
    ) : ProtectionRow

    /** A row that opens another screen when [onClick] is set, or only tells something. */
    class Link(
        override val label: String,
        val description: String,
        val onClick: (() -> Unit)?,
        val enabled: Boolean = true,
    ) : ProtectionRow
}

/** A choice of a [ProtectionRow.Choice]. Picking the chosen one again does nothing, unless it [repeats]. */
private class Option(
    val text: String,
    val selected: Boolean,
    val repeats: Boolean = false,
    val onSelect: () -> Unit,
)

/** A warning before a protection is turned off, which [onConfirm] then does. */
private class Warning(
    @param:StringRes val title: Int,
    @param:StringRes val message: Int,
    @param:StringRes val cancel: Int = R.string.dejavu_cancel,
    @param:StringRes val confirm: Int = R.string.dejavu_turn_off,
    val onConfirm: () -> Unit,
)

@Composable
private fun Section(title: String, rows: List<ProtectionRow>) {
    SettingsHeader(title)
    rows.forEachIndexed { index, row ->
        val modifier = Modifier.settingsCard(index, rows.size)
        when (row) {
            is ProtectionRow.Switch -> SwitchListItem(
                label = row.label,
                checked = row.on,
                modifier = modifier,
                maxLabelLines = 3,
                description = row.description,
                maxDescriptionLines = Int.MAX_VALUE,
                enabled = row.enabled,
                showSwitchAfter = true,
                onClick = row.onChange,
            )
            is ProtectionRow.Choice -> ChoiceItem(row, modifier)
            is ProtectionRow.Link -> TextListItem(
                label = row.label,
                modifier = modifier,
                maxLabelLines = 3,
                description = row.description,
                maxDescriptionLines = Int.MAX_VALUE,
                enabled = row.enabled,
                onClick = row.onClick,
            )
        }
    }
}

/** A row whose choice opens a menu from the chosen one at its end, laid out like the other rows. */
@Composable
private fun ChoiceItem(row: ProtectionRow.Choice, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(
                horizontal = FirefoxTheme.layout.space.dynamic200,
                vertical = FirefoxTheme.layout.space.static150,
            ),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = FirefoxTheme.typography.subtitle1,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
            )
            Text(
                text = row.description,
                style = FirefoxTheme.typography.body2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    // Long choices wrap, so the text beside them keeps most of the row.
                    modifier = Modifier.widthIn(max = 136.dp),
                )
                Icon(
                    painter = iconPainter(iconsR.drawable.mozac_ic_chevron_down_20),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            DejavuDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                row.options.forEach { option ->
                    DejavuMenuItem(
                        text = option.text,
                        onClick = {
                            expanded = false
                            if (!option.selected || option.repeats) option.onSelect()
                        },
                        leadingIcon = {
                            Icon(
                                painter = iconPainter(iconsR.drawable.mozac_ic_checkmark_24),
                                contentDescription = null,
                                modifier = Modifier.alpha(if (option.selected) 1f else 0f),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Asks for the address of a secure DNS provider of the user's own, which must be an https address. */
@Composable
private fun CustomDnsDialog(current: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf(current.ifEmpty { "https://" }) }
    var error by remember { mutableStateOf<Int?>(null) }
    DejavuDialog(
        title = stringResource(R.string.preference_doh_provider_custom_dialog_title),
        onDismissRequest = onDismiss,
        buttons = {
            DejavuDialogButton(
                text = stringResource(R.string.preference_doh_provider_custom_dialog_cancel),
                onClick = onDismiss,
            )
            DejavuDialogButton(
                text = stringResource(R.string.preference_doh_provider_custom_dialog_add),
                onClick = {
                    try {
                        onAdd(DohUrlValidator.validate(url.trim()))
                    } catch (_: UrlValidationException.NonHttpsUrlException) {
                        error = R.string.preference_doh_provider_custom_dialog_error_https
                    } catch (_: UrlValidationException.InvalidUrlException) {
                        error = R.string.preference_doh_provider_custom_dialog_error_invalid
                    }
                },
                style = DejavuButtonStyle.Primary,
                enabled = url.isNotBlank(),
            )
        },
    ) {
        DejavuTextField(
            value = url,
            onValueChange = {
                url = it
                error = null
            },
            label = stringResource(R.string.preference_doh_provider_custom_dialog_textfield),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
