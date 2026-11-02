/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mozilla.components.feature.summarize.settings.SummarizationSettings
import org.mozilla.fenix.FeatureFlags
import org.mozilla.fenix.GleanMetrics.AppTheme
import org.mozilla.fenix.GleanMetrics.CustomizationSettings
import org.mozilla.fenix.GleanMetrics.PullToRefreshInBrowser
import org.mozilla.fenix.GleanMetrics.ToolbarSettings
import org.mozilla.fenix.R
import org.mozilla.fenix.components.toolbar.ToolbarPosition
import org.mozilla.fenix.dejavu.browser.BarsLayout
import org.mozilla.fenix.dejavu.browser.DejavuToolbar
import org.mozilla.fenix.dejavu.discover.NewThing
import org.mozilla.fenix.dejavu.discover.NewThings
import org.mozilla.fenix.dejavu.settings.dejavuSettings
import org.mozilla.fenix.dejavu.settings.slideTo
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.isTallWindow
import org.mozilla.fenix.ext.isWideWindow
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.settings.settingssearch.PreferenceFileInformation.DejavuBarsPreferences
import org.mozilla.fenix.settings.settingssearch.SettingsSearchItem
import org.mozilla.fenix.settings.settingssearch.SettingsSearchProvider
import org.mozilla.fenix.translations.TranslationsEnabledSettings
import org.mozilla.fenix.utils.Settings
import org.mozilla.fenix.utils.view.addToRadioGroup

/** Lets the user customize the UI. */
@Suppress("TooManyFunctions")
open class CustomizationFragment : PreferenceFragmentCompat(), SystemInsetsPaddedFragment {
    private lateinit var radioLightTheme: RadioButtonPreference
    private lateinit var radioDarkTheme: RadioButtonPreference
    private lateinit var radioDarkestTheme: RadioButtonPreference
    private lateinit var radioAutoBatteryTheme: RadioButtonPreference
    private lateinit var radioFollowDeviceTheme: RadioButtonPreference
    private val args by navArgs<CustomizationFragmentArgs>()

    /**
     * Dejavu: Customize shows the theme and opens the bars, the tab bar and the gestures, which this fragment shows on
     * a screen of their own as [org.mozilla.fenix.dejavu.settings.DejavuBarsFragment].
     */
    protected open val showsBars: Boolean
        get() = false

    // Cached reactive feature state used when (re)building preferences.
    // Defaults match the DataStore defaults (summarization off, translations on)
    private var dynamicFeaturesStatus =
        DynamicFeaturesStatus(
            isSummarizationFeatureEnabled = false,
            isSummarizationGestureEnabled = false,
            isTranslationsFeatureEnabled = true,
            isTranslationsFeatureSupported = false,
        )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(
            if (showsBars) R.xml.dejavu_bars_preferences else R.xml.customization_preferences,
            rootKey,
        )

        setupPreferences(dynamicFeaturesStatus)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Dejavu: the menu's settings hold the new Clear data buttons, which an orange dot leads to.
        findPreference<Preference>(getString(R.string.pref_key_dejavu_more_menu))?.let {
            NewThings.follow(it, NewThing.CLEAR_DATA_ENTRIES, viewLifecycleOwner)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                val summarizationDataStore = SummarizationSettings.dataStore(requireContext())
                val translationsDataStore = TranslationsEnabledSettings.dataStore(requireContext())
                val browserStore = requireComponents.core.store
                combine(
                        summarizationDataStore.getFeatureEnabledUserStatus(),
                        summarizationDataStore.getGestureEnabledUserStatus(),
                        translationsDataStore.isEnabled,
                        browserStore.stateFlow,
                    ) { isSummarizationEnabled, isSummarizationGestureEnabled, areTranslationsEnabled, browserState ->
                        DynamicFeaturesStatus(
                            isSummarizationFeatureEnabled = isSummarizationEnabled == true,
                            isSummarizationGestureEnabled = isSummarizationGestureEnabled,
                            isTranslationsFeatureEnabled = areTranslationsEnabled,
                            isTranslationsFeatureSupported = browserState.translationEngine.isEngineSupported ?: false,
                        )
                    }
                    .distinctUntilChanged()
                    .collect { status ->
                        setupPreferences(status)
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(if (showsBars) R.string.dejavu_settings_bars_2 else R.string.preferences_customize))
        args.preferenceToScrollTo?.let {
            scrollToPreferenceWithHighlight(it)
        }
    }

    private fun setupPreferences(status: DynamicFeaturesStatus) {
        dynamicFeaturesStatus = status

        if (!showsBars) {
            bindFollowDeviceTheme()
            bindDarkTheme()
            bindDarkestTheme()
            bindLightTheme()
            bindAutoBatteryTheme()
            setupRadioGroups()
            setupMoreMenuEditButton()
            return
        }
        setupToolbarCategory()
        setupTabStripDisplayCategory()
        setupTabStripLocationCategory()
        setupToolbarLayout()
        updateToolbarShortcut()
        setupBarsCategory()
        setupHomeSearchCategory()
        setupSystemBarsSwitch()

        // if tab strip is enabled, swipe toolbar to switch tabs should not be enabled so the
        // preference is not shown
        val tabletAndTabStripEnabled = Settings(requireContext()).isTabStripEnabled
        setupGesturesCategory(
            isSwipeToolbarToSwitchTabsVisible = !tabletAndTabStripEnabled,
            isSummarizationEnabled = status.isSummarizationFeatureEnabled,
            isSummarizationGestureEnabled = status.isSummarizationGestureEnabled,
        )
    }

    private fun updateToolbarShortcut() {
        val category = requirePreference<PreferenceCategory>(R.string.pref_key_customization_category_toolbar_shortcut)
        val settings = requireComponents.settings
        val isExpandedToolbarEnabled = settings.shouldUseExpandedToolbar && isTallWindow() && !isWideWindow()
        val isAnyShortcutSelectedForSimpleToolbar = settings.toolbarSimpleShortcutKey != ShortcutType.NONE.value

        val shortcutPreference =
            if (isExpandedToolbarEnabled) {
                buildExpandedToolbarCustomButtonSetting()
            } else if (settings.isTabStripEnabled) {
                if (settings.toolbarTabStripShortcutKey != ShortcutType.NONE.value) {
                    buildTabStripToolbarWithCustomButtonSelectedSetting()
                } else {
                    buildTabStripToolbarWithNoCustomButtonSelectedSetting()
                }
            } else if (isAnyShortcutSelectedForSimpleToolbar) {
                buildSimpleToolbarWithCustomButtonSelectedSetting()
            } else {
                buildSimpleToolbarWithNoCustomButtonSelectedSetting()
            }
        category.apply {
            removeAll()
            addPreference(shortcutPreference)
            val shortcutOptions = shortcutPreference.getShortcutOptions()
            shortcutOptions.forEach(::addPreference)
        }
    }

    private fun buildExpandedToolbarCustomButtonSetting() =
        ToolbarExpandedShortcutPreference(requireContext()).apply {
            key = getString(R.string.pref_key_toolbar_expanded_shortcut)
            layoutResource = R.layout.preference_toolbar_shortcut
            isTranslationsFeatureEnabled = dynamicFeaturesStatus.isTranslationsFeatureEnabled
        }

    private fun buildSimpleToolbarWithCustomButtonSelectedSetting() =
        ToolbarSimpleShortcutPreference(requireContext()).apply {
            key = getString(R.string.pref_key_toolbar_simple_shortcut)
            layoutResource = R.layout.preference_toolbar_shortcut
            isTranslationsFeatureEnabled = dynamicFeaturesStatus.isTranslationsFeatureEnabled
            optionChangedListener = { newOption ->
                if (newOption == null || newOption.key.value == ShortcutType.NONE.value) {
                    updateToolbarShortcut()
                }
            }
        }

    private fun buildSimpleToolbarWithNoCustomButtonSelectedSetting() =
        ToolbarSimpleNoShortcutPreference(requireContext()).apply {
            key = getString(R.string.pref_key_toolbar_simple_no_shortcut)
            layoutResource = R.layout.preference_toolbar_shortcut
            isTranslationsFeatureEnabled = dynamicFeaturesStatus.isTranslationsFeatureEnabled
            optionChangedListener = { newOption ->
                if (newOption == null || newOption.key.value != ShortcutType.NONE.value) {
                    updateToolbarShortcut()
                }
            }
        }

    private fun buildTabStripToolbarWithCustomButtonSelectedSetting() =
        ToolbarTabStripShortcutPreference(requireContext()).apply {
            key = getString(R.string.pref_key_toolbar_tab_strip_shortcut)
            layoutResource = R.layout.preference_toolbar_shortcut
            isTranslationsFeatureEnabled = dynamicFeaturesStatus.isTranslationsFeatureEnabled
            optionChangedListener = { newOption ->
                if (newOption == null || newOption.key.value == ShortcutType.NONE.value) {
                    updateToolbarShortcut()
                }
            }
        }

    private fun buildTabStripToolbarWithNoCustomButtonSelectedSetting() =
        ToolbarTabStripNoShortcutPreference(requireContext()).apply {
            key = getString(R.string.pref_key_toolbar_tab_strip_no_shortcut)
            layoutResource = R.layout.preference_toolbar_shortcut
            isTranslationsFeatureEnabled = dynamicFeaturesStatus.isTranslationsFeatureEnabled
            optionChangedListener = { newOption ->
                if (newOption == null || newOption.key.value != ShortcutType.NONE.value) {
                    updateToolbarShortcut()
                }
            }
        }

    private fun setupRadioGroups() {
        addToRadioGroup(
            radioLightTheme,
            radioDarkTheme,
            radioDarkestTheme,
            if (SDK_INT >= Build.VERSION_CODES.P) {
                radioFollowDeviceTheme
            } else {
                radioAutoBatteryTheme
            },
        )
    }

    private fun bindLightTheme() {
        radioLightTheme = requirePreference(R.string.pref_key_light_theme)
        radioLightTheme.onClickListener {
            setNewTheme(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private fun bindAutoBatteryTheme() {
        radioAutoBatteryTheme = requirePreference(R.string.pref_key_auto_battery_theme)
        radioAutoBatteryTheme.onClickListener {
            setNewTheme(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
        }
    }

    private fun bindDarkTheme() {
        radioDarkTheme = requirePreference(R.string.pref_key_dark_theme)
        radioDarkTheme.onClickListener {
            AppTheme.darkThemeSelected.record(AppTheme.DarkThemeSelectedExtra("SETTINGS"))
            setNewTheme(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    private fun bindDarkestTheme() {
        val settings = requireComponents.settings
        radioDarkestTheme = requirePreference(R.string.pref_key_oled_theme)
        radioDarkestTheme.isVisible = settings.enableOledTheme
        radioDarkestTheme.onClickListener {
            AppTheme.oledThemeSelected.record(AppTheme.OledThemeSelectedExtra("SETTINGS"))
            setNewTheme(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    private fun bindFollowDeviceTheme() {
        radioFollowDeviceTheme = requirePreference(R.string.pref_key_follow_device_theme)
        if (SDK_INT >= Build.VERSION_CODES.P) {
            radioFollowDeviceTheme.onClickListener {
                setNewTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    private fun setNewTheme(mode: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == mode) return
        AppCompatDelegate.setDefaultNightMode(mode)
        activity?.recreate()
        requireComponents.core.applyPreferredColorScheme()
        requireComponents.useCases.sessionUseCases.reload.invoke()
    }

    /** Chooses where Dejavu's bars show around pages, and with it how the "More" menu opens. */
    private fun setupBarsCategory() {
        val dejavuSettings = dejavuSettings()
        val choices = mapOf(
            BarsLayout.ACTIONS_BAR to R.string.pref_key_dejavu_bars_actions_bar,
            BarsLayout.TOP to R.string.pref_key_dejavu_bars_top,
            BarsLayout.BOTTOM to R.string.pref_key_dejavu_bars_bottom,
        ).mapValues { (layout, key) ->
            requirePreference<RadioButtonPreference>(key).apply {
                onClickListener { dejavuSettings.setBarsLayout(layout) }
                setCheckedWithoutClickListener(dejavuSettings.barsLayout.value == layout)
            }
        }
        addToRadioGroup(*choices.values.toTypedArray())
    }

    /** Chooses whether the home screen shows its search bar on top, or at the bottom with the workspace bar on top. */
    private fun setupHomeSearchCategory() {
        val dejavuSettings = dejavuSettings()
        val choices = listOf(
            false to R.string.pref_key_dejavu_home_search_top,
            true to R.string.pref_key_dejavu_home_search_bottom,
        ).map { (atBottom, key) ->
            requirePreference<RadioButtonPreference>(key).apply {
                onClickListener { dejavuSettings.setHomeSearchAtBottom(atBottom) }
                setCheckedWithoutClickListener(dejavuSettings.homeSearchAtBottom.value == atBottom)
            }
        }
        addToRadioGroup(*choices.toTypedArray())
    }

    /** Hides the Edit button at the bottom of Dejavu's "More" menu, which Dejavu's own settings keep. */
    private fun setupMoreMenuEditButton() {
        val dejavuSettings = dejavuSettings()
        requirePreference<SwitchPreferenceCompat>(R.string.pref_key_dejavu_more_menu_hide_edit).apply {
            isChecked = dejavuSettings.moreMenuEditHidden.value
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                dejavuSettings.setMoreMenuEditHidden(newValue == true)
                true
            }
        }
    }

    /** Hides the status bar and the navigation bar over pages, which Dejavu's own settings keep. */
    private fun setupSystemBarsSwitch() {
        val dejavuSettings = dejavuSettings()
        requirePreference<SwitchPreferenceCompat>(R.string.pref_key_dejavu_hide_system_bars).apply {
            isChecked = dejavuSettings.systemBarsHidden.value
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                dejavuSettings.setSystemBarsHidden(newValue == true)
                true
            }
        }
    }

    private fun setupToolbarCategory() {
        val topPreference = requirePreference<RadioButtonPreference>(R.string.pref_key_toolbar_top)
        topPreference.onClickListener {
            ToolbarSettings.changedPosition.record(ToolbarSettings.ChangedPositionExtra(Position.TOP.name))

            updateToolbarLayoutIcons()
        }

        val bottomPreference = requirePreference<RadioButtonPreference>(R.string.pref_key_toolbar_bottom)
        bottomPreference.onClickListener {
            ToolbarSettings.changedPosition.record(ToolbarSettings.ChangedPositionExtra(Position.BOTTOM.name))

            updateToolbarLayoutIcons()
        }

        val toolbarPosition = requireComponents.settings.toolbarPosition
        topPreference.setCheckedWithoutClickListener(toolbarPosition == ToolbarPosition.TOP)
        bottomPreference.setCheckedWithoutClickListener(toolbarPosition == ToolbarPosition.BOTTOM)

        addToRadioGroup(topPreference, bottomPreference)
    }

    private fun setupTabStripDisplayCategory() {
        val tabStripSwitch = requirePreference<SwitchPreferenceCompat>(R.string.pref_key_tab_strip_show)
        val context = requireContext()

        tabStripSwitch.isChecked = Settings(requireContext()).isTabStripEnabled

        tabStripSwitch.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            context.components.settings.isTabStripEnabled = enabled
            setupToolbarLayout()
            setupTabStripLocationCategory()
            updateToolbarShortcut()
            true
        }
    }

    private fun setupTabStripLocationCategory() {
        val settings = requireComponents.settings
        val isTabStripEnabled = settings.isTabStripEnabled

        requirePreference<PreferenceCategory>(R.string.pref_key_tab_strip_location).isVisible = isTabStripEnabled
        if (!isTabStripEnabled) return

        val topPreference = requirePreference<RadioButtonPreference>(R.string.pref_key_tab_bar_top)
        topPreference.onClickListener {
            ToolbarSettings.changedPosition.record(ToolbarSettings.ChangedPositionExtra(Position.TOP.name))
        }
        val bottomPreference = requirePreference<RadioButtonPreference>(R.string.pref_key_tab_bar_bottom)
        bottomPreference.onClickListener {
            ToolbarSettings.changedPosition.record(ToolbarSettings.ChangedPositionExtra(Position.BOTTOM.name))
        }

        val tabStripPosition = settings.tabStripPosition
        topPreference.setCheckedWithoutClickListener(tabStripPosition == ToolbarPosition.TOP)
        bottomPreference.setCheckedWithoutClickListener(tabStripPosition == ToolbarPosition.BOTTOM)

        addToRadioGroup(topPreference, bottomPreference)
    }

    private fun setupToolbarLayout() {
        (requirePreference(R.string.pref_key_customization_category_toolbar_layout) as PreferenceCategory).apply {
            // Dejavu's toolbar is always a single row.
            isVisible = isTallWindow() && !isWideWindow() && !DejavuToolbar.enabled
        }

        val layoutToggle = requirePreference<ToggleRadioButtonPreference>(R.string.pref_key_toolbar_expanded)
        layoutToggle.setOnToggleChanged {
            updateToolbarShortcut()
        }
        updateToolbarLayoutIcons()
    }

    private fun updateToolbarLayoutIcons() {
        (requirePreference(R.string.pref_key_toolbar_expanded) as ToggleRadioButtonPreference).apply {
            if (requireComponents.settings.shouldUseBottomToolbar) {
                updateIcon(R.drawable.ic_toolbar_bottom_expanded, R.drawable.ic_toolbar_bottom_simple)
            } else {
                updateIcon(R.drawable.ic_toolbar_top_expanded, R.drawable.ic_toolbar_top_simple)
            }
        }
    }

    private fun setupGesturesCategory(
        isSwipeToolbarToSwitchTabsVisible: Boolean,
        isSummarizationEnabled: Boolean,
        isSummarizationGestureEnabled: Boolean,
    ) {
        requirePreference<SwitchPreferenceCompat>(R.string.pref_key_website_pull_to_refresh).apply {
            isVisible = FeatureFlags.PULL_TO_REFRESH_ENABLED
            isChecked = context.components.settings.isPullToRefreshEnabledInBrowser
            onPreferenceChangeListener = SharedPreferenceUpdater()
        }
        requirePreference<SwitchPreferenceCompat>(R.string.pref_key_dynamic_toolbar).apply {
            isChecked = context.components.settings.isDynamicToolbarEnabled
            onPreferenceChangeListener = SharedPreferenceUpdater()
        }
        requirePreference<SwitchPreferenceCompat>(R.string.pref_key_swipe_toolbar_switch_tabs).apply {
            isChecked = context.components.settings.isSwipeToolbarToSwitchTabsEnabled
            isVisible = isSwipeToolbarToSwitchTabsVisible
            onPreferenceChangeListener = SharedPreferenceUpdater()
        }
        requirePreference<SwitchPreferenceCompat>(R.string.pref_key_swipe_toolbar_show_tabs).apply {
            // In Dejavu, swiping the toolbar opens the menu; tabs are on the home screen.
            isVisible = !DejavuToolbar.enabled
            isChecked = context.components.settings.isSwipeToolbarToShowTabsEnabled
            onPreferenceChangeListener = SharedPreferenceUpdater()
        }
        requirePreference<SwitchPreferenceCompat>(R.string.pref_key_shake_gesture_enabled).apply {
            isVisible = context.components.settings.shakeToSummarizeFeatureFlagEnabled && isSummarizationEnabled
            isChecked = isSummarizationGestureEnabled
            onPreferenceChangeListener = { _, newValue ->
                val updatedValue = (newValue as? Boolean) ?: false
                viewLifecycleOwner.lifecycleScope.launch {
                    SummarizationSettings.dataStore(requireContext()).setGestureEnabledUserStatus(updatedValue)
                }
                true
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            resources.getString(R.string.pref_key_dejavu_tab_actions) ->
                return openDejavuScreen(R.id.dejavu_tab_actions_graph)
            resources.getString(R.string.pref_key_dejavu_more_menu) ->
                return openDejavuScreen(R.id.dejavu_more_menu_graph)
            resources.getString(R.string.pref_key_dejavu_bars) -> return openDejavuScreen(R.id.dejavu_bars_graph)
            resources.getString(R.string.pref_key_website_pull_to_refresh) -> {
                PullToRefreshInBrowser.enabled.set(requireComponents.settings.isPullToRefreshEnabledInBrowser)
            }
            resources.getString(R.string.pref_key_dynamic_toolbar) -> {
                CustomizationSettings.dynamicToolbar.set(requireComponents.settings.isDynamicToolbarEnabled)
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    /** Dejavu: opens one of the screens Customize leads to, only once when its entry is tapped twice. */
    private fun openDejavuScreen(@IdRes graph: Int): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.customizationFragment) navController.slideTo(graph)
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!showsBars) return
        setupToolbarLayout()
        updateToolbarShortcut()
    }

    companion object {
        // Used to send telemetry data about toolbar position changes
        enum class Position {
            TOP,
            BOTTOM,
        }
    }

    private data class DynamicFeaturesStatus(
        val isSummarizationFeatureEnabled: Boolean,
        val isSummarizationGestureEnabled: Boolean,
        val isTranslationsFeatureEnabled: Boolean,
        val isTranslationsFeatureSupported: Boolean,
    )
}

/** Provides the toolbar shortcuts options for use in settings search. */
object ToolbarShortcutSettingsSearchProvider : SettingsSearchProvider {
    // Dejavu: the toolbar settings are on the bars' own screen.
    private val preferenceFileInformation = DejavuBarsPreferences

    override fun getSearchItems(context: Context) =
        listOf(
            SettingsSearchItem(
                title = context.getString(R.string.preferences_toolbar_shortcut),
                summary = "",
                preferenceKey = context.getString(R.string.pref_key_customization_category_toolbar_shortcut),
                categoryHeader = context.getString(preferenceFileInformation.categoryHeaderResourceId),
                preferenceFileInformation = preferenceFileInformation,
            )
        )
}
