package io.github.hubertrybak.codestory

import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.ReflectionCallableMcpTool
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.hubertrybak.codestory.ui.StoryPlayer
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.StoryJson.withoutField
import io.github.hubertrybak.codestory.mcp.CodeStoryToolset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** IDE-level tests at the agent API seam: `code_story_show` as the IDE's MCP server calls it. */
class CodeStoryShowToolTest : HeavyPlatformTestCase() {
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        write("src/App.kt", (1..10).joinToString("\n", postfix = "\n") { "line $it" })
        write("src/Billing.kt", "class Billing {\n    fun charge() {}\n}\n")
        Files.createDirectories(root.resolve("docs"))
    }

    fun `test toolset is registered in the MCP server`() {
        assertTrue(McpToolset.EP.extensionList.any { it is CodeStoryToolset })
    }

    fun `test story parameter is a typed object`() {
        val schema = showTool().descriptor.inputSchema
        val story = schema.propertiesSchema.getValue("story").jsonObject

        assertEquals("object", story.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("title", "type", "summary", "request", "author", "baseRef", "steps"), story.getValue("properties").jsonObject.keys.toList())
        assertEquals(listOf("title", "type", "summary", "request", "author", "steps"), story.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val author = story.getValue("properties").jsonObject.getValue("author").jsonObject
        assertEquals(listOf("agent", "model"), author.getValue("properties").jsonObject.keys.toList())
        val step = story.getValue("properties").jsonObject.getValue("steps").jsonObject.getValue("items").jsonObject
        assertEquals(listOf("id", "title", "comment", "location"), step.getValue("properties").jsonObject.keys.toList())
        assertEquals(listOf("title", "comment"), step.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val location = step.getValue("properties").jsonObject.getValue("location").jsonObject
        assertEquals("integer", location.getValue("properties").jsonObject.getValue("startLine").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("file", "startLine", "endLine", "anchorText"), location.getValue("properties").jsonObject.keys.toList())
        assertEquals(listOf("file", "startLine", "endLine", "anchorText"), location.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val anchor = location.getValue("properties").jsonObject.getValue("anchorText").jsonObject
        assertTrue(anchor.toString(), anchor.getValue("description").jsonPrimitive.content.contains("startLine"))
        assertTrue("story" in schema.requiredProperties)
        assertTrue("projectPath" in schema.propertiesSchema)
        val file = location.getValue("properties").jsonObject.getValue("file").jsonObject
        assertTrue(file.toString(), file.getValue("description").jsonPrimitive.content.contains("repository root"))
    }

    fun `test valid story is saved and listed`() {
        val report = call(story(step(), step(file = quoted("src/Billing.kt"), start = "2", end = "2", anchor = quoted("fun charge() {}"), title = quoted("Charge"))))

        assertEquals("accepted", report.status)
        assertEmpty(report.errors)
        val id = report.storyId!!
        assertTrue(id, Regex("""\d{4}-\d{2}-\d{2}-payment-flow""").matches(id))
        assertEquals(".codestory/local/$id.story.json", report.file)

        val file = root.resolve(report.file!!)
        val saved = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(listOf("schemaVersion", "id", "title", "type", "summary", "request", "author", "createdAt", "git", "steps"), saved.keys.toList())
        assertEquals(1, saved.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals(id, saved.getValue("id").jsonPrimitive.content)
        assertEquals("src/Billing.kt", saved.getValue("steps").jsonArray[1].jsonObject.getValue("location").jsonObject.getValue("file").jsonPrimitive.content)
        assertTrue(root.resolve(".codestory/local/.gitignore").exists())

        val stories = CodeStoryService.getInstance(project).stories()
        assertEquals(listOf(id), stories.map { it.id })
        assertEquals("Payment flow", stories.single().story.title)
        assertEquals(listOf("Entry point", "Charge"), stories.single().story.steps.map { it.title })
        assertEquals(id, CodeStoryService.getInstance(project).lastShownStoryId)
    }

    fun `test paths are saved relative to the repository root`() {
        val report = call(story(step(file = quoted(".\\src\\App.kt"))))

        assertEquals("accepted", report.status)
        val saved = StoryFormat.decode(Files.readString(root.resolve(report.file!!))) as StoryFormat.Decoded.Valid
        assertEquals("src/App.kt", saved.story.steps.single().location!!.file)
    }

    fun `test accepted MCP story auto plays without a loaded tool window`() {
        val first = call(story(step(), step(file = quoted("src/Billing.kt"), start = "2", end = "2", anchor = quoted("fun charge() {}"))))
        val player = StoryPlayer.getInstance(project)
        PlatformTestUtil.waitWithEventsDispatching("MCP story did not start", { player.currentStory?.id == first.storyId }, 10)
        assertEquals(0, player.stepIndex)
        assertEquals(CodeStoryService.getInstance(project).stories().single().story, player.currentStory!!.story)
        assertEquals("App.kt", player.presentation!!.editor.virtualFile!!.name)
        player.next()
        val previous = player.presentation!!
        val second = call(story(step(), title = quoted("New story")))
        PlatformTestUtil.waitWithEventsDispatching("new MCP story did not replace playback", { player.currentStory?.id == second.storyId }, 10)
        assertEquals(0, player.stepIndex)
        assertTrue(previous.disposed)
        assertFalse(previous.range.isValid)
    }

    fun `test checks lines against the text that the IDE shows`() {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/App.kt"))!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "line 11\nline 12\n") }
        try {
            assertEquals("accepted", call(story(step(start = "11", end = "12"))).status)
        } finally {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().reloadFromDisk(document) }
        }
    }

    fun `test each error rejects the story and saves nothing`() {
        val cases = listOf(
            story(step(), title = quoted(" ")) to (null to ErrorCode.EMPTY_TEXT),
            """{"title": "Payment flow"}""" to (null to ErrorCode.MISSING_FIELD),
            story() to (null to ErrorCode.NO_STEPS),
            story(*Array(16) { step() }) to (null to ErrorCode.TOO_MANY_STEPS),
            story(step(), step(location = "{}")) to (2 to ErrorCode.MISSING_FIELD),
            withoutField(story(step()), "type") to (null to ErrorCode.MISSING_FIELD),
            story(step(), type = quoted("tour")) to (null to ErrorCode.INVALID_VALUE),
            story(step(), summary = quoted(" ")) to (null to ErrorCode.EMPTY_TEXT),
            story(step(), author = "{}") to (null to ErrorCode.MISSING_FIELD),
            story(step(id = quoted("same")), step(id = quoted("same"))) to (2 to ErrorCode.DUPLICATE_STEP_ID),
            story(step(start = "\"2\"")) to (1 to ErrorCode.WRONG_TYPE),
            story(step(file = quoted(root.resolve("src/App.kt").toString()))) to (1 to ErrorCode.ABSOLUTE_PATH),
            story(step(file = quoted("../outside.kt"))) to (1 to ErrorCode.PARENT_PATH),
            story(step(file = quoted("src/Missing.kt"))) to (1 to ErrorCode.FILE_NOT_FOUND),
            story(step(file = quoted("docs"))) to (1 to ErrorCode.NOT_A_FILE),
            story(step(start = "0")) to (1 to ErrorCode.INVALID_LINE),
            story(step(start = "4", end = "2")) to (1 to ErrorCode.REVERSED_RANGE),
            story(step(start = "9", end = "11")) to (1 to ErrorCode.LINES_OUTSIDE_FILE),
            story(step(comment = quoted("word ".repeat(60_000)))) to (null to ErrorCode.STORY_TOO_LARGE),
        )
        for ((json, expected) in cases) {
            val report = call(json)
            assertEquals(json.take(120), "rejected", report.status)
            assertNull(report.storyId)
            assertNull(report.file)
            assertTrue("$expected in ${report.errors}", expected.first to expected.second.code in report.errors.map { it.step to it.code })
            assertTrue(report.errors.all { it.message.isNotBlank() && it.hint.isNotBlank() })
            assertTrue(report.next.contains("code_story_show"))
        }
        assertFalse(root.resolve(".codestory").exists())
        assertEmpty(CodeStoryService.getInstance(project).stories())
    }

    fun `test symbolic link out of the project is rejected`() {
        // Not createTempDirectory(): the test project folder holds those directories.
        val outsideDir = Files.createTempDirectory("code-story-outside")
        try {
            val outside = Files.writeString(outsideDir.resolve("secret.kt"), "secret\n")
            Files.createSymbolicLink(root.resolve("link.kt"), outside)

            val report = call(story(step(file = quoted("link.kt"), start = "1", end = "1")))

            assertEquals("rejected", report.status)
            assertEquals(listOf(1 to ErrorCode.OUTSIDE_PROJECT.code), report.errors.map { it.step to it.code })
            assertFalse(root.resolve(".codestory").exists())
        } finally {
            outsideDir.toFile().deleteRecursively()
        }
    }

    fun `test a story with the same title gets a new file`() {
        val first = call(story(step())).storyId!!
        val second = call(story(step())).storyId!!

        assertEquals("$first-2", second)
        assertEquals(
            listOf(".gitignore", "$first-2.story.json", "$first.story.json"),
            root.resolve(".codestory/local").listDirectoryEntries().map { it.name }.sorted(),
        )
    }

    fun `test text-only steps are saved and stable IDs are assigned or preserved`() {
        val report = call(story(
            step(location = null, id = quoted("overview")),
            step(),
            step(location = "null"),
        ))
        assertEquals("accepted", report.status)
        val first = CodeStoryService.getInstance(project).stories().single().story.steps
        assertEquals("overview", first.first().id)
        assertNull(first.first().location)
        assertNull(first.last().location)
        assertEquals(3, first.map { it.id }.toSet().size)
        assertTrue(first.all { !it.id.isNullOrBlank() })
        val reread = CodeStoryService.getInstance(project).stories().single().story.steps
        assertEquals(first, reread)
    }

    fun `test plugin supplies creation time and unavailable Git instead of trusting agent metadata`() {
        val before = Instant.now()
        val input = JsonObject(Json.parseToJsonElement(story(step())).jsonObject + mapOf(
            "createdAt" to JsonPrimitive("2000-01-01T00:00:00Z"),
            "git" to JsonObject(mapOf("branch" to JsonPrimitive("invented"), "commit" to JsonPrimitive("invented"))),
        ))
        val report = call(input.toString())
        val after = Instant.now()
        assertEquals("accepted", report.status)
        val saved = CodeStoryService.getInstance(project).stories().single().story
        val createdAt = saved.createdAt!!
        assertTrue(createdAt.toString(), createdAt >= before && createdAt <= after)
        assertNull(saved.git.branch)
        assertNull(saved.git.commit)
        val json = Json.parseToJsonElement(Files.readString(root.resolve(report.file!!))).jsonObject
        assertEquals(JsonNull, json.getValue("git").jsonObject.getValue("branch"))
        assertEquals(JsonNull, json.getValue("git").jsonObject.getValue("commit"))
    }

    fun `test plugin captures Git branch and commit at creation`() {
        val git = GitFixture(root)
        git.init()
        val commit = git.command("rev-parse", "HEAD")
        val report = call(story(step()))
        assertEquals("accepted", report.status)
        git.command("checkout", "-b", "other-branch")
        val saved = CodeStoryService.getInstance(project).stories().single().story
        assertEquals("story-branch", saved.git.branch)
        assertEquals(commit, saved.git.commit)
    }

    fun `test detached HEAD has a commit and no branch`() {
        val git = GitFixture(root)
        git.init()
        val commit = git.command("rev-parse", "HEAD")
        git.command("checkout", "--detach", commit)
        assertEquals("accepted", call(story(step())).status)
        val saved = CodeStoryService.getInstance(project).stories().single().story
        assertNull(saved.git.branch)
        assertEquals(commit, saved.git.commit)
    }

    fun `test unborn Git branch has no commit`() {
        GitFixture(root).init(commit = false)
        assertEquals("accepted", call(story(step())).status)
        val saved = CodeStoryService.getInstance(project).stories().single().story
        assertEquals("story-branch", saved.git.branch)
        assertNull(saved.git.commit)
    }

    fun `test stories still save when optional Git integration is absent`() {
        GitFixture(root).init()
        ExtensionTestUtil.maskExtensions(StoryGitMetadata.EP, emptyList(), testRootDisposable)
        assertEquals("accepted", call(story(step())).status)
        val saved = CodeStoryService.getInstance(project).stories().single().story
        assertNull(saved.git.branch)
        assertNull(saved.git.commit)
    }

    fun `test without a project path the plugin does not guess`() {
        // The MCP server turns McpExpectedError into an error result for the agent.
        val error = runCatching { callTool(story(step()), project = null) }.exceptionOrNull()
        assertInstanceOf(error, McpExpectedError::class.java)
        error as McpExpectedError

        assertTrue(error.mcpErrorText, error.mcpErrorText.contains("projectPath"))
        assertFalse(root.resolve(".codestory").exists())
    }

    private class Report(val status: String, val storyId: String?, val file: String?, val errors: List<Item>, val next: String)

    private class Item(val step: Int?, val code: String, val message: String, val hint: String)

    /** Calls the tool and reads its structured result, which agents get as JSON. */
    private fun call(story: String): Report {
        val result = callTool(story, project)
        assertFalse(text(result), result.isError)
        val json = result.structuredContent!!
        assertEquals(json, Json.parseToJsonElement(text(result)))
        fun JsonObject.string(name: String) = (getValue(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Report(
            status = json.string("status")!!,
            storyId = json.string("storyId"),
            file = json.string("file"),
            errors = (json.getValue("errors") as JsonArray).map { it.jsonObject }.map {
                Item(it.getValue("step").jsonPrimitive.content.toIntOrNull(), it.string("code")!!, it.string("message")!!, it.string("hint")!!)
            },
            next = json.string("next")!!,
        )
    }

    private fun callTool(story: String, project: com.intellij.openapi.project.Project?): McpToolCallResult {
        val tool = showTool()
        val arguments = JsonObject(mapOf("story" to Json.parseToJsonElement(story), "projectPath" to JsonPrimitive(root.toString())))
        val callInfo = McpCallInfo(
            callId = 1,
            clientInfo = ClientInfo("Code Story test", "1.0"),
            project = project,
            mcpToolDescriptor = tool.descriptor,
            rawArguments = arguments,
            meta = JsonObject(emptyMap()),
            mcpSessionOptions = McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.DONT_ASK),
        )
        return runBlocking { withContext(McpCallAdditionalDataElement(callInfo)) { tool.call(arguments) } }
    }

    private fun showTool(): ReflectionCallableMcpTool = CodeStoryToolset().asTools().single { it.descriptor.name == "code_story_show" }

    private fun text(result: McpToolCallResult): String = result.content.joinToString("\n") { (it as McpToolCallResultContent.Text).text }

    private fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
