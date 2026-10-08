package io.github.hubertrybak.codestory.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
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
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.PositionTracker
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.CodeStorySettings
import io.github.hubertrybak.codestory.StorySettingsListener
import io.github.hubertrybak.codestory.core.StoredStory
import java.awt.Component
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.Icon

/** Owns everything installed in an editor for one step. No editor inlays are used. */
class StepPresentation(
    private val project: Project,
    val editor: Editor,
    private val stored: StoredStory,
    private val index: Int,
    private val player: StoryPlayer,
) : Disposable {
    val card = StepCardPanel(project, stored, index, player, ::dismissCard)
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

    fun dismissCard() {
        cardDismissed = true
        hideBalloon()
        setToolWindow(false)
        player.changed()
    }

    private fun queueUpdate() {
        if (disposed || updateQueued) return
        updateQueued = true
        ApplicationManager.getApplication().invokeLater({
            updateQueued = false
            updatePlacement()
        }, ModalityState.nonModal(), Condition<Any?> { disposed || project.isDisposed })
    }

    /** Repositions without navigating, scrolling or requesting focus. */
    internal fun updatePlacement() {
        if (disposed || editor.isDisposed || !range.isValid) return
        if (cardDismissed) return
        val visible = editor.scrollingModel.visibleArea
        val start = editor.offsetToXY(range.startOffset)
        val end = editor.offsetToXY(range.endOffset)
        val active = FileEditorManager.getInstance(project).selectedEditors.filterIsInstance<TextEditor>().any { it.editor == editor }
        if (!active) {
            hideBalloon()
            setToolWindow(false)
            return
        }
        // Until the editor has a viewport, keep its explanation available in the tool window.
        if (visible.width <= 0 || visible.height <= 0) {
            hideBalloon()
            setToolWindow(true)
            return
        }
        if (!editor.contentComponent.isShowing || end.y + editor.lineHeight <= visible.y || start.y >= visible.y + visible.height) {
            hideBalloon()
            setToolWindow(false)
            return
        }
        val document = editor.document
        val firstLine = maxOf(document.getLineNumber(range.startOffset), editor.xyToLogicalPosition(Point(visible.x, visible.y)).line)
        val lastLine = minOf(document.getLineNumber(range.endOffset), editor.xyToLogicalPosition(Point(visible.x, visible.y + visible.height)).line)
        var codeRight = start.x
        var wrapped = false
        for (line in firstLine..lastLine) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val from = editor.offsetToXY(lineStart)
            val to = editor.offsetToXY(lineEnd)
            codeRight = maxOf(codeRight, from.x, to.x)
            if (from.y != to.y) wrapped = true
        }
        val padding = JBUI.scale(40)
        val width = card.preferredSize.width + padding
        val height = card.preferredSize.height + padding
        val targetY = maxOf(start.y, visible.y) + editor.lineHeight / 2
        val cardY = (targetY - height / 2).coerceIn(visible.y, maxOf(visible.y, visible.y + visible.height - height))
        anchor = Point(maxOf(codeRight, visible.x) + JBUI.scale(8), targetY)
        val bounds = Rectangle(anchor.x, cardY, width, height)
        val caret = editor.visualPositionToXY(editor.caretModel.visualPosition)
        val caretBounds = Rectangle(caret.x, caret.y, JBUI.scale(8), editor.lineHeight)
        val fits = !wrapped && visible.contains(bounds) && !bounds.intersects(caretBounds)
        if (!fits) {
            hideBalloon()
            setToolWindow(true)
        } else {
            setToolWindow(false)
            val existing = balloon
            if (existing != null && !existing.isDisposed) existing.revalidate() else showBalloon()
        }
    }

    private fun showBalloon() {
        card.parent?.remove(card)
        val created = JBPopupFactory.getInstance().createBalloonBuilder(card)
            .setFillColor(UIUtil.getPanelBackground()).setBorderColor(JBColor.border())
            .setHideOnClickOutside(false).setHideOnKeyOutside(false).setHideOnAction(false)
            .setHideOnFrameResize(false).setHideOnLinkClick(false).setCloseButtonEnabled(false)
            .setRequestFocus(false).setShowCallout(true).setAnimationCycle(0).setDisposable(this)
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
            ).apply { gutterIconRenderer = StepBadge(stored, stepIndex, stepIndex == index, player) }
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
}

private class StepBadge(private val stored: StoredStory, private val index: Int, private val current: Boolean, private val player: StoryPlayer) : GutterIconRenderer() {
    override fun getIcon(): Icon = object : Icon {
        override fun getIconWidth() = JBUI.scale(18)
        override fun getIconHeight() = JBUI.scale(18)
        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create()
            try {
                g.font = UIUtil.getLabelFont().deriveFont(JBUI.scale(11).toFloat())
                val accent = JBUI.CurrentTheme.Link.Foreground.ENABLED
                g.color = if (current) accent else UIUtil.getPanelBackground()
                g.fillOval(x, y, iconWidth, iconHeight)
                g.color = if (current) UIUtil.getPanelBackground() else UIUtil.getLabelForeground()
                val text = "${index + 1}"
                g.drawString(text, x + (iconWidth - g.fontMetrics.stringWidth(text)) / 2, y + (iconHeight - g.fontMetrics.height) / 2 + g.fontMetrics.ascent)
            } finally { g.dispose() }
        }
    }
    override fun getTooltipText() = "Step ${index + 1}: ${stored.story.steps[index].title}"
    override fun getAccessibleName() = tooltipText
    override fun isNavigateAction() = true
    override fun isDumbAware() = true
    override fun getClickAction() = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) = player.play(stored, index)
    }
    override fun equals(other: Any?) = other is StepBadge && other.stored.key == stored.key && other.index == index && other.current == current
    override fun hashCode() = 31 * stored.key.hashCode() + index + if (current) 1 else 0
}
