package io.github.hubertrybak.codestory

import com.intellij.openapi.components.service
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.ShowStatus
import io.github.hubertrybak.codestory.core.Step
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoryAuthor
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryType
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** A shared story can arrive through Git with the same ID as an existing local story. */
class StoryIdentityCollisionTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private lateinit var folder: StoryFolder
    private lateinit var local: StoredStory
    private lateinit var shared: StoredStory
    private val service get() = CodeStoryService.getInstance(project)
    private val reader get() = project.service<StoryReaderState>()

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        folder = StoryFolder(root)
        val story = Story(
            "Collision story", StoryType.EXPLORATION, "Read the overview.", "Explain this story.",
            StoryAuthor("Test Agent", "test-model"), listOf(Step("Overview", "Read this first.", id = "entry")),
            delivery = StoryFormat.MCP_DELIVERY,
        )
        val saved = folder.save(story, Instant.parse("2026-10-10T12:00:00Z")) as StoryFolder.SaveResult.Saved
        local = folder.get(saved.id)!!
        Files.createDirectories(folder.sharedDir)
        val sharedFile = Files.copy(local.file, folder.sharedDir.resolve(local.file.fileName))
        shared = folder.list().single { it.file == sharedFile }
    }

    override fun tearDown() {
        try {
            StoryFileWatcher.getInstance(project).dispose()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            StoryPlayer.getInstance(project).stop()
        } catch (e: Throwable) { addSuppressedException(e) }
        finally { super.tearDown() }
    }

    fun `test submitting a shared draft leaves the matching local story and draft untouched`() {
        reader.saveDraft(StoryDraft(story = local.key, text = "Question about the local story"))
        reader.saveDraft(StoryDraft(story = shared.key, text = "Question about the shared story"))
        val localBefore = Files.readString(local.file)

        assertEquals(1, background { service.submitRound(shared) })

        assertEquals(localBefore, Files.readString(local.file))
        assertEquals("Question about the shared story", folder.get(shared)!!.story.threads.single().messages.single().text)
        assertEquals(listOf("Question about the local story"), reader.drafts(local.key).map { it.text })
        assertEmpty(reader.drafts(shared.key))
        val sharedAfter = Files.readString(shared.file)

        assertEquals(1, background { service.submitRound(local) })
        assertEquals("Question about the local story", folder.get(local)!!.story.threads.single().messages.single().text)
        assertEquals(sharedAfter, Files.readString(shared.file))
        assertEmpty(reader.drafts(local.key))
    }

    fun `test agent mutations report an ambiguous ID and preserve both files`() {
        val before = mapOf(local.file to Files.readString(local.file), shared.file to Files.readString(shared.file))
        val replacement = Json.parseToJsonElement(StoryFormat.encode(local.story.copy(title = "Replacement"), local.id))
        val operation = Json.parseToJsonElement("""{"op":"update","stepId":"entry","comment":"Revised explanation."}""")
        val answer = Json.parseToJsonElement("""{"threadId":"thread","round":1,"outcome":"answered","text":"An answer."}""")

        for (repositoryPath in listOf(null, root.toString())) {
            val shown = background { service.show(replacement, local.id, repositoryPath) }
            assertEquals(ShowStatus.REJECTED, shown.status)
            assertEquals(ErrorCode.AMBIGUOUS_STORY_ID.code, shown.errors.single().code)
            val patched = background { service.patch(local.id, listOf(operation), repositoryPath) }
            assertEquals(ShowStatus.REJECTED, patched.status)
            assertEquals(ErrorCode.AMBIGUOUS_STORY_ID.code, patched.errors.single().code)
            val replied = background { service.reply(local.id, listOf(answer), repositoryPath) }
            assertEmpty(replied.saved)
            assertEquals(ErrorCode.AMBIGUOUS_STORY_ID.code, replied.errors.single().code)
        }

        before.forEach { (file, text) -> assertEquals(text, Files.readString(file)) }
    }

    fun `test get feedback and listen reject ambiguous IDs without fetching comments`() {
        reader.saveDraft(StoryDraft(story = shared.key, text = "A pending shared question"))
        background { service.submitRound(shared) }
        val before = mapOf(local.file to Files.readString(local.file), shared.file to Files.readString(shared.file))
        val settings = CodeStorySettings.getInstance().state
        val automaticFeedback = settings.automaticFeedback
        try {
            settings.automaticFeedback = true
            for (repositoryPath in listOf(null, root.toString())) {
                assertAmbiguous { service.get(local.id, repositoryPath) }
                assertAmbiguous { service.feedback(local.id, repositoryPath) }
                assertAmbiguous { service.listenForFeedback(local.id, repositoryPath, waitSeconds = 1) }
                assertAmbiguous { service.feedback(null, repositoryPath) }
                assertAmbiguous { service.listenForFeedback(null, repositoryPath, waitSeconds = 1) }
            }
        } finally {
            settings.automaticFeedback = automaticFeedback
        }
        before.forEach { (file, text) -> assertEquals(text, Files.readString(file)) }
    }

    private fun assertAmbiguous(action: suspend () -> Any?) {
        val failure = runCatching { background(action) }.exceptionOrNull()
        assertTrue("Expected an IOException for the duplicate ID, got $failure", failure is IOException)
        assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains("local") && failure.message.orEmpty().contains("shared"))
    }

    /** Pump EDT events while the service runs background work that may need a read action. */
    private fun <T> background(action: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val result = scope.async { action() }
            PlatformTestUtil.waitWithEventsDispatching("Story service did not finish", { result.isCompleted }, 10)
            return runBlocking { result.await() }
        } finally {
            scope.cancel()
        }
    }
}
