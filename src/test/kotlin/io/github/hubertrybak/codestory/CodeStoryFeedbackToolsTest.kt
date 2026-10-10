package io.github.hubertrybak.codestory

import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.MessageAuthor
import io.github.hubertrybak.codestory.core.ReplyOutcome
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.StoryThread
import io.github.hubertrybak.codestory.core.StoryThreads
import io.github.hubertrybak.codestory.core.ThreadKind
import io.github.hubertrybak.codestory.core.ThreadMessage
import io.github.hubertrybak.codestory.core.ThreadState
import io.github.hubertrybak.codestory.core.WarningCode
import io.github.hubertrybak.codestory.mcp.CodeStoryToolset
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** The reader feedback loop at the agent API seam: drafts and rounds in the IDE, and the feedback and reply tools. */
class CodeStoryFeedbackToolsTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private val service get() = CodeStoryService.getInstance(project)
    private val reader get() = project.service<StoryReaderState>()
    private val player get() = StoryPlayer.getInstance(project)
    private val notifications = mutableListOf<Notification>()

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/App.kt"), (1..80).joinToString("\n") { "line $it" })
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { notifications += notification }
        })
    }

    override fun tearDown() {
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            player.stop()
        } catch (e: Throwable) { addSuppressedException(e) }
        finally { super.tearDown() }
    }

    fun `test a submitted round is fetched answered and closed`() {
        val stored = show(story(step(id = quoted("entry")), step(start = "10", end = "12", id = quoted("core"), title = quoted("Core logic"))))
        draft(stored, "Why does it start here?", step = "entry")
        draft(stored, "What is missing?")
        assertFalse(Files.readString(stored.file).contains("What is missing?"))
        assertEquals(2, submit(stored))
        assertEmpty(reader.drafts(stored.key))
        val submitted = saved(stored)
        assertEquals(listOf(ThreadState.SUBMITTED, ThreadState.SUBMITTED), StoryThreads.all(submitted).map { it.second.state })
        assertEquals("What is missing?", submitted.threads.single().messages.single().text)
        assertEquals(setOf(1), StoryThreads.all(submitted).flatMap { it.second.messages }.map { it.round }.toSet())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("Waiting for your agent", notifications.last().title)
        assertTrue(notifications.last().content.contains("/code-story feedback"))
        assertEquals("2", list().single().string("pendingComments"))

        val fetched = feedback()
        val comments = fetched.getValue("comments").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Why does it start here?", "What is missing?").toSet(), comments.map { it.string("text") }.toSet())
        val onStep = comments.single { it.string("target") == "step" }
        assertEquals("entry", onStep.getValue("step").jsonObject.string("id"))
        assertEquals("ok", onStep.getValue("step").jsonObject.string("status"))
        assertEquals("1", onStep.string("round"))
        assertEquals(stored.id, onStep.string("storyId"))
        assertEquals("story", comments.single { it != onStep }.string("target"))
        assertTrue(fetched.string("next").contains("code_story_reply"))
        assertTrue(StoryThreads.all(saved(stored)).all { it.second.state == ThreadState.RECEIVED })
        // Received comments stay available until an agent replies.
        assertEquals(comments, feedback().getValue("comments").jsonArray.map { it.jsonObject })

        val report = reply(stored.id, comments.map { reply(it, if (it == onStep) "answered" else "declined", "The controller receives the request first.") })
        assertEmpty(report.getValue("errors").jsonArray)
        assertEquals(comments.map { it.string("threadId") }.toSet(), report.getValue("saved").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val answered = saved(stored)
        assertEquals(ThreadState.ANSWERED, answered.steps[0].threads.single().state)
        assertEquals(ThreadState.DECLINED, answered.threads.single().state)
        val answer = answered.steps[0].threads.single().messages.last()
        assertEquals(MessageAuthor.AGENT, answer.author)
        assertEquals(ReplyOutcome.ANSWERED, answer.outcome)
        assertEquals(1, answer.round)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("2 answers arrived", notifications.last().title)

        // The first reply wins.
        val again = reply(stored.id, listOf(reply(onStep, "answered", "Another answer.")))
        assertEmpty(again.getValue("saved").jsonArray)
        assertEquals(ErrorCode.ALREADY_ANSWERED.code, again.getValue("errors").jsonArray.single().jsonObject.string("code"))
        assertEquals(answered, saved(stored))
        assertEmpty(feedback().getValue("comments").jsonArray)
        assertEquals("0", list().single().string("pendingComments"))
    }

    fun `test withdraw takes back a comment until an agent fetches it`() {
        val stored = show(story(step(id = quoted("entry"))))
        draft(stored, "Why does it start here?", step = "entry")
        submit(stored)
        val threadId = saved(stored).steps[0].threads.single().id
        assertTrue(background { service.withdraw(stored, threadId) })
        assertEquals(StoryThreads.strip(saved(stored)), saved(stored))
        assertEquals(listOf(Triple("Why does it start here?", "entry", null)), reader.drafts(stored.key).map { Triple(it.text, it.step, it.thread) })

        submit(stored)
        feedback(stored.id)
        val received = saved(stored)
        val thread = received.steps[0].threads.single()
        assertEquals(ThreadState.RECEIVED, thread.state)
        assertFalse(background { service.withdraw(stored, thread.id) })
        assertEquals(received, saved(stored))
        assertEmpty(reader.drafts(stored.key))
    }

    fun `test replies are checked against the fetched comments`() {
        val stored = show(story(step(id = quoted("entry"))))
        draft(stored, "Why does it start here?", step = "entry")
        submit(stored)
        val threadId = saved(stored).steps[0].threads.single().id
        fun error(report: JsonObject) = report.getValue("errors").jsonArray.single().jsonObject
        val answer = buildJsonObject { put("threadId", threadId); put("round", 1); put("outcome", "answered"); put("text", "Because.") }
        assertEquals(ErrorCode.NOT_FETCHED.code, error(reply(stored.id, listOf(answer))).string("code"))
        assertEquals(ErrorCode.STORY_NOT_FOUND.code, error(reply("2000-01-01-missing", listOf(answer))).string("code"))
        assertEquals(ErrorCode.INVALID_STORY_ID.code, error(reply("../src/App.kt", listOf(answer))).string("code"))
        assertEquals(ErrorCode.MISSING_FIELD.code, error(reply(stored.id, emptyList())).string("code"))

        feedback()
        val done = JsonObject(answer + ("outcome" to JsonPrimitive("done")))
        val unknown = JsonObject(answer + ("threadId" to JsonPrimitive("missing")))
        val invalid = reply(stored.id, listOf(done, unknown))
        assertEquals(listOf(ErrorCode.INVALID_VALUE.code to 1, ErrorCode.THREAD_NOT_FOUND.code to 2),
            invalid.getValue("errors").jsonArray.map { it.jsonObject.string("code") to it.jsonObject.string("reply").toInt() })
        assertTrue(invalid.string("next").contains("Nothing was saved"))
        assertEquals(ThreadState.RECEIVED, saved(stored).steps[0].threads.single().state)

        // A change request, for example from a shared story, can be declined but not answered.
        val change = StoryThread("change", ThreadKind.CHANGE, ThreadState.RECEIVED, listOf(ThreadMessage(MessageAuthor.READER, 1, Instant.now(), "Rename it.")))
        Files.writeString(stored.file, StoryFormat.encode(saved(stored).let { it.copy(threads = it.threads + change) }, stored.id))
        val decline = buildJsonObject { put("threadId", "change"); put("round", 1); put("outcome", "declined"); put("text", "A rename needs its own ticket.") }
        val mixed = reply(stored.id, listOf(JsonObject(decline + ("outcome" to JsonPrimitive("answered"))), answer))
        assertEquals(ErrorCode.OUTCOME_MISMATCH.code, error(mixed).string("code"))
        assertEquals(listOf(threadId), mixed.getValue("saved").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(mixed.string("next").contains("rejected"))
        val long = JsonObject(decline + ("text" to JsonPrimitive("One. Two. Three. Four; don't.")))
        val declined = reply(stored.id, listOf(long))
        assertEquals(listOf("change"), declined.getValue("saved").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf(WarningCode.COMMENT_SENTENCES.code, WarningCode.SEMICOLON.code, WarningCode.CONTRACTION.code),
            declined.getValue("warnings").jsonArray.map { it.jsonObject.string("code") }.toSet())
        assertEquals(ThreadState.DECLINED, saved(stored).threads.single().state)
    }

    fun `test a reader reply opens the thread again in a new round`() {
        val stored = show(story(step(id = quoted("entry"))))
        draft(stored, "Why does it start here?", step = "entry")
        submit(stored)
        val first = feedback().getValue("comments").jsonArray.single().jsonObject
        reply(stored.id, listOf(reply(first, "answered", "The controller receives the request first.")))
        val threadId = first.string("threadId")
        draft(stored, "Why not in the service?", step = "entry", thread = threadId)
        submit(stored)
        val reopened = saved(stored).steps[0].threads.single()
        assertEquals(ThreadState.SUBMITTED, reopened.state)
        assertEquals(listOf(1, 1, 2), reopened.messages.map { it.round })

        val second = feedback().getValue("comments").jsonArray.single().jsonObject
        assertEquals("2", second.string("round"))
        assertEquals("Why not in the service?", second.string("text"))
        assertEquals(listOf("reader", "agent"), second.getValue("earlierMessages").jsonArray.map { it.jsonObject.string("author") })
        val late = reply(stored.id, listOf(reply(first, "answered", "A late answer.")))
        assertEquals(ErrorCode.ALREADY_ANSWERED.code, late.getValue("errors").jsonArray.single().jsonObject.string("code"))
        assertEquals(listOf(threadId), reply(stored.id, listOf(reply(second, "answered", "The service has no request data."))).getValue("saved").jsonArray.map { it.jsonPrimitive.content })

        assertTrue(background { service.resolve(stored, threadId) })
        assertEquals(ThreadState.RESOLVED, saved(stored).steps[0].threads.single().state)
        assertFalse(background { service.resolve(stored, threadId) })
    }

    fun `test a revision keeps threads and moves the threads of removed steps to the story`() {
        val stored = show(story(step(id = quoted("entry")), step(start = "10", end = "12", id = quoted("core"), title = quoted("Core logic"))))
        draft(stored, "About the entry?", step = "entry")
        draft(stored, "About the core?", step = "core")
        draft(stored, "About the story?")
        submit(stored)
        val before = saved(stored)
        val revised = callTool("code_story_show", mapOf(
            "story" to Json.parseToJsonElement(story(step(id = quoted("entry"), comment = quoted("The revised entry.")), step(start = "20", end = "22", id = quoted("new")))),
            "storyId" to JsonPrimitive(stored.id),
        )).structuredContent!!
        assertEquals("accepted", revised.string("status"))
        val after = saved(stored)
        assertEquals("The revised entry.", after.steps[0].comment)
        assertEquals(before.steps[0].threads, after.steps[0].threads)
        assertEmpty(after.steps[1].threads)
        assertEquals(listOf(null, "Core logic"), after.threads.map { it.removedStep })
        assertEquals(StoryThreads.all(before).map { it.second.id }.toSet(), StoryThreads.all(after).map { it.second.id }.toSet())
        val fetched = callTool("code_story_get", mapOf("storyId" to JsonPrimitive(stored.id))).structuredContent!!
        assertEquals(2, fetched.getValue("threads").jsonArray.size)
        assertEquals("Core logic", feedback().getValue("comments").jsonArray.map { it.jsonObject }.single { it.string("text") == "About the core?" }.string("removedStep"))
    }

    fun `test answers reach a playing story without moving it`() {
        val stored = show(story(step(id = quoted("entry")), step(start = "10", end = "12", id = quoted("core"))))
        PlatformTestUtil.waitWithEventsDispatching("the story does not play", { player.currentStory?.key == stored.key }, 10)
        player.next()
        val presentation = player.presentation!!
        draft(stored, "Why is this the core?", step = "core")
        submit(stored)
        val comment = feedback().getValue("comments").jsonArray.single().jsonObject
        reply(stored.id, listOf(reply(comment, "answered", "It charges the card.")))
        PlatformTestUtil.waitWithEventsDispatching("the playing story has no answer", {
            player.currentStory?.story?.steps?.get(1)?.threads?.singleOrNull()?.state == ThreadState.ANSWERED
        }, 10)
        assertEquals(1, player.stepIndex)
        assertSame(presentation, player.presentation)
        assertFalse(presentation.disposed)
        assertEquals(10, player.currentStory!!.story.steps[1].location!!.startLine)

        val arrived = notifications.single { it.title == "An answer arrived" }
        Notification.fire(arrived, arrived.actions.single { it.templateText == "Open" }, null)
        assertEquals(stored.key, service.lastShownStoryKey)
    }

    fun `test drafts stay in workspace storage and leave with a deleted story`() {
        val stored = show(story(step(id = quoted("entry"))))
        draft(stored, "Why does it start here?", step = "entry")
        draft(stored, "What is missing?")
        val serialized = XmlSerializer.serialize(reader.state)
        val restored = StoryReaderState().apply { loadState(XmlSerializer.deserialize(serialized, StoryReaderState.ReadStories::class.java)) }
        assertEquals(reader.drafts(stored.key), restored.drafts(stored.key))
        assertEquals(2, restored.drafts(stored.key).size)
        assertFalse(Files.readString(stored.file).contains("What is missing?"))
        background { service.delete(stored) }
        assertEmpty(reader.drafts(stored.key))
    }

    fun `test patches validate locations preserve threads and automatically describe the changes in replies`() {
        val stored = show(story(step(id = quoted("entry")), step(id = quoted("core"), start = "10", end = "12"), step(id = quoted("old"), start = "20", end = "22", title = quoted("Old step"))))
        draft(stored, "Explain this entry?", step = "entry")
        draft(stored, "Is this still needed?", step = "old")
        submit(stored)
        val comments = feedback(stored.id).getValue("comments").jsonArray.map { it.jsonObject }
        val before = saved(stored)
        val report = patch(stored, """[
            {"op":"update","stepId":"entry","title":"Request entry","comment":"The controller checks the request."},
            {"op":"update","stepId":"core","location":{"file":"src/App.kt","startLine":14,"endLine":16,"anchorText":"line 15"}},
            {"op":"insert","afterStepId":"core","step":{"title":"Validation","comment":"Invalid requests stop here."}},
            {"op":"remove","stepId":"old"}
        ]""")
        assertEquals(report.toString(), "accepted", report.string("status"))
        assertEquals(stored.id, report.string("storyId"))
        assertEquals(listOf(2), report.getValue("corrections").jsonArray.map { it.jsonObject.string("step").toInt() })
        assertTrue(report.string("next").contains("code_story_reply"))
        val revised = saved(stored)
        assertEquals(before.createdAt, revised.createdAt)
        assertEquals(before.git, revised.git)
        assertEquals("Request entry", revised.steps[0].title)
        assertEquals("The controller checks the request.", revised.steps[0].comment)
        assertEquals(15, revised.steps[1].location!!.startLine)
        assertEquals("line 15\nline 16\nline 17", revised.steps[1].location!!.rangeText)
        assertNotNull(revised.steps[2].id)
        assertNull(revised.steps[2].location)
        assertEquals(before.steps[0].threads.single().id, revised.steps[0].threads.single().id)
        assertEquals("Old step", revised.threads.single().removedStep)
        assertEquals(before.steps[2].threads.single().messages, revised.threads.single().messages)
        assertTrue(reply(stored.id, comments.map { reply(it, "answered", "The explanation now includes validation.") }).getValue("errors").jsonArray.isEmpty())
        val answered = saved(stored)
        for ((_, thread) in StoryThreads.all(answered)) {
            assertEquals("Steps 1 and 2 updated, step 3 added, step 3 removed", thread.messages.last().storyChanges)
            assertNull(thread.storyBaseline)
            assertNull(thread.pendingStoryChanges)
        }
        val fetched = callTool("code_story_get", mapOf("storyId" to JsonPrimitive(stored.id))).structuredContent!!
        assertTrue(fetched.string("next").contains("code_story_patch"))
        assertTrue(fetched.toString().contains("storyChanges"))
    }

    fun `test invalid patches reject all operations and leave the file unchanged`() {
        val stored = show(story(step(id = quoted("entry"))))
        draft(stored, "Why here?", step = "entry")
        submit(stored)
        feedback(stored.id)
        val before = Files.readString(stored.file)
        val invalid = listOf(
            "[]" to "missing-field",
            "[false]" to "wrong-type",
            """[{"op":"update","stepId":"entry"}]""" to "missing-field",
            """[{"op":"remove","stepId":"missing"}]""" to "step-not-found",
            """[{"op":"confirm","stepId":"entry"}]""" to "invalid-value",
            """[{"op":"update","stepId":"entry","comment":"Changed."},{"op":"remove","stepId":"missing"}]""" to "step-not-found",
            """[{"op":"remove","stepId":"entry"}]""" to "no-steps",
            """[{"op":"update","stepId":"entry","comment":" "}]""" to "empty-text",
            """[{"op":"update","stepId":"entry","location":{"file":"../secret.kt","startLine":1,"endLine":1,"anchorText":"secret"}}]""" to "parent-path",
            """[{"op":"update","stepId":"entry","location":{"file":"src/App.kt","startLine":1,"endLine":1}}]""" to "missing-field",
            """[{"op":"insert","afterStepId":"entry","step":{"id":"entry","title":"Duplicate","comment":"Duplicate step."}}]""" to "duplicate-step-id",
        )
        for ((operations, code) in invalid) {
            val report = patch(stored, operations)
            assertEquals(report.toString(), "rejected", report.string("status"))
            assertTrue(report.toString(), report.getValue("errors").jsonArray.any { it.jsonObject.string("code") == code })
            assertTrue(report.string("next").contains("code_story_patch"))
            assertEquals(operations, before, Files.readString(stored.file))
        }
    }

    fun `test patches and replacements keep a net change summary across calls and later rounds start clean`() {
        val stored = show(story(step(id = quoted("entry")), step(id = quoted("core"), start = "10", end = "12")))
        draft(stored, "What happens next?", step = "entry")
        submit(stored)
        val comment = feedback(stored.id).getValue("comments").jsonArray.single().jsonObject
        assertEquals("accepted", patch(stored, """[{"op":"update","stepId":"entry","comment":"First clarification."}]""").string("status"))
        // Each call reads the persisted bookkeeping, including a repeated feedback fetch.
        feedback(stored.id)
        val revised = callTool("code_story_show", mapOf(
            "storyId" to JsonPrimitive(stored.id),
            "story" to Json.parseToJsonElement(story(step(id = quoted("entry"), comment = quoted("The final clarification.")), step(id = quoted("core"), start = "10", end = "12", comment = quoted("The service charges the card.")))),
        )).structuredContent!!
        assertEquals("accepted", revised.string("status"))
        reply(stored.id, listOf(reply(comment, "answered", "The service charges the card.")))
        assertEquals("Steps 1 and 2 updated", saved(stored).steps[0].threads.single().messages.last().storyChanges)
        draft(stored, "And after that?", thread = comment.string("threadId"))
        submit(stored)
        val followup = feedback(stored.id).getValue("comments").jsonArray.single().jsonObject
        assertTrue(followup.getValue("earlierMessages").toString().contains("Steps 1 and 2 updated"))
        reply(stored.id, listOf(reply(followup, "answered", "It saves the result.")))
        assertNull(saved(stored).steps[0].threads.single().messages.last().storyChanges)
    }

    fun `test patch supports text only updates sequential targets warnings and shared files`() {
        val stored = show(story(step(id = quoted("entry"))))
        background { service.setShared(stored, true) }
        val shared = service.storyFolder().get(stored.id)!!
        val before = saved(shared)
        val report = patch(shared, """[
            {"op":"update","stepId":"entry","location":null,"title":"This title has far too many words for a title"},
            {"op":"insert","afterStepId":"entry","step":{"id":"extra","title":"More detail","comment":"Read this next."}},
            {"op":"update","stepId":"extra","comment":"This explanation follows the entry."}
        ]""")
        assertEquals("accepted", report.string("status"))
        assertFalse(report.getValue("warnings").jsonArray.isEmpty())
        assertTrue(report.string("file").contains("/shared/"))
        val after = saved(shared)
        assertNull(after.steps[0].location)
        assertEquals(listOf("entry", "extra"), after.steps.map { it.id })
        assertEquals("This explanation follows the entry.", after.steps[1].comment)
        assertEquals(before.createdAt, after.createdAt)
        assertFalse(Files.exists(stored.file))
    }

    fun `test unread answers persist per reader and follow threads when steps are removed`() {
        val stored = show(story(step(id = quoted("entry")), step(id = quoted("core"), start = "10", end = "12")))
        draft(stored, "Why here?", step = "entry")
        submit(stored)
        val comment = feedback(stored.id).getValue("comments").jsonArray.single().jsonObject
        reply(stored.id, listOf(reply(comment, "answered", "The controller starts here.")))
        val threads = saved(stored).steps[0].threads
        assertTrue(reader.hasUnread(stored, threads))
        val before = Files.readString(stored.file)
        assertTrue(reader.markRead(stored, threads))
        val restored = StoryReaderState().apply {
            loadState(XmlSerializer.deserialize(XmlSerializer.serialize(reader.state), StoryReaderState.ReadStories::class.java))
        }
        assertFalse(restored.hasUnread(stored, threads))
        assertTrue(StoryReaderState().hasUnread(stored, threads))
        assertEquals(before, Files.readString(stored.file))
        patch(stored, """[{"op":"remove","stepId":"entry"}]""")
        assertFalse(restored.hasUnread(stored, saved(stored).threads))
        draft(stored, "Why exactly?", thread = comment.string("threadId"))
        submit(stored)
        val followup = feedback(stored.id).getValue("comments").jsonArray.single().jsonObject
        reply(stored.id, listOf(reply(followup, "answered", "The route sends requests to the controller.")))
        assertTrue(restored.hasUnread(stored, saved(stored).threads))
    }

    private fun patch(stored: StoredStory, operations: String): JsonObject = callTool("code_story_patch", mapOf(
        "storyId" to JsonPrimitive(stored.id), "operations" to Json.parseToJsonElement(operations),
    )).structuredContent!!

    private fun show(json: String): StoredStory {
        val report = callTool("code_story_show", mapOf("story" to Json.parseToJsonElement(json))).structuredContent!!
        assertEquals(report.toString(), "accepted", report.string("status"))
        return service.stories().single { it.id == report.string("storyId") }
    }

    private fun draft(stored: StoredStory, text: String, step: String? = null, thread: String? = null) =
        reader.saveDraft(StoryDraft(story = stored.key, step = step, thread = thread, text = text))

    private fun submit(stored: StoredStory) = background { service.submitRound(stored) }

    private fun saved(stored: StoredStory): Story = (StoryFormat.decode(Files.readString(stored.file)) as StoryFormat.Decoded.Valid).story

    private fun <T> background(action: () -> T): T = runBlocking { withContext(Dispatchers.IO) { action() } }

    private fun feedback(storyId: String? = null) = callTool("code_story_feedback", storyId?.let { mapOf("storyId" to JsonPrimitive(it)) } ?: emptyMap()).structuredContent!!

    private fun reply(storyId: String, replies: List<JsonElement>) =
        callTool("code_story_reply", mapOf("storyId" to JsonPrimitive(storyId), "replies" to JsonArray(replies))).structuredContent!!

    private fun reply(comment: JsonObject, outcome: String, text: String) = buildJsonObject {
        put("threadId", comment.string("threadId"))
        put("round", comment.string("round").toInt())
        put("outcome", outcome)
        put("text", text)
    }

    private fun list() = callTool("code_story_list").structuredContent!!.getValue("stories").jsonArray.map { it.jsonObject }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun callTool(name: String, args: Map<String, JsonElement> = emptyMap()): McpToolCallResult {
        val tool = CodeStoryToolset().asTools().single { it.descriptor.name == name }
        val arguments = JsonObject(args + ("projectPath" to JsonPrimitive(root.toString())))
        val info = McpCallInfo(1, ClientInfo("Code Story test", "1.0"), project, tool.descriptor, arguments,
            JsonObject(emptyMap()), McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.DONT_ASK))
        val result = runBlocking { withContext(McpCallAdditionalDataElement(info)) { tool.call(arguments) } }
        val text = result.content.joinToString("\n") { (it as McpToolCallResultContent.Text).text }
        if (result.isError) throw McpExpectedError(text)
        return result
    }
}
