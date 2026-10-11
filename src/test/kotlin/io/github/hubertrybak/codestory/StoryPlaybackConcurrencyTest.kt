package io.github.hubertrybak.codestory

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.hubertrybak.codestory.core.FileLookup
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.Step
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoryAuthor
import io.github.hubertrybak.codestory.core.StoryFiles
import io.github.hubertrybak.codestory.core.StoryThread
import io.github.hubertrybak.codestory.core.ThreadKind
import io.github.hubertrybak.codestory.core.ThreadState
import io.github.hubertrybak.codestory.core.StoryType
import io.github.hubertrybak.codestory.ui.StoryPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StoryPlaybackConcurrencyTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private val player get() = StoryPlayer.getInstance(project)
    private var gate: LookupGate? = null

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        Files.createDirectories(root)
        Files.writeString(root.resolve("source.txt"), "alpha\nbeta\n")
    }

    override fun tearDown() {
        try {
            gate?.release?.countDown()
            player.stop()
            awaitChecks()
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        } catch (error: Throwable) {
            addSuppressedException(error)
        } finally {
            super.tearDown()
        }
    }

    fun `test file lookup leaves the EDT free and stopped playback cannot reopen`() {
        val lookup = pauseLookup()
        player.play(story())
        awaitLookup(lookup)
        assertTrue(player.isResolving)
        assertFalse(lookup.onEdt.get())
        assertFalse(lookup.underReadLock.get())

        player.stop()
        assertFalse(player.isPlaying)
        lookup.release.countDown()
        awaitChecks()

        assertFalse(player.isPlaying)
        assertNull(player.presentation)
        assertEmpty(FileEditorManager.getInstance(project).openFiles.toList())
    }

    fun `test an obsolete resolution cannot replace newer playback`() {
        val lookup = pauseLookup()
        player.play(story())
        awaitLookup(lookup)
        val replacement = story("newer", listOf(Step("Overview", "The newer story.")))
        player.play(replacement)
        player.awaitPlayback()
        lookup.release.countDown()
        awaitChecks()

        assertEquals(replacement.key, player.currentStory!!.key)
        assertNull(player.presentation)
        assertEmpty(FileEditorManager.getInstance(project).openFiles.toList())
    }

    fun `test replies arriving during resolution survive its completion`() {
        val lookup = pauseLookup()
        val stored = story()
        player.play(stored)
        awaitLookup(lookup)
        val reply = StoryThread("answer", ThreadKind.QUESTION, ThreadState.ANSWERED, emptyList())
        val updated = StoredStory(stored.id, root, stored.file, stored.story.copy(threads = listOf(reply)), stored.modified)
        player.threadsChanged(updated)
        lookup.release.countDown()
        player.awaitPlayback()

        assertEquals(listOf(reply), player.currentStory!!.story.threads)
        assertNotNull(player.presentation)
    }

    fun `test edits made while resolving retry against the current document`() {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("source.txt"))!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val lookup = pauseLookup()
        player.play(story())
        awaitLookup(lookup)
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "inserted\n") }
        lookup.release.countDown()
        player.awaitPlayback()

        assertEquals(3, player.currentStory!!.story.steps.single().location!!.startLine)
        assertEquals(2, player.presentation!!.editor.caretModel.logicalPosition.line)
    }

    fun `test unopened source files get markers before their first edit`() {
        Files.writeString(root.resolve("other.txt"), "one\noriginal()\n")
        val other = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("other.txt"))!!
        val documents = FileDocumentManager.getInstance()
        assertNull(documents.getCachedDocument(other))
        val first = story().story.steps.single()
        player.play(story(steps = listOf(first,
            Step("Other", "Read other.", Location("other.txt", 2, 2, "original()", "original()"), "other"))))
        player.awaitPlayback()
        val document = documents.getCachedDocument(other)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(document.getLineStartOffset(1), document.getLineEndOffset(1), "updated()")
        }
        player.next()
        player.awaitPlayback()

        assertEquals(StepStatus.CODE_CHANGED, player.currentStory!!.status(1))
        assertEquals("updated()", player.currentStory!!.story.steps[1].location!!.anchorText)
        assertEquals("original()", player.currentStory!!.story.steps[1].location!!.rangeText)
    }

    fun `test relocation before the background snapshot retains live ranges`() {
        player.play(story())
        player.awaitPlayback()
        val previous = player.currentStory!!
        val document = player.presentation!!.editor.document
        val moved = StoredStory(previous.id, root, root.resolve("shared").resolve(previous.file.fileName),
            previous.story, previous.modified)
        // Holding the write lock makes the worker snapshot run after relocation. Its original
        // request still has the old key, while the live markers already have the new one.
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(document.getLineStartOffset(1), document.getLineEndOffset(1), "updated")
            player.play(previous)
            player.relocated(previous, moved)
        }
        player.awaitPlayback()

        assertEquals(moved.key, player.currentStory!!.key)
        assertEquals(StepStatus.CODE_CHANGED, player.currentStory!!.status(0))
        assertEquals("updated", player.currentStory!!.story.steps.single().location!!.anchorText)
        assertEquals("beta", player.currentStory!!.story.steps.single().location!!.rangeText)
    }

    private fun pauseLookup(): LookupGate {
        val lookup = LookupGate()
        val original = player.filesForPlayback
        player.filesForPlayback = { path, live ->
            val files = original(path, live)
            object : StoryFiles by files {
                override fun lookup(path: String): FileLookup {
                    if (lookup.once.compareAndSet(false, true)) {
                        val application = ApplicationManager.getApplication()
                        lookup.onEdt.set(application.isDispatchThread)
                        lookup.underReadLock.set(application.isReadAccessAllowed)
                        lookup.entered.countDown()
                        // Model an uninterruptible disk lookup. Cancellation must also guard
                        // the eventual completion, even when interrupting the worker is insufficient.
                        while (true) {
                            try {
                                check(lookup.release.await(20, TimeUnit.SECONDS)) { "lookup was not released" }
                                break
                            } catch (_: InterruptedException) { }
                        }
                    }
                    return files.lookup(path)
                }
            }
        }
        gate = lookup
        return lookup
    }

    private fun awaitLookup(lookup: LookupGate) = PlatformTestUtil.waitWithEventsDispatching(
        "the background lookup did not start", { lookup.entered.count == 0L }, 20)

    private fun awaitChecks() {
        PlatformTestUtil.waitWithEventsDispatching("background anchor checks did not finish", { !player.isCheckingAnchors }, 20)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun story(id: String = "original", steps: List<Step> = listOf(
        Step("Beta", "Read beta.", Location("source.txt", 2, 2, "beta", "beta"), "beta"),
    )) = StoredStory(id, root, root.resolve("$id.story.json"), Story(
        "Example", StoryType.REVIEW, "Summary", "Request", StoryAuthor("Agent", "model"), steps,
    ), FileTime.fromMillis(0))

    private class LookupGate {
        val once = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val onEdt = AtomicBoolean()
        val underReadLock = AtomicBoolean()
    }
}
