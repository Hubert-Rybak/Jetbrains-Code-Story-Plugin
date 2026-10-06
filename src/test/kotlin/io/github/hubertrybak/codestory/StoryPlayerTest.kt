package io.github.hubertrybak.codestory

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.Step
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import io.github.hubertrybak.codestory.ui.StoryReadingPosition
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class StoryPlayerTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private val player get() = StoryPlayer.getInstance(project)

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        Files.createDirectories(root)
        Files.writeString(root.resolve("first.txt"), (1..80).joinToString("\n") { "line $it" })
        Files.writeString(root.resolve("second.txt"), "another file\nsecond line\n")
    }

    override fun tearDown() {
        try {
            player.stop()
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally { super.tearDown() }
    }

    fun `test next previous and finish across files`() {
        val stored = story()
        player.play(stored)
        val first = player.presentation!!
        assertEquals(0, player.stepIndex)
        assertFalse(first.card.previous.isEnabled)
        first.card.next.doClick()
        assertEquals(1, player.stepIndex)
        assertTrue(first.disposed)
        assertFalse(first.range.isValid)
        assertEquals("second.txt", player.presentation!!.editor.virtualFile!!.name)
        assertEquals("Finish", player.presentation!!.card.next.text)
        player.presentation!!.card.previous.doClick()
        assertEquals(0, player.stepIndex)
        player.next()
        val last = player.presentation!!
        last.card.next.doClick()
        assertFalse(player.isPlaying)
        assertTrue(last.disposed)
        assertFalse(last.range.isValid)
        assertNull(player.presentation)
        assertTrue(player.wasVisited(stored.id, 0))
        assertTrue(player.wasVisited(stored.id, 1))
    }

    fun `test only one story plays and stop removes all presentation`() {
        player.play(story())
        val earlier = player.presentation!!
        val badges = earlier.badges.toList()
        player.play(story("other"))
        assertEquals("other", player.currentStory!!.id)
        assertTrue(earlier.disposed)
        assertTrue(badges.all { !it.isValid })
        val current = player.presentation!!
        val currentBadges = current.badges.toList()
        current.card.stop.doClick()
        assertTrue(current.disposed)
        assertFalse(current.range.isValid)
        assertTrue(currentBadges.all { !it.isValid })
        assertNull(current.card.parent)
        assertNull(current.balloon)
        assertFalse(current.inToolWindow)
    }

    fun `test play resumes and restart uses workspace position without changing the story`() {
        val stored = story()
        Files.writeString(stored.file, StoryFormat.encode(stored.story, stored.id))
        val before = Files.readString(stored.file)
        player.play(stored, 1)
        player.stop()
        val positions = project.service<StoryReadingPosition>()
        // Round-trip the exact persistent bean, as IDE workspace storage does.
        val saved = XmlSerializer.serialize(positions.state)
        positions.loadState(XmlSerializer.deserialize(saved, StoryReadingPosition.Positions::class.java))
        player.play(stored)
        assertEquals(1, player.stepIndex)
        player.restart(stored)
        assertEquals(0, player.stepIndex)
        assertEquals(before, Files.readString(stored.file))
        assertTrue(player.wasVisited(stored.id, 1))
    }

    fun `test closing and reopening card keeps range badges and position`() {
        player.play(story())
        val current = player.presentation!!
        val panel = StoryListPanel(project)
        try {
            current.updatePlacement()
            assertSame(current.card, panel.cardContainer.components.single())
            current.card.close.doClick()
            assertTrue(current.cardDismissed)
            assertTrue(player.isPlaying)
            assertTrue(current.range.isValid)
            assertTrue(current.badges.all { it.isValid })
            assertEmpty(panel.cardContainer.components.toList())
            // Ordinary presentation updates do not reopen an explicitly dismissed card.
            current.updatePlacement()
            assertTrue(current.cardDismissed)
            player.play(player.currentStory!!, player.stepIndex)
            assertTrue(current.disposed)
            val reopened = player.presentation!!
            assertFalse(reopened.cardDismissed)
            assertSame(reopened.card, panel.cardContainer.components.single())
            player.next()
            assertFalse(player.presentation!!.cardDismissed)
            player.stop()
            assertEmpty(panel.cardContainer.components.toList())
        } finally { Disposer.dispose(panel) }
    }

    fun `test badges navigate other steps in the current file and reopen the current card`() {
        val stored = story(steps = listOf(step("first.txt", 2), step("first.txt", 30), step("second.txt", 1)))
        player.play(stored)
        val first = player.presentation!!
        assertEquals(2, first.badges.size)
        first.dismissCard()
        val event = AnActionEvent.createFromAnAction(first.badges[0].gutterIconRenderer!!.clickAction!!, null, "test", DataContext.EMPTY_CONTEXT)
        first.badges[0].gutterIconRenderer!!.clickAction!!.actionPerformed(event)
        assertTrue(first.disposed)
        val reopened = player.presentation!!
        assertFalse(reopened.cardDismissed)
        reopened.badges[1].gutterIconRenderer!!.clickAction!!.actionPerformed(event)
        assertEquals(1, player.stepIndex)
        assertTrue(first.disposed)
        assertEquals(2, player.presentation!!.badges.size)
    }

    fun `test presentation adds no code line inlays and browsing does not navigate`() {
        player.play(story())
        val current = player.presentation!!
        val editor = current.editor
        val end = editor.document.textLength
        assertEmpty(editor.inlayModel.getInlineElementsInRange(0, end))
        assertEmpty(editor.inlayModel.getAfterLineEndElementsInRange(0, end))
        assertEmpty(editor.inlayModel.getBlockElementsInRange(0, end))
        val caret = editor.caretModel.offset
        editor.scrollingModel.scrollVertically(200)
        val scroll = editor.scrollingModel.verticalScrollOffset
        current.updatePlacement()
        assertEquals(0, player.stepIndex)
        assertEquals(caret, editor.caretModel.offset)
        assertEquals(scroll, editor.scrollingModel.verticalScrollOffset)
        assertTrue(current.range.isValid)
    }

    fun `test closing the target editor disposes the card and listeners`() {
        player.play(story())
        val current = player.presentation!!
        val badges = current.badges.toList()
        FileEditorManager.getInstance(project).closeFile(current.editor.virtualFile!!)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(current.disposed)
        assertFalse(current.range.isValid)
        assertTrue(badges.all { !it.isValid })
        assertFalse(player.isPlaying)
        assertNull(current.card.parent)
    }

    fun `test settings serialize and update the playing highlight`() {
        val settings = CodeStorySettings.getInstance()
        val original = settings.state
        try {
            val options = CodeStorySettings.Options().apply { bringToFront = false; highlightColor = 0x445566 }
            settings.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(options), CodeStorySettings.Options::class.java))
            assertFalse(settings.state.bringToFront)
            player.play(story())
            val current = player.presentation!!
            assertEquals(settings.rangeBackground, current.range.getTextAttributes(current.editor.colorsScheme)!!.backgroundColor)
        } finally { settings.loadState(original) }
    }

    fun `test keymap actions navigate and stop the same player`() {
        val context = DataContext { id -> if (id == CommonDataKeys.PROJECT.name) project else null }
        fun runAction(id: String) {
            val action = ActionManager.getInstance().getAction(id)
            val event = AnActionEvent.createFromAnAction(action, null, "test", context)
            action.update(event)
            assertTrue(id, event.presentation.isEnabled)
            action.actionPerformed(event)
        }
        player.play(story())
        runAction("CodeStory.NextStep")
        assertEquals(1, player.stepIndex)
        runAction("CodeStory.PreviousStep")
        assertEquals(0, player.stepIndex)
        runAction("CodeStory.StopStory")
        assertFalse(player.isPlaying)
    }

    fun `test project links open only on activation and cannot follow an outside symlink`() {
        val stored = story(steps = listOf(Step("Read", "[other](second.txt#L2)", Location("first.txt", 2, 2))))
        player.play(stored)
        val current = player.presentation!!
        assertEquals("first.txt", FileEditorManager.getInstance(project).selectedTextEditor!!.virtualFile!!.name)
        val outside = Files.createTempFile("code-story-link", ".txt")
        try {
            Files.createSymbolicLink(root.resolve("outside.txt"), outside)
            current.card.openLink("outside.txt")
            assertEquals("first.txt", FileEditorManager.getInstance(project).selectedTextEditor!!.virtualFile!!.name)
            current.card.openLink("second.txt#L2")
            val selected = FileEditorManager.getInstance(project).selectedTextEditor!!
            assertEquals("second.txt", selected.virtualFile!!.name)
            assertEquals(1, selected.caretModel.logicalPosition.line)
            assertEquals(0, player.stepIndex)
            assertSame(current, player.presentation)
        } finally { Files.deleteIfExists(outside) }
    }

    fun `test selecting the current step returns from a different file and reopens the card`() {
        val stored = story()
        player.play(stored)
        val current = player.presentation!!
        current.card.openLink("second.txt")
        current.dismissCard()
        assertEquals("second.txt", FileEditorManager.getInstance(project).selectedTextEditor!!.virtualFile!!.name)
        player.play(stored, 0)
        assertEquals("first.txt", FileEditorManager.getInstance(project).selectedTextEditor!!.virtualFile!!.name)
        assertFalse(player.presentation!!.cardDismissed)
        assertEquals(0, player.stepIndex)
    }

    fun `test navigation shortcuts are available without built in keymap conflicts`() {
        val manager = KeymapManager.getInstance()
        for (name in listOf("\$default", "Mac OS X 10.5+", "Visual Studio", "Visual Studio OSX", "ReSharper", "ReSharper OSX", "Visual Studio 2022")) {
            val keymap = manager.getKeymap(name) ?: continue
            for (id in listOf("CodeStory.NextStep", "CodeStory.PreviousStep", "CodeStory.StopStory")) {
                val action = ActionManager.getInstance().getAction(id)
                assertNotNull(id, action)
                val shortcut = keymap.getShortcuts(id).filterIsInstance<KeyboardShortcut>().single()
                assertNotNull(shortcut.secondKeyStroke)
                assertEquals(listOf(id), keymap.getActionIds(shortcut).toList())
                assertEquals(name, setOf("CodeStory.NextStep", "CodeStory.PreviousStep", "CodeStory.StopStory"), keymap.getActionIds(shortcut.firstKeyStroke).toSet())
            }
        }
    }

    private fun step(file: String, line: Int) = Step("Read $file", "Read `line` and **continue**.", Location(file, line, line))

    private fun story(id: String = "2026-10-06-example", steps: List<Step> = listOf(step("first.txt", 2), step("second.txt", 1))) =
        StoredStory(id, root, root.resolve("$id.story.json"), Story("Example", steps), FileTime.fromMillis(0))
}
