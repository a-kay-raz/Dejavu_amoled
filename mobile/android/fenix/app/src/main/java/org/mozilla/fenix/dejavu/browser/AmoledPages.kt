/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONObject
import org.mozilla.fenix.dejavu.settings.DejavuSettings

/**
 * AMOLED pages, dark mode only. The built-in extension in
 * assets/extensions/dejavu-amoled runs on every page but does nothing unless
 * the page reports prefers-color-scheme dark, so light mode stays exactly as
 * it was. A host turned off here gets no recolor at all, in any tab.
 */
object AmoledPages {
    private const val EXTENSION_ID = "amoled@dejavu"
    private const val EXTENSION_URL = "resource://android/assets/extensions/dejavu-amoled/"
    private const val PORT_NAME = "dejavuAmoled"

    private val logger = Logger("AmoledPages")
    private var extension: WebExtension? = null

    /** Adds the extension to [engine], and listens to the pages of every tab in [store]. */
    fun install(engine: Engine, store: BrowserStore, context: Context) {
        val settings = DejavuSettings.get(context)
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { installed ->
                extension = installed
                store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
                    listen(flow, installed, settings)
                }
            },
            onError = { logger.error("Could not install the extension for AMOLED pages", it) },
        )
    }

    private suspend fun listen(flow: Flow<BrowserState>, extension: WebExtension, settings: DejavuSettings) {
        flow.map { state -> (state.tabs + state.customTabs).mapNotNull { it.engineState.engineSession } }
            .distinctUntilChanged()
            .collect { sessions ->
                sessions.filterNot { extension.hasContentMessageHandler(it, PORT_NAME) }
                    .forEach { extension.registerContentMessageHandler(it, PORT_NAME, Messages(settings)) }
            }
    }

    /** Turns AMOLED pages off or back on for the site [tab] shows, and tells that page at once. */
    fun toggle(context: Context, tab: TabSessionState): Boolean {
        val host = hostOf(tab.content.url) ?: return false
        val settings = DejavuSettings.get(context)
        val disabled = host !in settings.amoledDisabledHosts.value
        if (!tab.content.private) {
            settings.setAmoledDisabled(host, disabled)
        }
        val port = tab.engineState.engineSession?.let { extension?.getConnectedPort(PORT_NAME, it) }
        port?.postMessage(JSONObject().put("action", "apply").put("disabled", disabled))
        return disabled
    }

    /** Whether AMOLED pages are off for the site [tab] shows. */
    fun isDisabled(context: Context, tab: TabSessionState): Boolean {
        val host = hostOf(tab.content.url) ?: return false
        return host in DejavuSettings.get(context).amoledDisabledHosts.value
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

    private class Messages(private val settings: DejavuSettings) : MessageHandler {
        override fun onPortMessage(message: Any, port: Port) {
            val json = message as? JSONObject ?: return
            if (json.optString("action") != "config") return
            val host = json.optString("host").removePrefix("www.")
            val disabled = host.isNotBlank() && host in settings.amoledDisabledHosts.value
            port.postMessage(JSONObject().put("action", "config").put("disabled", disabled))
        }

        override fun onPortDisconnected(port: Port) {
        }
    }
}
