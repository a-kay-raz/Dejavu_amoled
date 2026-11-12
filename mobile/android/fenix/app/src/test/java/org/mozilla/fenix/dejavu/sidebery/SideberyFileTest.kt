/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sidebery

import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideberyFileTest {
    @Test
    fun `a snapshot file gives its tab panels in the order of the navigation bar, its containers and its tabs`() {
        val snapshot = SideberyFile.read(SNAPSHOT)!!

        assertEquals("lzx1abc2def3", snapshot.id)
        assertEquals(1762500000000L, snapshot.time)
        assertEquals(
            listOf(
                SideberyPanel("researchpnl1", "Research", "blue"),
                SideberyPanel("workpanel001", "Work", "orange", containerId = "firefox-container-1"),
            ),
            snapshot.panels,
        )
        assertEquals(
            listOf(SideberyContainer("firefox-container-1", "Work", "orange", "briefcase")),
            snapshot.containers,
        )
        assertEquals(
            listOf(
                SideberyTab("https://mail.example.com/", "Mail", panelId = null, pinned = true),
                SideberyTab(READING_LIST, "Reading list", "researchpnl1", folded = true),
                SideberyTab("https://a.example.com/", "A", "researchpnl1", level = 1),
                SideberyTab("https://b.example.com/", "B", "researchpnl1", level = 2),
                SideberyTab("file:///home/me/notes.txt", "notes.txt", "researchpnl1"),
                SideberyTab("https://c.example.com/", "C", "researchpnl1", customTitle = "See"),
                SideberyTab("https://jira.example.com/", "Jira", "workpanel001", containerId = "firefox-container-1"),
            ),
            snapshot.windows.single().tabs,
        )
    }

    @Test
    fun `a backup gives its newest snapshot, with what did not change since an older one, and its other panels`() {
        val snapshot = SideberyFile.read(BACKUP)!!

        assertEquals("s2", snapshot.id)
        assertEquals(
            listOf(
                SideberyTab("https://one.example.com/", "One", "researchpnl1"),
                SideberyTab("https://three.example.com/", "Three", "researchpnl1", level = 1),
                SideberyTab("https://four.example.com/", "Four", "researchpnl1"),
            ),
            snapshot.windows.single().tabs,
        )
        assertEquals(listOf("researchpnl1", "laterpanel01"), snapshot.panels.map { it.id })
        assertEquals(listOf("Work", "Bank"), snapshot.containers.map { it.name })
    }

    @Test
    fun `a backup without snapshots gives its panels`() {
        val backup = """{"ver": "5.6.1", "sidebar": {"panels": {$LATER_PANEL}, "nav": ["laterpanel01"]}}"""

        val snapshot = SideberyFile.read(backup)!!

        assertEquals(listOf(SideberyPanel("laterpanel01", "Later")), snapshot.panels)
        assertTrue(snapshot.windows.isEmpty())
    }

    @Test
    fun `other files are not read`() {
        listOf("", "nope", "[]", "{}", """{"foo": 1}""", """{"ver": "5.6.1", "settings": {}, "snapshots": []}""")
            .forEach { assertNull(it, SideberyFile.read(it)) }
    }

    @Test
    fun `a written snapshot reads back the same, in the shape Sidebery imports`() {
        val snapshot = SideberySnapshot(
            id = "abc",
            time = 5L,
            panels = listOf(
                SideberyPanel("p1", "One", "blue", containerId = "c1", iconImage = "data:image/png;base64,AA"),
                SideberyPanel("p2", "Two"),
            ),
            containers = listOf(SideberyContainer("c1", "Work", "orange", "briefcase")),
            windows = listOf(
                SideberyWindow(
                    listOf(
                        SideberyTab("https://e.example/", "E", panelId = null, pinned = true),
                        SideberyTab(SideberyFile.groupUrl("G"), "G", "p1", folded = true),
                        SideberyTab("https://a.example/", "A", "p1", 1, containerId = "c1", customTitle = "Mine"),
                        SideberyTab("https://b.example/", "B", "p2"),
                    ),
                ),
            ),
        )

        val text = SideberyFile.write(snapshot)

        assertEquals(snapshot.copy(panels = snapshot.panels.map { it.copy(iconImage = null) }), SideberyFile.read(text))
        val json = Json.parseToJsonElement(text).jsonObject
        // What Sidebery's "Import snapshot" checks for.
        assertTrue(listOf("id", "time", "containers", "sidebar", "tabs").all { it in json })
        val groups = json.getValue("tabs").jsonArray.single().jsonArray
        assertEquals(listOf(1, 2, 1), groups.map { it.jsonArray.size })
        // Sidebery tells the pinned tabs every panel shows by this number, not by the text "-1".
        val pinnedPanel = groups[0].jsonArray[0].jsonObject.getValue("panelId").jsonPrimitive
        assertFalse(pinnedPanel.isString)
        assertEquals(-1, pinnedPanel.int)
        val sidebar = json.getValue("sidebar").jsonObject
        assertEquals(listOf("p1", "p2"), sidebar.getValue("nav").jsonArray.map { it.jsonPrimitive.content })
        val panel = sidebar.getValue("panels").jsonObject.getValue("p1").jsonObject
        assertEquals(2, panel.getValue("type").jsonPrimitive.int)
        assertEquals("c1", panel.getValue("newTabCtx").jsonPrimitive.content)
        assertEquals("data:image/png;base64,AA", panel.getValue("iconIMG").jsonPrimitive.content)
    }

    @Test
    fun `group tabs carry their name in their address, where Sidebery reads it`() {
        val url = SideberyFile.groupUrl("Reading list & more")

        assertEquals(
            "moz-extension://00000000-0000-0000-0000-000000000000/sidebery/group.html#Reading%20list%20%26%20more",
            url,
        )
        // Sidebery looks for its group page 52 characters in, after the add-on's own address.
        assertTrue(url.startsWith("/sidebery/group.html", 52))
        assertTrue(SideberyFile.isGroupUrl(url))
        assertEquals("Reading list & more", SideberyFile.groupName(url))
        assertEquals("Çay ve kahve ☕", SideberyFile.groupName(SideberyFile.groupUrl("Çay ve kahve ☕")))
        assertEquals("News", SideberyFile.groupName("$GROUP_PAGE#News:id:abc123~!aaaaaaaaaaaa!ch!~"))
        assertNull(SideberyFile.groupName(GROUP_PAGE))
        assertFalse(SideberyFile.isGroupUrl("https://example.com/sidebery/group.html"))
    }

    @Test
    fun `only web pages are brought over, a reader view giving the page it shows`() {
        assertEquals("https://a.example/x", SideberyFile.webAddress("https://a.example/x"))
        assertEquals("HTTP://b.example/", SideberyFile.webAddress("HTTP://b.example/"))
        assertEquals(
            "https://c.example/read?id=1",
            SideberyFile.webAddress("about:reader?url=https%3A%2F%2Fc.example%2Fread%3Fid%3D1"),
        )
        listOf(
            "file:///home/me/a.txt",
            "about:config",
            "about:newtab",
            "moz-extension://x/page.html",
            "data:text/plain,hi",
            "javascript:void(0)",
        ).forEach { assertNull(it, SideberyFile.webAddress(it)) }
    }

    @Test
    fun `the file is named like the snapshots Sidebery exports`() {
        assertEquals(
            "sidebery-snapshot-2026.11.07-14.05.09.json",
            SideberyFile.name(1794060309000L, TimeZone.getTimeZone("UTC")),
        )
    }

    companion object {
        const val GROUP_PAGE = "moz-extension://8c1b0e2a-3f4d-4c5b-9a6e-7f8091a2b3c4/sidebery/group.html"
        const val READING_LIST = "$GROUP_PAGE#Reading%20list"

        private const val WORK_CONTAINER = """"firefox-container-1": {"id": "firefox-container-1",
            "cookieStoreId": "firefox-container-1", "name": "Work", "icon": "briefcase", "color": "orange",
            "colorCode": "#ff9f00", "proxified": false, "proxy": null, "reopenRulesActive": false, "reopenRules": [],
            "userAgentActive": false, "userAgent": ""}"""
        private const val RESEARCH_PANEL = """"researchpnl1": {"type": 2, "id": "researchpnl1", "name": "Research",
            "color": "blue", "iconSVG": "icon_tabs", "newTabCtx": "none", "dropTabCtx": "none", "moveRules": []}"""
        const val LATER_PANEL = """"laterpanel01": {"type": 2, "id": "laterpanel01", "name": "Later",
            "color": "toolbar", "iconSVG": "icon_tabs", "newTabCtx": "none"}"""

        /** A snapshot as Sidebery exports it, with a pinned tab every panel shows, a group and a container. */
        const val SNAPSHOT = """{
            "id": "lzx1abc2def3",
            "time": 1762500000000,
            "containers": {$WORK_CONTAINER},
            "sidebar": {
                "panels": {
                    "history": {"type": 4, "id": "history", "name": "History"},
                    $RESEARCH_PANEL,
                    "workpanel001": {"type": 2, "id": "workpanel001", "name": "Work", "color": "orange",
                        "iconSVG": "icon_tabs", "newTabCtx": "firefox-container-1"},
                    "bookmarks001": {"type": 1, "id": "bookmarks001", "name": "Bookmarks"}
                },
                "nav": ["researchpnl1", "workpanel001", "history", "bookmarks001", "add_tp", "sp-0", "settings"]
            },
            "tabs": [[
                [{"url": "https://mail.example.com/", "title": "Mail", "panelId": -1, "pinned": true}],
                [
                    {"url": "$READING_LIST", "title": "Reading list", "panelId": "researchpnl1", "folded": true},
                    {"url": "https://a.example.com/", "title": "A", "panelId": "researchpnl1", "lvl": 1},
                    {"url": "https://b.example.com/", "title": "B", "panelId": "researchpnl1", "lvl": 2},
                    {"url": "file:///home/me/notes.txt", "title": "notes.txt", "panelId": "researchpnl1"},
                    {"url": "https://c.example.com/", "title": "C", "panelId": "researchpnl1", "customTitle": "See"}
                ],
                [{"url": "https://jira.example.com/", "title": "Jira", "panelId": "workpanel001",
                    "containerId": "firefox-container-1"}]
            ]]
        }"""

        /**
         * A backup as Sidebery exports all its data, with two snapshots: the newer one keeps -1 for what did not
         * change since the older one, and the backup has a panel and a container made after the last snapshot.
         */
        const val BACKUP = """{
            "ver": "5.6.1",
            "settings": {"pinnedTabsPosition": "top"},
            "favicons": ["data:image/png;base64,AAAA"],
            "favDomains": {"example.com": {"index": 0, "len": 1}},
            "favHashes": [123],
            "containers": {$WORK_CONTAINER, "firefox-container-2": {"id": "firefox-container-2", "name": "Bank",
                "icon": "dollar", "color": "green"}},
            "sidebar": {"panels": {$RESEARCH_PANEL, $LATER_PANEL}, "nav": ["researchpnl1", "laterpanel01", "settings"]},
            "snapshots": [
                {"id": "s1", "time": 1762400000000, "containers": {$WORK_CONTAINER},
                    "sidebar": {"panels": {$RESEARCH_PANEL}, "nav": ["researchpnl1"]},
                    "tabs": [[[
                        {"url": "https://one.example.com/", "title": "One", "panelId": "researchpnl1"},
                        {"url": "https://two.example.com/", "title": "Two", "panelId": "researchpnl1"}
                    ]]]},
                {"id": "s2", "time": 1762500000000, "containers": -1, "sidebar": -1,
                    "tabs": [[[
                        -1,
                        {"url": "https://three.example.com/", "title": "Three", "panelId": "researchpnl1", "lvl": 1},
                        {"url": "https://four.example.com/", "title": "Four", "panelId": "researchpnl1"}
                    ]]]}
            ]
        }"""
    }
}
