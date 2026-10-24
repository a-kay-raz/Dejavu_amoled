/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.ext.components

/**
 * Manages the built-in Dejavu AMOLED dark mode WebExtension for webpages.
 * When Dark mode is enabled, webpages are rendered with a pure #000000 AMOLED dark theme.
 * When Light mode is active, the extension is disabled and webpages render untouched in pure light mode.
 */
object AmoledWebpageTheme {
    private val logger = Logger("AmoledWebpageTheme")
    private const val EXTENSION_ID = "amoled-dark@dejavu.org"
    private const val EXTENSION_URL = "resource://android/assets/extensions/amoled_dark/"

    @Volatile
    private var installedExtension: WebExtension? = null

    /**
     * Installs the AMOLED dark WebExtension into [engine] and sets its initial active state.
     */
    fun install(engine: Engine, context: Context) {
        try {
            engine.installBuiltInWebExtension(
                id = EXTENSION_ID,
                url = EXTENSION_URL,
                onSuccess = { ext ->
                    installedExtension = ext
                    val isDark = context.components.core.getPreferredColorScheme() == PreferredColorScheme.Dark
                    applyState(engine, isDark)
                    logger.debug("AMOLED webpage extension installed: ${ext.id}")
                },
                onError = { throwable ->
                    logger.error("Failed to install AMOLED webpage extension", throwable)
                },
            )
        } catch (e: Exception) {
            logger.error("Error installing AMOLED webpage extension", e)
        }
    }

    /**
     * Synchronizes the AMOLED webpage extension state when theme or configuration changes.
     */
    fun sync(engine: Engine, context: Context) {
        val isDark = context.components.core.getPreferredColorScheme() == PreferredColorScheme.Dark
        applyState(engine, isDark)
    }

    private fun applyState(engine: Engine, isDark: Boolean) {
        val ext = installedExtension ?: return
        try {
            if (isDark) {
                engine.enableWebExtension(ext)
            } else {
                engine.disableWebExtension(ext, onSuccess = {})
            }
        } catch (e: Exception) {
            logger.error("Failed to toggle AMOLED webpage extension state", e)
        }
    }
}
