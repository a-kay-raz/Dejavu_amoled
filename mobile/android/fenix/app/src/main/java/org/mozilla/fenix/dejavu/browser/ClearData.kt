/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.appstate.AppAction
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.settings.slideTo
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.settings.deletebrowsingdata.DefaultDeleteBrowsingDataController
import org.mozilla.fenix.settings.deletebrowsingdata.DefaultDeleteBrowsingDataController.DataStorage
import org.mozilla.fenix.settings.deletebrowsingdata.DefaultDeleteBrowsingDataController.DeleteDataUseCases
import org.mozilla.fenix.settings.deletebrowsingdata.DefaultDeleteBrowsingDataController.Stores
import org.mozilla.fenix.settings.deletebrowsingdata.DeleteBrowsingDataOnQuitType

/**
 * Clear data: deletes the kinds of browsing data chosen under "Clear data button" in the "Delete browsing data on quit"
 * settings, from the Clear data and Clear data and quit entries of the menu and the bars, and by holding the home
 * screen's Clear button. Nothing is chosen at first; until something is, it opens those settings instead.
 */
internal object ClearData {
    /** The argument that has the "Delete browsing data on quit" settings show the choices of Clear data. */
    const val SHOW_CHOICES = "dejavu_show_clear_data_choices"

    /** The kinds of data that pages leave behind as they are visited. */
    val SiteData = setOf(
        DeleteBrowsingDataOnQuitType.COOKIES,
        DeleteBrowsingDataOnQuitType.CACHE,
        DeleteBrowsingDataOnQuitType.PERMISSIONS,
    )

    private val keys = mapOf(
        DeleteBrowsingDataOnQuitType.TABS to R.string.pref_key_dejavu_clear_data_tabs,
        DeleteBrowsingDataOnQuitType.HISTORY to R.string.pref_key_dejavu_clear_data_history,
        DeleteBrowsingDataOnQuitType.COOKIES to R.string.pref_key_dejavu_clear_data_cookies,
        DeleteBrowsingDataOnQuitType.CACHE to R.string.pref_key_dejavu_clear_data_cache,
        DeleteBrowsingDataOnQuitType.PERMISSIONS to R.string.pref_key_dejavu_clear_data_permissions,
        DeleteBrowsingDataOnQuitType.DOWNLOADS to R.string.pref_key_dejavu_clear_data_downloads,
    )

    /** The keys of the checkboxes that choose what Clear data deletes. */
    fun keys(context: Context): List<String> = keys.values.map(context::getString)

    /** The kind of data the checkbox with [key] chooses, see [keys]. */
    fun typeOf(context: Context, key: String): DeleteBrowsingDataOnQuitType? =
        keys.entries.firstOrNull { context.getString(it.value) == key }?.key

    /** The kinds of data Clear data deletes. */
    fun chosen(context: Context): Set<DeleteBrowsingDataOnQuitType> {
        val prefs = context.components.settings.preferences
        return keys.filterValues { prefs.getBoolean(context.getString(it), false) }.keys
    }

    /**
     * Whether Clear data has anything to delete among [types]: any of [tabs] open tabs, history with a page last
     * visited at [lastVisit] or [closedTabs] recently closed tabs, any of [downloads], or for the data pages leave
     * behind, a page visited since Clear data deleted it last, at [clearedAt].
     */
    @Suppress("LongParameterList")
    fun hasData(
        types: Set<DeleteBrowsingDataOnQuitType>,
        tabs: Int,
        closedTabs: Int,
        downloads: Int,
        lastVisit: Long?,
        clearedAt: Long,
    ): Boolean = types.any { type ->
        when (type) {
            DeleteBrowsingDataOnQuitType.TABS -> tabs > 0
            DeleteBrowsingDataOnQuitType.HISTORY -> lastVisit != null || closedTabs > 0
            DeleteBrowsingDataOnQuitType.DOWNLOADS -> downloads > 0
            DeleteBrowsingDataOnQuitType.COOKIES,
            DeleteBrowsingDataOnQuitType.CACHE,
            DeleteBrowsingDataOnQuitType.PERMISSIONS,
            -> lastVisit != null && lastVisit > clearedAt
        }
    }

    /** When the newest page in history was visited, or `null` when history is empty or cannot be read. */
    suspend fun lastVisit(context: Context): Long? = runCatching {
        context.components.core.historyStorage.getVisitsPaginated(offset = 0, count = 1).firstOrNull()?.visitTime
    }.getOrNull()

    /**
     * Deletes the data chosen, and runs [onCleared] once it is gone, or quits Dejavu then when [quit]. With nothing
     * chosen, asks to choose it first and opens its settings with [showChoices] instead, see [showChoices].
     *
     * @return The kinds of data being deleted, none when the settings opened.
     */
    fun run(
        context: Context,
        showChoices: () -> Unit,
        quit: Boolean = false,
        onCleared: () -> Unit = {},
    ): Set<DeleteBrowsingDataOnQuitType> {
        val types = chosen(context)
        if (types.isEmpty()) {
            Toast.makeText(context, R.string.dejavu_clear_data_choose, Toast.LENGTH_SHORT).show()
            showChoices()
            return emptySet()
        }
        val appContext = context.applicationContext
        val components = context.components
        val activity = context.activity
        if (quit) components.appStore.dispatch(AppAction.DeleteAndQuitStarted)
        components.applicationScope.launch(Dispatchers.Main) {
            delete(appContext, types)
            if (types.any { it in SiteData }) DejavuSettings.get(appContext).clearedDataAt = System.currentTimeMillis()
            if (quit) {
                activity?.finishAndRemoveTask()
            } else {
                val deleted = R.string.preferences_delete_browsing_data_snackbar
                Toast.makeText(appContext, deleted, Toast.LENGTH_SHORT).show()
                onCleared()
            }
        }
        return types
    }

    /** Clear data asked for on a page, which the home screen carries out once the page closed to it. */
    class Request(val quit: Boolean, val at: Long = SystemClock.elapsedRealtime())

    private val _asked = MutableStateFlow<Request?>(null)

    /** Clear data asked for on a page and waiting for the home screen, see [runFromPage]. */
    val asked: StateFlow<Request?> = _asked.asStateFlow()

    /**
     * Clear data on the page of [tab]: the page closes to the home screen first, so it is not left showing while its
     * data goes, and the home screen then deletes the data as holding Clear does, see [claim]. With nothing chosen, the
     * page stays, and the choices open over it with [showChoices].
     */
    fun runFromPage(
        context: Context,
        tab: TabSessionState,
        navController: NavController,
        quit: Boolean,
        showChoices: () -> Unit,
    ) {
        if (chosen(context).isEmpty()) {
            Toast.makeText(context, R.string.dejavu_clear_data_choose, Toast.LENGTH_SHORT).show()
            showChoices()
            return
        }
        TabZoom.zoomOut(
            tabId = tab.id,
            leave = { navController.navigate(NavGraphDirections.actionGlobalHome()) },
            afterLeaving = { _asked.value = Request(quit) },
        )
    }

    /** Takes [request] for the home screen to carry out: `false` when it was taken already, or asked too long ago. */
    fun claim(request: Request): Boolean =
        _asked.compareAndSet(request, null) && SystemClock.elapsedRealtime() - request.at < REQUEST_TIMEOUT_MS

    /** Opens the "Delete browsing data on quit" settings at the choices of Clear data. */
    fun showChoices(navController: NavController) {
        navController.slideTo(R.id.deleteBrowsingDataOnQuitFragment, Bundle().apply { putBoolean(SHOW_CHOICES, true) })
    }

    /** Deletes [types] of data the way "Delete browsing data" does, each at the same time as the others. */
    private suspend fun delete(context: Context, types: Set<DeleteBrowsingDataOnQuitType>) {
        val components = context.components
        val controller = DefaultDeleteBrowsingDataController(
            deleteDataUseCases = DeleteDataUseCases(
                removeAllTabs = components.useCases.tabsUseCases.removeAllTabs,
                removeAllDownloads = components.useCases.downloadUseCases.removeAllDownloads,
            ),
            dataStorage = DataStorage(
                history = components.core.historyStorage,
                permissions = components.core.permissionStorage,
            ),
            stores = Stores(appStore = components.appStore, browserStore = components.core.store),
            engine = components.core.engine,
            settings = components.settings,
        )
        supervisorScope { types.forEach { type -> launch { controller.deleteType(type) } } }
    }

    // A page closes to the home screen within a second or two; a request older than this lost its home screen.
    private const val REQUEST_TIMEOUT_MS = 10_000L

    private val Context.activity: Activity?
        get() = generateSequence(this) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
}
