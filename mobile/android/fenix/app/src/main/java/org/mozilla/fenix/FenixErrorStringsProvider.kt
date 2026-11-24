/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix

import android.content.Context
import mozilla.components.browser.errorpages.DefaultErrorStringsProvider
import mozilla.components.browser.errorpages.ErrorStrings
import mozilla.components.concept.engine.request.ErrorType
import org.mozilla.fenix.dejavu.ui.DejavuIcons
import org.mozilla.fenix.dejavu.ui.IconStyle

/**
 * Fenix-specific [ErrorStrings] provider.
 *
 * Configure error messages (and some error display behaviour) in Fenix-specific ways here.
 */
class FenixErrorStringsProvider : DefaultErrorStringsProvider() {
    override fun errorStringsFor(context: Context, errorType: ErrorType, uri: String?): ErrorStrings {
        val defaultStrings = super.errorStringsFor(context, errorType, uri)
        val strings = when (errorType) {
            ErrorType.ERROR_HTTPS_ONLY ->
                defaultStrings.copy(
                    title = context.getString(R.string.errorpage_httpsonly_title),
                    message =
                        context.getString(R.string.errorpage_httpsonly_message_title) +
                            "<br><br>" +
                            context.getString(R.string.errorpage_httpsonly_message_summary),
                )

            else -> defaultStrings
        }
        // Dejavu: its own pictures, made by scripts/make-media-illustrations.py, instead of Firefox's.
        return strings.copy(imageName = strings.imageName?.let { dejavuErrorPicture(errorType) })
    }

    /**
     * Dejavu's picture for the error page of [errorType], one of the `dejavu_error_*.svg` assets, in the style of icons
     * picked in Customize: the Classic style has the same Firefox icons.
     */
    private fun dejavuErrorPicture(errorType: ErrorType): String {
        val style = if (DejavuIcons.style == IconStyle.ROUNDED) "rounded_" else ""
        return "dejavu_error_$style${errorPictureName(errorType)}"
    }

    private fun errorPictureName(errorType: ErrorType): String = when (errorType) {
        ErrorType.ERROR_NO_INTERNET,
        ErrorType.ERROR_OFFLINE,
        ErrorType.ERROR_NET_INTERRUPT,
        ErrorType.ERROR_NET_RESET,
        ErrorType.ERROR_NET_TIMEOUT,
        ErrorType.ERROR_CONNECTION_REFUSED,
        ErrorType.ERROR_PROXY_CONNECTION_REFUSED,
        ErrorType.ERROR_UNKNOWN_PROXY_HOST,
        -> "offline"

        ErrorType.ERROR_SECURITY_SSL,
        ErrorType.ERROR_SECURITY_BAD_CERT,
        ErrorType.ERROR_BAD_HSTS_CERT,
        ErrorType.ERROR_HTTPS_ONLY,
        ErrorType.ERROR_PORT_BLOCKED,
        -> "lock"

        else -> "problem"
    }
}
