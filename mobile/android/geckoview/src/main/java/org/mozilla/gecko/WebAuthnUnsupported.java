/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.mozilla.gecko.util.ThreadUtils;

/**
 * Dejavu: tells the app each time WebAuthn turns down a page's request for a passkey or a security key because the
 * device cannot give one, as the F-Droid build's bridge does below Android 14 (see WebAuthnTokenManager in src/foss).
 * The page only gets an error, so the app can tell the user why.
 */
public final class WebAuthnUnsupported {
  private static final Set<Runnable> sListeners = new CopyOnWriteArraySet<>();

  private WebAuthnUnsupported() {}

  /** Runs {@code listener} on the UI thread each time a request is turned down, until it is removed. */
  @AnyThread
  public static void addListener(@NonNull final Runnable listener) {
    sListeners.add(listener);
  }

  @AnyThread
  public static void removeListener(@NonNull final Runnable listener) {
    sListeners.remove(listener);
  }

  /** Called by the WebAuthn bridge when it turned a request down, as the device cannot give a credential. */
  @AnyThread
  public static void notifyTurnedDown() {
    for (final Runnable listener : sListeners) {
      ThreadUtils.runOnUiThread(listener);
    }
  }
}
