/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.toolbar.ScrollableToolbar
import mozilla.components.feature.contextmenu.R as contextMenuR
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONObject
import org.mozilla.fenix.dejavu.menu.BORDER_ALPHA
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.snackbar.FenixSnackbarDelegate
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Mouse support the engine lacks: a middle-click on a link opens it in a background tab, and hovering a link shows its
 * address in a pill at the bottom-left of the page. A built-in extension reports both, and only for what a mouse did:
 * touch input and events a page makes up itself are ignored. A middle-click on a tab in the tab bar or on the home
 * screen closes it, see [closeOnMiddleClick].
 */
object DejavuMouse {
    private const val ID = "dejavu-mouse@dejavu.app"
    private const val URL = "resource://android/assets/extensions/dejavu_mouse/"
    private const val NAME = "dejavuMouse"

    private val logger = Logger("DejavuMouse")

    // Only used on the main thread, where the settings change and the extension's messages arrive.
    private val callbacks = OwnedValue<Callbacks>()
    private val registered = Registrations<EngineSession>()
    private var enabled = false
    private var listening: CoroutineScope? = null

    /** Whether mouse support is on. */
    val isEnabled: Boolean
        get() = enabled

    /**
     * Turns the extension of [engine] on while mouse support is [on], listening to the pages of every tab in [store],
     * and off while it is off. The pages already open follow at once: the engine stops the extension in them.
     *
     * The extension is disabled and enabled, never uninstalled: added again, it gets a new address, while the engine's
     * startup cache, which an uninstall only clears in its own session, still has the old one, and then none of its
     * scripts load.
     */
    fun apply(engine: Engine, store: BrowserStore, on: Boolean) {
        enabled = on
        if (!on) {
            listening?.cancel()
            listening = null
        }
        engine.installBuiltInWebExtension(
            ID,
            URL,
            onSuccess = { extension ->
                if (enabled) {
                    engine.enableWebExtension(
                        extension,
                        EnableSource.APP_SUPPORT,
                        onSuccess = { if (enabled && listening == null) listening = listen(store, it) },
                        onError = { logger.error("Could not enable the mouse extension", it) },
                    )
                } else {
                    engine.disableWebExtension(
                        extension,
                        EnableSource.APP_SUPPORT,
                        onSuccess = {},
                        onError = { logger.error("Could not disable the mouse extension", it) },
                    )
                }
            },
            onError = { logger.error("Could not install the mouse extension", it) },
        )
    }

    /**
     * Each page reports to the handler of its own session, so a click is known to come from its tab. Only a change in
     * the engine sessions of the tabs passes, compared in place, so the many other updates of a loading page cost one
     * walk over the tabs and nothing else.
     */
    private fun listen(store: BrowserStore, extension: WebExtension) =
        store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            flow.map { state -> state.tabs }
                .distinctUntilChanged { old, new -> haveSameSessions(old, new) }
                .collect { tabs ->
                    tabs.mapNotNull { it.engineState.engineSession }
                        .filter { registered.add(it) }
                        .forEach { extension.registerContentMessageHandler(it, NAME, handler) }
                }
        }

    /** Shows the address pill and opens middle-clicked links for the pages of [fragment] while it is started. */
    fun install(fragment: Fragment, browserLayout: CoordinatorLayout, snackbarContainer: View) {
        val components = fragment.requireComponents
        if (!components.settings.isMouseSupportEnabled) return
        val hovered = mutableStateOf<Hovered?>(null)
        val pill = ComposeView(fragment.requireContext()).apply {
            setContent { FirefoxTheme { AddressPill(hovered) } }
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.START }
        }
        browserLayout.addView(pill)

        fragment.viewLifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    val onHover = { url: String, source: EngineSession? ->
                        val tab = components.core.store.state.tabs.tabOf(source)
                        if (url.isNotEmpty() && tab != null) {
                            pill.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                                bottomMargin = browserLayout.bottomBarsHeight()
                            }
                            hovered.value = Hovered(url, tab.id)
                        } else {
                            hovered.value = null
                        }
                    }
                    val onOpen = { url: String, source: EngineSession? ->
                        openInBackground(fragment, snackbarContainer, url, source)
                    }
                    callbacks.set(this, Callbacks(onHover, onOpen))
                }

                override fun onStop(owner: LifecycleOwner) {
                    callbacks.clear(this)
                    hovered.value = null
                }

                override fun onDestroy(owner: LifecycleOwner) = browserLayout.removeView(pill)
            },
        )
    }

    /** What the started fragment does with the messages of the pages. */
    private class Callbacks(val onHover: (String, EngineSession?) -> Unit, val onOpen: (String, EngineSession?) -> Unit)

    /** The address of a hovered link, and the tab whose page shows it, which the pill takes its colors from. */
    private data class Hovered(val url: String, val tabId: String)

    /** The address of the hovered link, in the colors of its tab's workspace, like the bars around the page. */
    @Composable
    private fun AddressPill(hovered: MutableState<Hovered?>) {
        val shown = hovered.value ?: return
        val shape = RoundedCornerShape(8.dp)
        Text(
            text = shown.url,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .workspaceTheme(shownPopupTheme(shown.tabId))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = BORDER_ALPHA), shape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }

    /** Whether [old] and [new] have the same engine sessions in the same order. */
    private fun haveSameSessions(old: List<TabSessionState>, new: List<TabSessionState>): Boolean =
        old === new || (
            old.size == new.size &&
                old.indices.all { old[it].engineState.engineSession === new[it].engineState.engineSession }
            )

    private fun List<TabSessionState>.tabOf(source: EngineSession?): TabSessionState? =
        source?.let { firstOrNull { tab -> tab.engineState.engineSession === it } }

    /**
     * How far the bars at the bottom reach into [this] as they are shown right now, the bar below pages scrolled away
     * or not, and at least the navigation bar, which pages run down behind also with no bar below them.
     */
    private fun CoordinatorLayout.bottomBarsHeight(): Int {
        val toolbars = children
            .filter { it is ScrollableToolbar && it.isVisible && it.top >= height / 2 }
            .maxOfOrNull { (height - it.top - it.translationY.toInt()).coerceAtLeast(0) } ?: 0
        val navigationBar = ViewCompat.getRootWindowInsets(this)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        return maxOf(toolbars, navigationBar)
    }

    private val handler = object : MessageHandler {
        override fun onMessage(message: Any, source: EngineSession?): Any? {
            val json = message as? JSONObject ?: return null
            val url = json.optString("url")
            when (json.optString("type")) {
                "hover" -> callbacks.value?.onHover?.invoke(url.takeIf(::isOpenableLink).orEmpty(), source)
                "open" -> if (isOpenableLink(url)) callbacks.value?.onOpen?.invoke(url, source)
            }
            return null
        }
    }

    /** Opens [url] next to the tab whose page, shown by [source], was clicked: the split pane may show another tab. */
    private fun openInBackground(fragment: Fragment, snackbarContainer: View, url: String, source: EngineSession?) {
        val components = fragment.requireComponents
        val tab = components.core.store.state.tabs.tabOf(source) ?: return
        val tabId = components.useCases.tabsUseCases.addTab(
            url = url,
            selectTab = false,
            startLoading = true,
            parentId = tab.id,
            contextId = tab.contextId,
            private = tab.content.private,
            textDirectiveUserActivation = true,
        )
        FenixSnackbarDelegate(snackbarContainer).show(
            snackBarParentView = snackbarContainer,
            text = fragment.getString(contextMenuR.string.mozac_feature_contextmenu_snackbar_new_tab_opened),
            duration = Snackbar.LENGTH_LONG,
            action = fragment.getString(contextMenuR.string.mozac_feature_contextmenu_snackbar_action_switch),
            listener = { components.useCases.tabsUseCases.selectTab(tabId) },
        )
    }
}

/** Schemes a page may open in a new tab with a middle-click: web pages only, never local or privileged ones. */
private val OPENABLE_SCHEMES = setOf("http", "https")

/** Whether [url], reported by a page, is a web address that a middle-click may open. */
internal fun isOpenableLink(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "")
    return scheme.lowercase() in OPENABLE_SCHEMES && url.startsWith("$scheme://") && url.length > scheme.length + 3
}

/**
 * A value that belongs to the last owner to set it: another owner, started before this one stopped, keeps its value
 * when the first one clears.
 */
internal class OwnedValue<T : Any> {
    var value: T? = null
        private set
    private var owner: Any? = null

    fun set(owner: Any, value: T) {
        this.owner = owner
        this.value = value
    }

    /** Drops the value unless it was replaced by another owner than [owner] meanwhile. */
    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            value = null
        }
    }
}

/** The sessions seen so far, held weakly, so each is registered once however often the extension is enabled. */
internal class Registrations<T : Any> {
    private val seen = Collections.newSetFromMap(WeakHashMap<T, Boolean>())

    /** Whether [session] is new, and remembers it. */
    fun add(session: T): Boolean = seen.add(session)
}
