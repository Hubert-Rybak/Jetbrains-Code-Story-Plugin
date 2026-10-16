package io.github.hubertrybak.codestory

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.ActionLink
import io.github.hubertrybak.codestory.core.ReplyOutcome
import io.github.hubertrybak.codestory.core.ShowStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.ThreadKind
import io.github.hubertrybak.codestory.core.ThreadState
import io.github.hubertrybak.codestory.core.StoryThreads
import io.github.hubertrybak.codestory.ui.CommentsPanel
import io.github.hubertrybak.codestory.ui.StepItem
import io.github.hubertrybak.codestory.ui.StoryComments
import io.github.hubertrybak.codestory.ui.StoryItem
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** The comments in the details pane and the card: posting, waiting comments, withdrawal, answers and resolved threads. */
class StoryCommentsPanelTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private var panel: StoryListPanel? = null
    private val service get() = CodeStoryService.getInstance(project)
    private val reader get() = project.service<StoryReaderState>()
    private val comments: CommentsPanel get() = panel!!.details.comments!!

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/App.kt"), (1..10).joinToString("\n", postfix = "\n") { "line $it" })
    }

    override fun tearDown() {
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            StoryPlayer.getInstance(project).stop()
            panel?.let(Disposer::dispose)
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        } catch (e: Throwable) { addSuppressedException(e) }
        finally { super.tearDown() }
    }

    fun `test a comment on a step is posted at once and stays on its step`() {
        val stored = show(story(step(id = quoted("entry")), step(start = "5", end = "6", id = quoted("core"))))
        openPanel()
        selectStep(stored, "entry")
        assertTrue(notes().contains("No comments yet. Post a question or a change request below. Your listening agent answers here."))
        assertNull(panel!!.toolbarActions.getChildActionsOrStubs().firstOrNull { it.templatePresentation.text == "Submit Round" })
        // The input stays below the scrolling details.
        assertNull(SwingUtilities.getAncestorOfClass(JScrollPane::class.java, comments.composer))
        assertTrue(SwingUtilities.isDescendingFrom(comments.composer, panel!!.details.footer))

        val input = comments.shownInput()!!
        assertEquals("New comment about this step", input.title)
        assertEquals("Ask about this step, or request a change", input.text.placeholder)
        assertFalse(input.post.isEnabled)
        input.text.text = "  Why does it start here?  "
        assertTrue(input.post.isEnabled)
        input.post.doClick()
        await { labels().contains("Question · Waiting for agent") && reader.drafts(stored.key).isEmpty() }
        val thread = saved(stored).steps[0].threads.single()
        assertEquals("Why does it start here?" to ThreadState.SUBMITTED, thread.messages.single().text to thread.state)
        assertTrue(labels().contains("You"))
        assertTrue(notes().contains("Why does it start here?"))
        assertTrue(stepRow(stored, "entry").contains("1 open thread"))
        // A fresh input waits for the next comment.
        assertNotSame(input, comments.shownInput())
        assertEquals("", comments.shownInput()!!.text.text)

        // The comment belongs to its step only.
        selectStep(stored, "core")
        assertFalse(notes().contains("Why does it start here?"))
        assertTrue(saved(stored).steps[1].threads.isEmpty())
    }

    fun `test a posted comment waits for an agent and withdraw opens it for editing`() {
        val stored = show(story(step(id = quoted("entry"))))
        openPanel()
        selectStory(stored)
        perform(panel!!.contextActions, "Comment")
        val input = comments.shownInput()!!
        assertEquals("New comment about the story", input.title)
        input.text.text = "What is missing?"
        ctrlEnter(input.text)
        await { labels().contains("Question · Waiting for agent") }
        assertTrue(notes().contains("Listening agents pick up posted comments at once. If your agent is stopped, tell it /code-story feedback."))
        link("Copy").doClick()
        assertEquals("/code-story feedback", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        link("Copy for Codex").doClick()
        assertEquals("\$code-story feedback", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))

        link("Withdraw").doClick()
        await { comments.shownInput()?.title == "Edit unsent comment" && !labels().contains("Question · Waiting for agent") }
        assertEquals("What is missing?", comments.shownInput()!!.text.text)
        assertTrue(StoryThreads.all(saved(stored)).isEmpty())
        assertEquals(listOf("What is missing?"), reader.drafts(stored.key).map { it.text })
        // Leaving the edit keeps the comment as an unsent comment.
        comments.shownInput()!!.cancel.doClick()
        assertTrue(labels().contains("Unsent question"))
        assertEquals("New comment about the story", comments.shownInput()!!.title)
        link("Edit").doClick()
        comments.shownInput()!!.post.doClick()
        await { labels().contains("Question · Waiting for agent") && reader.drafts(stored.key).isEmpty() }
        assertEquals(listOf("What is missing?"), StoryThreads.all(saved(stored)).map { it.second.messages.single().text })

        // An agent fetched the comment: it can no longer be withdrawn.
        background { service.feedback(null, null) }
        await { labels().contains("Question · Agent working") }
        assertNull(links().firstOrNull { it.text == "Withdraw" })
        assertFalse(notes().any { it.startsWith("Listening agents pick up posted comments") })
    }

    fun `test answers appear in their thread and a reply reopens it`() {
        val stored = show(story(step(id = quoted("entry"))))
        background { service.post(stored, listOf(StoryDraft(story = stored.key, step = "entry", text = "Why does it start here?"))) }
        val comment = background { service.feedback(stored.id, null)!! }.comments.single()
        openPanel()
        selectStep(stored, "entry")
        assertTrue(labels().contains("Question · Agent working"))
        background {
            service.reply(stored.id, listOf(buildJsonObject {
                put("threadId", comment.threadId); put("round", comment.round); put("outcome", ReplyOutcome.ANSWERED.value); put("text", "The controller receives the request first.")
            }))
        }
        await { labels().contains("Question · Answered") }
        assertTrue(labels().containsAll(listOf("You", "Agent · answered")))
        assertTrue(notes().contains("The controller receives the request first."))

        link("Reply").doClick()
        assertEquals("Reply in this thread", comments.shownInput()!!.title)
        assertTrue(components(comments.composer).filterIsInstance<JLabel>().any { it.text == "Reply to “Why does it start here?”" })
        assertNull(links().firstOrNull { it.text == "Reply" })
        comments.shownInput()!!.cancel.doClick()
        assertEquals("New comment about this step", comments.shownInput()!!.title)

        link("Reply").doClick()
        comments.shownInput()!!.text.text = "Why not in the service?"
        comments.shownInput()!!.post.doClick()
        await { labels().contains("Question · Waiting for agent") }
        assertEquals(listOf(1, 1, 2), saved(stored).steps[0].threads.single().messages.map { it.round })
        link("Withdraw").doClick()
        await { comments.shownInput()?.title == "Edit unsent reply" }
        comments.shownInput()!!.cancel.doClick()
        await { labels().contains("Question · Answered") && labels().contains("Unsent reply") }
        assertEquals(comment.threadId, reader.drafts(stored.key).single().thread)
        link("Delete").doClick()
        assertEmpty(reader.drafts(stored.key))
        assertFalse(labels().contains("Unsent reply"))

        link("Resolve").doClick()
        await { notes().contains("Resolved threads are hidden.") }
        assertFalse(labels().contains("Question · Answered"))
        comments.showResolved.doClick()
        assertTrue(labels().contains("Question · Resolved"))
        assertNotNull(links().singleOrNull { it.text == "Reply" })
        assertNull(links().firstOrNull { it.text == "Resolve" })
    }

    fun `test unposted input survives reloads and waits while the reader looks elsewhere`() {
        val stored = show(story(step(id = quoted("entry")), step(start = "5", end = "6", id = quoted("core"))))
        openPanel()
        selectStep(stored, "entry")
        comments.startComment()
        val input = comments.shownInput()!!
        // Showing the same step again, as any reload does, keeps even an empty input, and so its focus.
        comments.show(service.stories().single { it.key == stored.key }, 0)
        assertSame(input, comments.shownInput())
        input.text.text = "Half written"
        // Another IDE, or an agent, changes the story meanwhile.
        background { CodeStoryService.getInstance(project).show(Json.parseToJsonElement(story(step(id = quoted("entry"), comment = quoted("A revised entry.")), step(start = "5", end = "6", id = quoted("core")))), stored.id) }
        await { panel!!.details.displayedText.contains("A revised entry.") }
        assertSame(input, comments.shownInput())
        assertEquals("Half written", input.text.text)
        // The input waits while the reader looks at another step.
        selectStep(stored, "core")
        assertNotSame(input, comments.shownInput())
        assertFalse(input.text in components(comments.composer))
        assertEquals("", comments.shownInput()!!.text.text)
        selectStep(stored, "entry")
        assertSame(input, comments.shownInput())
        assertTrue(input.text in components(comments.composer))
        input.cancel.doClick()
        assertEquals("", comments.shownInput()!!.text.text)
        assertEmpty(reader.drafts(stored.key))
        assertFalse(Files.readString(stored.file).contains("Half written"))
    }

    fun `test the card posts with Ctrl Enter and shows its threads without scrolling to the input`() {
        val stored = show(story(step(id = quoted("entry"))))
        val player = StoryPlayer.getInstance(project)
        await { player.currentStory?.id == stored.id && !player.isResolving }
        val card = player.presentation!!.card
        assertFalse(card.comments.isVisible)
        // The input is in the card's footer, outside the scrolling step text.
        assertTrue(SwingUtilities.isDescendingFrom(card.comments.composer, card))
        assertNull(SwingUtilities.getAncestorOfClass(JScrollPane::class.java, card.comments.composer))
        player.presentation!!.startComment()
        val input = card.comments.shownInput()!!
        assertEquals("New comment about this step", input.title)
        input.text.text = "Why does it start here?"
        ctrlEnter(input.text)
        await { labels(card.comments).contains("Question · Waiting for agent") }
        assertTrue(card.comments.isVisible)
        assertEquals("Why does it start here?", saved(stored).steps[0].threads.single().messages.single().text)
        card.comments.shownInput()!!.text.text = "Discard this."
        card.comments.shownInput()!!.cancel.doClick()
        assertEquals("", card.comments.shownInput()!!.text.text)
        assertEquals(1, saved(stored).steps[0].threads.size)
        // The current-step shortcut also reopens a dismissed card.
        player.presentation!!.dismissCard()
        player.commentOnCurrentStep()
        assertFalse(player.presentation!!.cardDismissed)
        assertNotNull(card.comments.shownInput())
    }

    fun `test the card shows story changes and answers stay unread until the card shows them`() {
        val stored = show(story(step(id = quoted("entry")), step(id = quoted("core"), start = "5", end = "6")))
        val player = StoryPlayer.getInstance(project)
        await { player.currentStory?.id == stored.id && !player.isResolving }
        background { service.post(stored, listOf(StoryDraft(story = stored.key, step = "entry", text = "Explain this?"))) }
        val comment = background { service.feedback(stored.id, null)!! }.comments.single()
        background { service.patch(stored.id, listOf(Json.parseToJsonElement("""{"op":"update","stepId":"entry","comment":"The controller receives the request."}"""))) }
        await { player.currentStory?.story?.steps?.first()?.comment == "The controller receives the request." && !player.isResolving }
        background {
            service.reply(stored.id, listOf(buildJsonObject {
                put("threadId", comment.threadId); put("round", comment.round); put("outcome", "answered"); put("text", "The controller receives the request first.")
            }))
        }
        await { player.currentStory!!.story.steps[0].threads.single().messages.size == 2 && !player.isResolving }
        val card = player.presentation!!.card
        await { components(card.comments).filterIsInstance<JTextArea>().any { it.text == "Story changes: Step 1 updated" } }
        // Without a window, the card does not show yet.
        assertTrue(reader.hasUnread(stored, saved(stored).steps[0].threads))
        openPanel()
        selectStep(stored, "core")
        assertTrue(stepRow(stored, "entry").contains("1 open thread"))
        assertTrue(stepRow(stored, "entry").contains("●"))
        val before = Files.readString(stored.file)
        card.comments.markShownRead()
        assertFalse(reader.hasUnread(stored, saved(stored).steps[0].threads))
        assertFalse(stepRow(stored, "entry").contains("●"))
        assertEquals(before, Files.readString(stored.file))
    }

    fun `test an unposted card input survives a story revision`() {
        val stored = show(story(step(id = quoted("entry"))))
        val player = StoryPlayer.getInstance(project)
        await { player.currentStory?.id == stored.id && !player.isResolving }
        player.presentation!!.startComment()
        player.presentation!!.card.comments.shownInput()!!.text.text = "Half written question"
        background { service.patch(stored.id, listOf(Json.parseToJsonElement("""{"op":"update","stepId":"entry","comment":"A clearer explanation."}"""))) }
        await { player.currentStory!!.story.steps[0].comment == "A clearer explanation." && !player.isResolving }
        assertEquals("Half written question", player.presentation!!.card.comments.shownInput()!!.text.text)
        assertEmpty(reader.drafts(stored.key))
    }

    fun `test a failed post keeps the comment as an unsent comment`() {
        val stored = show(story(step(id = quoted("entry"))))
        // Another IDE moved the story away meanwhile, so the write fails.
        val moved = StoredStory(stored.id, stored.root, root.resolve(".codestory/local/moved-away.story.json"), stored.story, stored.modified)
        val alone = CommentsPanel(StoryComments(project))
        alone.show(moved, 0)
        alone.shownInput()!!.text.text = "Is this kept?"
        alone.shownInput()!!.post.doClick()
        val draft = reader.drafts(moved.key).single()
        await { !reader.isSending(draft) }
        alone.show(moved, 0)
        assertEquals(listOf("Is this kept?"), reader.drafts(moved.key).map { it.text })
        assertTrue(labels(alone).contains("Unsent question"))
        assertTrue(components(alone).filterIsInstance<ActionLink>().map { it.text }.containsAll(listOf("Post", "Edit", "Delete")))

        // The story row counts unsent comments.
        reader.saveDraft(StoryDraft(story = stored.key, step = "entry", text = "Withdrawn earlier"))
        openPanel()
        assertTrue(storyRow(stored).endsWith("  1 unsent"))
    }

    private fun stepRow(stored: StoredStory, stepId: String): String {
        val tree = panel!!.tree
        val node = nodes().single { (it.userObject as? StepItem)?.let { step -> step.stored.key == stored.key && step.step.id == stepId } == true }
        return (tree.cellRenderer.getTreeCellRendererComponent(tree, node, false, false, false, 0, false) as SimpleColoredComponent).getCharSequence(false).toString()
    }

    fun `test change requests keep their kind through withdrawal and refresh is in the context menu`() {
        val stored = show(story(step(id = quoted("entry"))))
        val player = StoryPlayer.getInstance(project)
        await { player.currentStory?.id == stored.id && !player.isResolving }
        val card = player.presentation!!.card
        player.presentation!!.startComment()
        val input = card.comments.shownInput()!!
        assertTrue(input.question.isSelected)
        input.change.doClick()
        input.text.text = "Change the entry."
        input.post.doClick()
        await { saved(stored).steps.single().threads.singleOrNull()?.kind == ThreadKind.CHANGE }
        openPanel()
        selectStep(stored, "entry")
        await { labels().contains("Change request · Waiting for agent") }
        link("Withdraw").doClick()
        await { comments.shownInput()?.title == "Edit unsent comment" }
        assertTrue(comments.shownInput()!!.change.isSelected)
        comments.shownInput()!!.question.doClick()
        comments.shownInput()!!.post.doClick()
        await { saved(stored).steps.single().threads.singleOrNull()?.kind == ThreadKind.QUESTION }
        await { labels().contains("Question · Waiting for agent") }
        link("Withdraw").doClick()
        await { comments.shownInput()?.title == "Edit unsent comment" }
        comments.shownInput()!!.change.doClick()
        comments.shownInput()!!.post.doClick()
        await { labels().contains("Change request · Waiting for agent") }
        perform(panel!!.contextActions, "Request refresh")
        await { labels().contains("Refresh request · Waiting for agent") }
        val submitted = service.storyFolder().get(stored.id)!!.story
        assertEquals(setOf(ThreadKind.CHANGE, ThreadKind.REFRESH), submitted.steps.single().threads.map { it.kind }.toSet())
        // Comments posted before an agent fetched them form one round.
        assertEquals(1, StoryThreads.pending(submitted).map { it.round }.distinct().size)
        val fetched = background { service.feedback(stored.id, null)!! }.comments
        background {
            service.reply(stored.id, fetched.map { buildJsonObject {
                put("threadId", it.threadId); put("round", it.round); put("outcome", "done")
                put("text", "Reviewed the current code."); put("changedFiles", JsonArray(emptyList()))
            } })
        }
        await { labels().contains("Refresh request · Done") && labels().contains("Change request · Done") }
        // A new reader comment defaults to Question even in a refresh or change thread.
        links().last { it.text == "Reply" }.doClick()
        assertTrue(comments.shownInput()!!.question.isSelected)
        comments.shownInput()!!.change.doClick()
        comments.shownInput()!!.text.text = "Change the entry again."
        comments.shownInput()!!.post.doClick()
        await { labels().contains("Change request · Waiting for agent") }
        link("Withdraw").doClick()
        await { labels().contains("Refresh request · Done") && comments.shownInput()?.title == "Edit unsent reply" }
        assertEquals(ThreadKind.CHANGE, reader.drafts(stored.key).single().kind)
        comments.shownInput()!!.post.doClick()
        await { labels().contains("Change request · Waiting for agent") }
        assertEquals(ThreadKind.CHANGE, background { service.feedback(stored.id, null)!! }.comments.single().kind)
    }

    private fun show(json: String): StoredStory {
        val report = background { service.show(Json.parseToJsonElement(json)) }
        assertEquals(report.errors.toString(), ShowStatus.ACCEPTED, report.status)
        return service.stories().single { it.id == report.storyId }
    }

    private fun saved(stored: StoredStory) = (StoryFormat.decode(Files.readString(stored.file)) as StoryFormat.Decoded.Valid).story

    private fun <T> background(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.IO) { action() } }

    private fun openPanel() {
        panel = StoryListPanel(project)
        val loaded = panel!!.loaded()
        await { loaded.isDone && nodes().any { it.userObject is StoryItem } }
    }

    private fun nodes() = (panel!!.tree.model.root as DefaultMutableTreeNode).preorderEnumeration().toList().filterIsInstance<DefaultMutableTreeNode>()

    private fun selectStory(stored: StoredStory) {
        panel!!.tree.selectionPath = TreePath(nodes().single { (it.userObject as? StoryItem)?.stored?.key == stored.key }.path)
    }

    private fun selectStep(stored: StoredStory, stepId: String) {
        panel!!.tree.selectionPath = TreePath(nodes().single { (it.userObject as? StepItem)?.let { item -> item.stored.key == stored.key && item.step.id == stepId } == true }.path)
    }

    private fun storyRow(stored: StoredStory): String {
        val tree = panel!!.tree
        val node = nodes().single { (it.userObject as? StoryItem)?.stored?.key == stored.key }
        val renderer = tree.cellRenderer.getTreeCellRendererComponent(tree, node, false, false, false, 0, false)
        return (renderer as SimpleColoredComponent).getCharSequence(false).toString()
    }

    private fun ctrlEnter(text: JTextArea) {
        val post = text.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK))!!
        post.actionPerformed(ActionEvent(text, ActionEvent.ACTION_PERFORMED, "post"))
    }

    private fun components(container: Container): List<Component> =
        container.components.filter { it.isVisible }.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }

    private fun labels(container: Container = comments) = components(container).filterIsInstance<JLabel>().map { it.text }
    private fun notes() = components(comments).filterIsInstance<JTextArea>().filter { !it.isEditable }.map { it.text }
    private fun links() = components(comments).filterIsInstance<ActionLink>()
    private fun link(text: String) = links().single { it.text == text }

    private fun perform(actions: DefaultActionGroup, name: String, label: String = name) {
        val action = actions.getChildActionsOrStubs().single { it.templatePresentation.text == name }
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertEquals(label, event.presentation.text)
        action.actionPerformed(event)
    }

    private fun await(condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("the comments did not update", condition, 20)
}
