package io.github.hubertrybak.codestory

import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.SimpleColoredComponent
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.mcp.CodeStoryToolset
import io.github.hubertrybak.codestory.ui.StepNavigator
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Anchors at the agent API seam: edit the fixture documents, then check corrections, statuses and story files. */
class StoryAnchoringTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private val player get() = StoryPlayer.getInstance(project)
    private var panel: StoryListPanel? = null

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Billing.kt"), BILLING)
        Files.writeString(root.resolve("src/Other.kt"), "class Other {\n    fun run() {}\n}\n")
    }

    override fun tearDown() {
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            player.stop()
            panel?.let { Disposer.dispose(it) }
            val manager = FileDocumentManager.getInstance()
            WriteAction.run<RuntimeException> { manager.unsavedDocuments.forEach { manager.reloadFromDisk(it) } }
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test lines a few lines off are corrected, reported and saved`() {
        val report = show(story(charge(start = 6, end = 9), refund()))

        assertEquals("accepted", report.string("status"))
        val corrections = report.getValue("corrections").jsonArray.map { it.jsonObject }
        assertEquals(listOf("1" to "lines-shifted"), corrections.map { it.string("step") to it.string("code") })
        assertEquals("Step 1: lines 6–9 changed to 4–7.", corrections.single().string("message"))
        assertTrue(report.string("next").contains("corrected"))
        val saved = savedLocation(report.string("storyId"), 0)
        assertEquals(Location("src/Billing.kt", 4, 7, "fun charge(amount: Int) {", CHARGE), saved)
        assertEquals(REFUND, savedLocation(report.string("storyId"), 1).rangeText)
    }

    fun `test the check on arrival uses unsaved edits and rejects anchors that are not nearby`() {
        val document = document()
        edit { document.insertString(0, "// one\n// two\n// three\n") }

        val shifted = show(story(charge(), refund()))
        assertEquals("accepted", shifted.string("status"))
        assertEquals(listOf("Step 1: lines 4–7 changed to 7–10.", "Step 2: lines 9–11 changed to 12–14."),
            shifted.getValue("corrections").jsonArray.map { it.jsonObject.string("message") })
        assertEquals(CHARGE, savedLocation(shifted.string("storyId"), 0).rangeText)

        val rejected = show(story(charge(), step(file = quoted("src/Billing.kt"), start = "9", end = "11", anchor = quoted("fun missing() {"))))
        assertEquals("rejected", rejected.string("status"))
        val error = rejected.getValue("errors").jsonArray.single().jsonObject
        assertEquals("2" to "anchor-not-found", error.string("step") to error.string("code"))
        assertTrue(error.string("message"), error.string("message").contains("Line 9 is `gateway.send(amount)`."))
        assertEquals(1, CodeStoryService.getInstance(project).stories().size)
    }

    fun `test opening stories finds moved changed and lost steps and rewrites the local story`() {
        val id = show(story(charge(), refund(), audit())).string("storyId")
        awaitPlaying(id)
        player.stop()
        val document = document()
        edit {
            // Delete audit(), change the code of refund() and add lines above everything.
            document.deleteString(document.getLineStartOffset(12), document.getLineStartOffset(15))
            document.replaceString(document.getLineStartOffset(9), document.getLineEndOffset(9), "        gateway.reverse(amount)")
            document.insertString(0, "// one\n// two\n")
        }

        val entry = list().single()
        assertEquals("lost", entry.string("status"))
        assertEquals(listOf("1", "1", "1"), listOf("movedSteps", "changedSteps", "lostSteps").map { entry.string(it) })
        // Moved anchors are saved in the local story; the lost step keeps its lines.
        assertEquals(listOf(6 to 9, 11 to 13, 13 to 15), (0..2).map { savedLocation(id, it).let { l -> l.startLine to l.endLine } })
        assertEquals(REFUND, savedLocation(id, 1).rangeText)
        val again = list().single()
        assertEquals("lost", again.string("status"))
        assertEquals(listOf("0", "1", "1"), listOf("movedSteps", "changedSteps", "lostSteps").map { again.string(it) })
        assertEquals(11, get(id).getValue("steps").jsonArray[1].jsonObject.getValue("location").jsonObject.string("startLine").toInt())
    }

    fun `test corrections for a shared story stay in memory`() {
        val first = show(story(charge(), refund()))
        val id = first.string("storyId")
        awaitPlaying(id)
        player.stop()
        val shared = Files.createDirectories(root.resolve(".codestory/shared")).resolve("$id.story.json")
        Files.move(root.resolve(first.string("file")), shared)
        val before = Files.readString(shared)
        val document = document()
        edit { document.insertString(0, "// one\n// two\n") }

        val entry = list().single()
        assertEquals("moved" to "2", entry.string("status") to entry.string("movedSteps"))
        assertEquals(before, Files.readString(shared))
        assertEquals(6, get(id).getValue("steps").jsonArray[0].jsonObject.getValue("location").jsonObject.string("startLine").toInt())
        val playing = CodeStoryService.getInstance(project).stories().single()
        player.play(playing, 1)
        assertEquals(11, player.currentStory!!.story.steps[1].location!!.startLine)
        assertEquals(10, document.getLineNumber(StepNavigator.getInstance(project).currentHighlighter!!.startOffset))
        player.stop()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(before, Files.readString(shared))
    }

    fun `test live markers follow edits during playback and the local story is rewritten`() {
        val id = show(story(charge(), refund(), audit())).string("storyId")
        awaitPlaying(id)
        val document = document()
        // Three lines above refund() while the card of charge() is open.
        edit { document.insertString(document.getLineStartOffset(7), "\n\n\n") }

        player.next()

        assertEquals(1, player.stepIndex)
        assertEquals(12 to 14, player.currentStory!!.story.steps[1].location!!.let { it.startLine to it.endLine })
        assertEquals(StepStatus.MOVED, player.currentStory!!.status(1))
        assertEquals(11, document.getLineNumber(StepNavigator.getInstance(project).currentHighlighter!!.startOffset))
        assertNull(player.presentation!!.card.statusText)
        PlatformTestUtil.waitWithEventsDispatching("moved anchors are not saved", { savedLocation(id, 1).startLine == 12 }, TIMEOUT_SECONDS)
        assertEquals(listOf(4, 12, 16), (0..2).map { savedLocation(id, it).startLine })
        player.previous()
        assertEquals(StepStatus.OK, player.currentStory!!.status(0))
        assertEquals(3, document.getLineNumber(StepNavigator.getInstance(project).currentHighlighter!!.startOffset))
    }

    fun `test an edited first line during playback is code changed, also after playback stops`() {
        val id = show(story(charge(), refund())).string("storyId")
        awaitPlaying(id)
        val document = document()
        edit { document.replaceString(document.getLineStartOffset(8), document.getLineEndOffset(8), "    fun refund(amount: Long) {") }

        player.next()

        assertEquals(StepStatus.CODE_CHANGED, player.currentStory!!.status(1))
        assertEquals("The code changed after the agent wrote this step.", player.presentation!!.card.statusText)
        player.stop()
        PlatformTestUtil.waitWithEventsDispatching("the followed anchor is not saved",
            { savedLocation(id, 1).anchorText == "fun refund(amount: Long) {" }, TIMEOUT_SECONDS)
        assertEquals(REFUND, savedLocation(id, 1).rangeText)
        val entry = list().single()
        assertEquals("code-changed" to listOf("0", "1", "0"), entry.string("status") to listOf("movedSteps", "changedSteps", "lostSteps").map { entry.string(it) })
    }

    fun `test lost and changed steps show in the tree and the card, and a lost step plays at its stored lines`() {
        val id = show(story(charge(), refund(), audit())).string("storyId")
        awaitPlaying(id)
        player.stop()
        val document = document()
        edit {
            document.deleteString(document.getLineStartOffset(12), document.getLineStartOffset(15))
            document.replaceString(document.getLineStartOffset(9), document.getLineEndOffset(9), "        gateway.reverse(amount)")
        }
        val panel = StoryListPanel(project).also { panel = it }
        PlatformTestUtil.waitWithEventsDispatching("the stories are not listed", { panel.tree.rowCount == 6 && rowText(0).contains("lost") }, TIMEOUT_SECONDS)

        assertTrue(rowText(0), rowText(0).endsWith("  1 lost, 1 code changed"))
        assertEquals("1. Charge a card  Billing.kt:4–7  ✓", rowText(1))
        assertEquals("2. Refund a payment  Billing.kt:9–11  code changed", rowText(2))
        assertEquals("3. Audit the account  Billing.kt:13–15  lost", rowText(3))

        val stored = CodeStoryService.getInstance(project).stories().single()
        player.play(stored, 2)
        assertTrue(player.isPlaying)
        assertEquals(StepStatus.LOST, player.currentStory!!.status(2))
        assertEquals(Location("src/Billing.kt", 13, 15, "fun audit() {", AUDIT), player.currentStory!!.story.steps[2].location)
        assertEquals("The code changed. This step can be wrong.", player.presentation!!.card.statusText)
        assertEquals(12, document.getLineNumber(StepNavigator.getInstance(project).currentHighlighter!!.startOffset))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(panel.details.displayedText, panel.details.displayedText.contains("Step 3 of 3: Audit the account\n\nThe code changed. This step can be wrong."))
        player.previous()
        assertEquals("The code changed after the agent wrote this step.", player.presentation!!.card.statusText)
    }

    fun `test a step whose file is gone plays as lost without stopping the story`() {
        val other = step(file = quoted("src/Other.kt"), start = "2", end = "2", anchor = quoted("fun run() {}"), title = quoted("Run it"))
        val id = show(story(charge(), other)).string("storyId")
        awaitPlaying(id)
        Files.delete(root.resolve("src/Other.kt"))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src"))!!.refresh(false, true)

        player.next()

        assertTrue(player.isPlaying)
        assertEquals(1, player.stepIndex)
        assertEquals(StepStatus.LOST, player.currentStory!!.status(1))
        assertNull(player.presentation)
        assertEquals("lost" to "1", list().single().let { it.string("status") to it.string("lostSteps") })
    }

    private fun charge(start: Int = 4, end: Int = 7) =
        step(file = quoted("src/Billing.kt"), start = "$start", end = "$end", anchor = quoted("fun charge(amount: Int) {"), title = quoted("Charge a card"))

    private fun refund() = step(file = quoted("src/Billing.kt"), start = "9", end = "11", anchor = quoted("fun refund(amount: Int) {"), title = quoted("Refund a payment"))

    private fun audit() = step(file = quoted("src/Billing.kt"), start = "13", end = "15", anchor = quoted("fun audit() {"), title = quoted("Audit the account"))

    private fun document(): Document =
        FileDocumentManager.getInstance().getDocument(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/Billing.kt"))!!)!!

    private fun edit(action: () -> Unit) = WriteCommandAction.runWriteCommandAction(project, action)

    private fun awaitPlaying(id: String) =
        PlatformTestUtil.waitWithEventsDispatching("the story did not start", { player.currentStory?.id == id }, TIMEOUT_SECONDS)

    private fun savedLocation(id: String, index: Int): Location {
        val file = CodeStoryService.getInstance(project).storyFolder().get(id)!!.file
        val story = (StoryFormat.decode(Files.readString(file)) as StoryFormat.Decoded.Valid).story
        return story.steps[index].location!!
    }

    private fun rowText(row: Int): String {
        val tree = panel!!.tree
        val path = tree.getPathForRow(row + 1)
        val renderer = tree.cellRenderer.getTreeCellRendererComponent(tree, path.lastPathComponent, false, true, true, row, false)
        return (renderer as SimpleColoredComponent).getCharSequence(false).toString()
    }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun show(json: String): JsonObject = call("code_story_show", mapOf("story" to Json.parseToJsonElement(json)))

    private fun get(id: String): JsonObject = call("code_story_get", mapOf("storyId" to JsonPrimitive(id)))

    private fun list(): List<JsonObject> = call("code_story_list").getValue("stories").jsonArray.map { it.jsonObject }

    private fun call(name: String, args: Map<String, JsonElement> = emptyMap()): JsonObject {
        val tool = CodeStoryToolset().asTools().single { it.descriptor.name == name }
        val arguments = JsonObject(args + ("projectPath" to JsonPrimitive(root.toString())))
        val info = McpCallInfo(1, ClientInfo("Code Story test", "1.0"), project, tool.descriptor, arguments,
            JsonObject(emptyMap()), McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.DONT_ASK))
        val result: McpToolCallResult = runBlocking { withContext(McpCallAdditionalDataElement(info)) { tool.call(arguments) } }
        assertFalse(result.content.joinToString { (it as McpToolCallResultContent.Text).text }, result.isError)
        return result.structuredContent!!
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10

        val CHARGE = "    fun charge(amount: Int) {\n        check(amount > 0)\n        gateway.send(amount)\n    }"
        val REFUND = "    fun refund(amount: Int) {\n        gateway.send(-amount)\n    }"
        val AUDIT = "    fun audit() {\n        log(\"audit\")\n    }"
        val BILLING = "package billing\n\nclass Billing(private val gateway: Gateway) {\n$CHARGE\n\n$REFUND\n\n$AUDIT\n}\n"
    }
}
