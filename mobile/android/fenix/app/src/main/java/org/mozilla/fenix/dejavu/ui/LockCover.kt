/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.workspaces.WorkspaceLock
import org.mozilla.fenix.settings.logins.ui.DefaultBiometricUtils

/**
 * What shows over a locked workspace or the locked private tabs: [icon], a lock unless given, [title] and the button
 * that unlocks them, [button] unless given, which a tap anywhere on it presses too. [onLeave], when given, adds a
 * button that goes back to the workspaces.
 */
@Composable
internal fun LockCover(
    title: String,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = iconsR.drawable.mozac_ic_lock_24,
    button: String = stringResource(R.string.pbm_authentication_unlock),
    onLeave: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onUnlock)
            .padding(24.dp),
    ) {
        Icon(
            painter = iconPainter(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        DejavuDialogButton(text = button, onClick = onUnlock, style = DejavuButtonStyle.Primary)
        if (onLeave != null) {
            Spacer(Modifier.height(8.dp))
            DejavuDialogButton(text = stringResource(R.string.dejavu_workspace_locked_leave), onClick = onLeave)
        }
    }
}

/**
 * Blurs what is drawn while [locked], too much to read, and keeps it from screen readers. Android draws blurs from 12
 * on; before, it is not drawn at all.
 */
internal fun Modifier.lockedContent(locked: Boolean): Modifier = when {
    !locked -> this
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> clearAndSetSemantics {}.blur(LOCK_BLUR.dp, BlurredEdgeTreatment.Rectangle)
    else -> clearAndSetSemantics {}.alpha(0f)
}

private const val LOCK_BLUR = 28

/**
 * Asks the user to unlock with their fingerprint, face or screen lock, as Firefox asks before it shows passwords. When
 * the phone has no screen lock, Firefox's warning about it lets the user go on without one.
 */
internal class UnlockPrompt(private val activity: FragmentActivity?) {
    /** Shows the phone's screen lock, for phones that unlock with no fingerprint or face. */
    internal var screenLock: ActivityResultLauncher<Intent>? = null
    private var onUnlocked: (() -> Unit)? = null

    /**
     * Asks, with [title] on the prompt, and runs [onUnlocked] once the user unlocked. [automatic] tells that the screen
     * asks by itself, as it shows: a fingerprint sensor that is not ready then leaves the cover's button to ask again,
     * rather than going on to the screen lock.
     */
    fun ask(title: String, automatic: Boolean = false, onUnlocked: () -> Unit) {
        val activity = activity ?: return
        // A prompt of a screen that closed since, as the activity starting again, never ends: it no longer counts.
        if (WorkspaceLock.prompting && promptedBy?.get()?.isDestroyed == false) return
        promptedBy = WeakReference(activity)
        this.onUnlocked = onUnlocked
        when {
            // The fingerprint or face first, with the screen lock a button away: offered both at once, some phones,
            // like Xiaomi's, go straight to the screen lock.
            DefaultBiometricUtils.canUseBiometricAuthentication(activity) -> {
                WorkspaceLock.prompting = true
                val callback = BiometricCallback(activity, title, automatic)
                BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setAllowedAuthenticators(BIOMETRIC_WEAK)
                        .setNegativeButtonText(activity.getString(R.string.dejavu_unlock_use_screen_lock))
                        .build(),
                )
            }
            DefaultBiometricUtils.canUsePinVerification(activity) -> askScreenLock(activity, title)
            else -> DefaultBiometricUtils.showPinWarningPrompt(activity) { finish(unlocked = true) }
        }
    }

    private fun askScreenLock(activity: FragmentActivity, title: String) {
        val launcher = screenLock
        val intent = DefaultBiometricUtils.getConfirmDeviceCredentialIntent(title, activity)
        if (launcher == null || intent == null) {
            finish(unlocked = false)
            return
        }
        WorkspaceLock.prompting = true
        launcher.launch(intent)
    }

    /** Ends the prompt; [unlocked] tells whether the user unlocked. */
    fun finish(unlocked: Boolean) {
        WorkspaceLock.prompting = false
        val then = onUnlocked
        onUnlocked = null
        if (unlocked) then?.invoke()
    }

    // A fingerprint that does not match leaves the prompt open for another try, so only its end counts.
    private inner class BiometricCallback(
        private val activity: FragmentActivity,
        private val title: String,
        private val automatic: Boolean,
    ) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(unlocked = true)

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = when (errorCode) {
            // The user asked for the screen lock, or too many tries locked the fingerprint for a while.
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            -> askScreenLock(activity, title)
            // The user turned away, or Android ended the prompt, as when Dejavu is left.
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_TIMEOUT,
            -> finish(unlocked = false)
            // The sensor cannot be used now.
            else -> if (automatic) finish(unlocked = false) else askScreenLock(activity, title)
        }
    }
}

/** An [UnlockPrompt] for this screen. */
@Composable
internal fun rememberUnlockPrompt(): UnlockPrompt {
    val context = LocalContext.current
    val prompt = remember(context) { UnlockPrompt(context.findFragmentActivity()) }
    prompt.screenLock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        prompt.finish(unlocked = result.resultCode == Activity.RESULT_OK)
    }
    return prompt
}

/**
 * Whether a prompt to unlock may show by itself now: not while the phone's own screen lock shows, which a screen saver
 * can briefly leave Dejavu running under, as Firefox found for its private tabs.
 */
internal fun Context.mayAskToUnlock(): Boolean = getSystemService<KeyguardManager>()?.isDeviceLocked != true

/**
 * Waits until the window of this view has the focus, as a prompt to unlock asked for by itself would otherwise show
 * while the phone's lock screen or the app before Dejavu still go away, when the fingerprint sensor may not be ready.
 */
internal suspend fun View.awaitWindowFocus() {
    if (hasWindowFocus()) return
    val observer = viewTreeObserver
    suspendCancellableCoroutine { continuation ->
        val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        observer.addOnWindowFocusChangeListener(listener)
        continuation.invokeOnCancellation { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
    }
}

// The screen whose prompt shows, see UnlockPrompt.ask.
private var promptedBy: WeakReference<FragmentActivity>? = null

private fun Context.findFragmentActivity(): FragmentActivity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}
