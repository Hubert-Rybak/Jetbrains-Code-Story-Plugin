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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.SimpleColoredComponent
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.WarningCode
import io.github.hubertrybak.codestory.mcp.CodeStoryToolset
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
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

/** Change information at the agent API seam, with a fixture Git history: badges, the diff base and "Show diff". */
class StoryChangeInfoTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private lateinit var git: GitFixture
    private var panel: StoryListPanel? = null
    private val diffs = RecordingDiffManager()

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        write("src/App.kt", (1..30).joinToString("\n", postfix = "\n") { "line $it" })
        write("src/Old.kt", (1..5).joinToString("\n", postfix = "\n") { "old $it" })
        git = GitFixture(root)
        git.init()
        // Uncommitted work: a changed line and four added lines, a new file, and a renamed file with a changed line.
        write("src/App.kt", (1..30).flatMap { line ->
            when (line) {
                5 -> listOf("changed 5")
                20 -> listOf("line 20") + (1..4).map { "added $it" }
                else -> listOf("line $line")
            }
        }.joinToString("\n", postfix = "\n"))
        write("src/New.kt", (1..3).joinToString("\n", postfix = "\n") { "new $it" })
        git.command("mv", "src/Old.kt", "src/Renamed.kt")
        write("src/Renamed.kt", "old 1\nold 2\nedited 3\nold 4\nold 5\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
        ApplicationManager.getApplication().replaceService(DiffManager::class.java, diffs, testRootDisposable)
    }

    override fun tearDown() {
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            StoryPlayer.getInstance(project).stop()
            panel?.let { Disposer.dispose(it) }
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test steps show added and modified lines since the base, also after the work is committed`() {
        val base = git.command("rev-parse", "HEAD")
        val report = show(changeStory(baseRef = quoted("HEAD")))
        assertEmpty(report.getValue("warnings").jsonArray)
        val id = report.string("storyId")
        val saved = Json.parseToJsonElement(Files.readString(root.resolve(report.string("file")))).jsonObject
        assertEquals("HEAD is saved as its commit", base, saved.string("baseRef"))
        assertEquals(base, get(id).string("baseRef"))

        openPanel(7)
        assertEquals(EXPECTED_STEPS, (1..6).map { stepRow(it) })
        panel!!.tree.setSelectionRow(0)
        assertTrue(panel!!.details.displayedText, panel!!.details.displayedText.contains("Diff base: $base"))

        git.command("add", "-A")
        git.command("-c", "user.name=Code Story Test", "-c", "user.email=story@example.test", "commit", "--no-gpg-sign", "-m", "The change")
        assertFalse(base == git.command("rev-parse", "HEAD"))
        // A new panel reads the stories after the commit.
        Disposer.dispose(panel!!)
        openPanel(7)
        assertEquals(EXPECTED_STEPS, (1..6).map { stepRow(it) })
    }

    fun `test show diff opens the base against the current file at the step range`() {
        show(changeStory(baseRef = quoted("HEAD")))
        val base = git.command("rev-parse", "HEAD")
        openPanel(7)

        select(2)
        assertTrue(panel!!.details.diffLink.parent.isVisible)
        panel!!.details.diffLink.doClick()
        val modified = awaitDiff()
        assertEquals((1..30).joinToString("\n", postfix = "\n") { "line $it" }, (modified.contents[0] as DocumentContent).document.text)
        val current = modified.contents[1] as DocumentContent
        assertEquals("App.kt", FileDocumentManager.getInstance().getFile(current.document)!!.name)
        assertTrue(current.document.text.contains("added 4"))
        assertEquals(Pair.create(Side.RIGHT, 3), modified.getUserData(DiffUserDataKeys.SCROLL_TO_LINE))
        assertTrue(modified.title!!, modified.title!!.contains("src/App.kt") && modified.title!!.contains(base.take(10)))

        select(4)
        perform(showDiffAction())
        val added = awaitDiff()
        assertInstanceOf(added.contents[0], EmptyContent::class.java)
        assertEquals(Pair.create(Side.RIGHT, 0), added.getUserData(DiffUserDataKeys.SCROLL_TO_LINE))

        select(5)
        perform(showDiffAction())
        val renamed = awaitDiff()
        assertEquals("old 1\nold 2\nold 3\nold 4\nold 5\n", (renamed.contents[0] as DocumentContent).document.text)
        assertTrue(renamed.contentTitles[0]!!, renamed.contentTitles[0]!!.contains("src/Old.kt"))

        select(6)
        assertFalse("a text-only step has no diff", panel!!.details.diffLink.parent.isVisible)
        assertFalse(update(showDiffAction()).isEnabledAndVisible)
    }

    fun `test a lost step shows no badge`() {
        show(changeStory(baseRef = quoted("HEAD")))
        // Without the live markers of the playing story, the step is found by its anchor text only.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        StoryPlayer.getInstance(project).stop()
        FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        // The added lines are gone, and the stored lines of step 1 now hold other new code.
        write("src/App.kt", (1..30).joinToString("\n", postfix = "\n") { if (it == 5) "changed 5" else if (it in 21..24) "rewritten $it" else "line $it" })
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/App.kt"))!!.refresh(false, false)

        openPanel(7)
        assertEquals("1. Added lines  App.kt:21–24  lost", stepRow(1))
        assertEquals(EXPECTED_STEPS.drop(1), (2..6).map { stepRow(it) })
    }

    fun `test an unknown base gives a warning and no change information`() {
        val unknown = "0123456789abcdef0123456789abcdef01234567"
        val report = show(changeStory(baseRef = quoted(unknown)))
        assertEquals("accepted", report.string("status"))
        val warning = report.getValue("warnings").jsonArray.map { it.jsonObject }.single { it.string("code") == WarningCode.UNKNOWN_BASE_REF.code }
        assertEquals(JsonNull, warning.getValue("step"))
        assertTrue(warning.string("message"), warning.string("message").contains(unknown))
        assertTrue(warning.string("hint").isNotBlank())
        assertEquals(unknown, get(report.string("storyId")).string("baseRef"))

        openPanel(7)
        assertEquals(PLAIN_STEPS, (1..6).map { stepRow(it) })
        panel!!.tree.setSelectionRow(0)
        assertTrue(panel!!.details.displayedText, panel!!.details.displayedText.contains("Diff base: $unknown (Git does not know this commit)"))
        assertNoDiff()
    }

    fun `test a story without a base shows no change information`() {
        val report = show(changeStory(baseRef = null))
        assertEmpty(report.getValue("warnings").jsonArray)
        assertFalse(get(report.string("storyId")).containsKey("baseRef"))
        openPanel(7)
        assertEquals(PLAIN_STEPS, (1..6).map { stepRow(it) })
        panel!!.tree.setSelectionRow(0)
        assertFalse(panel!!.details.displayedText.contains("Diff base"))
        assertNoDiff()
    }

    fun `test without the Git plugin the feature is off and nothing fails`() {
        ExtensionTestUtil.maskExtensions(StoryGitMetadata.EP, emptyList(), testRootDisposable)
        val report = show(changeStory(baseRef = quoted("HEAD")))
        assertEquals("accepted", report.string("status"))
        assertEmpty(report.getValue("warnings").jsonArray)
        assertEquals("HEAD", get(report.string("storyId")).string("baseRef"))
        openPanel(7)
        assertEquals(PLAIN_STEPS, (1..6).map { stepRow(it) })
        assertNoDiff()
    }

    /** No step offers "Show diff" in the details pane or the context menu, and no diff opened. */
    private fun assertNoDiff() {
        for (row in 1..6) {
            select(row)
            assertFalse("row $row", panel!!.details.diffLink.parent.isVisible)
            assertFalse("row $row", update(showDiffAction()).isEnabledAndVisible)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEmpty(diffs.requests)
    }

    private fun changeStory(baseRef: String?) = story(
        step(start = "21", end = "24", anchor = quoted("added 1"), title = quoted("Added lines")),
        step(start = "4", end = "6", anchor = quoted("line 4"), title = quoted("Changed line")),
        step(start = "10", end = "12", title = quoted("Same code")),
        step(file = quoted("src/New.kt"), start = "1", end = "3", anchor = quoted("new 1"), title = quoted("New file")),
        step(file = quoted("src/Renamed.kt"), start = "2", end = "4", anchor = quoted("old 2"), title = quoted("Renamed file")),
        step(title = quoted("Overview"), location = null),
        baseRef = baseRef,
    )

    /** Opens the tool window content once the stories and their change information are shown. */
    private fun openPanel(rows: Int) {
        val panel = StoryListPanel(project).also { panel = it }
        val loaded = panel.loaded()
        PlatformTestUtil.waitWithEventsDispatching("the stories are not listed", { loaded.isDone && panel.tree.rowCount == rows }, TIMEOUT_SECONDS)
        // The change information was queued before the load completed.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        panel.tree.setSize(800, 400)
    }

    /** The step rows; step 1 has the visited mark of the auto-play. */
    private fun stepRow(row: Int): String {
        val tree = panel!!.tree
        val path = tree.getPathForRow(row)
        val renderer = tree.cellRenderer.getTreeCellRendererComponent(tree, path.lastPathComponent, false, true, true, row, false)
        return (renderer as SimpleColoredComponent).getCharSequence(false).toString().removeSuffix("  ✓")
    }

    private fun select(row: Int) = panel!!.tree.setSelectionRow(row)

    private fun showDiffAction() = panel!!.contextActions.getChildActionsOrStubs().single { it.templatePresentation.text == "Show Diff" }

    private fun update(action: AnAction) = event(action).also { action.update(it) }.presentation

    private fun perform(action: AnAction) {
        val event = event(action)
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        action.actionPerformed(event)
    }

    private fun event(action: AnAction) = TestActionEvent.createTestEvent(action)

    private fun awaitDiff(): SimpleDiffRequest {
        PlatformTestUtil.waitWithEventsDispatching("the diff did not open", { diffs.requests.isNotEmpty() }, TIMEOUT_SECONDS)
        return diffs.requests.removeFirst() as SimpleDiffRequest
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

    /** Records the diffs that the plugin opens; a test has no diff window. */
    private class RecordingDiffManager : DiffManager() {
        val requests = ArrayDeque<DiffRequest>()

        override fun showDiff(project: Project?, request: DiffRequest) { requests += request }
        override fun showDiff(project: Project?, request: DiffRequest, hints: DiffDialogHints) { requests += request }
        override fun showDiff(project: Project?, chain: DiffRequestChain, hints: DiffDialogHints) = throw UnsupportedOperationException()
        override fun createRequestPanel(project: Project?, parent: Disposable, window: Window?): DiffRequestPanel = throw UnsupportedOperationException()
        override fun showMerge(project: Project?, request: MergeRequest) = throw UnsupportedOperationException()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20

        val EXPECTED_STEPS = listOf(
            "1. Added lines  App.kt:21–24  A",
            "2. Changed line  App.kt:4–6  M",
            "3. Same code  App.kt:10–12",
            "4. New file  New.kt:1–3  A",
            "5. Renamed file  Renamed.kt:2–4  M",
            "6. Overview",
        )
        val PLAIN_STEPS = EXPECTED_STEPS.map { it.removeSuffix("  A").removeSuffix("  M") }
    }
}
