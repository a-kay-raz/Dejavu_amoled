/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import mozilla.components.browser.state.state.content.HistoryState
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.concept.engine.history.HistoryItem
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.fenix.components.toolbar.StartPageActions

class TabPagesTest {
    private fun history(vararg urls: String, current: Int = urls.lastIndex) = HistoryState(
        items = urls.map { HistoryItem(title = it.removePrefix("https://").removeSuffix(".example/"), uri = it) },
        currentIndex = current,
    )

    @Test
    fun `the counter shows 1 on the first page, one more for each page opened, one less for each page gone back to`() {
        assertEquals(1, pageCountOf(history(A), A))
        assertEquals(2, pageCountOf(history(A, B), B))
        assertEquals(1, pageCountOf(history(A, B, current = 0), A))
    }

    @Test
    fun `the homepage a tab opened on does not count, as Back closes the tab there`() {
        assertEquals(1, pageCountOf(history(ABOUT_HOME_URL, A), A))
        assertEquals(0, pageCountOf(history(ABOUT_HOME_URL), ABOUT_HOME_URL))
    }

    @Test
    fun `until the browser tells the history of a tab, its page is the only one`() {
        assertEquals(1, pageCountOf(HistoryState(), A))
        assertEquals(0, pageCountOf(HistoryState(), ABOUT_HOME_URL))
        assertEquals(0, pageCountOf(HistoryState(), ""))
    }

    @Test
    fun `the pages of a tab are listed newest first, the shown one marked, without the homepage`() {
        assertEquals(
            listOf(
                TabPage(3, "c", C, isShown = false),
                TabPage(2, "b", B, isShown = true),
                TabPage(1, "a", A, isShown = false),
            ),
            pagesOf(history(ABOUT_HOME_URL, A, B, C, current = 2), title = "b", url = B),
        )
    }

    @Test
    fun `a page without a title is listed by its address`() {
        val untitled = HistoryState(items = listOf(HistoryItem(title = "", uri = A)), currentIndex = 0)

        assertEquals(listOf(TabPage(0, A, A, isShown = true)), pagesOf(untitled, title = "", url = A))
    }

    @Test
    fun `until the browser tells the history of a tab, only its page is listed`() {
        assertEquals(listOf(TabPage(0, "A page", A, isShown = true)), pagesOf(HistoryState(), title = "A page", url = A))
        assertEquals(emptyList<TabPage>(), pagesOf(HistoryState(), title = "", url = ABOUT_HOME_URL))
    }

    @Test
    fun `holding the counter opens what tapping the site's security icon opened, nothing where the icon did nothing`() {
        val siteInfo = Action.ActionButtonRes(0, 0, onClick = StartPageActions.SiteInfoClicked)
        val nothing = Action.ActionButtonRes(0, 0, onClick = object : BrowserToolbarEvent {})

        assertEquals(StartPageActions.SiteInfoClicked, listOf(siteInfo).siteInfoClick())
        assertNull(listOf(nothing).siteInfoClick())
        assertNull(emptyList<Action>().siteInfoClick())
    }

    private companion object {
        const val A = "https://a.example/"
        const val B = "https://b.example/"
        const val C = "https://c.example/"
    }
}
