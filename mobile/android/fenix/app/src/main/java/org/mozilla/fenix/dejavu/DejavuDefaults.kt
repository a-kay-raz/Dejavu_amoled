/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.IconRequest
import mozilla.components.browser.icons.generator.DefaultIconGenerator
import mozilla.components.browser.state.action.SearchAction
import mozilla.components.browser.state.state.availableSearchEngines
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.search.ext.createSearchEngine
import org.mozilla.fenix.R
import org.mozilla.fenix.components.ADDITIONAL_BUNDLED_SEARCH_ENGINE_IDS
import org.mozilla.fenix.dejavu.browser.ProtectionSettings
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.getPreferenceKey
import org.mozilla.fenix.onboarding.FenixOnboarding
import org.mozilla.fenix.settings.ShortcutType
import org.mozilla.fenix.utils.Settings

/**
 * Fenix preferences Dejavu changes once, so the user can still change them back in Fenix's settings.
 */
internal object DejavuDefaults {
    // Kept from before the rename to Dejavu, so saved data still loads.
    private const val PREFS_NAME = "kaizen_settings"
    private const val KEY_VERSION = "defaults_version"
    private const val VERSION = 11
    private const val POCKET_STORIES_WORK_TAG = "mozilla.components.service.pocket.recommendations.refresh.work.tag"
    private const val POCKET_SPONSORED_WORK_TAG = "mozilla.components.service.pocket.sponsored.content.refresh.work.tag"
    private const val KEY_SEARCH_VERSION = "search_defaults_version"
    private const val SEARCH_VERSION = 1

    // The search engines shown above the address bar from the start; the others are turned on in the search settings.
    private val SHORTCUT_ENGINE_IDS = setOf("google", "ddg", "youtube")

    // Addresses of secure DNS that Cloudflare and NextDNS run for Firefox, and their own public ones.
    private val FIREFOX_DOH_ADDRESSES = mapOf(
        "https://mozilla.cloudflare-dns.com/dns-query" to "https://cloudflare-dns.com/dns-query",
        "https://firefox.dns.nextdns.io/" to "https://dns.nextdns.io/",
    )

    // Site searches Dejavu adds to the search engines, as a name, a search URL and the site whose letter is their icon.
    private val ADDED_ENGINES = listOf(
        Triple("GitHub", "https://github.com/search?q={searchTerms}", "https://github.com"),
        Triple("Stack Overflow", "https://stackoverflow.com/search?q={searchTerms}", "https://stackoverflow.com"),
    )

    /**
     * Applies the defaults the first time this Dejavu version runs. Runs before telemetry and experiments start, so
     * their defaults hold from the first launch.
     */
    fun applyOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val applied = prefs.getInt(KEY_VERSION, 0)
        if (applied >= VERSION) return

        var stopStoryDownloads = false
        with(context.components.settings) {
            if (applied < 1) {
                // New tabs start from the Dejavu home screen instead of an about:home tab.
                enableHomepageAsNewTab = false
                toolbarExpandedShortcutKey = ShortcutType.HOMEPAGE.value
            }
            if (applied < 2) {
                // One toolbar row: the shortcut left of the address bar is Back, and home is always on its right.
                shouldUseExpandedToolbar = false
                toolbarSimpleShortcutKey = ShortcutType.BACK.value
                toolbarTabStripShortcutKey = ShortcutType.BACK.value
            }
            if (applied < 3) {
                // Dejavu has no shortcuts, collections, inactive tabs or tab groups; workspaces and pins replace them.
                showTopSitesFeature = false
                showContileFeature = false
                hideCollectionsUi = true
                inactiveTabsAreEnabled = false
                tabGroupsEnabled = false
            }
            if (applied < 4) {
                // Private by default: no suggestions from the search engine, Firefox Suggest or sponsors, and no
                // telemetry, studies, daily usage ping or links inviting others to Firefox.
                preferences.edit {
                    putBoolean(context.getPreferenceKey(R.string.pref_key_show_search_suggestions), false)
                    putBoolean(context.getPreferenceKey(R.string.pref_key_show_nonsponsored_suggestions), false)
                    putBoolean(context.getPreferenceKey(R.string.pref_key_show_sponsored_suggestions), false)
                    // Tabs from other devices come from Zen's sync instead.
                    putBoolean(context.getPreferenceKey(R.string.pref_key_search_synced_tabs), false)
                }
                isTelemetryEnabled = false
                isExperimentationEnabled = false
                isDailyUsagePingEnabled = false
                whatsappLinkSharingEnabled = false
                // The address bar is always at the top, with Dejavu's actions bar at the bottom.
                shouldUseBottomToolbar = false
                shouldUseExpandedToolbar = true
                shouldFollowDeviceTheme = true
                shouldUseLightTheme = false
                shouldUseDarkTheme = false
                shouldUseOledTheme = false
            }
            if (applied < 5) {
                // The options under the suggestion switches turned off above.
                preferences.edit {
                    putBoolean(context.getPreferenceKey(R.string.pref_key_show_trending_search_suggestions), false)
                    putBoolean(context.getPreferenceKey(R.string.pref_key_search_optimization_cards), false)
                }
            }
            if (applied < 6) {
                // Dejavu starts on its own home screen, without Firefox's onboarding (marked done below) or its Terms
                // of Use prompt.
                isTermsOfUsePromptEnabled = false
            }
            if (applied < 7) {
                // Dejavu's home screen shows no Pocket stories, so neither they nor the sponsored stories, which are
                // ads from Mozilla's ad service, are downloaded. Page summaries and Relay email masks are Mozilla's
                // online services for Firefox, so they are off too.
                showPocketRecommendationsFeature = false
                preferences.edit {
                    putBoolean(context.getPreferenceKey(R.string.pref_key_pocket_sponsored_stories), false)
                }
                shakeToSummarizeFeatureFlagEnabled = false
                isEmailMaskFeatureEnabled = false
                isEmailMaskSuggestionEnabled = false
                stopStoryDownloads = true
            }
            if (applied < 8) {
                turnOnSecureDns()
            }
            if (applied < 9) {
                useMaxSecureDns()
                // The engine writes no crash dumps anymore; the ones older versions wrote can hold parts of pages.
                File(context.filesDir, "minidumps").deleteRecursively()
            }
            if (applied < 10) {
                useStrictTrackingProtection(context)
                // Normal tabs tell websites not to sell or share the user's data too. Earlier versions saved it as off
                // as soon as the tracking protection settings were opened.
                shouldEnableGlobalPrivacyControl = true
            }
            if (applied < 11) {
                // Dejavu's tracking protection settings show each protection on its own, kept in Firefox's custom
                // level.
                ProtectionSettings(context, this).takeOverTrackingProtection()
            }
        }
        // Last, as it starts the browser engine (StrictMode's profiler marker does), which reads settings like secure
        // DNS once, as it starts.
        if (applied < 6) FenixOnboarding(context).finish()
        prefs.edit { putInt(KEY_VERSION, VERSION) }
        if (stopStoryDownloads) {
            // Downloads scheduled by an earlier version keep running until they are cancelled. The first use of
            // WorkManager starts the browser engine, so this waits on the main thread until the app has started.
            Handler(Looper.getMainLooper()).post {
                WorkManager.getInstance(context).run {
                    cancelAllWorkByTag(POCKET_STORIES_WORK_TAG)
                    cancelAllWorkByTag(POCKET_SPONSORED_WORK_TAG)
                }
            }
        }
    }

    /**
     * Turns remote debugging over USB off at every start, before the browser engine reads it: while it is on, a
     * computer connected over USB controls the browser without asking, so it lasts only until Dejavu closes. Its switch
     * keeps its own copy in the default preferences, which the settings screen shows.
     */
    fun applyEveryStart(context: Context) {
        val key = context.getPreferenceKey(R.string.pref_key_remote_debugging)
        listOf(context.components.settings.preferences, PreferenceManager.getDefaultSharedPreferences(context))
            .filter { it.getBoolean(key, false) }
            .forEach { it.edit { putBoolean(key, false) } }
    }

    /**
     * Secure DNS (DNS over HTTPS) is on, through Quad9 unless another provider was picked. Default Protection left it
     * off in Dejavu and is gone, so it becomes Increased Protection, which falls back to the network's DNS when the
     * provider cannot be reached.
     */
    private fun Settings.turnOnSecureDns() {
        if (getDohSettingsMode() == Engine.DohSettingsMode.DEFAULT) {
            setDohSettingsMode(Engine.DohSettingsMode.INCREASED)
        }
        FIREFOX_DOH_ADDRESSES[dohProviderUrl]?.let { dohProviderUrl = it }
    }

    /**
     * Secure DNS is at Max Protection, so it never falls back to the network's DNS on its own: when the provider cannot
     * be reached, a warning comes first. Increased Protection, Dejavu's default before, fell back without a word, which
     * also lost Encrypted Client Hello. Off stays off.
     */
    private fun Settings.useMaxSecureDns() {
        if (getDohSettingsMode() == Engine.DohSettingsMode.INCREASED) {
            setDohSettingsMode(Engine.DohSettingsMode.MAX)
        }
    }

    /**
     * Enhanced Tracking Protection is Strict, as in IronFox: on top of Standard it blocks tracking content and
     * fingerprinters, deletes the data of bounce trackers instead of only recording them, and protects against
     * fingerprinting in normal tabs too. A site it breaks can be let through with the shield in the address bar.
     * Custom stays Custom.
     */
    private fun Settings.useStrictTrackingProtection(context: Context) {
        if (!useStandardTrackingProtection) return
        preferences.edit {
            putBoolean(context.getPreferenceKey(R.string.pref_key_tracking_protection_standard_option), false)
            putBoolean(context.getPreferenceKey(R.string.pref_key_tracking_protection_strict_default), true)
        }
    }

    /**
     * Picks the search engines shown above the address bar once they have loaded: Google, DuckDuckGo, YouTube and the
     * default one are on, the others off. GitHub and Stack Overflow join the search engines, off. Someone who already
     * picked their shortcuts keeps them and only gets the added ones. Their icon is the site's letter, so nothing is
     * downloaded for them.
     */
    fun applySearchShortcutsOnce(context: Context, store: BrowserStore, scope: CoroutineScope) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SEARCH_VERSION, 0) >= SEARCH_VERSION) return
        scope.launch(Dispatchers.Main) {
            val search = store.stateFlow.first { it.search.complete }.search
            val disabled = search.disabledSearchEngineIds.toSet()
            if (disabled == ADDITIONAL_BUNDLED_SEARCH_ENGINE_IDS.toSet()) {
                val shown = SHORTCUT_ENGINE_IDS + setOfNotNull(search.selectedOrDefaultSearchEngine?.id)
                val engines = search.regionSearchEngines + search.additionalSearchEngines +
                    search.availableSearchEngines + search.customSearchEngines
                engines.map { it.id }.distinct().forEach { id ->
                    val enabled = id in shown
                    val wasOff = id in disabled
                    if (enabled == wasOff) {
                        store.dispatch(SearchAction.UpdateDisabledSearchEngineIdsAction(id, isEnabled = enabled))
                    }
                }
            }
            val existing = search.customSearchEngines.flatMap { it.resultUrls }.toSet()
            ADDED_ENGINES.filter { (_, url, _) -> url !in existing }.forEach { (name, url, site) ->
                val icon = DefaultIconGenerator().generate(context, IconRequest(site)).bitmap
                val engine = createSearchEngine(name, url, icon)
                context.components.useCases.searchUseCases.addSearchEngine(engine)
                store.dispatch(SearchAction.UpdateDisabledSearchEngineIdsAction(engine.id, isEnabled = false))
            }
            prefs.edit { putInt(KEY_SEARCH_VERSION, SEARCH_VERSION) }
        }
    }
}
