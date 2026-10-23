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
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.xmlb.XmlSerializer
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.Step
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoryAuthor
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryType
import io.github.hubertrybak.codestory.ui.StepNavigator
import io.github.hubertrybak.codestory.ui.StepNumberIcon
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import io.github.hubertrybak.codestory.ui.StoryReadingPosition
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import javax.swing.JComponent
import kotlin.math.abs
import kotlin.math.roundToInt

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
        player.awaitPlayback()
        val first = player.presentation!!
        assertEquals(0, player.stepIndex)
        assertFalse(first.card.previous.isEnabled)
        first.card.next.doClick()
        player.awaitPlayback()
        assertEquals(1, player.stepIndex)
        assertTrue(first.disposed)
        assertFalse(first.range.isValid)
        assertEquals("second.txt", player.presentation!!.editor.virtualFile!!.name)
        assertEquals("Finish", player.presentation!!.card.next.text)
        player.presentation!!.card.previous.doClick()
        player.awaitPlayback()
        assertEquals(0, player.stepIndex)
        player.next()
        player.awaitPlayback()
        val last = player.presentation!!
        last.card.next.doClick()
        player.awaitPlayback()
        assertFalse(player.isPlaying)
        assertTrue(last.disposed)
        assertFalse(last.range.isValid)
        assertNull(player.presentation)
        assertTrue(player.wasVisited(stored, 0))
        assertTrue(player.wasVisited(stored, 1))
    }

    fun `test only one story plays and stop removes all presentation`() {
        player.play(story())
        player.awaitPlayback()
        val earlier = player.presentation!!
        val badges = earlier.badges.toList()
        player.play(story("other"))
        player.awaitPlayback()
        assertEquals("other", player.currentStory!!.id)
        assertTrue(earlier.disposed)
        assertTrue(badges.all { !it.isValid })
        val current = player.presentation!!
        val currentBadges = current.badges.toList()
        current.card.stop.doClick()
        player.awaitPlayback()
        assertTrue(current.disposed)
        assertFalse(current.range.isValid)
        assertTrue(currentBadges.all { !it.isValid })
        assertNull(current.card.parent)
        assertNull(current.balloon)
        assertFalse(current.inToolWindow)
    }

    fun `test text-only playback shows details without changing the editor`() {
        val stored = story(steps = listOf(step("first.txt", 2), Step("Overview", "Keep the current code in view."), step("second.txt", 1)))
        val panel = StoryListPanel(project)
        try {
            awaitInitialLoad(panel)
            panel.showStories(listOf(stored))
            player.play(stored)
            player.awaitPlayback()
            val current = player.presentation!!
            val manager = FileEditorManager.getInstance(project)
            val files = manager.openFiles.toList()
            val caret = current.editor.caretModel.offset
            val scroll = current.editor.scrollingModel.visibleArea
            val badges = current.badges.toList()
            assertEquals(1, badges.size)

            player.next()
            player.awaitPlayback()

            assertEquals(1, player.stepIndex)
            assertTrue(player.wasVisited(stored, 1))
            assertTrue(panel.details.displayedText.contains("Step 2 of 3: Overview"))
            assertTrue(panel.details.displayedText.contains("Keep the current code in view."))
            assertSame(current.editor, manager.selectedTextEditor)
            assertEquals(files, manager.openFiles.toList())
            assertEquals(caret, current.editor.caretModel.offset)
            assertEquals(scroll, current.editor.scrollingModel.visibleArea)
            assertSame(current.range, StepNavigator.getInstance(project).currentHighlighter)
            assertTrue(badges.all { it.isValid })
            assertTrue(current.cardDismissed)
            assertNull(current.card.parent)

            panel.tree.setSelectionRow(1)
            assertTrue(panel.details.displayedText.contains("Written by Test Agent"))
            player.play(stored, 1)
            player.awaitPlayback()
            assertTrue(panel.details.displayedText.contains("Step 2 of 3: Overview"))

            player.next()
            player.awaitPlayback()
            assertEquals("second.txt", manager.selectedTextEditor!!.virtualFile!!.name)
            assertTrue(current.disposed)
            assertFalse(current.range.isValid)
            val next = player.presentation!!
            player.previous()
            player.awaitPlayback()
            assertEquals(1, player.stepIndex)
            assertSame(next.range, StepNavigator.getInstance(project).currentHighlighter)
            assertTrue(panel.details.displayedText.contains("Step 2 of 3: Overview"))
            player.stop()
            assertFalse(next.range.isValid)
        } finally { Disposer.dispose(panel) }
    }

    fun `test a text-only story can play and finish without opening an editor`() {
        val stored = story(steps = listOf(Step("Overview", "Start here."), Step("Checks", "Check the result.")))
        player.play(stored)
        player.awaitPlayback()
        val panel = StoryListPanel(project)
        try {
            awaitInitialLoad(panel)
            panel.showStories(listOf(stored), stored.key)
            assertTrue(panel.details.displayedText.contains("Step 1 of 2: Overview"))
            assertTrue(player.isPlaying)
            assertNull(player.presentation)
            assertEmpty(FileEditorManager.getInstance(project).openFiles.toList())
            player.next()
            player.awaitPlayback()
            assertTrue(panel.details.displayedText.contains("Step 2 of 2: Checks"))
            assertEquals(1, player.stepIndex)
            player.next()
            player.awaitPlayback()
            assertFalse(player.isPlaying)
            assertNull(StepNavigator.getInstance(project).currentHighlighter)
            assertEmpty(FileEditorManager.getInstance(project).openFiles.toList())
        } finally { Disposer.dispose(panel) }
    }

    fun `test play resumes and restart uses workspace position without changing the story`() {
        val stored = story()
        Files.writeString(stored.file, StoryFormat.encode(stored.story, stored.id))
        val before = Files.readString(stored.file)
        player.play(stored, 1)
        player.awaitPlayback()
        player.stop()
        val positions = project.service<StoryReadingPosition>()
        // Round-trip the exact persistent bean, as IDE workspace storage does.
        val saved = XmlSerializer.serialize(positions.state)
        positions.loadState(XmlSerializer.deserialize(saved, StoryReadingPosition.Positions::class.java))
        player.play(stored)
        player.awaitPlayback()
        assertEquals(1, player.stepIndex)
        player.restart(stored)
        player.awaitPlayback()
        assertEquals(0, player.stepIndex)
        assertEquals(before, Files.readString(stored.file))
        assertTrue(player.wasVisited(stored, 1))
    }

    fun `test existing workspace reading position survives repository grouping`() {
        val stored = story()
        project.service<StoryReaderState>().prepare(stored, legacyIdentityUnique = true)
        val positions = project.service<StoryReadingPosition>().state.steps
        positions[stored.id] = 1
        player.play(stored)
        player.awaitPlayback()
        assertEquals(1, player.stepIndex)
        assertEquals(1, positions[stored.key])
        assertFalse(positions.containsKey(stored.id))
    }

    fun `test closing and reopening card keeps range badges and position`() {
        player.play(story())
        player.awaitPlayback()
        val current = player.presentation!!
        val panel = StoryListPanel(project)
        try {
            current.updatePlacement()
            assertSame(current.card, panel.cardContainer.components.single())
            current.card.close.doClick()
            player.awaitPlayback()
            assertTrue(current.cardDismissed)
            assertTrue(player.isPlaying)
            assertTrue(current.range.isValid)
            assertTrue(current.badges.all { it.isValid })
            assertEmpty(panel.cardContainer.components.toList())
            // Ordinary presentation updates do not reopen an explicitly dismissed card.
            current.updatePlacement()
            assertTrue(current.cardDismissed)
            player.play(player.currentStory!!, player.stepIndex)
            player.awaitPlayback()
            assertTrue(current.disposed)
            val reopened = player.presentation!!
            assertFalse(reopened.cardDismissed)
            assertSame(reopened.card, panel.cardContainer.components.single())
            player.next()
            player.awaitPlayback()
            assertFalse(player.presentation!!.cardDismissed)
            player.stop()
            assertEmpty(panel.cardContainer.components.toList())
        } finally { Disposer.dispose(panel) }
    }

    fun `test dragging the card resizes every card and a double-click on the grip restores the default`() {
        val settings = CodeStorySettings.getInstance().state
        val saved = settings.cardWidth to settings.cardHeight
        try {
            settings.cardWidth = 0
            settings.cardHeight = 0
            player.play(story())
            player.awaitPlayback()
            val card = player.presentation!!.card
            val start = card.fittedSize(Int.MAX_VALUE, Int.MAX_VALUE)
            assertEquals(JBUIScale.scale(440), start.width)
            card.floating = true
            card.size = start
            drag(card.resizeGrip, Point(4, 4), 60, 40)
            val resized = Dimension(start.width + 60, start.height + 40)
            assertEquals(resized, card.fittedSize(Int.MAX_VALUE, Int.MAX_VALUE))
            assertEquals(unscaled(resized.width) to unscaled(resized.height), settings.cardWidth to settings.cardHeight)
            // Beside the code, the card gets narrower and shorter instead of covering code.
            assertEquals(Dimension(card.minimumWidth, resized.height - 20), card.fittedSize(card.minimumWidth, resized.height - 20))

            // The next card has the same size.
            player.next()
            player.awaitPlayback()
            val next = player.presentation!!.card
            assertEquals(resized, next.fittedSize(Int.MAX_VALUE, Int.MAX_VALUE))
            // The right edge changes the width only, and a drag stops at the room beside the code.
            next.floating = true
            next.size = resized
            next.room = Dimension(resized.width + 20, resized.height)
            drag(next, Point(resized.width - 2, resized.height / 2), 100, 50)
            assertEquals(Dimension(resized.width + 20, resized.height), next.fittedSize(Int.MAX_VALUE, Int.MAX_VALUE))
            // Away from the edges, a drag changes nothing.
            drag(next, Point(resized.width / 2, 2), 100, 50)
            assertEquals(Dimension(resized.width + 20, resized.height), next.fittedSize(Int.MAX_VALUE, Int.MAX_VALUE))

            next.floating = true
            next.resizeGrip.mouseListeners.forEach { it.mouseClicked(mouse(next.resizeGrip, MouseEvent.MOUSE_CLICKED, Point(4, 4), clicks = 2)) }
            assertEquals(0 to 0, settings.cardWidth to settings.cardHeight)
            assertEquals(start.width, next.fittedSize(Int.MAX_VALUE, Int.MAX_VALUE).width)
        } finally {
            settings.cardWidth = saved.first
            settings.cardHeight = saved.second
        }
    }

    fun `test step badges are gutter sized, filled for the current step and outlined for others`() {
        val muted = Color(0x767A8A)
        assertEquals(JBUIScale.scale(14) to JBUIScale.scale(14), StepNumberIcon(3, false) { muted }.let { it.iconWidth to it.iconHeight })
        assertEquals(JBUIScale.scale(18), StepNumberIcon(12, false) { muted }.iconWidth)
        fun paint(number: Int, current: Boolean): BufferedImage {
            val icon = StepNumberIcon(number, current) { muted }
            return BufferedImage(icon.iconWidth * 2, icon.iconHeight * 2, BufferedImage.TYPE_INT_ARGB).also { image ->
                val g = image.createGraphics()
                try { g.scale(2.0, 2.0); icon.paintIcon(null, g, 0, 0) } finally { g.dispose() }
            }
        }
        fun rgb(image: BufferedImage, x: Int, y: Int) = image.getRGB(x, y) and 0xFFFFFF
        fun nearWhite(pixel: Int) = listOf(16, 8, 0).all { (pixel shr it and 0xFF) >= 200 }
        // Antialiasing may round a channel by one.
        fun assertColor(expected: Int, actual: Int) =
            assertTrue("%06x is not %06x".format(actual, expected), listOf(16, 8, 0).all { abs((expected shr it and 0xFF) - (actual shr it and 0xFF)) <= 2 })

        val current = paint(2, true)
        val middle = current.height / 2
        assertColor(0x3574F0, rgb(current, 3, middle))
        assertTrue("The current step's number is white", (0 until current.width).any { nearWhite(rgb(current, it, middle)) })
        val other = paint(3, false)
        assertColor(muted.rgb and 0xFFFFFF, rgb(other, 0, middle))
        assertEquals("Other steps are outlined, not filled", 0, other.getRGB(4, middle) ushr 24)
    }

    private fun unscaled(value: Int) = (value / JBUIScale.scale(1f)).roundToInt()

    private fun mouse(component: JComponent, id: Int, at: Point, clicks: Int = 1) = MouseEvent(
        component, id, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, at.x, at.y, at.x + 100, at.y + 100, clicks, false, MouseEvent.BUTTON1,
    )

    private fun drag(component: JComponent, from: Point, dx: Int, dy: Int) {
        val to = Point(from.x + dx, from.y + dy)
        component.mouseListeners.forEach { it.mousePressed(mouse(component, MouseEvent.MOUSE_PRESSED, from)) }
        component.mouseMotionListeners.forEach { it.mouseDragged(mouse(component, MouseEvent.MOUSE_DRAGGED, to)) }
        component.mouseListeners.forEach { it.mouseReleased(mouse(component, MouseEvent.MOUSE_RELEASED, to)) }
    }

    fun `test badges navigate other steps in the current file and reopen the current card`() {
        val stored = story(steps = listOf(step("first.txt", 2), step("first.txt", 30), step("second.txt", 1)))
        player.play(stored)
        player.awaitPlayback()
        val first = player.presentation!!
        assertEquals(2, first.badges.size)
        first.dismissCard()
        val event = AnActionEvent.createFromAnAction(first.badges[0].gutterIconRenderer!!.clickAction!!, null, "test", DataContext.EMPTY_CONTEXT)
        first.badges[0].gutterIconRenderer!!.clickAction!!.actionPerformed(event)
        player.awaitPlayback()
        assertTrue(first.disposed)
        val reopened = player.presentation!!
        assertFalse(reopened.cardDismissed)
        reopened.badges[1].gutterIconRenderer!!.clickAction!!.actionPerformed(event)
        player.awaitPlayback()
        assertEquals(1, player.stepIndex)
        assertTrue(first.disposed)
        assertEquals(2, player.presentation!!.badges.size)
    }

    fun `test presentation adds no code line inlays and browsing does not navigate`() {
        player.play(story())
        player.awaitPlayback()
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
        player.awaitPlayback()
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
            player.awaitPlayback()
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
            player.awaitPlayback()
        }
        player.play(story())
        player.awaitPlayback()
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
        player.awaitPlayback()
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
        player.awaitPlayback()
        val current = player.presentation!!
        current.card.openLink("second.txt")
        current.dismissCard()
        assertEquals("second.txt", FileEditorManager.getInstance(project).selectedTextEditor!!.virtualFile!!.name)
        player.play(stored, 0)
        player.awaitPlayback()
        assertEquals("first.txt", FileEditorManager.getInstance(project).selectedTextEditor!!.virtualFile!!.name)
        assertFalse(player.presentation!!.cardDismissed)
        assertEquals(0, player.stepIndex)
    }

    fun `test navigation shortcuts are available without built in keymap conflicts`() {
        val manager = KeymapManager.getInstance()
        for (name in listOf("\$default", "Mac OS X 10.5+", "Visual Studio", "Visual Studio OSX", "ReSharper", "ReSharper OSX", "Visual Studio 2022")) {
            val keymap = manager.getKeymap(name) ?: continue
            for (id in listOf("CodeStory.NextStep", "CodeStory.PreviousStep", "CodeStory.StopStory", "CodeStory.CommentOnCurrentStep", "CodeStory.ShowTimeline")) {
                val action = ActionManager.getInstance().getAction(id)
                assertNotNull(id, action)
                val shortcut = keymap.getShortcuts(id).filterIsInstance<KeyboardShortcut>().single()
                assertNotNull(shortcut.secondKeyStroke)
                assertEquals(listOf(id), keymap.getActionIds(shortcut).toList())
                assertEquals(name, setOf("CodeStory.NextStep", "CodeStory.PreviousStep", "CodeStory.StopStory", "CodeStory.CommentOnCurrentStep", "CodeStory.ShowTimeline"), keymap.getActionIds(shortcut.firstKeyStroke).toSet())
            }
        }
    }

    private fun awaitInitialLoad(panel: StoryListPanel) {
        val loaded = panel.loaded()
        PlatformTestUtil.waitWithEventsDispatching("the initial story list did not load", { loaded.isDone }, 20)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun step(file: String, line: Int) = Step("Read $file", "Read `line` and **continue**.", Location(file, line, line))

    private fun story(id: String = "2026-10-06-example", steps: List<Step> = listOf(step("first.txt", 2), step("second.txt", 1))) =
        StoredStory(id, root, root.resolve("$id.story.json"), Story(
            "Example", StoryType.CHANGE, "Read both files.", "Explain the change.", StoryAuthor("Test Agent", "test-model"),
            steps.mapIndexed { index, step -> step.copy(id = step.id ?: "step-${index + 1}") },
            Instant.parse("2026-10-06T12:00:00Z"),
        ), FileTime.fromMillis(0))
}
