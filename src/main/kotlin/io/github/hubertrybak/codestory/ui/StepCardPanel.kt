package io.github.hubertrybak.codestory.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.UISettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryMarkdown
import io.github.hubertrybak.codestory.core.ThreadState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

/** What the reader sees about the code of a step: a warning for a lost step, a note for changed code. */
fun statusNote(status: StepStatus): String? = when (status) {
    StepStatus.LOST -> "The code changed. This step can be wrong."
    StepStatus.CODE_CHANGED -> "The code changed after the agent wrote this step."
    StepStatus.OK, StepStatus.MOVED -> null
}

/** This exact panel moves between the balloon and the tool window. */
class StepCardPanel(
    private val project: Project,
    private var stored: StoredStory,
    private val index: Int,
    player: StoryPlayer,
    dismiss: () -> Unit,
    private val layoutChanged: () -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    internal val currentStory: StoredStory get() = stored
    val previous = JButton("Prev").apply {
        mnemonic = 'P'.code
        isEnabled = index > 0
        addActionListener { player.previous() }
    }
    val next = JButton(if (index == stored.story.steps.lastIndex) "Finish" else "Next").apply {
        mnemonic = if (index == stored.story.steps.lastIndex) 'F'.code else 'N'.code
        addActionListener { player.next() }
    }
    val stop = JButton("Stop").apply { mnemonic = 'S'.code; addActionListener { player.stop() } }
    val close = JButton(AllIcons.Actions.Close).apply {
        toolTipText = "Close card (keep story position)"
        accessibleContext.accessibleName = "Close card"
        addActionListener { dismiss() }
    }
    /** The warning or note about the code of the step, if it changed. */
    val statusText: String? = statusNote(stored.status(index))
    private val noteIcon = JLabel(if (stored.status(index) == StepStatus.LOST) AllIcons.General.Warning else AllIcons.General.Information)
        .apply { verticalAlignment = SwingConstants.TOP }
    private val note = statusText?.let { text ->
        JTextArea(text).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
            accessibleContext.accessibleName = text
        }
    }
    val comment = JEditorPane().apply {
        isEditable = false
        isOpaque = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) openLink(it.description) }
    }
    private val readerComments = StoryComments(project)
    val comments = CommentsPanel(readerComments, compact = true, layoutChanged = ::resizeCard).apply { isOpaque = false }
    val commentButton = JButton("💬").apply {
        toolTipText = "Comment on current step"
        accessibleContext.accessibleName = "Comment on current step"
        addActionListener { startComment() }
    }
    val threadLine = ActionLink("") {
        comments.expanded = !comments.expanded
        if (comments.expanded) comments.markShownRead()
    }
    private val body = object : JPanel(VerticalLayout(8)), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply {
        isOpaque = false
        add(comment)
        add(threadLine)
        add(comments)
    }

    init {
        border = JBUI.Borders.empty(10)
        val step = stored.story.steps[index]
        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JLabel("<html>Step ${index + 1} of ${stored.story.steps.size} · ${StoryMarkdown.escape(step.title)}</html>"), BorderLayout.CENTER)
            add(close, BorderLayout.EAST)
        }
        add(if (note == null) header else JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(noteIcon, BorderLayout.WEST)
                add(note, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(body, true).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(previous); add(next); add(stop); add(commentButton)
        }, BorderLayout.SOUTH)
        refreshTheme()
        refreshComments(stored)
    }

    fun startComment() {
        comments.expanded = false
        comments.startComment()
        // Resizing may move the input from a balloon to the tool window first.
        ApplicationManager.getApplication().invokeLater({
            comments.shownInput()?.text?.requestFocusInWindow()
        }, ModalityState.nonModal(), project.disposed)
    }

    fun refreshComments(stored: StoredStory) {
        this.stored = stored
        comments.show(stored, index)
        val step = stored.story.steps[index]
        val threads = step.threads.count { it.state != ThreadState.RESOLVED }
        val ids = step.threads.map { it.id }.toSet()
        val drafts = readerComments.drafts(stored).count { it.thread in ids || (it.thread == null && it.step == step.id) }
        threadLine.text = "💬 $threads ${if (threads == 1) "thread" else "threads"}" +
            if (drafts > 0) " · $drafts ${if (drafts == 1) "draft" else "drafts"}" else ""
        resizeCard()
    }

    fun relocated(previous: StoredStory, current: StoredStory) {
        comments.relocated(previous, current)
        refreshComments(current)
    }

    fun refreshTheme() {
        background = UIUtil.getPanelBackground()
        comment.font = UIUtil.getLabelFont()
        comment.foreground = UIUtil.getLabelForeground()
        val kit = HTMLEditorKit()
        val textColor = "#%06x".format(comment.foreground.rgb and 0xffffff)
        kit.styleSheet.addRule("body { color: $textColor; font-family: '${comment.font.family}'; font-size: ${comment.font.size}pt; }")
        kit.styleSheet.addRule("p { margin-top: 0; margin-bottom: 6px; }")
        kit.styleSheet.addRule("ul { margin-top: 0; margin-bottom: 6px; margin-left: 16px; }")
        val linkColor = "#%06x".format(JBUI.CurrentTheme.Link.Foreground.ENABLED.rgb and 0xffffff)
        kit.styleSheet.addRule("a { color: $linkColor; }")
        comment.editorKit = kit
        comment.text = "<html><body>${StoryMarkdown.render(stored.story.steps[index].comment)}</body></html>"
        comment.caretPosition = 0
        resizeCard()
    }

    private fun resizeCard() {
        val width = JBUI.scale(320)
        comment.setSize(width - JBUI.scale(20), Short.MAX_VALUE.toInt())
        val noteHeight = note?.let {
            it.font = UIUtil.getLabelFont()
            it.foreground = UIUtil.getLabelForeground()
            it.setSize(width - JBUI.scale(26) - noteIcon.preferredSize.width, Short.MAX_VALUE.toInt())
            it.preferredSize.height + JBUI.scale(6)
        } ?: 0
        comments.setSize(width - JBUI.scale(20), Short.MAX_VALUE.toInt())
        comments.doLayout()
        val discussionHeight = comments.preferredSize.height.coerceAtMost(JBUI.scale(240))
        preferredSize = Dimension(width, comment.preferredSize.height.coerceAtMost(JBUI.scale(200)) + JBUI.scale(115) + noteHeight + discussionHeight)
        revalidate()
        layoutChanged()
    }

    /** Hyperlinks have no side effects until activation; project links cannot escape the repository. */
    internal fun openLink(target: String) {
        if (StoryMarkdown.isWebLink(target)) {
            BrowserUtil.browse(target)
            return
        }
        val link = StoryMarkdown.fileLink(target) ?: return
        val path = stored.root.resolve(link.path)
        val safe = runCatching { Files.isRegularFile(path) && path.toRealPath().startsWith(stored.root.toRealPath()) }.getOrDefault(false)
        if (!safe) return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, file, link.startLine - 1, 0)
                .setUsePreviewTab(UISettings.getInstance().openInPreviewTabIfPossible), true,
        )
    }
}
