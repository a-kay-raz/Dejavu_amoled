/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview;

import android.content.pm.PackageManager;
import android.os.Build;
import java.nio.ByteBuffer;
import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.WebAuthnCredentialManager;
import org.mozilla.gecko.WebAuthnUnsupported;
import org.mozilla.gecko.annotation.WrapForJNI;
import org.mozilla.gecko.util.GeckoBundle;
import org.mozilla.gecko.util.ThreadUtils;
import org.mozilla.gecko.util.WebAuthnUtils;

/**
 * The F-Droid build's WebAuthn bridge.
 *
 * <p>Gecko calls these three methods over JNI, so they keep the signatures the C++ side expects.
 * The Google Play FIDO library is not free software and is not part of this build, so everything
 * goes through Android's own credential manager instead. That needs Android 14 or later and a
 * credential provider installed; on anything older WebAuthn reports that it is not supported,
 * exactly as it does today on a device with no Google Play services, and tells the app through
 * {@link WebAuthnUnsupported} so it can say why the page failed.
 */
public class WebAuthnTokenManager {

  private static boolean credentialManagerAvailable() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      return false;
    }
    final PackageManager packageManager =
        GeckoAppShell.getApplicationContext().getPackageManager();
    return packageManager.hasSystemFeature(PackageManager.FEATURE_CREDENTIALS);
  }

  @WrapForJNI(calledFrom = "gecko")
  private static GeckoResult<WebAuthnUtils.MakeCredentialResponse> webAuthnMakeCredential(
      final GeckoBundle credentialBundle,
      final ByteBuffer userId,
      final ByteBuffer challenge,
      final Object[] idList,
      final ByteBuffer transportList,
      final GeckoBundle authenticatorSelection,
      final GeckoBundle extensions,
      final int[] algs,
      final ByteBuffer clientDataHash,
      final String requestJSON) {
    if (!credentialManagerAvailable()) {
      WebAuthnUnsupported.notifyTurnedDown();
      return GeckoResult.fromException(new WebAuthnUtils.Exception("NOT_SUPPORTED_ERR"));
    }

    final byte[] clientDataHashBytes = new byte[clientDataHash.remaining()];
    clientDataHash.get(clientDataHashBytes);

    final GeckoResult<WebAuthnUtils.MakeCredentialResponse> result = new GeckoResult<>();
    ThreadUtils.runOnUiThread(
        () ->
            WebAuthnCredentialManager.makeCredential(
                    credentialBundle.getString("origin"), clientDataHashBytes, requestJSON)
                .accept(result::complete, result::completeExceptionally));
    return result;
  }

  @WrapForJNI(calledFrom = "gecko")
  private static GeckoResult<WebAuthnUtils.GetAssertionResponse> webAuthnGetAssertion(
      final ByteBuffer challenge,
      final Object[] idList,
      final ByteBuffer transportList,
      final GeckoBundle assertionBundle,
      final GeckoBundle extensions,
      final ByteBuffer clientDataHash,
      final String requestJSON) {
    if (!credentialManagerAvailable()) {
      WebAuthnUnsupported.notifyTurnedDown();
      return GeckoResult.fromException(new WebAuthnUtils.Exception("NOT_SUPPORTED_ERR"));
    }

    final byte[] clientDataHashBytes = new byte[clientDataHash.remaining()];
    clientDataHash.get(clientDataHashBytes);

    final GeckoResult<WebAuthnUtils.GetAssertionResponse> result = new GeckoResult<>();
    ThreadUtils.runOnUiThread(
        () ->
            WebAuthnCredentialManager.prepareGetAssertion(
                    assertionBundle.getString("origin"), clientDataHashBytes, requestJSON)
                .accept(
                    pendingHandle -> {
                      if (pendingHandle == null) {
                        // No credential manager credentials, and no Google Play fallback here.
                        result.completeExceptionally(
                            new WebAuthnUtils.Exception("NOT_ALLOWED_ERR"));
                        return;
                      }
                      ThreadUtils.runOnUiThread(
                          () ->
                              WebAuthnCredentialManager.getAssertion(pendingHandle)
                                  .accept(result::complete, result::completeExceptionally));
                    },
                    result::completeExceptionally));
    return result;
  }

  @WrapForJNI(calledFrom = "gecko")
  private static GeckoResult<Boolean> webAuthnIsUserVerifyingPlatformAuthenticatorAvailable() {
    return GeckoResult.fromValue(credentialManagerAvailable());
  }
}
