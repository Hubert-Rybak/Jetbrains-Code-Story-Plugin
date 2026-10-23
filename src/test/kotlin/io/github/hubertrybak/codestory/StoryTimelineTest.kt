package io.github.hubertrybak.codestory

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.hubertrybak.codestory.core.DiffLine
import io.github.hubertrybak.codestory.core.ShowStatus
import io.github.hubertrybak.codestory.core.StepKind
import io.github.hubertrybak.codestory.core.StoryJson.jsonText
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.ThreadKind
import io.github.hubertrybak.codestory.ui.APPLY_PROPOSAL_TEXT
import io.github.hubertrybak.codestory.ui.CodeSnippet
import io.github.hubertrybak.codestory.ui.StepItem
import io.github.hubertrybak.codestory.ui.StepNavigator
import io.github.hubertrybak.codestory.ui.StoryDetailsPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import io.github.hubertrybak.codestory.ui.StoryTimeline
import io.github.hubertrybak.codestory.ui.TimelinePanel
import io.github.hubertrybak.codestory.ui.proposedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicHTML

/** The timeline view: every step of a story with its explanation and code, beside the editor that follows it. */
class StoryTimelineTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private var panel: TimelinePanel? = null
    private val player get() = StoryPlayer.getInstance(project)
    private val settings get() = CodeStorySettings.getInstance()
    private var savedView = StoryView.CARD

    /** An explanation, context for it, a proposal for its file, a proposal of new code and a text-only step. */
    private val kinds = story(
        step(start = "10", end = "12", id = quoted("entry")),
        step(file = quoted("src/Billing.kt"), start = "2", end = "2", anchor = quoted("fun charge() {}"), title = quoted("The billing call"),
            kind = quoted("context"), id = quoted("billing")),
        step(start = "20", end = "21", title = quoted("Retry the call"), kind = quoted("proposal"), proposedCode = jsonText("line 20\n    retry()"), id = quoted("retry")),
        step(location = null, title = quoted("Add a test"), kind = quoted("proposal"), proposedCode = jsonText("fun retries() {}"), id = quoted("test")),
        step(location = null, title = quoted("What to check"), comment = quoted("Check the retry."), id = quoted("checks")),
    )

    override fun setUp() {
        super.setUp()
        savedView = settings.state.storyView
        settings.state.storyView = StoryView.CARD
        root = Path.of(project.basePath!!)
        write("src/App.kt", (1..60).joinToString("\n", postfix = "\n") { "line $it" })
        write("src/Billing.kt", "class Billing {\n    fun charge() {}\n}\n")
    }

    override fun tearDown() {
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            player.stop()
            panel?.let(Disposer::dispose)
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            settings.state.storyView = savedView
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test every step shows its kind and its code with the lines of its file`() {
        val id = show(kinds)
        val panel = openTimeline()

        assertEquals(id, panel.shown!!.id)
        assertEquals("Payment flow", panel.header.title.text)
        assertEquals("Change · 5 steps (2 proposals, 1 context step) · ${id.take(10)}", panel.header.meta.text)
        assertEquals("Written by Test Agent (test-model)", panel.header.author.text)
        val blocks = panel.blocks
        assertEquals(listOf(StepKind.EXPLANATION, StepKind.CONTEXT, StepKind.PROPOSAL, StepKind.PROPOSAL, StepKind.EXPLANATION), blocks.map { it.kind })
        assertEquals(listOf(false, true, true, true, false), blocks.map { it.chip.isVisible })
        assertEquals(listOf("Context", "Proposal"), blocks.slice(1..2).map { it.chip.text })
        // Context hangs from the rail under the step that it gives background for.
        assertTrue(blocks[1].rail.preferredSize.width > blocks[0].rail.preferredSize.width)
        assertEquals("App.kt:10–12", blocks[0].locationLink.text)

        val entry = blocks[0].snippet!!
        assertEquals("line 10\nline 11\nline 12", entry.editor.document.text)
        assertEquals(listOf(10, 11, 12), entry.lines.numbers)
        assertTrue(entry.editor.settings.isLineNumbersShown)
        assertEquals("fun charge() {}", blocks[1].snippet!!.editor.document.text)
        assertEquals(listOf(2), blocks[1].snippet!!.lines.numbers)

        val retry = blocks[2].snippet!!
        assertEquals("line 20\nline 21\n    retry()", retry.editor.document.text)
        assertEquals(listOf(DiffLine.Change.KEPT, DiffLine.Change.REMOVED, DiffLine.Change.ADDED), retry.lines.changes)
        assertEquals(listOf(20, 21, null), retry.lines.numbers)
        assertEquals(listOf(20, null, 21), retry.lines.proposedNumbers)
        assertEquals("Proposed change: 1 line removed, 1 line added", blocks[2].snippetCaption.text)
        val changed = retry.editor.markupModel.allHighlighters.filter { it.textAttributesKey == DiffColors.DIFF_DELETED || it.textAttributesKey == DiffColors.DIFF_INSERTED }
        assertEquals(listOf(1 to DiffColors.DIFF_DELETED, 2 to DiffColors.DIFF_INSERTED),
            changed.map { retry.editor.document.getLineNumber(it.startOffset) to it.textAttributesKey }.sortedBy { it.first })
        assertTrue(blocks[2].preview.isVisible)
        assertTrue(blocks[2].askToApply.isVisible)

        val newCode = blocks[3].snippet!!
        assertEquals("fun retries() {}", newCode.editor.document.text)
        assertFalse(newCode.editor.settings.isLineNumbersShown)
        assertEquals("Proposed new code", blocks[3].snippetCaption.text)
        assertFalse(blocks[3].preview.isVisible)

        assertNull(blocks[4].snippet)
        assertFalse(blocks[4].locationLink.isVisible)
        assertEquals("Check the retry.", blocks[4].text.markdown)
    }

    fun `test code shows without the indentation that all its lines share`() {
        write("src/Nested.kt", "class Nested {\n    fun charge() {\n\n        bill()\n    }\n}\n")
        show(story(
            step(file = quoted("src/Nested.kt"), start = "2", end = "5", anchor = quoted("fun charge() {")),
            step(file = quoted("src/Nested.kt"), start = "4", end = "4", anchor = quoted("bill()"), title = quoted("Retry the bill"),
                kind = quoted("proposal"), proposedCode = jsonText("        bill()\n        retry()")),
        ))
        val blocks = openTimeline().blocks

        assertEquals("fun charge() {\n\n    bill()\n}", blocks[0].snippet!!.editor.document.text)
        assertEquals(listOf(2, 3, 4, 5), blocks[0].snippet!!.lines.numbers)
        // The proposed lines keep their indentation next to the current lines.
        assertEquals("bill()\nretry()", blocks[1].snippet!!.editor.document.text)
        assertEquals(listOf(DiffLine.Change.KEPT, DiffLine.Change.ADDED), blocks[1].snippet!!.lines.changes)
    }

    fun `test a click on a code line plays its step in the timeline and puts the caret on that line`() {
        show(kinds)
        val panel = openTimeline()

        // Line 2 of the snippet is line 11 of the file.
        panel.blocks[0].snippet!!.lineClicked(1, clickCount = 1)
        player.awaitPlayback()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(StoryView.TIMELINE, player.view)
        assertEquals(0, player.stepIndex)
        val presentation = player.presentation!!
        assertTrue(presentation.cardDismissed)
        assertNull(presentation.balloon)
        assertFalse(presentation.inToolWindow)
        assertEquals(10, presentation.editor.caretModel.logicalPosition.line)
        val highlighter = StepNavigator.getInstance(project).currentHighlighter!!
        assertEquals(9, presentation.editor.document.getLineNumber(highlighter.startOffset))
        assertEquals(listOf(true, false, false, false, false), panel.blocks.map { it.isCurrent })
        assertTrue(panel.blocks[0].composer.isVisible)
        assertFalse(panel.blocks[1].composer.isVisible)

        // Context plays like any other step.
        panel.blocks[1].snippet!!.lineClicked(0, clickCount = 1)
        player.awaitPlayback()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, player.stepIndex)
        assertEquals("Billing.kt", FileEditorManager.getInstance(project).selectedTextEditor!!.virtualFile!!.name)
        assertTrue(player.presentation!!.cardDismissed)
        assertEquals(listOf(false, true, false, false, false), panel.blocks.map { it.isCurrent })

        // A text-only step leaves the editor as it is.
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        panel.blocks[4].title.dispatchEvent(MouseEvent(panel.blocks[4].title, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1))
        player.awaitPlayback()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(4, player.stepIndex)
        assertSame(editor, FileEditorManager.getInstance(project).selectedTextEditor)
        assertTrue(panel.blocks[4].isCurrent)
    }

    fun `test a selection in the code stays for copying, and a double-click on a word opens the code`() {
        show(kinds)
        val blocks = openTimeline().blocks
        val context = blocks[1].snippet!!
        // A click that extends the selection, for example with Shift, does not open the code.
        context.editor.selectionModel.setSelection(0, 3)
        click(context, line = 0)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(0, player.stepIndex)
        assertTrue(context.editor.selectionModel.hasSelection())

        // The line numbers always open the code.
        click(context, line = 0, onLineNumbers = true)
        player.awaitPlayback()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, player.stepIndex)

        // A double-click selects a word, then opens the code without the selection. The snippet
        // takes it at its second press; a press in a test editor leaks a hover listener.
        val entry = blocks[0].snippet!!
        entry.editor.selectionModel.setSelection(0, 4)
        entry.lineClicked(1, clickCount = 2)
        player.awaitPlayback()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(0, player.stepIndex)
        assertFalse(entry.editor.selectionModel.hasSelection())
        assertEquals(10, player.presentation!!.editor.caretModel.logicalPosition.line)
    }

    fun `test the timeline follows the player and the step card comes back on request`() {
        show(kinds)
        val panel = openTimeline()
        // The playing story shows with its current step, and the card stays until the reader switches.
        assertTrue(panel.blocks[0].isCurrent)
        assertFalse(player.presentation!!.cardDismissed)

        StoryTimeline.getInstance(project).open(player.currentStory!!)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(StoryView.TIMELINE, player.view)
        assertTrue(player.presentation!!.cardDismissed)
        assertNull(player.presentation!!.balloon)

        player.next()
        player.awaitPlayback()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf(false, true, false, false, false), panel.blocks.map { it.isCurrent })
        assertTrue(player.presentation!!.cardDismissed)

        player.showIn(StoryView.CARD)
        player.awaitPlayback()
        assertEquals(StoryView.CARD, player.view)
        assertEquals(1, player.stepIndex)
        assertFalse(player.presentation!!.cardDismissed)
        assertEquals("Step 2 of 5 · Context", player.presentation!!.card.stepNumber.text)
    }

    fun `test opening the timeline brings the current step into view, and reloads keep the place of the reader`() {
        show(kinds)
        val panel = openTimeline()
        player.play(player.currentStory!!, 1)
        player.awaitPlayback()
        settle()
        panel.setSize(800, 300)
        layOut(panel)
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, panel.blocks[0]) as JViewport
        val block = panel.blocks[1]
        fun blockInView() = viewport.viewRect.contains(SwingUtilities.convertPoint(block.parent, block.location, viewport.view))
        val end = viewport.view.height - viewport.height

        // The reader scrolls to the end of the story, then opens it from the step card.
        viewport.viewPosition = Point(0, end)
        assertFalse(blockInView())
        StoryTimeline.getInstance(project).open(player.currentStory!!)
        settle()
        assertTrue(blockInView())

        // New text in the timeline, for example after a revision or an edit, does not scroll to that text.
        viewport.viewPosition = Point(0, end)
        panel.reload()
        settle()
        assertEquals(end, viewport.viewPosition.y)
    }

    fun `test new stories open in the view that the reader used last`() {
        show(kinds)
        val panel = openTimeline()
        StoryTimeline.getInstance(project).open(player.currentStory!!)
        settle()
        assertEquals(StoryView.TIMELINE, settings.state.storyView)

        val second = show(story(step(), title = quoted("Second story")))
        assertTrue(player.presentation!!.cardDismissed)
        // The timeline follows the new playback.
        settle()
        assertEquals(second, panel.shown!!.id)
        assertTrue(panel.blocks.single().isCurrent)
    }

    fun `test a timeline that opens shows the story that the reader opened, else the playing story`() {
        val first = show(kinds)
        val second = show(story(step(), title = quoted("Second story")))
        val older = CodeStoryService.getInstance(project).stories().first { it.id == first }
        StoryTimeline.getInstance(project).open(older)
        assertEquals(first, openTimeline().shown!!.id)
        Disposer.dispose(panel!!)
        assertEquals(second, openTimeline().shown!!.id)
    }

    fun `test a proposal goes to the agent as a change request and its change can be previewed`() {
        val id = show(kinds)
        val panel = openTimeline()
        val step = panel.shown!!.story.steps[2]
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/App.kt"))!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val lines = (1..60).map { "line $it" }
        assertEquals((lines.take(20) + "    retry()" + lines.drop(21)).joinToString("\n", postfix = "\n"), proposedText(document, step.location!!, step.proposedCode!!))
        assertEquals((lines.take(19) + lines.drop(21)).joinToString("\n", postfix = "\n"), proposedText(document, step.location!!, ""))

        panel.blocks[2].askToApply.doClick()
        PlatformTestUtil.waitWithEventsDispatching("the change request is not in the story", {
            CodeStoryService.getInstance(project).storyFolder().get(id)!!.story.steps[2].threads.isNotEmpty()
        }, TIMEOUT_SECONDS)
        settle()
        val thread = CodeStoryService.getInstance(project).storyFolder().get(id)!!.story.steps[2].threads.single()
        assertEquals(ThreadKind.CHANGE, thread.kind)
        assertEquals(APPLY_PROPOSAL_TEXT, thread.messages.single().text)
        assertFalse(panel.blocks[2].askToApply.isVisible)

        // The agent gets the proposal with the request.
        val feedback = runBlocking { withContext(Dispatchers.IO) { CodeStoryService.getInstance(project).feedback(id, null)!! } }
        val comment = feedback.comments.single()
        assertEquals(StepKind.PROPOSAL, comment.step!!.kind)
        assertEquals("line 20\n    retry()", comment.step!!.proposedCode)
    }

    fun `test edits move and change the code of the steps in the timeline`() {
        show(kinds)
        val panel = openTimeline()
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/App.kt"))!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!

        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "new line\n") }
        PlatformTestUtil.waitWithEventsDispatching("the code did not move", { panel.blocks[0].snippet!!.lines.numbers == listOf(11, 12, 13) }, TIMEOUT_SECONDS)
        assertEquals("line 10\nline 11\nline 12", panel.blocks[0].snippet!!.editor.document.text)
        assertEquals("App.kt:11–13", panel.blocks[0].locationLink.text)

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(document.getLineStartOffset(11), document.getLineEndOffset(11), "changed")
        }
        PlatformTestUtil.waitWithEventsDispatching("the changed code did not show", { panel.blocks[0].snippet!!.editor.document.text == "line 10\nchanged\nline 12" }, TIMEOUT_SECONDS)
        assertEquals("The code changed after the agent wrote this step.", panel.blocks[0].note.text)
    }

    fun `test comment on current step opens the comment input of the step in the timeline`() {
        show(kinds)
        val panel = openTimeline()
        // The added line of the proposal opens the file line before it.
        panel.blocks[2].snippet!!.lineClicked(2, clickCount = 2)
        player.awaitPlayback()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(20, player.presentation!!.editor.caretModel.logicalPosition.line)

        player.commentOnCurrentStep()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val block = panel.blocks[2]
        assertTrue(block.composer.isVisible)
        assertSame(block.comments.composer, block.composer.getComponent(0))
        assertEquals("New comment about this step", block.comments.shownInput()!!.title)
        assertTrue(panel.blocks.filter { it !== block }.none { it.composer.isVisible })
    }

    fun `test the details and the step card name the kind of a step`() {
        show(kinds)
        val stored = player.currentStory!!
        val details = StoryDetailsPanel()
        details.showStep(StepItem(stored, 2))
        assertTrue(details.displayedText, details.displayedText.startsWith("Step 3 of 5 (proposal): Retry the call"))
        assertTrue(details.displayedText, details.displayedText.endsWith("Proposed code:\nline 20\n    retry()"))
        details.showStep(StepItem(stored, 0))
        assertTrue(details.displayedText, details.displayedText.startsWith("Step 1 of 5: Entry point"))

        player.play(stored, 2)
        player.awaitPlayback()
        val card = player.presentation!!.card
        assertEquals("Step 3 of 5 · Proposal", card.stepNumber.text)
        assertTrue(card.previewProposal.isVisible)
        player.play(stored, 0)
        player.awaitPlayback()
        assertEquals("Step 1 of 5", player.presentation!!.card.stepNumber.text)
        assertFalse(player.presentation!!.card.previewProposal.isVisible)
    }

    fun `test paths from story files never show as HTML`() {
        val name = "<html><b>bold.kt"
        try {
            write("src/$name", "fun bold() {}\n")
        } catch (_: InvalidPathException) {
            return // The file system does not allow this name.
        }
        show(story(step(file = quoted("src/$name"), start = "1", end = "1", anchor = quoted("fun bold() {}"))))
        val link = openTimeline().blocks.single().locationLink
        assertEquals("$name:1", link.text)
        assertNull(link.getClientProperty(BasicHTML.propertyKey))
    }

    private fun show(json: String): String {
        // Like the MCP tool: off the EDT.
        val report = runBlocking { withContext(Dispatchers.IO) { CodeStoryService.getInstance(project).show(Json.parseToJsonElement(json)) } }
        assertEquals(report.errors.toString(), ShowStatus.ACCEPTED, report.status)
        PlatformTestUtil.waitWithEventsDispatching("the story does not play", {
            player.currentStory?.id == report.storyId && !player.isResolving
        }, TIMEOUT_SECONDS)
        return report.storyId!!
    }

    private fun openTimeline(): TimelinePanel {
        val panel = TimelinePanel(project).also { panel = it }
        panel.setSize(800, 2000)
        settle()
        return panel
    }

    /** Waits until the timeline has shown what it read, including reloads that playback started. */
    private fun settle() {
        repeat(3) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val loaded = panel!!.loaded()
            PlatformTestUtil.waitWithEventsDispatching("the timeline did not load", { loaded.isDone }, TIMEOUT_SECONDS)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    /** A single click as AWT reports it, on snippet line [line] of the code or of the line numbers. */
    private fun click(snippet: CodeSnippet, line: Int, onLineNumbers: Boolean = false) {
        val target = if (onLineNumbers) snippet.editor.gutterComponentEx else snippet.editor.contentComponent
        val y = snippet.editor.logicalPositionToXY(LogicalPosition(line, 0)).y + 1
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, y, 1, false, MouseEvent.BUTTON1))
    }

    /** Lays out [component] and its children, which `validate` skips without a window. Wrapped text needs a few passes. */
    private fun layOut(component: Component) {
        fun layOutTree(c: Component) {
            if (c !is Container) return
            c.doLayout()
            c.components.forEach(::layOutTree)
        }
        repeat(3) { layOutTree(component) }
    }

    private fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10
    }
}
