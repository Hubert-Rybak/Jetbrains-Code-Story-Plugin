package io.github.hubertrybak.codestory.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.PositionTracker
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.CodeStorySettings
import io.github.hubertrybak.codestory.StorySettingsListener
import io.github.hubertrybak.codestory.StoryReaderListener
import io.github.hubertrybak.codestory.core.StoredStory
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/** Owns everything installed in an editor for one step. No editor inlays are used. */
class StepPresentation(
    private val project: Project,
    val editor: Editor,
    private var stored: StoredStory,
    private val index: Int,
    private val player: StoryPlayer,
) : Disposable {
    val card = StepCardPanel(project, stored, index, player, ::dismissCard, ::queueUpdate, ::focusCode)
    val range = checkNotNull(StepNavigator.getInstance(project).currentHighlighter)
    val badges = mutableListOf<RangeHighlighter>()
    var balloon: Balloon? = null
        private set
    var inToolWindow = false
        private set
    var cardDismissed = false
        private set
    var disposed = false
        private set
    private var updateQueued = false
    private var anchor = Point()

    init {
        project.messageBus.connect(this).subscribe(StoryReaderListener.TOPIC, StoryReaderListener {
            ApplicationManager.getApplication().invokeLater({
                if (!disposed) card.refreshComments(player.currentStory?.takeIf { it.key == stored.key } ?: stored)
            }, ModalityState.nonModal(), Condition<Any?> { disposed || project.isDisposed })
        })
        installBadges()
        editor.scrollingModel.addVisibleAreaListener({ queueUpdate() }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = queueUpdate()
        }, this)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = queueUpdate()
        }, this)
        val componentListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = queueUpdate()
            override fun componentShown(e: ComponentEvent) = queueUpdate()
            override fun componentHidden(e: ComponentEvent) = queueUpdate()
        }
        val hierarchyListener = HierarchyListener {
            if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) queueUpdate()
        }
        editor.component.addComponentListener(componentListener)
        editor.component.addHierarchyListener(hierarchyListener)
        Disposer.register(this, Disposable {
            editor.component.removeComponentListener(componentListener)
            editor.component.removeHierarchyListener(hierarchyListener)
        })
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = queueUpdate()
        })
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                if (event.editor == editor && !disposed) player.stop()
            }
        }, this)
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { refreshTheme() })
        connection.subscribe(StorySettingsListener.TOPIC, StorySettingsListener { refreshTheme() })
    }

    fun showCard() {
        updatePlacement()
        FileEditorManager.getInstance(project).runWhenLoaded(editor) { queueUpdate() }
    }

    /** Sharing changes the story's file identity while its editor and partially typed comment stay open. */
    fun relocated(previous: StoredStory, current: StoredStory) {
        if (stored.key != previous.key) return
        stored = current
        badges.forEach { highlighter ->
            (highlighter.gutterIconRenderer as? StepBadge)?.let { highlighter.gutterIconRenderer = it.relocated(current) }
        }
        card.relocated(previous, current)
    }

    fun dismissCard() {
        cardDismissed = true
        hideBalloon()
        setToolWindow(false)
        player.changed()
    }

    fun startComment() {
        cardDismissed = false
        card.startComment()
        updatePlacement()
        if (balloon == null && !inToolWindow) setToolWindow(true)
        card.comments.startComment()
    }

    /** Escape in the card's comment input returns to the code. */
    private fun focusCode() {
        if (!disposed && !editor.isDisposed) IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }

    private fun queueUpdate() {
        if (disposed || updateQueued) return
        updateQueued = true
        ApplicationManager.getApplication().invokeLater({
            updateQueued = false
            updatePlacement()
        }, ModalityState.nonModal(), Condition<Any?> { disposed || project.isDisposed })
    }

    /** Repositions without navigating, scrolling or taking focus; a comment input that had the focus keeps it. */
    internal fun updatePlacement() {
        if (disposed || editor.isDisposed || !range.isValid) return
        if (cardDismissed) return
        val visible = editor.scrollingModel.visibleArea
        val start = editor.offsetToXY(range.startOffset)
        val end = editor.offsetToXY(range.endOffset)
        val active = FileEditorManager.getInstance(project).selectedEditors.filterIsInstance<TextEditor>().any { it.editor == editor }
        // While the reader writes a comment, the card stays in view in the tool window, with the keyboard focus.
        val writing = card.comments.isWriting()
        val focusedInput = card.comments.shownInput()?.text?.takeIf { it.isFocusOwner }
        fun dock(show: Boolean) {
            hideBalloon()
            setToolWindow(show)
            focusedInput?.requestFocusInWindow()
        }
        if (!active) return dock(writing)
        // Until the editor has a viewport, keep its explanation available in the tool window.
        if (visible.width <= 0 || visible.height <= 0) return dock(true)
        if (!editor.contentComponent.isShowing || end.y + editor.lineHeight <= visible.y || start.y >= visible.y + visible.height) {
            return dock(writing)
        }
        val document = editor.document
        val firstLine = maxOf(document.getLineNumber(range.startOffset), editor.xyToLogicalPosition(Point(visible.x, visible.y)).line)
        val lastLine = minOf(document.getLineNumber(range.endOffset), editor.xyToLogicalPosition(Point(visible.x, visible.y + visible.height)).line)
        var rangeRight = start.x
        var wrapped = false
        for (line in firstLine..lastLine) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val from = editor.offsetToXY(lineStart)
            val to = editor.offsetToXY(lineEnd)
            rangeRight = maxOf(rangeRight, from.x, to.x)
            if (from.y != to.y) wrapped = true
        }
        val margin = JBUI.scale(8)
        // The balloon's own border around the card.
        val frame = JBUI.scale(BALLOON_INSET + 1)
        val maxHeight = visible.height - 2 * margin - 2 * frame
        val rangeTop = maxOf(start.y, visible.y)

        /** The card's top sits at the range; a card that is taller than the room below moves up. */
        fun boundsFor(size: Dimension): Rectangle {
            val height = size.height + 2 * frame
            val top = (rangeTop - margin).coerceIn(visible.y + margin, maxOf(visible.y + margin, visible.y + visible.height - margin - height))
            // The card is taller than the range, so it must also clear the longer lines next to the range.
            var codeRight = rangeRight
            val coveredFirst = editor.xyToLogicalPosition(Point(visible.x, top)).line.coerceIn(0, document.lineCount - 1)
            val coveredLast = editor.xyToLogicalPosition(Point(visible.x, top + height)).line.coerceIn(0, document.lineCount - 1)
            for (line in coveredFirst..coveredLast) {
                if (editor.foldingModel.isOffsetCollapsed(document.getLineStartOffset(line))) continue
                codeRight = maxOf(codeRight, editor.offsetToXY(document.getLineEndOffset(line)).x)
            }
            return Rectangle(maxOf(codeRight, visible.x) + JBUI.scale(16), top, size.width + 2 * frame, height)
        }
        var size = card.fittedSize(Int.MAX_VALUE, maxHeight)
        var bounds = boundsFor(size)
        // Without room for the reader's width, a narrower card can still fit beside the code.
        val room = visible.x + visible.width - margin - bounds.x - 2 * frame
        card.room = Dimension(room, maxHeight)
        if (room < size.width && room >= card.minimumWidth) {
            size = card.fittedSize(room, maxHeight)
            bounds = boundsFor(size)
        }
        val caret = editor.visualPositionToXY(editor.caretModel.visualPosition)
        val caretBounds = Rectangle(caret.x, caret.y, JBUI.scale(8), editor.lineHeight)
        if (wrapped || !visible.contains(bounds) || bounds.intersects(caretBounds)) {
            // Without room beside the code, the card still floats over the editor: below or above the
            // range, or over the code at the editor's right edge. Only an editor narrower than the
            // card itself sends it to the tool window, so resizing the IDE never moves it there.
            val width = visible.width - 2 * margin - 2 * frame
            if (width < card.minimumWidth || maxHeight <= 0) return dock(true)
            val right = visible.x + visible.width - margin
            val rangeBottom = minOf(end.y + editor.lineHeight, visible.y + visible.height)
            fun at(size: Dimension, top: Int) =
                Rectangle(minOf(maxOf(start.x, visible.x + margin), right - size.width - 2 * frame), top, size.width + 2 * frame, size.height + 2 * frame)
            val below = (visible.y + visible.height - margin - rangeBottom - margin - 2 * frame).let { room ->
                card.fittedSize(width, room).takeIf { it.height <= room }?.let { it to at(it, rangeBottom + margin) }
            }
            val above = (rangeTop - margin - visible.y - margin - 2 * frame).let { room ->
                card.fittedSize(width, room).takeIf { it.height <= room }?.let { it to at(it, rangeTop - margin - it.height - 2 * frame) }
            }
            val overlay = card.fittedSize(width, maxHeight).let { fitted ->
                fitted to Rectangle(right - fitted.width - 2 * frame, boundsFor(fitted).y, fitted.width + 2 * frame, fitted.height + 2 * frame)
            }
            val placed = listOfNotNull(below, above).firstOrNull { (_, it) -> visible.contains(it) && !it.intersects(caretBounds) } ?: overlay
            size = placed.first
            bounds = placed.second
            card.room = Dimension(width, maxHeight)
        }
        setToolWindow(false)
        card.floating = true
        card.preferredSize = size
        // Without a pointer, the balloon centers on the anchor, so the card's top-left stays where it was put.
        anchor = Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
        val existing = balloon
        if (existing != null && !existing.isDisposed) existing.revalidate() else showBalloon()
        focusedInput?.requestFocusInWindow()
    }

    private fun showBalloon() {
        card.parent?.remove(card)
        val created = JBPopupFactory.getInstance().createBalloonBuilder(card)
            .setFillColor(UIUtil.getPanelBackground()).setBorderColor(JBColor.border())
            .setBorderInsets(JBUI.insets(BALLOON_INSET))
            .setHideOnClickOutside(false).setHideOnKeyOutside(false).setHideOnAction(false)
            .setHideOnFrameResize(false).setHideOnLinkClick(false).setCloseButtonEnabled(false)
            .setRequestFocus(false).setShowCallout(false).setAnimationCycle(0).setDisposable(this)
            .createBalloon()
        balloon = created
        created.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (balloon === created && !disposed) dismissCard()
            }
        })
        created.show(object : PositionTracker<Balloon>(editor.contentComponent) {
            override fun recalculateLocation(balloon: Balloon) = RelativePoint(editor.contentComponent, anchor)
        }, Balloon.Position.atRight)
    }

    private fun hideBalloon() {
        val old = balloon
        balloon = null
        old?.let { Disposer.dispose(it) }
    }

    private fun setToolWindow(show: Boolean) {
        if (show) card.floating = false
        if (inToolWindow == show) return
        inToolWindow = show
        player.changed()
        if (show) ToolWindowManager.getInstance(project).getToolWindow(CodeStoryService.TOOL_WINDOW_ID)?.show()
    }

    private fun refreshTheme() {
        if (disposed || editor.isDisposed || !range.isValid) return
        (editor.markupModel as MarkupModelEx).setRangeHighlighterAttributes(
            range, TextAttributes().apply { backgroundColor = CodeStorySettings.getInstance().rangeBackground },
        )
        hideBalloon()
        card.refreshTheme()
        editor.contentComponent.repaint()
        queueUpdate()
    }

    private fun installBadges() {
        val currentFile = checkNotNull(stored.story.steps[index].location).file
        for ((stepIndex, step) in stored.story.steps.withIndex()) {
            val location = step.location ?: continue
            if (location.file != currentFile) continue
            val line = (location.startLine - 1).coerceIn(0, editor.document.lineCount - 1)
            badges += editor.markupModel.addRangeHighlighter(
                editor.document.getLineStartOffset(line), editor.document.getLineStartOffset(line),
                HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.LINES_IN_RANGE,
            ).apply { gutterIconRenderer = StepBadge(stored, stepIndex, stepIndex == index, player, editor) { card.currentStory } }
        }
    }

    override fun dispose() {
        disposed = true
        hideBalloon()
        inToolWindow = false
        card.parent?.remove(card)
        badges.forEach { it.dispose() }
        badges.clear()
        range.dispose()
    }

    private companion object {
        /** The space between the balloon's border and the card, which has its own padding. */
        const val BALLOON_INSET = 4
    }
}

/**
 * The number of a step in the gutter, as large as other gutter icons. The current step is filled
 * with the accent color; other steps are outlined in the color of the line numbers.
 */
internal class StepNumberIcon(number: Int, private val current: Boolean, private val lineNumbers: () -> Color?) : Icon {
    private val label = number.toString()

    override fun getIconWidth() = JBUI.scale(if (label.length > 1) 18 else 14)
    override fun getIconHeight() = JBUI.scale(14)

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g)
            UISettings.setupAntialiasing(g)
            val width = iconWidth.toFloat()
            val height = iconHeight.toFloat()
            val arc = JBUI.scale(6).toFloat()
            val muted = lineNumbers() ?: UIUtil.getContextHelpForeground()
            if (current) {
                g.color = ACCENT
                g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width, height, arc, arc))
                // Not JBColor.WHITE, which turns black in dark themes.
                g.color = Color.WHITE
            } else {
                val line = JBUIScale.scale(1f)
                g.color = muted
                g.stroke = BasicStroke(line)
                g.draw(RoundRectangle2D.Float(x + line / 2, y + line / 2, width - line, height - line, arc, arc))
            }
            g.font = JBFont.label().deriveFont(Font.BOLD, JBUIScale.scale(9.5f))
            // Center the digits themselves; font metrics leave room for accents and descenders.
            val glyphs = g.font.createGlyphVector(g.fontRenderContext, label).visualBounds
            g.drawString(label, (x + (width - glyphs.width) / 2 - glyphs.x).toFloat(), (y + (height - glyphs.height) / 2 - glyphs.y).toFloat())
        } finally {
            g.dispose()
        }
    }

    private companion object {
        /** The accent of default buttons: white text stays readable on it in light and dark themes. */
        val ACCENT = JBColor.namedColor("CodeStory.stepBadgeBackground", JBColor(0x3574F0, 0x3574F0))
    }
}

private class StepBadge(
    private val stored: StoredStory,
    private val index: Int,
    private val current: Boolean,
    private val player: StoryPlayer,
    private val editor: Editor,
    private val presentedStory: () -> StoredStory,
) : GutterIconRenderer() {
    // The line number color of the caret row is the stronger one, so other steps stay easy to find.
    override fun getIcon(): Icon = StepNumberIcon(index + 1, current) {
        editor.colorsScheme.getColor(EditorColors.LINE_NUMBER_ON_CARET_ROW_COLOR) ?: editor.colorsScheme.getColor(EditorColors.LINE_NUMBERS_COLOR)
    }
    override fun getTooltipText() = "Step ${index + 1}: ${stored.story.steps[index].title}"
    override fun getAccessibleName() = tooltipText
    override fun isNavigateAction() = true
    override fun isDumbAware() = true
    override fun getClickAction() = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            // Answers arrive without replacing the presentation, so its original snapshot can be stale.
            val latest = player.currentStory?.takeIf { it.key == stored.key }
                ?: presentedStory().takeIf { it.key == stored.key } ?: stored
            val stepId = stored.story.steps[index].id
            val target = if (stepId == null) index else latest.story.steps.indexOfFirst { it.id == stepId }
            if (target in latest.story.steps.indices) player.play(latest, target)
        }
    }
    fun relocated(stored: StoredStory) = StepBadge(stored, index, current, player, editor, presentedStory)
    override fun equals(other: Any?) = other is StepBadge && other.stored.key == stored.key && other.index == index && other.current == current
    override fun hashCode() = 31 * stored.key.hashCode() + index + if (current) 1 else 0
}
