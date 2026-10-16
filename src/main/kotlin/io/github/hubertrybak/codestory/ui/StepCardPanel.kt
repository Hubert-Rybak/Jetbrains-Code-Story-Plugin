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
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.CodeStorySettings
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryMarkdown
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit
import kotlin.math.roundToInt

/** What the reader sees about the code of a step: a warning for a lost step, a note for changed code. */
fun statusNote(status: StepStatus): String? = when (status) {
    StepStatus.LOST -> "The code changed. This step can be wrong."
    StepStatus.CODE_CHANGED -> "The code changed after the agent wrote this step."
    StepStatus.OK, StepStatus.MOVED -> null
}

/**
 * This exact panel moves between the balloon and the tool window. The step text and the comments
 * scroll; the comment input and the playback buttons stay in view. The reader resizes a floating
 * card by its right edge, its bottom edge or its corner grip, and every card keeps that size.
 */
class StepCardPanel(
    private val project: Project,
    private var stored: StoredStory,
    private val index: Int,
    player: StoryPlayer,
    dismiss: () -> Unit,
    private val layoutChanged: () -> Unit = {},
    focusCode: () -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(GAP))) {
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
    val close = InplaceButton(IconButton("Close card (keep story position)", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { dismiss() }
    /** The warning or note about the code of the step, if it changed. */
    val statusText: String? = statusNote(stored.status(index))
    private val noteIcon = JLabel(if (stored.status(index) == StepStatus.LOST) AllIcons.General.Warning else AllIcons.General.Information)
        .apply { verticalAlignment = SwingConstants.TOP }
    private val note = statusText?.let { text -> PlainText(text).apply { isFocusable = false; accessibleContext.accessibleName = text } }
    private val stepNumber = JBLabel("Step ${index + 1} of ${stored.story.steps.size}").apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val title = PlainText(stored.story.steps[index].title, bold = true).apply { isFocusable = false }
    val comment = JEditorPane().apply {
        isEditable = false
        isOpaque = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) openLink(it.description) }
    }
    private val readerComments = StoryComments(project)
    val comments = CommentsPanel(readerComments, compact = true, layoutChanged = ::resizeCard, escape = focusCode).apply { isOpaque = false }
    val resizeGrip: JComponent = ResizeGrip()

    /** `true` in the balloon beside the code; `false` in the tool window, where the card fills the width. */
    var floating = true
        set(value) {
            if (field == value) return
            field = value
            resizeGrip.isVisible = value
            cursor = Cursor.getDefaultCursor()
            if (!value) dockedSize()
        }

    /** The width and height that the reader chose by dragging; `null` keeps the default width and a height that fits. */
    private var chosenWidth: Int? = CodeStorySettings.getInstance().state.cardWidth.takeIf { it > 0 }?.let { JBUIScale.scale(it) }
    private var chosenHeight: Int? = CodeStorySettings.getInstance().state.cardHeight.takeIf { it > 0 }?.let { JBUIScale.scale(it) }

    private class Measure(val width: Int, val header: Int, val body: Int, val footer: Int)
    private var measured: Measure? = null

    private val body = object : JPanel(VerticalLayout(12)), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply {
        isOpaque = false
        add(comment)
        add(comments)
    }
    private val scroll = ScrollPaneFactory.createScrollPane(body, true).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(JPanel(VerticalLayout(2)).apply {
            isOpaque = false
            add(stepNumber)
            add(title)
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(close, BorderLayout.NORTH)
        }, BorderLayout.EAST)
        note?.let {
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(8)
                add(noteIcon, BorderLayout.WEST)
                add(it, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }
    }
    private val footer = JPanel(VerticalLayout(10)).apply {
        isOpaque = false
        add(comments.composer)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(HorizontalLayout(6)).apply {
                isOpaque = false
                add(previous); add(next); add(stop)
            }, BorderLayout.WEST)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(resizeGrip, BorderLayout.SOUTH)
            }, BorderLayout.EAST)
        })
    }

    init {
        // The balloon paints the rounded background; the tool window has the same color.
        isOpaque = false
        border = JBUI.Borders.empty(10, 12)
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        Resizer(grip = false).let { addMouseListener(it); addMouseMotionListener(it) }
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (!floating && preferredSize.width != width) dockedSize()
            }
        })
        refreshTheme()
        refreshComments(stored)
    }

    /** Moves the keyboard focus to the comment input, which is always in view. */
    fun startComment() {
        comments.startComment()
        // Resizing may move the input from a balloon to the tool window first.
        ApplicationManager.getApplication().invokeLater({
            comments.startComment()
        }, ModalityState.nonModal(), project.disposed)
    }

    fun refreshComments(stored: StoredStory) {
        this.stored = stored
        comments.show(stored, index)
        resizeCard()
    }

    fun relocated(previous: StoredStory, current: StoredStory) {
        comments.relocated(previous, current)
        refreshComments(current)
    }

    fun refreshTheme() {
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
        note?.foreground = UIUtil.getLabelForeground()
        stepNumber.foreground = UIUtil.getContextHelpForeground()
        resizeCard()
    }

    /**
     * The floating size within [maxWidth] × [maxHeight]: the width that the reader chose, or the
     * default width, and the height that the reader chose, or the height of the content up to a default.
     */
    fun fittedSize(maxWidth: Int, maxHeight: Int): Dimension {
        val width = (chosenWidth ?: JBUI.scale(DEFAULT_WIDTH)).coerceAtMost(maxWidth).coerceAtLeast(minimumWidth)
        val measure = measure(width)
        val content = contentHeight(measure)
        val height = (chosenHeight ?: content.coerceAtMost(JBUI.scale(DEFAULT_HEIGHT))).coerceAtMost(maxHeight)
        return Dimension(width, height.coerceAtLeast(minimumHeight(measure).coerceAtMost(content)))
    }

    /** Narrower than this, the buttons and the comment input do not fit, so the card goes to the tool window. */
    val minimumWidth: Int get() = JBUI.scale(MIN_WIDTH)

    /** The header and the footer, with at least a few lines of the step text between them. */
    private fun minimumHeight(measure: Measure) = contentHeight(measure) - measure.body + JBUI.scale(MIN_BODY)

    private fun contentHeight(measure: Measure) =
        insets.top + measure.header + measure.body + measure.footer + 2 * JBUI.scale(GAP) + insets.bottom

    /**
     * Measures the parts of the card at [width]: wrapped text knows its height only after it has a
     * width. The step text keeps its scroll position.
     */
    private fun measure(width: Int): Measure {
        measured?.takeIf { it.width == width }?.let { return it }
        val inner = width - insets.left - insets.right
        val position = scroll.viewport.viewPosition
        val heights = listOf(header, body, footer).map { part ->
            part.setSize(inner, Short.MAX_VALUE.toInt())
            repeat(2) { layoutTree(part) }
            part.preferredSize.height
        }
        scroll.viewport.viewPosition = position
        revalidate()
        return Measure(width, heights[0], heights[1], heights[2]).also { measured = it }
    }

    private fun layoutTree(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach(::layoutTree)
    }

    /** In the tool window, the card is as wide as the tool window and as high as its content, or the chosen height. */
    private fun dockedSize() {
        if (width <= 0) return
        preferredSize = Dimension(width, chosenHeight ?: contentHeight(measure(width)).coerceAtMost(JBUI.scale(DEFAULT_HEIGHT)))
        revalidate()
    }

    private fun resizeCard() {
        measured = null
        if (!floating) dockedSize()
        revalidate()
        repaint()
        layoutChanged()
    }

    /**
     * The largest size beside the code at the last placement. A drag stops there, so the card follows
     * the mouse back at once, and the saved size is the size that the reader saw.
     */
    internal var room: Dimension? = null

    private fun resizeTo(width: Int?, height: Int?) {
        width?.let { chosenWidth = it.coerceAtMost(room?.width ?: Int.MAX_VALUE).coerceIn(minimumWidth, JBUI.scale(MAX_WIDTH)) }
        height?.let { chosenHeight = it.coerceAtMost(room?.height ?: Int.MAX_VALUE).coerceIn(JBUI.scale(MIN_HEIGHT), JBUI.scale(MAX_HEIGHT)) }
        layoutChanged()
    }

    private fun saveSize() = CodeStorySettings.getInstance().state.apply {
        cardWidth = chosenWidth?.let { (it / JBUIScale.scale(1f)).roundToInt() } ?: 0
        cardHeight = chosenHeight?.let { (it / JBUIScale.scale(1f)).roundToInt() } ?: 0
    }

    private fun resetSize() {
        chosenWidth = null
        chosenHeight = null
        saveSize()
        resizeCard()
    }

    /** Drags the right edge, the bottom edge, or both with the grip; a double-click on the grip restores the default size. */
    private inner class Resizer(private val grip: Boolean) : MouseAdapter() {
        private var start: Point? = null
        private var startSize = Dimension()
        private var edges = 0

        private fun edgesAt(e: MouseEvent): Int = when {
            !floating -> 0
            grip -> RIGHT or BOTTOM
            else -> (if (e.x >= width - insets.right) RIGHT else 0) or (if (e.y >= height - insets.bottom) BOTTOM else 0)
        }

        override fun mouseMoved(e: MouseEvent) {
            if (grip) return
            cursor = Cursor.getPredefinedCursor(when (edgesAt(e)) {
                RIGHT or BOTTOM -> Cursor.SE_RESIZE_CURSOR
                RIGHT -> Cursor.E_RESIZE_CURSOR
                BOTTOM -> Cursor.S_RESIZE_CURSOR
                else -> Cursor.DEFAULT_CURSOR
            })
        }

        override fun mouseExited(e: MouseEvent) {
            if (!grip && start == null) cursor = Cursor.getDefaultCursor()
        }

        override fun mousePressed(e: MouseEvent) {
            edges = edgesAt(e)
            if (edges == 0) return
            start = e.locationOnScreen
            startSize = this@StepCardPanel.size
        }

        override fun mouseDragged(e: MouseEvent) {
            val from = start ?: return
            resizeTo(
                (startSize.width + e.xOnScreen - from.x).takeIf { edges and RIGHT != 0 },
                (startSize.height + e.yOnScreen - from.y).takeIf { edges and BOTTOM != 0 },
            )
        }

        override fun mouseReleased(e: MouseEvent) {
            if (start == null) return
            start = null
            saveSize()
        }

        override fun mouseClicked(e: MouseEvent) {
            if (grip && e.clickCount == 2) resetSize()
        }
    }

    /** Three diagonal rows of dots in the bottom-right corner, like a window size grip. */
    private inner class ResizeGrip : JPanel() {
        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
            toolTipText = "Drag to resize the card. Double-click to restore the default size."
            getAccessibleContext().accessibleName = "Resize card"
            Resizer(grip = true).let { addMouseListener(it); addMouseMotionListener(it) }
        }

        override fun getPreferredSize(): Dimension = JBUI.size(12, 12)

        override fun paintComponent(g: Graphics) {
            val g2 = g.create()
            try {
                GraphicsUtil.setupAAPainting(g2)
                g2.color = JBColor.namedColor("Label.disabledForeground", JBColor.GRAY)
                val dot = JBUI.scale(2)
                val step = JBUI.scale(4)
                for (row in 0 until 3) for (column in 0..row) {
                    g2.fillOval(width - dot - column * step, height - dot - (row - column) * step, dot, dot)
                }
            } finally {
                g2.dispose()
            }
        }
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

    private companion object {
        const val GAP = 10
        const val DEFAULT_WIDTH = 440
        const val DEFAULT_HEIGHT = 440
        const val MIN_WIDTH = 320
        const val MIN_HEIGHT = 200
        const val MAX_WIDTH = 1200
        const val MAX_HEIGHT = 1200
        /** At least this much of the step text stays visible in a short card. */
        const val MIN_BODY = 48
        const val RIGHT = 1
        const val BOTTOM = 2
    }
}
