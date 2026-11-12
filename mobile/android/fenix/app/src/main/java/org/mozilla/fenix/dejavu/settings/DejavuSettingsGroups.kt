/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.annotation.XmlRes
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import mozilla.components.service.fxrelay.eligibility.Eligible
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.getPreferenceKey
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.nimbus.FxNimbus
import org.mozilla.fenix.settings.CustomizationFragment
import org.mozilla.fenix.settings.requirePreference
import org.mozilla.fenix.settings.scrollToPreferenceWithHighlight
import org.mozilla.fenix.utils.Settings

/**
 * A settings screen that groups entries the main settings used to show, each opening the screen it opened there.
 *
 * @param preferences The entries.
 * @param title Title of the settings toolbar.
 * @param destination This screen's own destination, so a second tap does not open an entry twice.
 */
abstract class DejavuSettingsGroupFragment(
    @param:XmlRes private val preferences: Int,
    @param:StringRes private val title: Int,
    @param:IdRes private val destination: Int,
) : PreferenceFragmentCompat(), SystemInsetsPaddedFragment {
    /** Where each entry leads, by its key. */
    internal abstract val entries: List<Entry>

    /** Where the entry with the key [key] leads: the screen or graph [opens], which slides in from the side. */
    internal class Entry(@param:StringRes val key: Int, @param:IdRes val opens: Int)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(title))
        update(requireComponents.settings)
        arguments?.getString(ARG_PREFERENCE_TO_SCROLL_TO)?.let(::scrollToPreferenceWithHighlight)
    }

    /** Brings the summaries of the entries up to date. */
    protected open fun update(settings: Settings) = Unit

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val entry = entries.firstOrNull { getString(it.key) == preference.key }
            ?: return super.onPreferenceTreeClick(preference)
        val navController = findNavController()
        if (navController.currentDestination?.id == destination) {
            navController.slideTo(entry.opens)
        }
        return true
    }

    private companion object {
        const val ARG_PREFERENCE_TO_SCROLL_TO = "preference_to_scroll_to"
    }
}

/** Closing and sleeping tabs, containers, the homepage, where links open and moving tabs to Sidebery, from General. */
class DejavuTabsWorkspacesFragment : DejavuSettingsGroupFragment(
    R.xml.dejavu_tabs_workspaces_preferences,
    R.string.dejavu_settings_tabs_workspaces,
    R.id.dejavuTabsWorkspacesFragment,
) {
    override val entries = listOf(
        Entry(R.string.pref_key_tabs, R.id.tabsSettingsFragment),
        Entry(R.string.pref_key_dejavu_tab_sleep, R.id.dejavu_tab_sleep_graph),
        Entry(R.string.pref_key_dejavu_containers, R.id.dejavu_containers_graph),
        Entry(R.string.pref_key_home, R.id.homeSettingsFragment),
        Entry(R.string.pref_key_dejavu_external_links, R.id.dejavu_external_links_graph),
        Entry(R.string.pref_key_open_links_in_apps, R.id.openLinksInAppsFragment),
        Entry(R.string.pref_key_dejavu_tabs_import_export, R.id.dejavu_tabs_import_export_graph),
    )

    override fun update(settings: Settings) {
        requirePreference<Preference>(R.string.pref_key_tabs).summary = settings.getTabTimeoutString()
        requirePreference<Preference>(R.string.pref_key_open_links_in_apps).summary =
            settings.getOpenLinksInAppsString()
        setupHomepagePreference(settings)
    }

    @VisibleForTesting
    internal fun setupHomepagePreference(settings: Settings) {
        with(requirePreference<Preference>(R.string.pref_key_home)) {
            summary =
                when {
                    settings.alwaysOpenTheHomepageWhenOpeningTheApp ->
                        getString(R.string.opening_screen_homepage_summary)

                    settings.openHomepageAfterFourHoursOfInactivity ->
                        getString(R.string.opening_screen_after_four_hours_of_inactivity_summary)

                    settings.alwaysOpenTheLastTabWhenOpeningTheApp ->
                        getString(R.string.opening_screen_last_tab_summary)

                    else -> null
                }
        }
    }
}

/** Passwords, autofill, accessibility, language, translations and AI controls, from General. */
class DejavuPasswordsLanguageFragment : DejavuSettingsGroupFragment(
    R.xml.dejavu_passwords_language_preferences,
    R.string.dejavu_settings_passwords_language,
    R.id.dejavuPasswordsLanguageFragment,
) {
    override val entries = listOf(
        Entry(R.string.pref_key_passwords, R.id.savedLoginsAuthFragment),
        Entry(R.string.pref_key_email_masks, R.id.emailMasksSettingsFragment),
        Entry(R.string.pref_key_credit_cards, R.id.autofill_graph),
        Entry(R.string.pref_key_accessibility, R.id.accessibilityFragment),
        Entry(R.string.pref_key_language, R.id.localeSettingsFragment),
        Entry(R.string.pref_key_translation, R.id.translations_settings_graph),
        Entry(R.string.pref_key_page_summaries, R.id.pageSummariesSettingsFragment),
        Entry(R.string.pref_key_ai_controls, R.id.aiControlsFragment),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val components = requireComponents

        findPreference<Preference>(getPreferenceKey(R.string.pref_key_translation))?.isVisible =
            FxNimbus.features.translations.value().globalSettingsEnabled &&
                components.core.store.state.translationEngine.isEngineSupported == true

        findPreference<Preference>(getPreferenceKey(R.string.pref_key_page_summaries))?.isVisible =
            components.settings.shakeToSummarizeFeatureFlagEnabled

        findPreference<Preference>(getPreferenceKey(R.string.pref_key_ai_controls))?.isVisible =
            components.settings.aiControlsFeatureFlagEnabled
    }

    override fun update(settings: Settings) {
        val autofillPreference = requirePreference<Preference>(R.string.pref_key_credit_cards)
        autofillPreference.title =
            if (settings.addressFeature) {
                getString(R.string.preferences_autofill)
            } else {
                getString(R.string.preferences_credit_cards_2)
            }

        setupEmailMaskPreference(settings, requireComponents)

        if (!settings.isAutofillSupported) {
            requirePreference<Preference>(R.string.pref_key_passwords).isVisible = false
            autofillPreference.isVisible = false
        }
    }

    @VisibleForTesting
    internal fun setupEmailMaskPreference(settings: Settings, components: Components) {
        findPreference<Preference>(getPreferenceKey(R.string.pref_key_email_masks))?.let {
            it.isVisible =
                settings.isEmailMaskFeatureEnabled &&
                    components.relayEligibilityStore.state.eligibilityState is Eligible
        }
    }
}

/** Where the bars show on pages and on the home screen, full screen, the tab bar and the gestures, from Customize. */
class DejavuBarsFragment : CustomizationFragment() {
    override val showsBars: Boolean
        get() = true
}
