/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.content.Context
import mozilla.components.support.test.robolectric.testContext
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.HttpMethod
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.browser.BarsLayout
import org.mozilla.fenix.dejavu.browser.FloatingButtonPlace
import org.mozilla.fenix.dejavu.menu.AddressBarButtons
import org.mozilla.fenix.dejavu.ui.AccentColor
import org.mozilla.fenix.dejavu.ui.IconStyle
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DejavuSettingsTest {
    /** Settings as the app reads them when it starts. */
    private fun newSettings(): DejavuSettings =
        DejavuSettings::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    @Test
    fun `essentials per container follow sync until the user chooses`() {
        val settings = newSettings()
        assertFalse(settings.essentialsPerContainer.value)

        settings.setEssentialsPerContainerFromSync(true)
        assertTrue(settings.essentialsPerContainer.value)
        assertTrue(newSettings().essentialsPerContainer.value)

        settings.setEssentialsPerContainer(false)
        settings.setEssentialsPerContainerFromSync(true)
        assertFalse(settings.essentialsPerContainer.value)
        assertFalse(newSettings().essentialsPerContainer.value)
    }

    @Test
    fun `the status bar and the navigation bar show over pages until the user hides them`() {
        val settings = newSettings()
        assertFalse(settings.systemBarsHidden.value)

        settings.setSystemBarsHidden(true)
        assertTrue(settings.systemBarsHidden.value)
        assertTrue(newSettings().systemBarsHidden.value)

        settings.setSystemBarsHidden(false)
        assertFalse(newSettings().systemBarsHidden.value)
    }

    @Test
    fun `full screen view stays as the user left it, and its button where the user dragged it`() {
        val settings = newSettings()
        assertFalse(settings.fullScreenView.value)
        assertEquals(FloatingButtonPlace(), settings.fullScreenButton.value)

        settings.setFullScreenView(true)
        settings.setFullScreenButton(FloatingButtonPlace(atEnd = false, height = 0.2f))
        val read = newSettings()
        assertTrue(read.fullScreenView.value)
        assertEquals(FloatingButtonPlace(atEnd = false, height = 0.2f), read.fullScreenButton.value)

        read.setFullScreenView(false)
        assertFalse(newSettings().fullScreenView.value)
    }

    @Test
    fun `animations and the genie are on until the user turns them off`() {
        val settings = newSettings()
        assertTrue(settings.animations.value)
        assertTrue(settings.genieEffect.value)

        settings.setGenieEffect(false)
        assertTrue(newSettings().animations.value)
        assertFalse(newSettings().genieEffect.value)

        settings.setAnimations(false)
        settings.setGenieEffect(true)
        assertFalse(newSettings().animations.value)
        assertTrue(newSettings().genieEffect.value)
    }

    @Test
    fun `the accent color and the style of icons stay as the user picked them`() {
        val settings = newSettings()
        assertEquals(AccentColor.NEUTRAL, settings.accentColor.value)
        assertEquals(IconStyle.DEJAVU, settings.iconStyle.value)

        settings.setAccentColor(AccentColor.BLUE)
        settings.setIconStyle(IconStyle.FIREFOX)
        assertEquals(AccentColor.BLUE, newSettings().accentColor.value)
        assertEquals(IconStyle.FIREFOX, newSettings().iconStyle.value)
    }

    @Test
    fun `the essentials show until the user hides them, all of them or one by one`() {
        val settings = newSettings()
        assertTrue(settings.essentialsShown.value)
        assertEquals(emptySet<String>(), settings.hiddenEssentials.value)

        settings.setEssentialHidden("a", hidden = true)
        settings.setEssentialHidden("b", hidden = true)
        settings.setEssentialHidden("a", hidden = false)
        settings.setEssentialsShown(false)
        val read = newSettings()
        assertFalse(read.essentialsShown.value)
        assertEquals(setOf("b"), read.hiddenEssentials.value)
    }

    @Test
    fun `the bars keep the layout the user chose, and the address bar the buttons the user chose`() {
        val settings = newSettings()
        assertEquals(BarsLayout.ACTIONS_BAR, settings.barsLayout.value)
        // Next to the actions bar, the address bar starts without buttons; without it, with New tab and Home.
        assertEquals(AddressBarButtons(), settings.addressBarButtonsIn(BarsLayout.ACTIONS_BAR))
        assertEquals(AddressBarButtons("new_tab", "home"), settings.addressBarButtonsIn(BarsLayout.TOP))
        assertEquals(AddressBarButtons("new_tab", "home"), settings.addressBarButtonsIn(BarsLayout.BOTTOM))

        settings.setBarsLayout(BarsLayout.BOTTOM)
        settings.setAddressBarButtons(AddressBarButtons(start = null, end = "share"))
        val read = newSettings()
        assertEquals(BarsLayout.BOTTOM, read.barsLayout.value)
        // Buttons the user chose show in every layout, a side left empty included.
        assertEquals(AddressBarButtons(null, "share"), read.addressBarButtonsIn(BarsLayout.ACTIONS_BAR))
        assertEquals(AddressBarButtons(null, "share"), read.addressBarButtonsIn(BarsLayout.TOP))

        read.resetAddressBarButtons()
        assertNull(newSettings().addressBarButtons.value)
        assertEquals(AddressBarButtons(), newSettings().addressBarButtonsIn(BarsLayout.ACTIONS_BAR))
    }

    @Test
    fun `a deleted custom action leaves the side of the address bar it was on`() {
        val settings = newSettings()
        val action = CustomAction("a", "Karakeep", HttpMethod.POST, "https://example.com", emptyList(), "{}")
        settings.saveCustomAction(action)
        settings.setAddressBarButtons(AddressBarButtons(start = RowAction.keyOf("a"), end = "home"))

        settings.deleteCustomAction("a")

        assertEquals(AddressBarButtons(null, "home"), settings.addressBarButtons.value)
        assertEquals(AddressBarButtons(null, "home"), newSettings().addressBarButtons.value)
    }

    @Test
    fun `custom actions an older Dejavu saved in plain text still load, and saved ones load again`() {
        val action = CustomAction("a", "Karakeep", HttpMethod.POST, "https://example.com", emptyList(), "{}")
        testContext.getSharedPreferences("kaizen_settings", Context.MODE_PRIVATE).edit()
            .putString("custom_actions", JSONArray().put(action.toJson()).toString())
            .commit()

        val settings = newSettings()
        assertEquals(listOf(action), settings.customActions.value)

        val renamed = action.copy(name = "Linkwarden")
        settings.saveCustomAction(renamed)
        assertEquals(listOf(renamed), newSettings().customActions.value)
    }
}
