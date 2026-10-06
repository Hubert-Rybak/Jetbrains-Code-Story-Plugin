package io.github.hubertrybak.codestory.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.UISettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryMarkdown
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

/** This exact panel moves between the balloon and the tool window. */
class StepCardPanel(
    private val project: Project,
    private val stored: StoredStory,
    private val index: Int,
    player: StoryPlayer,
    dismiss: () -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(8))) {
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
    val comment = JEditorPane().apply {
        isEditable = false
        isOpaque = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) openLink(it.description) }
    }

    init {
        border = JBUI.Borders.empty(10)
        val step = stored.story.steps[index]
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JLabel("<html>Step ${index + 1} of ${stored.story.steps.size} · ${StoryMarkdown.escape(step.title)}</html>"), BorderLayout.CENTER)
            add(close, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(comment, true).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(previous); add(next); add(stop)
        }, BorderLayout.SOUTH)
        refreshTheme()
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
        val width = JBUI.scale(320)
        comment.setSize(width - JBUI.scale(20), Short.MAX_VALUE.toInt())
        preferredSize = Dimension(width, comment.preferredSize.height.coerceAtMost(JBUI.scale(200)) + JBUI.scale(90))
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
