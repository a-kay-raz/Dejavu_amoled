/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import mozilla.components.ExperimentalAndroidComponentsApi
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.preferences.Branch
import mozilla.components.concept.engine.preferences.SetBrowserPreference
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.gecko.GeckoAppShell

/**
 * WebRTC, which video calls use, and the location websites get, as the privacy and security settings say. Unless
 * direct connections are allowed, WebRTC connects only through the site's relay servers and never offers the phone's
 * own addresses; unless network location is allowed, the location comes from GPS only. The WebRTC prefs are set as
 * the engine's defaults, so values changed in about:config stay.
 */
object CallsAndLocation {
    private val logger = Logger("CallsAndLocation")

    // Read when a call starts its connection, so a change reaches the next call.
    internal val RELAY_ONLY_PREFS = listOf(
        "media.peerconnection.ice.relay_only",
        "media.peerconnection.ice.no_host",
        "media.peerconnection.ice.default_address_only",
    )

    /** The engine defaults for WebRTC with direct connections allowed or not, as [direct] says. */
    internal fun prefs(direct: Boolean): List<SetBrowserPreference<Boolean>> =
        RELAY_ONLY_PREFS.map { SetBrowserPreference.setBoolPref(it, !direct, Branch.DEFAULT) }

    /** Sets WebRTC in [engine], when it starts and when the switch changes. */
    @OptIn(ExperimentalAndroidComponentsApi::class)
    fun applyDirectConnections(engine: Engine, direct: Boolean) {
        engine.setBrowserPrefs(
            prefs = prefs(direct),
            onSuccess = {},
            onError = { logger.error("Could not set the WebRTC prefs", it) },
        )
    }

    /** Lets Android's network location be used besides GPS, or not, when Dejavu starts and when the switch changes. */
    fun applyNetworkLocation(allowed: Boolean) {
        try {
            val method = GeckoAppShell::class.java.getMethod("setNetworkLocationAllowed", java.lang.Boolean.TYPE)
            method.invoke(null, allowed)
        } catch (_: Throwable) {
            // GeckoView without custom Dejavu patch
        }
    }
}
