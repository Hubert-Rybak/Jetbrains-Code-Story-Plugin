package io.github.hubertrybak.codestory

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.SimpleColoredComponent
import io.github.hubertrybak.codestory.core.ShowStatus
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.StoryType
import io.github.hubertrybak.codestory.ui.StepNavigator
import io.github.hubertrybak.codestory.ui.StoryItem
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode

/** The Code Story tool window lists the saved stories, and a click on a step opens its code. */
class StoryListPanelTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private var panel: StoryListPanel? = null

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        write("src/App.kt", (1..60).joinToString("\n", postfix = "\n") { "line $it" })
        write("src/Billing.kt", "class Billing {\n    fun charge() {}\n}\n")
    }

    override fun tearDown() {
        try {
            StoryPlayer.getInstance(project).stop()
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            panel?.let { Disposer.dispose(it) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test lists stories with their steps`() {
        val id = show(story(step(start = "40", end = "52"), step(file = quoted("src/Billing.kt"), start = "2", end = "2", anchor = quoted("fun charge() {}"), title = quoted("Charge"))))

        val tree = openPanel().tree

        assertEquals(5, tree.rowCount)
        assertEquals("Payment flow  ${id.take(10)}", rowText(0))
        assertEquals("1. Entry point  App.kt:40–52  ✓", rowText(1))
        assertEquals("2. Charge  Billing.kt:2", rowText(2))
    }

    fun `test a story from the agent appears in the open tool window`() {
        val tree = openPanel().tree
        assertEquals(0, tree.rowCount)

        show(story(step(), title = quoted("Bug trace")))
        PlatformTestUtil.waitWithEventsDispatching("the new story is not selected", { tree.rowCount == 4 && tree.minSelectionRow == 2 }, TIMEOUT_SECONDS)

        assertTrue(rowText(0).startsWith("Bug trace"))
    }

    fun `test clicking a step opens the file and highlights the range`() {
        show(story(step(start = "40", end = "52"), step(file = quoted("src/Billing.kt"), start = "2", end = "3", anchor = quoted("fun charge() {}"), title = quoted("Charge"))))
        openPanel()

        click(row = 1)

        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("App.kt", FileDocumentManager.getInstance().getFile(editor.document)!!.name)
        assertEquals(39, editor.caretModel.logicalPosition.line)
        assertTrue(panel!!.details.displayedText.contains("The app starts here."))
        val highlighter = StepNavigator.getInstance(project).currentHighlighter!!
        assertTrue(highlighter in editor.markupModel.allHighlighters)
        assertEquals(39, editor.document.getLineNumber(highlighter.startOffset))
        assertEquals(51, editor.document.getLineNumber(highlighter.endOffset))

        click(row = 2)

        val billing = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("Billing.kt", FileDocumentManager.getInstance().getFile(billing.document)!!.name)
        val next = StepNavigator.getInstance(project).currentHighlighter!!
        assertEquals(1, billing.document.getLineNumber(next.startOffset))
        assertEquals(2, billing.document.getLineNumber(next.endOffset))
        assertFalse("the earlier highlight is removed", highlighter.isValid)
    }

    fun `test story details show the full header`() {
        val git = GitFixture(root)
        git.init()
        val commit = git.command("rev-parse", "HEAD")
        show(story(step(), type = quoted("review")))
        openPanel()
        panel!!.tree.setSelectionRow(1)
        val text = panel!!.details.displayedText
        assertTrue(text, text.contains("Payment flow"))
        assertTrue(text, text.contains("Type: review"))
        assertTrue(text, text.contains("The controller charges the card."))
        assertTrue(text, text.contains("Request: Explain the payment flow"))
        assertTrue(text, text.contains("Written by Test Agent (test-model)"))
        assertTrue(text, text.contains("Date:"))
        assertTrue(text, text.contains("Branch: story-branch"))
        assertTrue(text, text.contains("Commit: $commit"))
        assertFalse(text, text.contains("Current branch is"))
        assertTrue(rowText(0), rowText(0).endsWith("  story-branch"))
    }

    fun `test clicking and pressing Enter on a text-only step leave the editor as it is`() {
        show(story(step(start = "40", end = "52"), step(title = quoted("Overview"), location = null)))
        openPanel()
        click(1)
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.selectedTextEditor!!
        val files = manager.openFiles.toList()
        val caret = editor.caretModel.offset
        val scroll = editor.scrollingModel.visibleArea
        val highlighter = StepNavigator.getInstance(project).currentHighlighter

        click(2)
        assertEquals("2. Overview  ✓", rowText(2))
        assertTrue(panel!!.details.displayedText.contains("Step 2 of 2: Overview"))
        assertTrue(panel!!.details.displayedText.contains("The app starts here."))
        val event = KeyEvent(panel!!.tree, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n')
        panel!!.tree.keyListeners.forEach { it.keyPressed(event) }
        StoryPlayer.getInstance(project).awaitPlayback()
        assertTrue(event.isConsumed)
        assertSame(editor, manager.selectedTextEditor)
        assertEquals(files, manager.openFiles.toList())
        assertEquals(caret, editor.caretModel.offset)
        assertEquals(scroll, editor.scrollingModel.visibleArea)
        assertSame(highlighter, StepNavigator.getInstance(project).currentHighlighter)
    }

    fun `test a text-only step can be opened without an editor`() {
        show(story(step(location = null)))
        openPanel()
        click(1)
        assertTrue(panel!!.details.displayedText.contains("The app starts here."))
        assertEmpty(FileEditorManager.getInstance(project).openFiles.toList())
        assertNull(StepNavigator.getInstance(project).currentHighlighter)
    }

    fun `test refreshing on another branch shows a note without changing stored metadata`() {
        val git = GitFixture(root)
        git.init()
        show(story(step()))
        openPanel()
        panel!!.tree.setSelectionRow(1)
        git.command("checkout", "-b", "other-branch")
        panel!!.reload()
        PlatformTestUtil.waitWithEventsDispatching(
            "branch difference is not shown",
            { panel!!.details.displayedText.contains("Current branch is other-branch") },
            TIMEOUT_SECONDS,
        )
        assertTrue(panel!!.details.displayedText.contains("this story was created on story-branch"))
        assertEquals("story-branch", CodeStoryService.getInstance(project).stories().single().story.git.branch)
    }

    fun `test each story type has a distinct icon`() {
        val icons = StoryType.entries.map { type ->
            val id = show(story(step(), title = quoted(type.value), type = quoted(type.value)))
            if (panel == null) openPanel()
            panel!!.showStories(CodeStoryService.getInstance(project).stories(), id)
            val tree = panel!!.tree
            val path = (0 until tree.rowCount).map { tree.getPathForRow(it) }.first {
                ((it.lastPathComponent as DefaultMutableTreeNode).userObject as? StoryItem)?.stored?.id == id
            }
            tree.selectionPath = path
            val row = tree.getRowForPath(path)
            val renderer = tree.cellRenderer.getTreeCellRendererComponent(tree, tree.getPathForRow(row).lastPathComponent, false, true, false, row, false)
            (renderer as SimpleColoredComponent).icon
        }
        assertEquals(4, icons.toSet().size)
        assertTrue(icons.all { it != null })
    }

    private fun show(json: String): String {
        // Like the MCP tool: off the EDT.
        val report = runBlocking { withContext(Dispatchers.IO) { CodeStoryService.getInstance(project).show(Json.parseToJsonElement(json)) } }
        assertEquals(report.errors.toString(), ShowStatus.ACCEPTED, report.status)
        val player = StoryPlayer.getInstance(project)
        PlatformTestUtil.waitWithEventsDispatching("the story does not play", {
            player.currentStory?.id == report.storyId && !player.isResolving
        }, TIMEOUT_SECONDS)
        return report.storyId!!
    }

    private fun openPanel(): StoryListPanel {
        val panel = StoryListPanel(project).also { panel = it }
        val stories = CodeStoryService.getInstance(project).stories()
        PlatformTestUtil.waitWithEventsDispatching(
            "the stories are not listed",
            { panel.tree.rowCount == 2 + stories.sumOf { it.story.steps.size + 1 } || (stories.isEmpty() && loaded(panel)) },
            TIMEOUT_SECONDS,
        )
        panel.tree.setSize(600, 400)
        return panel
    }

    /** The first load ends with an empty tree too, so wait until the loader is idle. */
    private fun loaded(panel: StoryListPanel): Boolean {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return panel.tree.rowCount == 0
    }

    private fun rowText(row: Int): String {
        val tree = panel!!.tree
        val path = tree.getPathForRow(row + 1)
        val renderer = tree.cellRenderer.getTreeCellRendererComponent(tree, path.lastPathComponent, false, true, true, row, false)
        return (renderer as SimpleColoredComponent).getCharSequence(false).toString()
    }

    private fun click(row: Int) {
        val tree = panel!!.tree
        val bounds = tree.getRowBounds(row + 1)
        val x = bounds.x + 2
        val y = bounds.y + bounds.height / 2
        tree.setSelectionRow(row + 1)
        for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            val event = MouseEvent(tree, id, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1)
            // The tree's own press handling needs a display; the click listener does not check this flag on press.
            if (id == MouseEvent.MOUSE_PRESSED) event.consume()
            tree.dispatchEvent(event)
        }
        StoryPlayer.getInstance(project).awaitPlayback()
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
