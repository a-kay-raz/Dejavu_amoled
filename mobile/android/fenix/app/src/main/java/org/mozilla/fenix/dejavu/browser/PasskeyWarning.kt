/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import org.mozilla.fenix.R
import org.mozilla.fenix.snackbar.FenixSnackbarDelegate
import org.mozilla.gecko.WebAuthnUnsupported

/**
 * Tells the user, in [snackbarContainer], why a page's passkey sign-in failed at once where Dejavu cannot use passkeys
 * yet: on Android 13 and older, and on a phone without Android's credential manager. Dejavu has no Google Play library
 * for passkeys and security keys, so it asks Android's credential manager, which those lack, and the page only gets an
 * error. It is told while the page is shown, as a page asks only then.
 */
fun Fragment.installPasskeyWarning(snackbarContainer: View) {
    val warning = Runnable {
        FenixSnackbarDelegate(snackbarContainer).show(
            snackBarParentView = snackbarContainer,
            text = getString(R.string.dejavu_passkeys_unsupported),
            duration = Snackbar.LENGTH_LONG,
        )
    }
    viewLifecycleOwner.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                WebAuthnUnsupported.addListener(warning)
            }

            override fun onPause(owner: LifecycleOwner) {
                WebAuthnUnsupported.removeListener(warning)
            }
        },
    )
}
