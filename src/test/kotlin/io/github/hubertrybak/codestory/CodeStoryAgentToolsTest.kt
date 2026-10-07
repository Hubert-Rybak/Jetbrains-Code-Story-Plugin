package io.github.hubertrybak.codestory

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.ReflectionCallableMcpTool
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.StoryValidator
import io.github.hubertrybak.codestory.core.StoryWritingChecks
import io.github.hubertrybak.codestory.core.ValidationResult
import io.github.hubertrybak.codestory.core.WarningCode
import io.github.hubertrybak.codestory.mcp.CodeStoryToolset
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JCheckBox
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/** Calls the same reflection tools and JSON arguments that the IDE's MCP server uses. */
class CodeStoryAgentToolsTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private val player get() = StoryPlayer.getInstance(project)

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/App.kt"), (1..80).joinToString("\n") { "line $it" })
    }

    override fun tearDown() {
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            player.stop()
        } catch (e: Throwable) { addSuppressedException(e) }
        finally { super.tearDown() }
    }

    fun `test all agent tools expose their typed parameters`() {
        assertEquals(setOf("code_story_guide", "code_story_list", "code_story_get", "code_story_show"),
            CodeStoryToolset().asTools().map { it.descriptor.name }.toSet())
        val show = tool("code_story_show").descriptor.inputSchema
        assertTrue("storyId" in show.propertiesSchema)
        assertFalse("storyId" in show.requiredProperties)
        assertTrue("storyId" in tool("code_story_get").descriptor.inputSchema.requiredProperties)
    }

    fun `test guide comes from this plugin version and includes a valid example`() {
        val result = call("code_story_guide", project = null)
        val content = text(result)
        val guide = (runCatching { Json.parseToJsonElement(content) }.getOrNull() as? JsonPrimitive)?.content ?: content
        val version = PluginManagerCore.getPlugin(PluginId.getId("io.github.hubertrybak.codestory"))!!.version
        val bundled = javaClass.getResourceAsStream("/code-story/guide.md")!!.bufferedReader().use { it.readText() }
        assertEquals("# Code Story writing guide\n\nInstalled plugin version: $version\n\n$bundled", guide)
        for (rule in listOf("ASD-STE100", "20 words", "25 words", "60 words", "30 lines", "code_story_get", "Fix all reported errors and writing warnings once",
            "anchorText", "within 20 lines", "corrections", "code-changed", "lost")) {
            assertTrue(rule, guide.contains(rule))
        }
        val example = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).find(guide)!!.groupValues[1]
        val checked = StoryValidator(null).validate(Json.parseToJsonElement(example))
        assertInstanceOf(checked, ValidationResult.Valid::class.java)
        assertEmpty(StoryWritingChecks().check((checked as ValidationResult.Valid).story))
    }

    fun `test list and get read local and shared stories with creation metadata`() {
        assertEmpty(list())
        val git = GitFixture(root)
        git.init()
        val first = show(story(step()))
        val second = show(story(step(location = null), title = quoted("Shared story"), type = quoted("review")))
        val shared = root.resolve(".codestory/shared")
        Files.createDirectories(shared)
        Files.move(root.resolve(second.string("file")), shared.resolve(second.string("storyId") + ".story.json"))

        val listed = list()
        assertEquals(listOf(second.string("storyId"), first.string("storyId")), listed.map { it.string("id") })
        for (entry in listed) {
            assertEquals("story-branch", entry.string("branch"))
            assertEquals("ok", entry.string("status"))
            for (count in listOf("movedSteps", "changedSteps", "lostSteps")) assertEquals(count, "0", entry.getValue(count).jsonPrimitive.content)
            val saved = get(entry.string("id"))
            assertEquals(saved.string("title"), entry.string("title"))
            assertEquals(saved.string("type"), entry.string("type"))
            assertEquals(saved.string("createdAt").take(10), entry.string("date"))
            assertEquals(git.command("rev-parse", "HEAD"), saved.getValue("git").jsonObject.string("commit"))
            assertTrue(saved.getValue("steps").jsonArray.single().jsonObject.string("id").isNotBlank())
            assertEquals("Test Agent", saved.getValue("author").jsonObject.string("agent"))
            assertEquals("Explain the payment flow", saved.string("request"))
            assertEquals("The controller charges the card.", saved.string("summary"))
        }
        assertEquals(listOf("shared", "local"), listed.map { it.string("folder") })
        assertEquals(Json.parseToJsonElement(Files.readString(shared.resolve(second.string("storyId") + ".story.json"))), get(second.string("storyId")))
    }

    fun `test list status uses current unsaved code without showing writing warnings`() {
        val report = show(story(step(start = "79", end = "80", comment = quoted("Don't use this; check it."))))
        val id = report.string("storyId")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/App.kt"))!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("one line\n") }
        try {
            val entry = list().single()
            assertEquals("lost", entry.string("status"))
            assertEquals("1", entry.getValue("lostSteps").jsonPrimitive.content)
            assertFalse(entry.containsKey("warnings"))
            assertEquals(id, get(id).string("id"))
        } finally {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().reloadFromDisk(document) }
        }
    }

    fun `test replacement keeps file ID metadata step IDs and playing position`() {
        GitFixture(root).init()
        val first = show(story(step(id = quoted("entry")), step(start = "10", end = "12", id = quoted("core"))))
        val id = first.string("storyId")
        awaitTitle("Payment flow")
        player.next()
        val previous = player.presentation!!
        val before = get(id)
        val replacement = show(story(step(id = quoted("entry")), step(start = "20", end = "22", id = quoted("core"), comment = quoted("The revised code saves the result.")), title = quoted("Revised flow")), id)
        awaitTitle("Revised flow")

        assertEquals(id, replacement.string("storyId"))
        assertEquals(first.string("file"), replacement.string("file"))
        assertEquals(1, player.stepIndex)
        assertEquals(20, player.currentStory!!.story.steps[1].location!!.startLine)
        assertTrue(previous.disposed)
        assertFalse(previous.range.isValid)
        assertTrue(player.wasVisited(id, 0))
        val after = get(id)
        assertEquals(before.getValue("createdAt"), after.getValue("createdAt"))
        assertEquals(before.getValue("git"), after.getValue("git"))
        assertEquals(listOf("entry", "core"), after.getValue("steps").jsonArray.map { it.jsonObject.string("id") })
        assertEquals(2, root.resolve(".codestory/local").listDirectoryEntries().size)
    }

    fun `test shorter replacement stays at its last step`() {
        val first = show(story(step(), step(start = "10", end = "12"), step(start = "20", end = "22")))
        awaitTitle("Payment flow")
        player.next()
        player.next()
        assertEquals(2, player.stepIndex)
        show(story(step(), step(start = "30", end = "32"), title = quoted("Shorter flow")), first.string("storyId"))
        awaitTitle("Shorter flow")
        assertEquals(1, player.stepIndex)
        assertTrue(player.isLastStep)
        assertEquals(30, player.currentStory!!.story.steps[1].location!!.startLine)
    }

    fun `test shared replacement stays shared and text-only reload disposes stale presentation`() {
        val first = show(story(step(), step(start = "10", end = "12")))
        val id = first.string("storyId")
        awaitTitle("Payment flow")
        player.next()
        val old = player.presentation!!
        val shared = Files.createDirectories(root.resolve(".codestory/shared")).resolve("$id.story.json")
        Files.move(root.resolve(first.string("file")), shared)
        val report = show(story(step(), step(location = null), title = quoted("Shared revision")), id)
        awaitTitle("Shared revision")
        assertEquals(".codestory/shared/$id.story.json", report.string("file"))
        assertEquals(1, player.stepIndex)
        assertTrue(old.disposed)
        assertNull(player.presentation)
        assertEquals("shared", list().single().string("folder"))
        assertFalse(root.resolve(first.string("file")).exists())
        assertEquals("Shared revision", get(id).string("title"))
    }

    fun `test rejected replacement leaves the saved story and player unchanged`() {
        val first = show(story(step()))
        awaitTitle("Payment flow")
        val presentation = player.presentation
        val file = root.resolve(first.string("file"))
        val before = Files.readString(file)
        val report = show(story(step(file = quoted("src/Missing.kt"))), first.string("storyId"))
        assertEquals("rejected", report.string("status"))
        assertEquals(before, Files.readString(file))
        assertSame(presentation, player.presentation)
        assertEquals(0, player.stepIndex)
        assertEquals(2, file.parent.listDirectoryEntries().size)
    }

    fun `test unknown or unsafe IDs never create or overwrite a story`() {
        val cases = listOf("2000-01-01-missing" to ErrorCode.STORY_NOT_FOUND,
            "../src/App.kt" to ErrorCode.INVALID_STORY_ID, "/tmp/story" to ErrorCode.INVALID_STORY_ID,
            "2000-01-01-story/../../src/App.kt" to ErrorCode.INVALID_STORY_ID)
        for ((id, code) in cases) {
            val report = show(story(step()), id)
            assertEquals("rejected", report.string("status"))
            assertEquals(code.code, report.getValue("errors").jsonArray.single().jsonObject.string("code"))
            assertInstanceOf(runCatching { get(id) }.exceptionOrNull(), McpExpectedError::class.java)
        }
        assertFalse(root.resolve(".codestory").exists())
        assertEquals(80, Files.readAllLines(root.resolve("src/App.kt")).size)
    }

    fun `test writing and range warnings are accepted and only returned to the agent`() {
        val report = show(story(step(start = "1", end = "31", title = quoted("one two three four five six seven eight nine"), comment = quoted("Don't use this; check it.")), summary = quoted("One. Two. Three. Four.")))
        assertEquals("accepted", report.string("status"))
        assertEmpty(report.getValue("errors").jsonArray)
        val warnings = report.getValue("warnings").jsonArray.map { it.jsonObject }
        assertEquals(setOf(WarningCode.RANGE_TOO_LONG.code, WarningCode.TITLE_TOO_LONG.code,
            WarningCode.SUMMARY_SENTENCES.code, WarningCode.SEMICOLON.code, WarningCode.CONTRACTION.code), warnings.map { it.string("code") }.toSet())
        assertTrue(warnings.all { it.string("message").isNotBlank() && it.string("hint").isNotBlank() })
        val id = report.string("storyId")
        assertTrue(report.string("next").contains(id))
        assertTrue(report.string("next").contains("once"))
        awaitTitle("Payment flow")
        assertFalse(get(id).containsKey("warnings"))
        assertFalse(Files.readString(root.resolve(report.string("file"))).contains("warnings"))
        val panel = StoryListPanel(project)
        try {
            panel.showStories(CodeStoryService.getInstance(project).stories(), id)
            assertTrue(panel.details.displayedText.contains("Don't use this; check it."))
            assertTrue(warnings.none { panel.details.displayedText.contains(it.string("message")) })
        } finally { Disposer.dispose(panel) }
        val revised = show(story(step(start = "1", end = "30", title = quoted("Check this flow"), comment = quoted("Check the request."))), id)
        assertEmpty(revised.getValue("warnings").jsonArray)
        assertEquals(id, revised.string("storyId"))
        assertEquals(2, root.resolve(".codestory/local").listDirectoryEntries().size)
    }

    fun `test optional hints are disabled by default and enabled through the settings page`() {
        val settings = CodeStorySettings.getInstance()
        val original = settings.state
        val configurable = CodeStoryConfigurable()
        try {
            settings.loadState(CodeStorySettings.Options())
            val input = story(step(comment = quoted("The value is stored while using a mapping.")))
            assertEmpty(show(input).getValue("warnings").jsonArray)
            assertFalse(settings.state.optionalStyleHints)
            fun checkboxes(container: Container): List<JCheckBox> = container.components.flatMap {
                if (it is JCheckBox) listOf(it) else if (it is Container) checkboxes(it) else emptyList()
            }
            val checkbox = checkboxes(configurable.createComponent() as Container).single { it.text.contains("passive voice") }
            assertFalse(checkbox.isSelected)
            checkbox.isSelected = true
            assertTrue(configurable.isModified)
            configurable.apply()
            assertFalse(configurable.isModified)
            val serialized = XmlSerializer.serialize(settings.state)
            settings.loadState(XmlSerializer.deserialize(serialized, CodeStorySettings.Options::class.java))
            assertTrue(settings.state.optionalStyleHints)
            assertEquals(setOf(WarningCode.PASSIVE_VOICE.code, WarningCode.ING_WORD.code), show(input).getValue("warnings").jsonArray.map { it.jsonObject.string("code") }.toSet())
            assertEmpty(show(story(step(comment = quoted("The string contains a mapping.")))).getValue("warnings").jsonArray)
        } finally { configurable.disposeUIResources(); settings.loadState(original) }
    }

    fun `test project tools require an open project`() {
        for (name in listOf("code_story_list", "code_story_get", "code_story_show")) {
            val args = when (name) {
                "code_story_get" -> mapOf("storyId" to JsonPrimitive("2000-01-01-story"))
                "code_story_show" -> mapOf("story" to Json.parseToJsonElement(story(step())))
                else -> emptyMap()
            }
            val error = runCatching { call(name, args, project = null) }.exceptionOrNull()
            assertInstanceOf(error, McpExpectedError::class.java)
            assertTrue((error as McpExpectedError).mcpErrorText.contains("projectPath"))
        }
    }

    fun `test a symlink story cannot be listed fetched or replaced`() {
        val first = show(story(step()))
        val id = first.string("storyId")
        val file = root.resolve(first.string("file"))
        val outside = Files.createTempFile("code-story-outside-story", ".story.json")
        val before = Files.readString(file)
        try {
            Files.writeString(outside, before)
            Files.delete(file)
            Files.createSymbolicLink(file, outside)
            assertEmpty(list())
            assertInstanceOf(runCatching { get(id) }.exceptionOrNull(), McpExpectedError::class.java)
            val report = show(story(step(), title = quoted("Outside revision")), id)
            assertEquals("rejected", report.string("status"))
            assertEquals(ErrorCode.STORY_NOT_FOUND.code, report.getValue("errors").jsonArray.single().jsonObject.string("code"))
            assertEquals(before, Files.readString(outside))
        } finally { Files.deleteIfExists(outside) }
    }

    private fun awaitTitle(title: String) = PlatformTestUtil.waitWithEventsDispatching("Story did not reload: $title",
        { player.currentStory?.story?.title == title }, 10)

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun show(json: String, id: String? = null): JsonObject = call("code_story_show",
        mapOf("story" to Json.parseToJsonElement(json)) + if (id == null) emptyMap() else mapOf("storyId" to JsonPrimitive(id))).structuredContent!!

    private fun get(id: String) = call("code_story_get", mapOf("storyId" to JsonPrimitive(id))).structuredContent!!

    private fun list(): List<JsonObject> {
        val result = call("code_story_list")
        val json = result.structuredContent!!
        assertEquals(json, Json.parseToJsonElement(text(result)))
        assertTrue(json.string("next").contains("code_story_get"))
        return json.getValue("stories").jsonArray.map { it.jsonObject }
    }

    private fun call(name: String, args: Map<String, JsonElement> = emptyMap(), project: Project? = this.project): McpToolCallResult {
        val tool = tool(name)
        val arguments = JsonObject(args + ("projectPath" to JsonPrimitive(root.toString())))
        val info = McpCallInfo(1, ClientInfo("Code Story test", "1.0"), project, tool.descriptor, arguments,
            JsonObject(emptyMap()), McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.DONT_ASK))
        val result = runBlocking { withContext(McpCallAdditionalDataElement(info)) { tool.call(arguments) } }
        assertFalse(text(result), result.isError)
        return result
    }

    private fun tool(name: String): ReflectionCallableMcpTool = CodeStoryToolset().asTools().single { it.descriptor.name == name }
    private fun text(result: McpToolCallResult) = result.content.joinToString("\n") { (it as McpToolCallResultContent.Text).text }
}
