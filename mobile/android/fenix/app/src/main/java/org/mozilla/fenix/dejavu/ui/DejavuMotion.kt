/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.fenix.dejavu.settings.DejavuSettings

/**
 * How fast Dejavu's screens, menus and buttons move: as the animator duration scale of the system's settings has them,
 * or not at all once the user turned animations off in Customize (issue #294). Every Compose animation of the app's
 * windows follows it, see [install], so it ends at once when it does not move. Dejavu's motions that Compose does not
 * run ask [animates], and the genie of the home screen's buttons asks [genie].
 */
internal object DejavuMotion : MotionDurationScale {
    private var systemScale by mutableFloatStateOf(1f)
    private var animationsOn by mutableStateOf(true)
    private var genieOn by mutableStateOf(true)

    override val scaleFactor: Float
        get() = motionScale(systemScale, animationsOn)

    /** Whether anything moves at all. */
    val animates: Boolean
        get() = scaleFactor > 0f

    /** Whether pages pour out of the home screen's buttons, see [org.mozilla.fenix.dejavu.home.PageGenie]. */
    val genie: Boolean
        get() = animates && genieOn

    /**
     * Makes the Compose animations of every window the app opens from now on go at [scaleFactor], which follows the
     * system's setting and [settings] from then on, in [scope].
     */
    @OptIn(InternalComposeUiApi::class)
    fun install(context: Context, settings: DejavuSettings, scope: CoroutineScope) {
        val resolver = context.contentResolver
        val readSystemScale = { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
        systemScale = readSystemScale()
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    systemScale = readSystemScale()
                }
            },
        )
        animationsOn = settings.animations.value
        genieOn = settings.genieEffect.value
        scope.launch { settings.animations.collect { animationsOn = it } }
        scope.launch { settings.genieEffect.collect { genieOn = it } }
        // Compose otherwise follows the system's setting alone, and only when a window has no scale of its own.
        WindowRecomposerPolicy.setFactory(
            WindowRecomposerFactory { root -> root.createLifecycleAwareWindowRecomposer(DejavuMotion) },
        )
    }
}

/** How fast animations go: as the system's [systemScale] has them, or not at all when [animationsOn] is not set. */
internal fun motionScale(systemScale: Float, animationsOn: Boolean): Float = if (animationsOn) systemScale else 0f
