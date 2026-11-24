/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.history

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository

/**
 * Where a page was last opened: its workspace and, for a pinned tab, the folder its pin is in.
 */
data class VisitOrigin(val workspaceId: String, val folderId: String?)

/**
 * Remembers, for the pages visited lately, the workspace and folder they were opened in, so History can group them.
 * History itself only knows addresses. Kept in a small file, the [MAX_PAGES] latest pages only, and only while history
 * has them, see [forgetUnvisited]. Private tabs are never remembered.
 */
class VisitOrigins private constructor(private val file: File) {
    // In access order, so the least recently visited page is the first to go.
    private val origins = object : LinkedHashMap<String, VisitOrigin>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VisitOrigin>) = size > MAX_PAGES
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saving: Job? = null

    @Volatile
    private var loaded = false

    /** Where [url] was last opened, or `null` if it is not remembered. */
    fun originOf(url: String): VisitOrigin? = synchronized(origins) { origins[url] }

    /** Remembers that [url] was opened in [origin]. */
    fun record(url: String, origin: VisitOrigin) {
        val changed = synchronized(origins) { origins.put(url, origin) != origin }
        if (changed) scheduleSave()
    }

    /**
     * Forgets the pages history no longer has, like after the user deleted them, in the background. [isVisited] tells
     * for each page of a list whether history has it.
     */
    fun forgetUnvisited(isVisited: suspend (List<String>) -> List<Boolean>) {
        scope.launch {
            load()
            val urls = synchronized(origins) { origins.keys.toList() }
            if (urls.isEmpty()) return@launch
            val visited = runCatching { isVisited(urls) }.getOrNull()?.takeIf { it.size == urls.size } ?: return@launch
            val gone = urls.filterIndexed { index, _ -> !visited[index] }
            if (gone.isEmpty()) return@launch
            synchronized(origins) { gone.forEach { origins.remove(it) } }
            scheduleSave()
        }
    }

    /** Reads the file, keeping what was recorded before it was read as the newer. */
    fun load() {
        if (loaded) return
        val saved = runCatching { JSONObject(file.readText()) }.getOrNull()
        synchronized(origins) {
            if (saved != null) {
                val newer = LinkedHashMap(origins)
                origins.clear()
                saved.optJSONArray(KEY_PAGES)?.let { pages ->
                    for (index in 0 until pages.length()) {
                        val page = pages.optJSONArray(index) ?: continue
                        val url = page.optString(0).ifEmpty { null } ?: continue
                        val workspaceId = page.optString(1).ifEmpty { null } ?: continue
                        origins[url] = VisitOrigin(workspaceId, page.optString(2).ifEmpty { null })
                    }
                }
                origins.putAll(newer)
            }
            loaded = true
        }
    }

    private fun scheduleSave() {
        saving?.cancel()
        saving = scope.launch {
            delay(SAVE_DELAY_MS)
            load()
            val pages = JSONArray()
            synchronized(origins) {
                origins.forEach { (url, origin) ->
                    pages.put(JSONArray().put(url).put(origin.workspaceId).put(origin.folderId.orEmpty()))
                }
            }
            runCatching {
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(JSONObject().put(KEY_PAGES, pages).toString())
                temporary.renameTo(file)
            }
        }
    }

    companion object {
        private const val FILE_NAME = "dejavu_visit_origins.json"
        private const val KEY_PAGES = "pages"
        private const val MAX_PAGES = 2000
        private const val INITIAL_CAPACITY = 256
        private const val LOAD_FACTOR = 0.75f
        private const val SAVE_DELAY_MS = 3000L

        @Volatile
        private var instance: VisitOrigins? = null

        /** Returns the process wide [VisitOrigins]. Does not read the file, see [load]. */
        fun get(context: Context): VisitOrigins =
            instance ?: synchronized(this) {
                instance ?: VisitOrigins(File(context.applicationContext.filesDir, FILE_NAME)).also { instance = it }
            }
    }
}

/**
 * Records in [VisitOrigins] the workspace and folder of each page a normal tab goes to, unless the workspace saves no
 * history. Once history is deleted, it forgets the pages history no longer has, as [isVisited] tells, and the pages
 * closed pinned tabs would reopen on.
 */
internal class VisitOriginsMiddleware(
    private val origins: VisitOrigins,
    private val isVisited: suspend (List<String>) -> List<Boolean>,
) : Middleware<BrowserState, BrowserAction> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        next(action)
        if (action is EngineAction.PurgeHistoryAction) {
            origins.forgetUnvisited(isVisited)
            val repository = WorkspaceRepository.peek() ?: return
            scope.launch { repository.forgetUnvisitedOpenPages(isVisited) }
            return
        }
        if (action !is ContentAction.UpdateUrlAction) return
        val tab = store.state.findTab(action.sessionId)?.takeUnless { it.content.private } ?: return
        if (!action.url.startsWith("http")) return
        val workspaces = WorkspaceRepository.peek()?.state?.value ?: return
        val pin = workspaces.pinOf(tab.id)
        val workspaceId = pin?.workspaceId ?: workspaces.workspaceOf(tab.id)
        if (WorkspaceHistory.isOff(workspaces, tab.id)) return
        origins.record(action.url, VisitOrigin(workspaceId, pin?.parentId))
    }
}
