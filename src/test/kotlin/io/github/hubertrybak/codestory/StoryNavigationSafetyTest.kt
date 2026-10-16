package io.github.hubertrybak.codestory

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.MessageAuthor
import io.github.hubertrybak.codestory.core.ReplyOutcome
import io.github.hubertrybak.codestory.core.Step
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoryAuthor
import io.github.hubertrybak.codestory.core.StoryThread
import io.github.hubertrybak.codestory.core.StoryType
import io.github.hubertrybak.codestory.core.ThreadKind
import io.github.hubertrybak.codestory.core.ThreadMessage
import io.github.hubertrybak.codestory.core.ThreadState
import io.github.hubertrybak.codestory.ui.StepNavigator
import io.github.hubertrybak.codestory.ui.StepItem
import io.github.hubertrybak.codestory.ui.StoryItem
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import java.awt.Container
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import javax.swing.JLabel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Saved locations and retained gutter actions must remain safe and current after files or threads change. */
class StoryNavigationSafetyTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private val source get() = root.resolve("src/navigation.txt")
    private val player get() = StoryPlayer.getInstance(project)

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        Files.createDirectories(source.parent)
        Files.writeString(source, "first line\nsecond line\nthird line\n")
    }

    override fun tearDown() {
        try {
            player.stop()
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test gutter navigation retains answers received during playback`() {
        val original = stored()
        play(original)
        val presentation = player.presentation!!
        val updated = withAnswer(original)
        player.threadsChanged(updated)
        assertEquals(updated.story.steps[0].threads, player.currentStory!!.story.steps[0].threads)
        assertSame(presentation, player.presentation)

        clickBadge()

        assertEquals("A gutter click must not restore the pre-answer snapshot", updated.story.steps[0].threads, player.currentStory!!.story.steps[0].threads)
        assertTrue(labels(player.presentation!!.card.comments).contains("Question · Answered"))
    }

    fun `test a retained gutter badge keeps answers after another text-only story plays`() {
        val original = stored()
        play(original)
        val updated = withAnswer(original)
        player.threadsChanged(updated)
        val overview = StoredStory("overview", root, root.resolve(".codestory/local/overview.story.json"),
            original.story.copy(steps = listOf(Step("Overview", "Read the overview.", id = "overview"))), original.modified)
        play(overview)

        clickBadge()

        assertEquals(original.key, player.currentStory!!.key)
        assertEquals(updated.story.steps[0].threads, player.currentStory!!.story.steps[0].threads)
    }

    fun `test sharing retains card input and gutter actions follow the new story identity`() {
        val original = stored()
        play(original)
        val presentation = player.presentation!!
        presentation.startComment()
        presentation.card.comments.shownInput()!!.text.text = "Half written question"
        val moved = StoredStory(original.id, root, root.resolve(".codestory/shared/${original.id}.story.json"), original.story, original.modified)

        player.relocated(original, moved)

        assertSame(presentation, player.presentation)
        val input = presentation.card.comments.shownInput()!!
        assertEquals("Half written question", input.text.text)
        assertEquals(moved.key, input.draft.story)
        val updated = withAnswer(moved)
        player.threadsChanged(updated)
        input.post()
        val reader = project.service<StoryReaderState>()
        val posted = reader.drafts(moved.key).single()
        assertEquals("Half written question", posted.text)
        // This fixture story has no file, so the comment stays unsent after the write fails.
        PlatformTestUtil.waitWithEventsDispatching("The comment was not posted", { !reader.isSending(posted) }, 10)
        clickBadge()
        assertEquals(moved.key, player.currentStory!!.key)
        assertEquals(updated.story.steps[0].threads, player.currentStory!!.story.steps[0].threads)
    }

    fun `test sharing preserves tree selection expansion and unsaved details input across queued reloads`() {
        val original = stored()
        val otherRoot = root.resolve("other-repository")
        val other = StoredStory(original.id, otherRoot, otherRoot.resolve(".codestory/local/${original.id}.story.json"),
            original.story.copy(title = "Same ID in another repository"), original.modified)
        val moved = StoredStory(original.id, root, root.resolve(".codestory/shared/${original.id}.story.json"), original.story, original.modified)
        val panel = StoryListPanel(project)
        try {
            awaitLoaded(panel)
            panel.showStories(listOf(original, other))
            fun nodes() = (panel.tree.model.root as DefaultMutableTreeNode).preorderEnumeration().toList().filterIsInstance<DefaultMutableTreeNode>()
            fun storyNode(key: String) = nodes().single { (it.userObject as? StoryItem)?.stored?.key == key }
            panel.tree.expandPath(TreePath(storyNode(original.key).path))
            panel.tree.expandPath(TreePath(storyNode(other.key).path))
            val step = nodes().single { (it.userObject as? StepItem)?.stored?.key == original.key }
            panel.tree.selectionPath = TreePath(step.path)
            panel.details.comments!!.startComment()
            panel.details.comments!!.shownInput()!!.text.text = "Half typed details"
            // This load starts before the relocation and must not restore the old tree snapshot.
            panel.reload()
            project.messageBus.syncPublisher(StoryListListener.TOPIC).storyRelocated(original, moved)
            awaitLoaded(panel)

            val selected = (panel.tree.lastSelectedPathComponent as DefaultMutableTreeNode).userObject as StepItem
            assertEquals(moved.key, selected.stored.key)
            assertEquals("start", selected.step.id)
            assertTrue(panel.tree.isExpanded(TreePath(storyNode(moved.key).path)))
            assertTrue(panel.tree.isExpanded(TreePath(storyNode(other.key).path)))
            assertEquals("Same ID in another repository", (storyNode(other.key).userObject as StoryItem).stored.story.title)
            val input = panel.details.comments!!.shownInput()!!
            assertEquals("Half typed details", input.text.text)
            assertEquals(moved.key, input.draft.story)

            panel.showStories(listOf(moved, other))
            assertSame(input, panel.details.comments!!.shownInput())
            assertEquals(moved.key, ((panel.tree.lastSelectedPathComponent as DefaultMutableTreeNode).userObject as StepItem).stored.key)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun `test lost location cannot navigate outside root through a replacement symlink`() {
        val original = stored()
        withOutsideSource {
            play(original)

            assertEquals(StepStatus.LOST, player.currentStory!!.status(0))
            assertNull("Rejected source paths must not reopen in an editor", FileEditorManager.getInstance(project).selectedTextEditor)
            assertNull(player.presentation)
        }
    }

    fun `test a lost anchor inside the repository still opens its stored lines`() {
        val original = stored(anchor = "removed line")
        play(original)

        assertEquals(StepStatus.LOST, player.currentStory!!.status(0))
        assertEquals(source.toString(), player.presentation!!.editor.virtualFile!!.path)
    }

    fun `test navigation permits a symlink whose canonical target stays inside the repository`() {
        Files.createSymbolicLink(root.resolve("src/link.txt"), source)

        val editor = StepNavigator.getInstance(project).open(root, Location("src/link.txt", 2, 2))

        assertNotNull(editor)
        assertEquals(source.toString(), editor!!.virtualFile!!.path)
        assertEquals(1, editor.caretModel.logicalPosition.line)
    }

    fun `test diff cannot open a source replaced with an outside symlink`() {
        GitFixture(root).init()
        val original = stored(baseRef = "HEAD")
        val diffs = RecordingDiffManager()
        val notifications = mutableListOf<Notification>()
        ApplicationManager.getApplication().replaceService(DiffManager::class.java, diffs, testRootDisposable)
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { notifications += notification }
        })
        withOutsideSource {
            StoryChangeInfo.getInstance(project).showDiff(original, 0)
            PlatformTestUtil.waitWithEventsDispatching("The diff request did not finish", {
                diffs.requests.isNotEmpty() || notifications.any { it.content.contains("cannot show the diff") }
            }, 10)

            assertEmpty(diffs.requests)
            assertTrue(notifications.any { it.content.contains("outside the repository") })
        }
    }

    private fun play(stored: StoredStory) {
        player.play(stored)
        awaitPlayback()
    }

    private fun clickBadge() {
        val action = player.presentation!!.badges.single().gutterIconRenderer!!.clickAction!!
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT))
        awaitPlayback()
    }

    private fun awaitPlayback() = PlatformTestUtil.waitWithEventsDispatching("Story navigation did not finish", { !player.isResolving }, 10)

    private fun labels(container: Container): List<String> = container.components.flatMap { component ->
        listOfNotNull((component as? JLabel)?.text) + if (component is Container) labels(component) else emptyList()
    }

    private fun awaitLoaded(panel: StoryListPanel) {
        val loaded = panel.loaded()
        PlatformTestUtil.waitWithEventsDispatching("Story list did not finish loading", { loaded.isDone }, 10)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun withOutsideSource(action: () -> Unit) {
        val outside = Files.createTempFile("code-story-outside-navigation", ".txt")
        try {
            Files.writeString(outside, "outside repository\n")
            Files.delete(source)
            Files.createSymbolicLink(source, outside)
            action()
        } finally {
            player.stop()
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            Files.deleteIfExists(source)
            Files.deleteIfExists(outside)
        }
    }

    private fun withAnswer(stored: StoredStory): StoredStory {
        val thread = StoryThread("answer-thread", ThreadKind.QUESTION, ThreadState.ANSWERED, listOf(
            ThreadMessage(MessageAuthor.READER, 1, Instant.EPOCH, "Why this line?"),
            ThreadMessage(MessageAuthor.AGENT, 1, Instant.EPOCH, "It starts the flow.", ReplyOutcome.ANSWERED),
        ))
        val story = stored.story.copy(steps = stored.story.steps.map { it.copy(threads = listOf(thread)) })
        return StoredStory(stored.id, stored.root, stored.file, story, stored.modified)
    }

    private fun stored(anchor: String = "first line", baseRef: String? = null): StoredStory {
        val id = "2026-10-10-navigation"
        return StoredStory(id, root, root.resolve(".codestory/local/$id.story.json"), Story(
            "Navigation", StoryType.EXPLORATION, "Read the source.", "Explain this line.", StoryAuthor("Test", "test"),
            listOf(Step("Start", "This starts the flow.", Location("src/navigation.txt", 1, 1, anchor, anchor), "start")),
            Instant.parse("2026-10-10T12:00:00Z"), baseRef = baseRef,
        ), FileTime.fromMillis(0))
    }

    private class RecordingDiffManager : DiffManager() {
        val requests = mutableListOf<DiffRequest>()
        override fun showDiff(project: Project?, request: DiffRequest) { requests += request }
        override fun showDiff(project: Project?, request: DiffRequest, hints: DiffDialogHints) { requests += request }
        override fun showDiff(project: Project?, chain: DiffRequestChain, hints: DiffDialogHints) = throw UnsupportedOperationException()
        override fun createRequestPanel(project: Project?, parent: Disposable, window: Window?): DiffRequestPanel = throw UnsupportedOperationException()
        override fun showMerge(project: Project?, request: MergeRequest) = throw UnsupportedOperationException()
    }
}
