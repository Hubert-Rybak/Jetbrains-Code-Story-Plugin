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
import io.github.hubertrybak.codestory.core.StoryThreads
import io.github.hubertrybak.codestory.ui.CommentsPanel
import io.github.hubertrybak.codestory.ui.StepItem
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
import javax.swing.JTextArea
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** The comments in the details pane: drafts, rounds, waiting comments, answers and resolved threads. */
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

    fun `test the reader writes edits and deletes drafts of a step`() {
        val stored = show(story(step(id = quoted("entry")), step(start = "5", end = "6", id = quoted("core"))))
        openPanel()
        selectStep(stored, "entry")
        assertTrue(notes().contains("No comments yet. Ask a question, then submit it to your agent."))
        assertFalse(comments.submit.isEnabled)

        link("Comment on step").doClick()
        assertEquals("New comment about this step", comments.input!!.title)
        comments.input!!.text.text = "  Why does it start here?  "
        comments.input!!.save.doClick()
        assertNull(comments.input)
        val draft = reader.drafts(stored.key).single()
        assertEquals("Why does it start here?" to "entry", draft.text to draft.step)
        assertTrue(labels().contains("Draft question"))
        assertTrue(notes().contains("Why does it start here?"))
        assertTrue(storyRow(stored).endsWith("  1 draft"))
        assertEquals("Submit Round (1)", comments.submit.text)
        assertTrue(comments.submit.isEnabled)
        assertEquals("Submit Round (1)", update(panel!!.toolbarActions, "Submit Round").text)

        link("Edit").doClick()
        assertEquals("Why does it start here?", comments.input!!.text.text)
        comments.input!!.text.text = "Why here?"
        comments.input!!.save.doClick()
        assertEquals(listOf(draft.id to "Why here?"), reader.drafts(stored.key).map { it.id to it.text })
        link("Edit").doClick()
        comments.input!!.text.text = "Changed my mind"
        comments.input!!.cancel.doClick()
        assertEquals(listOf("Why here?"), reader.drafts(stored.key).map { it.text })

        // The draft belongs to its step only.
        selectStep(stored, "core")
        assertFalse(notes().contains("Why here?"))
        assertEquals("Submit Round (1)", comments.submit.text)
        selectStep(stored, "entry")
        link("Delete").doClick()
        assertEmpty(reader.drafts(stored.key))
        assertFalse(storyRow(stored).contains("draft"))
        assertFalse(update(panel!!.toolbarActions, "Submit Round").isEnabled)
        assertFalse(Files.readString(stored.file).contains("Why here?"))
    }

    fun `test submit shows waiting comments and withdraw returns them to the drafts`() {
        val stored = show(story(step(id = quoted("entry"))))
        openPanel()
        selectStory(stored)
        perform(panel!!.contextActions, "Comment")
        assertEquals("New comment about the story", comments.input!!.title)
        comments.input!!.text.text = "What is missing?"
        comments.input!!.save.doClick()
        perform(panel!!.toolbarActions, "Submit Round", "Submit Round (1)")
        await { labels().contains("Question · Waiting for agent") }
        assertEmpty(reader.drafts(stored.key))
        assertTrue(notes().contains("What is missing?"))
        assertTrue(notes().contains("Waiting for your agent: tell it /code-story feedback"))
        link("Copy").doClick()
        assertEquals("/code-story feedback", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        link("Copy for Codex").doClick()
        assertEquals("\$code-story feedback", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))

        link("Withdraw").doClick()
        await { labels().contains("Draft question") && !labels().contains("Question · Waiting for agent") }
        assertEquals(listOf("What is missing?"), reader.drafts(stored.key).map { it.text })
        assertTrue(StoryThreads.all(saved(stored)).isEmpty())
        comments.submit.doClick()
        await { labels().contains("Question · Waiting for agent") }

        // An agent fetched the comment: it can no longer be withdrawn.
        background { service.feedback(null, null) }
        await { labels().contains("Question · Agent working") }
        assertNull(links().firstOrNull { it.text == "Withdraw" })
        assertFalse(notes().contains("Waiting for your agent: tell it /code-story feedback"))
    }

    fun `test answers appear in their thread and resolved threads hide until shown`() {
        val stored = show(story(step(id = quoted("entry"))))
        reader.saveDraft(StoryDraft(story = stored.key, step = "entry", text = "Why does it start here?"))
        background { service.submitRound(stored) }
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
        assertTrue(labels().containsAll(listOf("Reader · round 1", "Agent · round 1 · answered")))
        assertTrue(notes().contains("The controller receives the request first."))

        link("Reply").doClick()
        assertEquals("Reply in this thread", comments.input!!.title)
        comments.input!!.text.text = "Why not in the service?"
        comments.input!!.save.doClick()
        assertTrue(labels().contains("Draft reply"))
        assertEquals(comment.threadId, reader.drafts(stored.key).single().thread)
        link("Delete").doClick()

        link("Resolve").doClick()
        await { notes().contains("Resolved threads are hidden.") }
        assertFalse(labels().contains("Question · Answered"))
        comments.showResolved.doClick()
        assertTrue(labels().contains("Question · Resolved"))
        assertNotNull(links().singleOrNull { it.text == "Reply" })
        assertNull(links().firstOrNull { it.text == "Resolve" })
    }

    fun `test an unsaved comment survives reloads and waits while the reader looks elsewhere`() {
        val stored = show(story(step(id = quoted("entry")), step(start = "5", end = "6", id = quoted("core"))))
        openPanel()
        selectStep(stored, "entry")
        comments.startComment()
        val input = comments.input!!
        input.text.text = "Half written"
        // Another IDE, or an agent, changes the story meanwhile.
        background { CodeStoryService.getInstance(project).show(Json.parseToJsonElement(story(step(id = quoted("entry"), comment = quoted("A revised entry.")), step(start = "5", end = "6", id = quoted("core")))), stored.id) }
        await { panel!!.details.displayedText.contains("A revised entry.") }
        assertSame(input, comments.shownInput())
        assertEquals("Half written", input.text.text)
        // The input waits while the reader looks at another step.
        selectStep(stored, "core")
        assertNull(comments.shownInput())
        assertFalse(input.text in components(comments))
        assertTrue(links().any { it.text == "Comment on step" })
        selectStep(stored, "entry")
        assertSame(input, comments.shownInput())
        assertTrue(input.text in components(comments))
        input.cancel.doClick()
        assertNull(comments.input)
        assertEmpty(reader.drafts(stored.key))
    }

    fun `test card comments save drafts with Ctrl Enter cancel and expand threads inside the card`() {
        val stored = show(story(step(id = quoted("entry"))))
        val player = StoryPlayer.getInstance(project)
        await { player.currentStory?.id == stored.id }
        val card = player.presentation!!.card
        assertEquals("💬 0 threads", card.threadLine.text)
        assertFalse(card.comments.expanded)
        card.commentButton.doClick()
        val input = card.comments.shownInput()!!
        input.text.text = "Why does it start here?"
        val saveShortcut = input.text.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK))!!
        saveShortcut.actionPerformed(ActionEvent(input.text, ActionEvent.ACTION_PERFORMED, "save"))
        await { card.threadLine.text == "💬 0 threads · 1 draft" }
        assertEquals("entry", reader.drafts(stored.key).single().step)
        assertFalse(Files.readString(stored.file).contains("Why does it start here?"))
        card.commentButton.doClick()
        card.comments.input!!.text.text = "Discard this."
        card.comments.input!!.cancel.doClick()
        assertEquals(1, reader.drafts(stored.key).size)
        card.threadLine.doClick()
        assertTrue(card.comments.expanded)
        assertTrue(components(card.comments).filterIsInstance<JTextArea>().any { it.text == "Why does it start here?" })
        card.comments.submit.doClick()
        await { card.threadLine.text == "💬 1 thread" }
        assertTrue(components(card.comments).filterIsInstance<JLabel>().any { it.text == "Question · Waiting for agent" })
        // The current-step shortcut also reopens a dismissed card.
        player.presentation!!.dismissCard()
        player.commentOnCurrentStep()
        assertFalse(player.presentation!!.cardDismissed)
        assertNotNull(card.comments.shownInput())
        card.comments.input!!.cancel.doClick()
    }

    fun `test collapsed card leaves new replies unread until threads open and shows story changes`() {
        val stored = show(story(step(id = quoted("entry")), step(id = quoted("core"), start = "5", end = "6")))
        val player = StoryPlayer.getInstance(project)
        await { player.currentStory?.id == stored.id }
        reader.saveDraft(StoryDraft(story = stored.key, step = "entry", text = "Explain this?"))
        background { service.submitRound(stored) }
        val comment = background { service.feedback(stored.id, null)!! }.comments.single()
        background { service.patch(stored.id, listOf(Json.parseToJsonElement("""{"op":"update","stepId":"entry","comment":"The controller receives the request."}"""))) }
        await { player.currentStory?.story?.steps?.first()?.comment == "The controller receives the request." }
        background {
            service.reply(stored.id, listOf(buildJsonObject {
                put("threadId", comment.threadId); put("round", comment.round); put("outcome", "answered"); put("text", "The controller receives the request first.")
            }))
        }
        await { player.currentStory!!.story.steps[0].threads.single().messages.size == 2 }
        val card = player.presentation!!.card
        assertFalse(card.comments.expanded)
        assertTrue(reader.hasUnread(stored, saved(stored).steps[0].threads))
        openPanel()
        selectStep(stored, "core")
        assertTrue(stepRow(stored, "entry").contains("1 open thread"))
        assertTrue(stepRow(stored, "entry").contains("●"))
        val before = Files.readString(stored.file)
        card.threadLine.doClick()
        assertFalse(reader.hasUnread(stored, saved(stored).steps[0].threads))
        assertFalse(stepRow(stored, "entry").contains("●"))
        assertEquals(before, Files.readString(stored.file))
        assertTrue(components(card.comments).filterIsInstance<JTextArea>().any { it.text == "Story changes: Step 1 updated" })
    }

    fun `test an unsaved card input survives a story revision`() {
        val stored = show(story(step(id = quoted("entry"))))
        val player = StoryPlayer.getInstance(project)
        await { player.currentStory?.id == stored.id }
        player.presentation!!.card.commentButton.doClick()
        player.presentation!!.card.comments.input!!.text.text = "Half written question"
        background { service.patch(stored.id, listOf(Json.parseToJsonElement("""{"op":"update","stepId":"entry","comment":"A clearer explanation."}"""))) }
        await { player.currentStory!!.story.steps[0].comment == "A clearer explanation." }
        assertEquals("Half written question", player.presentation!!.card.comments.shownInput()!!.text.text)
        assertEmpty(reader.drafts(stored.key))
    }

    private fun stepRow(stored: StoredStory, stepId: String): String {
        val tree = panel!!.tree
        val node = nodes().single { (it.userObject as? StepItem)?.let { step -> step.stored.key == stored.key && step.step.id == stepId } == true }
        return (tree.cellRenderer.getTreeCellRendererComponent(tree, node, false, false, false, 0, false) as SimpleColoredComponent).getCharSequence(false).toString()
    }

    fun `test change request toggles in card and details preserve draft kind and refresh is in the context menu`() {
        val stored = show(story(step(id = quoted("entry"))))
        val player = StoryPlayer.getInstance(project)
        await { player.currentStory?.id == stored.id }
        val card = player.presentation!!.card
        card.commentButton.doClick()
        val input = card.comments.shownInput()!!
        assertTrue(input.question.isSelected)
        input.change.doClick()
        input.text.text = "Change the entry."
        input.save.doClick()
        assertEquals(ThreadKind.CHANGE, reader.drafts(stored.key).single().kind)
        openPanel()
        selectStep(stored, "entry")
        assertTrue(labels().contains("Draft change request"))
        link("Edit").doClick()
        assertTrue(comments.input!!.change.isSelected)
        comments.input!!.question.doClick()
        comments.input!!.save.doClick()
        assertEquals(ThreadKind.QUESTION, reader.drafts(stored.key).single().kind)
        link("Edit").doClick()
        comments.input!!.change.doClick()
        comments.input!!.save.doClick()
        comments.submit.doClick()
        await { labels().contains("Change request · Waiting for agent") }
        perform(panel!!.contextActions, "Request refresh")
        await { labels().contains("Refresh request · Waiting for agent") }
        val submitted = service.storyFolder().get(stored.id)!!.story
        assertEquals(setOf(ThreadKind.CHANGE, ThreadKind.REFRESH), submitted.steps.single().threads.map { it.kind }.toSet())
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
        assertTrue(comments.input!!.question.isSelected)
        comments.input!!.change.doClick()
        comments.input!!.text.text = "Change the entry again."
        comments.input!!.save.doClick()
        comments.submit.doClick()
        await { labels().contains("Change request · Waiting for agent") }
        link("Withdraw").doClick()
        await { labels().contains("Refresh request · Done") && reader.drafts(stored.key).isNotEmpty() }
        assertEquals(ThreadKind.CHANGE, reader.drafts(stored.key).single().kind)
        comments.submit.doClick()
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

    private fun components(container: Container): List<Component> =
        container.components.filter { it.isVisible }.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }

    private fun labels() = components(comments).filterIsInstance<JLabel>().map { it.text }
    private fun notes() = components(comments).filterIsInstance<JTextArea>().filter { !it.isEditable }.map { it.text }
    private fun links() = components(comments).filterIsInstance<ActionLink>()
    private fun link(text: String) = links().single { it.text == text }

    private fun update(actions: DefaultActionGroup, name: String) = actions.getChildActionsOrStubs().single { it.templatePresentation.text == name }.let { action ->
        TestActionEvent.createTestEvent(action).also(action::update).presentation
    }

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
