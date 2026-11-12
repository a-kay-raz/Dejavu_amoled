/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sidebery

import kotlin.random.Random
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.createTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.workspaces.MAX_ESSENTIALS
import org.mozilla.fenix.dejavu.workspaces.MAX_FOLDER_DEPTH
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.SyncedDevice
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme

class SideberyImportExportTest {
    private var lastId = 0
    private val newId = { "id-${++lastId}" }

    @Test
    fun `workspaces become panels listing their folders as groups, then pinned and unpinned tabs`() {
        val result = SideberyExport.export(
            state = dejavu,
            tabs = tabs,
            containers = containers,
            iconImage = { if (it.id == HOME) "data:image/png;base64,AA" else null },
            now = NOW,
            random = Random(1),
        )

        val snapshot = result.snapshot
        assertEquals(
            listOf(
                SideberyPanel(HOME_PANEL, "Home", "blue", iconImage = "data:image/png;base64,AA"),
                SideberyPanel(WORK_PANEL, "Work", "orange"),
            ),
            snapshot.panels,
        )
        assertEquals(listOf(SideberyContainer("work-ctx", "Work", "orange", "briefcase")), snapshot.containers)
        assertEquals(
            listOf(
                SideberyTab("https://mail.example/", "mail", panelId = null, pinned = true),
                SideberyTab(SideberyFile.groupUrl("News"), "News", HOME_PANEL, folded = true),
                SideberyTab("https://paper.example/", "paper", HOME_PANEL, level = 1),
                SideberyTab(SideberyFile.groupUrl("Tech"), "Tech", HOME_PANEL, level = 1),
                SideberyTab("https://gadgets.example/", "gadgets", HOME_PANEL, level = 2, containerId = "work-ctx"),
                SideberyTab("https://mine.example/", "My page", HOME_PANEL, customTitle = "My page"),
                SideberyTab("https://x.example/", "X", HOME_PANEL, customTitle = "Ex"),
                SideberyTab("https://temp.example/", "https://temp.example/", HOME_PANEL),
                SideberyTab("https://jira.example/", "Jira", WORK_PANEL, containerId = "work-ctx"),
            ),
            snapshot.windows.single().tabs,
        )
        assertEquals(7, result.tabs)
        assertEquals(2, result.workspaces)
        assertTrue(snapshot.id, snapshot.id.startsWith(NOW.toString(36)))
    }

    @Test
    fun `importing an export into the same Dejavu adds nothing`() {
        val exported = SideberyExport.export(dejavu, tabs, containers, now = NOW).snapshot
        val read = SideberyFile.read(SideberyFile.write(exported))!!

        val openTabs = tabs.filterNot { it.content.private }.map { it.id to it.content.url }

        val plan = plan(read, dejavu, containers, openTabs)

        assertTrue(plan.summary.toString(), plan.summary.isEmpty)
        assertEquals(dejavu, plan.state)
        assertTrue(plan.tabs.isEmpty())
        assertTrue(plan.containers.isEmpty())
    }

    @Test
    fun `panels become workspaces, groups folders of pinned tabs, other tabs unpinned tabs, pinned tabs essentials`() {
        val plan = plan(SideberyFile.read(SideberyFileTest.SNAPSHOT)!!, fresh)

        assertEquals(
            SideberyImport.Summary(
                newWorkspaces = 1,
                folders = 1,
                pinnedTabs = 2,
                essentials = 1,
                unpinnedTabs = 2,
                newContainers = 1,
                leftOut = 1,
            ),
            plan.summary,
        )
        val state = plan.state
        val research = state.workspaces.single { it.name == "Research" }
        assertEquals(WorkspaceTheme(colors = listOf(ContainerColor.BLUE.argb.toInt())), research.theme)
        val folder = state.pins.single { it.isFolder }
        assertEquals("Reading list" to research.id, folder.title to folder.workspaceId)
        assertTrue(folder.collapsed)
        assertEquals(listOf("A", "B"), state.children(research.id, folder.id).map { it.title })
        assertEquals(listOf("https://mail.example.com/"), state.essentials.map { it.url })

        val container = plan.containers.single()
        assertEquals(
            SideberyImport.NewContainer(
                container.contextId,
                "Work",
                ContainerColor.ORANGE,
                ContainerState.Icon.BRIEFCASE,
            ),
            container,
        )
        val (see, jira) = plan.tabs
        assertEquals(SideberyImport.NewTab(see.id, "https://c.example.com/", "C", contextId = null), see)
        assertEquals(research.id, state.assignments[see.id])
        assertEquals("See", state.tabTitles[see.id])
        assertEquals(SideberyImport.NewTab(jira.id, "https://jira.example.com/", "Jira", container.contextId), jira)
        assertEquals(WORK, state.assignments[jira.id])
        assertEquals(fresh.activeWorkspaceId, state.activeWorkspaceId)
    }

    @Test
    fun `importing the same file again adds nothing`() {
        val snapshot = SideberyFile.read(SideberyFileTest.SNAPSHOT)!!
        val first = plan(snapshot, fresh)
        val addedContainers = first.containers.map { ContainerRecord(it.contextId, it.name, it.color, it.icon, 0, 0) }

        val second = plan(snapshot, first.state, addedContainers, openTabs = first.tabs.map { it.id to it.url })

        assertTrue(second.summary.toString(), second.summary.isEmpty)
        assertEquals(first.state, second.state)
    }

    @Test
    fun `a panel adds to the workspace it was exported from, even when one of them was renamed`() {
        val renamed = workspace(WORK, "Job")
        val start = fresh.copy(workspaces = listOf(workspace(HOME, "Home"), renamed))
        val snapshot = snapshotOf(
            listOf(SideberyPanel(SideberyExport.panelIdOf(renamed), "Work")),
            SideberyTab("https://jira.example/", "Jira", SideberyExport.panelIdOf(renamed)),
        )

        val plan = plan(snapshot, start)

        assertEquals(0, plan.summary.newWorkspaces)
        assertEquals(WORK, plan.state.assignments[plan.tabs.single().id])
    }

    @Test
    fun `the tabs of groups nested deeper than folders go into the deepest folder`() {
        val groups = (0..6).map { level -> SideberyTab(SideberyFile.groupUrl("G$level"), "G$level", "p", level) }
        val snapshot = snapshotOf(
            listOf(SideberyPanel("p", "Home")),
            *(groups + SideberyTab("https://deep.example/", "Deep", "p", level = 7)).toTypedArray(),
        )

        val plan = plan(snapshot, fresh)

        assertEquals(MAX_FOLDER_DEPTH, plan.summary.folders)
        val deepest = plan.state.pins.single { it.isFolder && it.title == "G4" }
        assertEquals(MAX_FOLDER_DEPTH, plan.state.folderDepth(deepest.id))
        assertEquals(deepest.id, plan.state.pins.single { it.url == "https://deep.example/" }.parentId)
        assertEquals(HOME, deepest.workspaceId)
    }

    @Test
    fun `pinned tabs of a panel are pinned in its workspace, and so are essentials beyond the twelfth`() {
        val everywhere = (1..MAX_ESSENTIALS + 2).map {
            SideberyTab("https://e$it.example/", "E$it", panelId = null, pinned = true)
        }
        val snapshot = snapshotOf(
            listOf(SideberyPanel("p", "Research")),
            *(everywhere + SideberyTab("https://own.example/", "Own", "p", pinned = true)).toTypedArray(),
        )

        val plan = plan(snapshot, fresh)

        assertEquals(MAX_ESSENTIALS, plan.summary.essentials)
        val research = plan.state.workspaces.single { it.name == "Research" }
        assertEquals(
            listOf("E13", "E14", "Own"),
            plan.state.children(research.id, parentId = null).map { it.title },
        )
    }

    @Test
    fun `tabs of private windows are left out, and tabs of unknown panels go to the first panel's workspace`() {
        val snapshot = SideberySnapshot(
            id = "s",
            time = 0L,
            panels = listOf(SideberyPanel("p", "Research")),
            containers = emptyList(),
            windows = listOf(
                SideberyWindow(
                    listOf(
                        SideberyTab(SideberyFile.groupUrl("Secret"), "Secret", "p"),
                        SideberyTab("https://private.example/", "Private", "p", level = 1),
                    ),
                    private = true,
                ),
                SideberyWindow(listOf(SideberyTab("https://lost.example/", "Lost", "gone"))),
            ),
        )

        val plan = plan(snapshot, fresh)

        assertEquals(1, plan.summary.leftOut)
        assertEquals(0, plan.summary.folders)
        val research = plan.state.workspaces.single { it.name == "Research" }
        assertEquals(research.id, plan.state.assignments[plan.tabs.single().id])
    }

    @Test
    fun `a group keeps its tabs when a tab of another panel comes between them`() {
        val snapshot = snapshotOf(
            listOf(SideberyPanel("p", "Research"), SideberyPanel("q", "Other")),
            SideberyTab(SideberyFile.groupUrl("Tools"), "Tools", "p"),
            SideberyTab("https://other.example/", "Other", "q"),
            SideberyTab("https://map.example/", "Map", "p", level = 1),
        )

        val plan = plan(snapshot, fresh)

        val folder = plan.state.pins.single { it.isFolder }
        assertEquals(folder.id, plan.state.pins.single { it.url == "https://map.example/" }.parentId)
        assertEquals(listOf("https://other.example/"), plan.tabs.map { it.url })
    }

    @Test
    fun `empty tabs are skipped without counting them, and pages that are essentials are not pinned again`() {
        val start = fresh.copy(pins = listOf(pin("mail", workspaceId = null, essential = true)))
        val snapshot = snapshotOf(
            listOf(SideberyPanel("p", "Research")),
            SideberyTab("https://mail.example/", "Mail", "p", pinned = true),
            SideberyTab("about:newtab", "New Tab", "p", pinned = true),
            SideberyTab(SideberyFile.groupUrl("Tools"), "Tools", "p"),
            SideberyTab("about:blank", "New Tab", "p", level = 1),
            SideberyTab("https://map.example/", "Map", "p", level = 2),
        )

        val plan = plan(snapshot, start)

        assertEquals(SideberyImport.Summary(newWorkspaces = 1, folders = 1, pinnedTabs = 1), plan.summary)
        val folder = plan.state.pins.single { it.isFolder }
        assertEquals(folder.id, plan.state.pins.single { it.url == "https://map.example/" }.parentId)
    }

    @Test
    fun `panels take the colors of workspace themes, the most colorful of their colors`() {
        assertEquals("orange", sideberyColorOf(WorkspaceTheme.Gold.colors))
        assertEquals("blue", sideberyColorOf(listOf(0xFFFFFFFF.toInt(), 0xFF5A87FD.toInt())))
        assertEquals("pink", sideberyColorOf(listOf(ContainerColor.PINK.argb.toInt())))
        assertEquals("turquoise", sideberyColorOf(listOf(ContainerColor.CYAN.argb.toInt())))
        assertEquals("toolbar", sideberyColorOf(listOf(ContainerColor.GRAY.argb.toInt())))
        assertEquals("toolbar", sideberyColorOf(emptyList()))
    }

    private fun plan(
        snapshot: SideberySnapshot,
        state: WorkspaceState,
        containers: List<ContainerRecord> = emptyList(),
        openTabs: List<Pair<String, String>> = emptyList(),
    ) = SideberyImport.plan(
        snapshot = snapshot,
        state = state,
        containers = containers,
        openTabs = openTabs,
        perContainerEssentials = false,
        defaultName = "Home",
        now = NOW,
        newId = newId,
    )

    private fun snapshotOf(panels: List<SideberyPanel>, vararg tabs: SideberyTab) =
        SideberySnapshot("s", 0L, panels, emptyList(), listOf(SideberyWindow(tabs.toList())))

    private fun workspace(
        id: String,
        name: String,
        containerId: String? = null,
        theme: WorkspaceTheme? = null,
        device: SyncedDevice? = null,
    ) = Workspace(id, name, containerId, theme = theme, createdAt = 0, updatedAt = 0, device = device)

    @Suppress("LongParameterList")
    private fun pin(
        id: String,
        workspaceId: String?,
        parentId: String? = null,
        title: String = id,
        url: String = "https://$id.example/",
        folder: Boolean = false,
        essential: Boolean = false,
        staticLabel: Boolean = false,
        collapsed: Boolean = false,
        containerId: String? = null,
        tabId: String? = null,
    ) = PinnedItem(
        id = id,
        workspaceId = workspaceId,
        parentId = parentId,
        kind = if (folder) PinKind.FOLDER else PinKind.TAB,
        title = title,
        url = url.takeUnless { folder },
        containerId = containerId,
        collapsed = collapsed,
        essential = essential,
        staticLabel = staticLabel,
        tabId = tabId,
        createdAt = 0,
        updatedAt = 0,
    )

    /** One workspace and a second one named " work ", which a panel named "Work" adds to. */
    private val fresh = WorkspaceState(
        workspaces = listOf(workspace(HOME, "Home"), workspace(WORK, " work ")),
        pins = emptyList(),
        activeWorkspaceId = HOME,
        assignments = emptyMap(),
    )

    /**
     * Home, with an essential, a folder holding a pinned tab and a folder, a pinned tab with its own title, and a pin
     * only a computer opens; Work with its container; and the workspace of a laptop on the account.
     */
    private val dejavu = WorkspaceState(
        workspaces = listOf(
            workspace(HOME, "Home", theme = WorkspaceTheme(listOf(0xFF5A87FD.toInt()))),
            workspace(WORK, "Work", containerId = "work-ctx"),
            workspace(LAPTOP, "Laptop", device = SyncedDevice.DESKTOP),
        ),
        pins = listOf(
            pin("mail", workspaceId = null, essential = true),
            pin("news", HOME, title = "News", folder = true, collapsed = true),
            pin("paper", HOME, parentId = "news"),
            pin("tech", HOME, parentId = "news", title = "Tech", folder = true),
            pin("gadgets", HOME, parentId = "tech", containerId = "work-ctx"),
            pin("mine", HOME, title = "My page", staticLabel = true, tabId = "t-mine"),
            pin("config", HOME, url = "about:config"),
        ),
        activeWorkspaceId = HOME,
        assignments = mapOf(
            "t-x" to HOME,
            "t-jira" to WORK,
            "t-private" to HOME,
            "t-popup" to HOME,
            "t-laptop" to LAPTOP,
            "t-mine" to HOME,
            "t-blank" to HOME,
            "t-temp" to HOME,
        ),
        tabTitles = mapOf("t-x" to "Ex"),
        openedByPages = setOf("t-popup"),
    )

    private val tabs = listOf(
        createTab("https://x.example/", id = "t-x", title = "X"),
        createTab("https://jira.example/", id = "t-jira", title = "Jira", contextId = "work-ctx"),
        createTab("https://private.example/", private = true, id = "t-private"),
        createTab("https://popup.example/", id = "t-popup"),
        createTab("https://laptop.example/", id = "t-laptop"),
        createTab("https://mine.example/", id = "t-mine"),
        createTab("about:blank", id = "t-blank"),
        createTab("https://temp.example/", id = "t-temp", contextId = "temp-ctx"),
    )

    private val containers = listOf(
        ContainerRecord("work-ctx", "Work", ContainerColor.ORANGE, ContainerState.Icon.BRIEFCASE, 0, 0),
        ContainerRecord("temp-ctx", "tmp3", ContainerColor.WHITE, ContainerState.Icon.FINGERPRINT, 0, 0, true),
        ContainerRecord("bank-ctx", "Bank", ContainerColor.GREEN, ContainerState.Icon.DOLLAR, 0, 0),
    )

    private companion object {
        const val NOW = 1_762_500_000_000L
        const val HOME = "{11111111-aaaa-bbbb-cccc-000000000001}"
        const val WORK = "{22222222-aaaa-bbbb-cccc-000000000002}"
        const val LAPTOP = "{44444444-aaaa-bbbb-cccc-000000000004}"
        const val HOME_PANEL = "11111111aaaa"
        const val WORK_PANEL = "22222222aaaa"
    }
}
