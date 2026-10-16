package io.github.hubertrybak.codestory

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.hubertrybak.codestory.core.DraftComment
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.StoryThreads
import io.github.hubertrybak.codestory.core.StoryValidator
import io.github.hubertrybak.codestory.core.ValidationResult
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant

/** The real disk-to-player seam, also run in a sandbox with MCP Server disabled. */
class StoryFileDropTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private lateinit var panel: StoryListPanel
    private val player get() = StoryPlayer.getInstance(project)
    private val watcher get() = StoryFileWatcher.getInstance(project)
    private val service get() = CodeStoryService.getInstance(project)

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/App.kt"), (1..10).joinToString("\n", postfix = "\n") { "line $it" })
        panel = StoryListPanel(project)
        if (java.lang.Boolean.getBoolean("codeStory.testWithoutMcp")) {
            assertFalse("MCP must actually be disabled in this sandbox", StoryMcpSupport.status().available)
            assertTrue(CodeStorySetup.getInstance().status(project).text().contains("disabled or unavailable"))
        }
    }

    override fun tearDown() {
        try {
            watcher.dispose()
            player.stop()
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            Disposer.dispose(panel)
        } catch (e: Throwable) { addSuppressedException(e) }
        finally { super.tearDown() }
    }

    fun `test a valid atomic drop is listed and played without MCP or VFS refresh`() {
        start()
        val file = drop()
        await { player.currentStory?.id == ID && panel.tree.rowCount == 5 }
        assertEquals(0, player.stepIndex)
        assertEquals("App.kt", player.presentation!!.editor.virtualFile!!.name)
        assertEquals(listOf(ID), service.list().map { it.id })
        assertTrue(Files.exists(file.parent.resolve(".gitignore")))
        val stored = (StoryFormat.decode(Files.readString(file)) as StoryFormat.Decoded.Valid).story
        assertNull(stored.delivery)
        assertEquals("line 2\nline 3\nline 4", stored.steps.first().location!!.rangeText)
        assertTrue(watcher.problems.isEmpty())
    }

    fun `test a drop waiting before the project opens is played`() {
        drop()
        start()
        await { player.currentStory?.id == ID }
        assertEquals(0, player.stepIndex)
    }

    fun `test pending index rebuilds from shared stories at startup and after external removal`() {
        val folder = service.storyFolder()
        Files.createDirectories(folder.sharedDir)
        val file = folder.sharedDir.resolve("$ID.story.json")
        val original = (StoryFormat.decode(contents()) as StoryFormat.Decoded.Valid).story
        val pending = StoryThreads.submit(original, listOf(DraftComment("A shared question?")), Instant.now())
        Files.writeString(file, StoryFormat.encode(pending, ID))
        val index = folder.localDir.resolve(StoryFolder.PENDING_INDEX)
        start()
        fun count(): String? = runCatching {
            Json.parseToJsonElement(Files.readString(index)).jsonObject.getValue("pendingComments").jsonPrimitive.content
        }.getOrNull()
        await { count() == "1" }
        assertNull(player.currentStory)
        assertTrue(Files.exists(folder.localDir.resolve(".gitignore")))
        Files.delete(file)
        await { count() == "0" }
    }

    fun `test a changed drop reloads at the current step`() {
        start()
        drop()
        await { player.currentStory?.id == ID }
        player.next()
        assertEquals(1, player.stepIndex)
        drop(contents = contents().replace("Payment flow", "Revised flow"))
        await { player.currentStory?.story?.title == "Revised flow" }
        assertEquals(1, player.stepIndex)
        assertEquals(listOf(ID), service.list().map { it.id })
    }

    fun `test invalid drop shows actionable errors in the tool window and correction clears them`() {
        start()
        val file = drop(contents = contents().replace("src/App.kt", "src/Missing.kt"))
        await { panel.fileProblems.text.contains("src/Missing.kt") }
        assertNull(player.currentStory)
        assertEmpty(service.list())
        assertEquals(ErrorCode.FILE_NOT_FOUND.code, watcher.problems.getValue(file).first().code)
        assertTrue(panel.fileProblems.text.contains("Step 1"))
        drop()
        await { player.currentStory?.id == ID && panel.fileProblems.text.isEmpty() }
        assertEquals(1, service.list().size)
    }

    fun `test invalid JSON and oversized files show problems and deletion clears them`() {
        start()
        val file = drop(contents = "{ broken")
        await { panel.fileProblems.text.contains("JSON") }
        drop(contents = " ".repeat(256 * 1024 + 1))
        await { watcher.problems[file]?.firstOrNull()?.code == ErrorCode.STORY_TOO_LARGE.code }
        assertNull(player.currentStory)
        Files.delete(file)
        await { panel.fileProblems.text.isEmpty() }
    }

    fun `test edits to saved anchors are validated and restoring the file plays again`() {
        start()
        val file = drop()
        await { player.currentStory?.id == ID }
        val valid = Files.readString(file)
        player.stop()
        drop(contents = valid.replace("\"anchorText\": \"line 2\"", "\"anchorText\": \"missing anchor\""))
        await { watcher.problems[file]?.firstOrNull()?.code == ErrorCode.ANCHOR_NOT_FOUND.code }
        assertNull(player.currentStory)
        drop(contents = valid)
        await { player.currentStory?.id == ID && panel.fileProblems.text.isEmpty() }
    }

    fun `test restoring the same valid bytes after an oversized replacement clears the error`() {
        start()
        val file = drop()
        await { player.currentStory?.id == ID }
        val valid = Files.readString(file)
        player.stop()
        drop(contents = " ".repeat(256 * 1024 + 1))
        await { watcher.problems[file]?.firstOrNull()?.code == ErrorCode.STORY_TOO_LARGE.code }
        drop(contents = valid)
        await { player.currentStory?.id == ID && panel.fileProblems.text.isEmpty() }
    }

    fun `test replacing a story directory registers the new folder and forgets the old one`() {
        start()
        val file = drop()
        await { player.currentStory?.id == ID }
        player.stop()
        val moved = root.resolve("old-stories")
        Files.move(file.parent, moved)
        await { panel.tree.rowCount == 0 }
        Files.writeString(moved.resolve(file.fileName), "not a story")
        drop(contents = contents().replace("Payment flow", "New folder"))
        await { player.currentStory?.story?.title == "New folder" }
        assertTrue(watcher.problems.isEmpty())
    }

    fun `test a marked MCP file and a shared story are listed without auto play`() {
        start()
        drop(contents = contents(marker = true))
        await { service.list().size == 1 && panel.tree.rowCount >= 1 }
        assertNull(player.currentStory)
        val second = "2026-10-08-shared"
        drop(id = second, contents = contents(id = second), directory = ".codestory/shared")
        await { service.list().size == 2 && panel.tree.rowCount >= 2 }
        assertNull(player.currentStory)
    }

    fun `test dropped anchors use unsaved editor text just like the MCP tool`() {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/App.kt"))!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "extra line\n") }
        try {
            start()
            drop()
            await { player.currentStory?.id == ID }
            assertEquals(3, player.currentStory!!.story.steps.first().location!!.startLine)
        } finally {
            player.stop()
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().reloadFromDisk(document) }
        }
    }

    fun `test temporary and out of scope files are ignored`() {
        start()
        val local = root.resolve(".codestory/local")
        Files.createDirectories(local)
        Files.writeString(local.resolve("story.tmp"), "not JSON")
        Files.writeString(root.resolve("$ID.story.json"), "not JSON")
        val subfolder = local.resolve("nested")
        Files.createDirectories(subfolder)
        Files.writeString(subfolder.resolve("$ID.story.json"), "not JSON")
        drop()
        await { player.currentStory?.id == ID }
        assertEmpty(watcher.problems.values)
        assertEquals(1, service.list().size)
    }

    fun `test anchor rewrites do not restart stopped file drops`() {
        start()
        val file = drop()
        await { player.currentStory?.id == ID }
        player.stop()
        val stored = (StoryFormat.decode(Files.readString(file)) as StoryFormat.Decoded.Valid).story
        // Another IDE may also capture a different range snapshot from its unsaved document.
        val moved = stored.copy(steps = stored.steps.map {
            it.copy(location = it.location?.copy(startLine = 3, endLine = 5, rangeText = "line 2\nother edit\nline 4"))
        })
        drop(contents = StoryFormat.encode(moved, ID))
        // A second story makes completion of the directory scan observable, without starting playback.
        val marked = "2026-10-08-marked"
        drop(id = marked, contents = contents(id = marked, marker = true))
        await { panel.tree.rowCount >= 6 }
        assertNull(player.currentStory)
    }

    fun `test sharing and unsharing a file drop never deliver it again`() {
        start()
        val file = drop()
        await { player.currentStory?.id == ID }
        player.stop()
        val original = Files.readString(file)
        val local = service.stories().single()
        runBlocking { withContext(Dispatchers.IO) { service.setShared(local, true) } }
        val shared = service.stories().single()
        assertEquals("shared", shared.folder)
        runBlocking { withContext(Dispatchers.IO) { service.setShared(shared, false) } }
        val marked = "2026-10-08-marked"
        drop(id = marked, contents = contents(id = marked, marker = true))
        await { panel.tree.rowCount >= 6 }
        assertEquals(original, Files.readString(file))
        assertNull(player.currentStory)
        assertEmpty(watcher.problems.values)
    }

    fun `test comments on a dropped story need no new arrival check and do not replay it`() {
        start()
        val file = drop()
        await { player.currentStory?.id == ID }
        player.stop()
        FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        // The code of the first step is gone, so the saved anchors no longer pass the arrival check.
        Files.writeString(root.resolve("src/App.kt"), "other\n")
        val stored = service.stories().single()
        assertEquals(StepStatus.LOST, stored.status(0))
        val comment = StoryDraft(story = stored.key, step = "entry", text = "Is this step still right?")
        assertEquals(1, runBlocking { withContext(Dispatchers.IO) { service.post(stored, listOf(comment)) } })
        // The watcher reads files in name order, so a problem in this later file shows that it handled the comment.
        val sentinel = drop(id = "2026-10-08-zz-sentinel", contents = "{ broken")
        await { watcher.problems.containsKey(sentinel) }
        assertEquals(setOf(sentinel), watcher.problems.keys)
        assertNull(player.currentStory)
        assertEquals(1, service.list().single { it.id == ID }.pendingComments)
        assertTrue(Files.readString(file).contains("Is this step still right?"))
    }

    fun `test comments written by another IDE reach the playing story at its step`() {
        start()
        val file = drop()
        await { player.currentStory?.id == ID }
        player.next()
        val story = (StoryFormat.decode(Files.readString(file)) as StoryFormat.Decoded.Valid).story
        val commented = StoryThreads.submit(story, listOf(DraftComment("Asked in the other IDE?", stepId = "entry")), Instant.now())
        drop(contents = StoryFormat.encode(commented, ID))
        await { player.currentStory?.story?.steps?.first()?.threads?.singleOrNull()?.messages?.single()?.text == "Asked in the other IDE?" }
        assertEquals(1, player.stepIndex)
        assertEmpty(watcher.problems.values)
    }

    fun `test drops in separate repositories validate and play at their own roots`() {
        val repositories = listOf("first", "second").map { name ->
            root.resolve(name).also { repository ->
                Files.createDirectories(repository.resolve("src"))
                Files.copy(root.resolve("src/App.kt"), repository.resolve("src/App.kt"))
                GitFixture(repository).init()
            }
        }
        ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(repositories.map { VcsDirectoryMapping(it.toString(), "Git") })
        start()
        drop(directory = "first/.codestory/local")
        await { player.currentStory?.root == repositories[0] }
        player.next()
        drop(directory = "second/.codestory/local")
        await { player.currentStory?.root == repositories[1] }
        assertEquals(0, player.stepIndex)
        assertEquals(2, service.list().size)
        // Startup may have watched the plain project before VCS mappings were configured.
        assertFalse(Files.exists(root.resolve(".codestory/local")))
        assertFalse(Files.exists(root.resolve(".codestory/shared")))

        val invalid = drop(contents = contents().replace("src/App.kt", "src/Missing.kt"), directory = "second/.codestory/local")
        await { watcher.problems[invalid]?.firstOrNull()?.code == ErrorCode.FILE_NOT_FOUND.code }
        val sharedId = "2026-10-08-shared"
        drop(id = sharedId, contents = contents(id = sharedId), directory = "first/.codestory/shared")
        await { service.list().size == 2 && panel.fileProblems.text.contains("src/Missing.kt") }
        assertEquals(repositories[1], player.currentStory!!.root)
        assertTrue(service.stories().single { it.id == sharedId }.isNew)
        assertEquals(repositories[0].toString(), service.list().single { it.id == ID }.repositoryPath)
    }

    private fun start() = runBlocking { CodeStoryStartup().execute(project) }
    private fun await(condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("File-drop state did not arrive", { !player.isResolving && condition() }, 15)

    private fun drop(id: String = ID, contents: String = contents(id), directory: String = ".codestory/local"): Path {
        val dir = root.resolve(directory)
        Files.createDirectories(dir)
        val temporary = Files.createTempFile(dir, ".story-", ".tmp")
        Files.writeString(temporary, contents)
        return Files.move(temporary, dir.resolve("$id.story.json"), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun contents(id: String = ID, marker: Boolean = false): String {
        val valid = StoryValidator(null).validate(Json.parseToJsonElement(story(
            step(id = quoted("entry")), step(id = quoted("checks"), location = null),
        ))) as ValidationResult.Valid
        return StoryFormat.encode(valid.story.copy(createdAt = Instant.parse("2026-10-08T12:00:00Z"), delivery = if (marker) "mcp" else null), id)
    }

    companion object { private const val ID = "2026-10-08-payment-flow" }
}
