package io.github.hubertrybak.codestory

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.SimpleColoredComponent
import io.github.hubertrybak.codestory.core.StepChange
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.mcp.CodeStoryToolset
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path

/**
 * Change information at the agent API seam, with a fixture Git history: the committed code is the
 * base, and the uncommitted work adds `audit()`, changes `refund()` and adds `Report.kt`.
 */
class StoryChangeInfoTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private lateinit var git: GitFixture
    private lateinit var base: String
    private val player get() = StoryPlayer.getInstance(project)
    private var panel: StoryListPanel? = null
    private val diffs = mutableListOf<DiffRequest>()

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        write("src/Billing.kt", BASE_BILLING)
        git = GitFixture(root)
        git.init()
        base = git.command("rev-parse", "HEAD")
        write("src/Billing.kt", BILLING)
        write("src/Report.kt", REPORT)
        git.refresh()
    }

    override fun tearDown() {
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            player.stop()
            panel?.let { Disposer.dispose(it) }
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test added, modified and unchanged ranges show A, M and no badge, also after a commit`() {
        val report = show(story(audit(), charge(), refund(), report(), step(location = null, title = quoted("What to check")), baseRef = quoted("HEAD")))

        assertEquals("accepted", report.string("status"))
        assertEmpty(report.getValue("warnings").jsonArray.map { it.jsonObject.string("code") }.filter { it == "unknown-base-ref" })
        val id = report.string("storyId")
        // HEAD is saved as the full commit SHA.
        assertEquals(base, get(id).string("baseRef"))
        openPanel(rows = 6)

        assertEquals(
            listOf(
                "1. Audit the account  Billing.kt:4–6  A  ✓",
                "2. Charge a card  Billing.kt:8–11",
                "3. Refund a payment  Billing.kt:13–15  M",
                "4. Build the report  Report.kt:1–3  A",
                "5. What to check",
            ),
            (1..5).map(::rowText),
        )
        panel!!.tree.setSelectionRow(0)
        assertTrue(panel!!.details.displayedText, panel!!.details.displayedText.contains("Diff base: $base"))

        // The base stays the same commit after the work is committed.
        git.commit("src")
        val changes = runBlocking {
            withContext(Dispatchers.IO) {
                val service = CodeStoryService.getInstance(project)
                val stored = service.stories().single()
                StoryChangeInfo.getInstance(project).changes(stored, service.ideFiles(stored.root))
            }
        } as StoryChanges.Known
        assertEquals(base, changes.commit)
        assertEquals(listOf(StepChange.ADDED, null, StepChange.MODIFIED, StepChange.ADDED, null), changes.steps)
    }

    fun `test Show diff opens the base revision against the current file at the step range`() {
        ApplicationManager.getApplication().replaceService(DiffManager::class.java, RecordingDiffManager(), testRootDisposable)
        show(story(refund(), report(), step(location = null, title = quoted("What to check")), baseRef = quoted("HEAD")))
        val panel = openPanel(rows = 4)

        // From the details pane.
        panel.tree.setSelectionRow(1)
        assertTrue(panel.details.showDiffLink.isVisible)
        panel.details.showDiffLink.doClick()
        PlatformTestUtil.waitWithEventsDispatching("the diff did not open", { diffs.size == 1 }, TIMEOUT_SECONDS)
        val modified = diffs.single() as SimpleDiffRequest
        assertEquals(listOf("Base ${base.take(8)}", "Current"), modified.contentTitles)
        assertEquals(BASE_BILLING, (modified.contents[0] as DocumentContent).document.text)
        assertEquals(BILLING, (modified.contents[1] as DocumentContent).document.text)
        assertEquals(Pair.create(Side.RIGHT, 12), modified.getUserData(DiffUserDataKeys.SCROLL_TO_LINE))

        // From the context menu, for a file that is new since the base.
        panel.tree.setSelectionRow(2)
        val action = showDiffAction()
        assertTrue(update(action))
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        PlatformTestUtil.waitWithEventsDispatching("the diff did not open", { diffs.size == 2 }, TIMEOUT_SECONDS)
        val added = diffs.last() as SimpleDiffRequest
        assertInstanceOf(added.contents[0], EmptyContent::class.java)
        assertEquals(REPORT, (added.contents[1] as DocumentContent).document.text)
        assertEquals(Pair.create(Side.RIGHT, 0), added.getUserData(DiffUserDataKeys.SCROLL_TO_LINE))

        // A text-only step and the story have no diff.
        panel.tree.setSelectionRow(3)
        assertFalse(panel.details.showDiffLink.isVisible)
        assertFalse(update(action))
        panel.tree.setSelectionRow(0)
        assertFalse(panel.details.showDiffLink.isVisible)
        assertFalse(update(action))
    }

    fun `test an unknown baseRef gives a warning and shows no change information`() {
        for (unknown in listOf("0123456789abcdef0123456789abcdef01234567", "--output=report.txt")) {
            val report = show(story(refund(), title = quoted("Unknown $unknown".take(20)), baseRef = quoted(unknown)))
            assertEquals("accepted", report.string("status"))
            val warning = report.getValue("warnings").jsonArray.map { it.jsonObject }.single { it.string("code") == "unknown-base-ref" }
            assertEquals(JsonNull, warning.getValue("step"))
            assertTrue(warning.string("message"), warning.string("message").contains(unknown))
            assertTrue(report.string("next").contains("Fix the warnings"))
            assertEquals(unknown, get(report.string("storyId")).string("baseRef"))
        }
        assertFalse(Files.exists(root.resolve("report.txt")))
        val panel = openPanel(rows = 4)

        for (row in listOf(1, 3)) {
            assertEquals("1. Refund a payment  Billing.kt:13–15  ✓", rowText(row))
            assertNoDiff(panel, row)
            panel.tree.setSelectionRow(row - 1)
            assertTrue(panel.details.displayedText, panel.details.displayedText.contains("(Git does not know this commit)"))
        }
    }

    fun `test a story without a base shows no change information`() {
        val report = show(story(audit(), refund()))
        assertEquals(listOf<String>(), report.getValue("warnings").jsonArray.map { it.jsonObject.string("code") }.filter { it == "unknown-base-ref" })
        assertFalse(get(report.string("storyId")).containsKey("baseRef"))
        val panel = openPanel(rows = 3)

        assertEquals("1. Audit the account  Billing.kt:4–6  ✓", rowText(1))
        assertEquals("2. Refund a payment  Billing.kt:13–15", rowText(2))
        assertNoDiff(panel, row = 2)
        panel.tree.setSelectionRow(0)
        assertFalse(panel.details.displayedText, panel.details.displayedText.contains("Diff base"))
    }

    fun `test without the Git plugin the feature is off and nothing fails`() {
        ExtensionTestUtil.maskExtensions(StoryGitMetadata.EP, emptyList(), testRootDisposable)
        val report = show(story(audit(), refund(), baseRef = quoted("HEAD")))

        assertEquals("accepted", report.string("status"))
        assertEquals(listOf<String>(), report.getValue("warnings").jsonArray.map { it.jsonObject.string("code") }.filter { it == "unknown-base-ref" })
        assertEquals("HEAD", get(report.string("storyId")).string("baseRef"))
        val panel = openPanel(rows = 3)
        assertEquals("1. Audit the account  Billing.kt:4–6  ✓", rowText(1))
        assertEquals("2. Refund a payment  Billing.kt:13–15", rowText(2))
        assertNoDiff(panel, row = 2)
    }

    private fun assertNoDiff(panel: StoryListPanel, row: Int) {
        panel.tree.setSelectionRow(row)
        assertTrue(panel.details.displayedText.contains("Refund a payment"))
        assertFalse(panel.details.showDiffLink.isVisible)
        assertFalse(update(showDiffAction()))
    }

    private fun showDiffAction(): AnAction = panel!!.contextMenu.getChildren(null).single { it.templateText == "Show Diff" }

    /** Whether the context-menu action shows for the selected row. */
    private fun update(action: AnAction): Boolean {
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    private inner class RecordingDiffManager : DiffManager() {
        override fun showDiff(project: Project?, request: DiffRequest) { diffs += request }
        override fun showDiff(project: Project?, request: DiffRequest, hints: DiffDialogHints) { diffs += request }
        override fun showDiff(project: Project?, requests: DiffRequestChain, hints: DiffDialogHints) = throw UnsupportedOperationException()
        override fun createRequestPanel(project: Project?, parent: Disposable, window: Window?): DiffRequestPanel = throw UnsupportedOperationException()
        override fun showMerge(project: Project?, request: MergeRequest) = throw UnsupportedOperationException()
    }

    private fun audit() = step(file = quoted("src/Billing.kt"), start = "4", end = "6", anchor = quoted("fun audit(): String {"), title = quoted("Audit the account"))

    private fun charge() = step(file = quoted("src/Billing.kt"), start = "8", end = "11", anchor = quoted("fun charge(amount: Int) {"), title = quoted("Charge a card"))

    private fun refund() = step(file = quoted("src/Billing.kt"), start = "13", end = "15", anchor = quoted("fun refund(amount: Int) {"), title = quoted("Refund a payment"))

    private fun report() = step(file = quoted("src/Report.kt"), start = "1", end = "3", anchor = quoted("class Report {"), title = quoted("Build the report"))

    /** Stops the auto-played story, opens the tool window content and expands every story into [rows] rows. */
    private fun openPanel(rows: Int): StoryListPanel {
        PlatformTestUtil.waitWithEventsDispatching("the story did not start", { player.isPlaying }, TIMEOUT_SECONDS)
        player.stop()
        val panel = StoryListPanel(project).also { panel = it }
        PlatformTestUtil.waitWithEventsDispatching("the stories are not listed", { panel.tree.rowCount > 0 }, TIMEOUT_SECONDS)
        var row = 0
        while (row < panel.tree.rowCount) panel.tree.expandRow(row++)
        assertEquals(rows, panel.tree.rowCount)
        return panel
    }

    private fun rowText(row: Int): String {
        val tree = panel!!.tree
        val path = tree.getPathForRow(row) ?: return ""
        val renderer = tree.cellRenderer.getTreeCellRendererComponent(tree, path.lastPathComponent, false, true, true, row, false)
        return (renderer as SimpleColoredComponent).getCharSequence(false).toString()
    }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun show(json: String): JsonObject = call("code_story_show", mapOf("story" to Json.parseToJsonElement(json)))

    private fun get(id: String): JsonObject = call("code_story_get", mapOf("storyId" to JsonPrimitive(id)))

    private fun call(name: String, args: Map<String, JsonElement>): JsonObject {
        val tool = CodeStoryToolset().asTools().single { it.descriptor.name == name }
        val arguments = JsonObject(args + ("projectPath" to JsonPrimitive(root.toString())))
        val info = McpCallInfo(1, ClientInfo("Code Story test", "1.0"), project, tool.descriptor, arguments,
            JsonObject(emptyMap()), McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.DONT_ASK))
        val result: McpToolCallResult = runBlocking { withContext(McpCallAdditionalDataElement(info)) { tool.call(arguments) } }
        assertFalse(result.content.joinToString { (it as McpToolCallResultContent.Text).text }, result.isError)
        return result.structuredContent!!
    }

    private fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10

        val CHARGE = "    fun charge(amount: Int) {\n        check(amount > 0)\n        gateway.send(amount)\n    }"
        val BASE_BILLING = "package billing\n\nclass Billing(private val gateway: Gateway) {\n$CHARGE\n\n" +
            "    fun refund(amount: Int) {\n        gateway.send(-amount)\n    }\n}\n"

        /** `audit()` is new, `charge()` is the same and one line of `refund()` changed. */
        val BILLING = "package billing\n\nclass Billing(private val gateway: Gateway) {\n" +
            "    fun audit(): String {\n        return log.read(\"audit\")\n    }\n\n$CHARGE\n\n" +
            "    fun refund(amount: Int) {\n        gateway.reverse(amount)\n    }\n}\n"

        /** A file that is not in the base commit. */
        val REPORT = "class Report {\n    fun build() = \"report\"\n}\n"
    }
}
