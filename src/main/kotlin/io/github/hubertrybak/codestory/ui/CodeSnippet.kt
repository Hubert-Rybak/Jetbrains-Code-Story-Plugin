package io.github.hubertrybak.codestory.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import io.github.hubertrybak.codestory.core.DiffLine
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * The lines that a code snippet shows. [numbers] has the file line of each line, or `null` for a
 * line that only a proposal has. A proposal also has the line of each line in the proposed code,
 * and the change of each line.
 */
class SnippetLines(
    val text: List<String>,
    val numbers: List<Int?>,
    val proposedNumbers: List<Int?>? = null,
    val changes: List<DiffLine.Change>? = null,
) {
    companion object {
        fun code(lines: List<String>, firstLine: Int) = SnippetLines(lines, lines.indices.map { firstLine + it })

        /** A proposal for the lines from [firstLine]: the proposed code starts at the same line. */
        fun proposal(diff: List<DiffLine>, firstLine: Int): SnippetLines {
            var proposed = firstLine
            return SnippetLines(
                diff.map { it.text }, diff.map { it.line },
                diff.map { if (it.change == DiffLine.Change.REMOVED) null else proposed++ }, diff.map { it.change },
            )
        }

        /** New code that a proposal adds, without a place in a file. */
        fun newCode(lines: List<String>) = SnippetLines(lines, lines.map { null }, changes = lines.map { DiffLine.Change.ADDED })
    }

    /** The same lines without the indentation that all of them share, so nested code does not use up the width of the timeline. */
    fun dedented(): SnippetLines {
        val indent = text.filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it == ' ' || it == '\t' } }
            .reduceOrNull { a, b -> a.commonPrefixWith(b) }
        if (indent.isNullOrEmpty()) return this
        return SnippetLines(text.map { if (it.startsWith(indent)) it.substring(indent.length) else it.trimStart() }, numbers, proposedNumbers, changes)
    }
}

/**
 * Code in a read-only editor, with the colors of its file type and the line numbers of its file.
 * The indentation that all lines share is left out. Long lines wrap, and the snippet is as tall
 * as its code, so the timeline around it scrolls instead of the snippet. A click on a line
 * reports the file line of that line.
 */
class CodeSnippet(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val document = EditorFactory.getInstance().createDocument("")
    val editor: EditorEx = EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW) as EditorEx
    var lines = SnippetLines(emptyList(), emptyList())
        private set
    private val changeHighlighters = mutableListOf<RangeHighlighter>()
    /** The file, or the name of the file, whose highlighter colors the code. */
    private var highlighted: Any? = null
    private var hovered = false

    /** Gets the file line of a clicked line, or the nearest file line for a line that only a proposal has, and whether it was a double-click. */
    var onLineClick: (Int?, Boolean) -> Unit = { _, _ -> }

    init {
        Disposer.register(parent) { EditorFactory.getInstance().releaseEditor(editor) }
        editor.settings.apply {
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isAutoCodeFoldingEnabled = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isCaretRowShown = false
            isRightMarginShown = false
            isVirtualSpace = false
            isUseSoftWraps = true
            // Wrapped lines keep their indentation; arrows at the wrap points would only add noise.
            isPaintSoftWraps = false
            setGutterIconsShown(false)
        }
        editor.setVerticalScrollbarVisible(false)
        editor.setHorizontalScrollbarVisible(false)
        editor.setBorder(JBUI.Borders.empty())
        editor.contextMenuGroupId = null
        val wheel = MouseWheelListener(::scrollTimeline)
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = setHovered(true)
            override fun mouseExited(e: MouseEvent) = setHovered(false)

            // A double-click opens the code at its second press: while the first click plays the
            // step, the release and the click of the second can miss the snippet.
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) clicked(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) clicked(e)
            }

            // The events of AWT, not of the editor, which reports no click without its release.
            // The code and the line numbers share their y.
            private fun clicked(e: MouseEvent) =
                lineClicked(editor.xyToLogicalPosition(Point(0, e.y)).line, e.clickCount, onLineNumbers = e.component === editor.gutterComponentEx)
        }
        for (component in listOf(editor.contentComponent, editor.gutterComponentEx)) {
            component.addMouseWheelListener(wheel)
            component.addMouseListener(mouse)
        }
        // Wrapped lines change the height of the code when the timeline gets narrower or wider.
        editor.contentComponent.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = revalidate()
        })
        isOpaque = false
        updateBorder()
        add(editor.component, BorderLayout.CENTER)
    }

    /** Shows [code] of [file], or of a file named [fileName] when the file cannot open. */
    fun show(code: SnippetLines, file: VirtualFile?, fileName: String?) {
        val lines = code.dedented()
        val text = lines.text.joinToString("\n")
        if (document.text != text) ApplicationManager.getApplication().runWriteAction { document.setText(text) }
        this.lines = lines
        val source: Any? = file ?: fileName
        if (source != highlighted) {
            editor.highlighter = highlighter(file, fileName)
            highlighted = source
        }
        val numbered = lines.numbers.any { it != null } || lines.proposedNumbers?.any { it != null } == true
        editor.settings.isLineNumbersShown = numbered
        editor.gutter.setLineNumberConverter(Numbers(lines.numbers), lines.proposedNumbers?.let(::Numbers))
        changeHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        changeHighlighters.clear()
        lines.changes?.forEachIndexed { line, change ->
            val key = when (change) {
                DiffLine.Change.REMOVED -> DiffColors.DIFF_DELETED
                DiffLine.Change.ADDED -> DiffColors.DIFF_INSERTED
                DiffLine.Change.KEPT -> return@forEachIndexed
            }
            changeHighlighters += editor.markupModel.addLineHighlighter(key, line, HighlighterLayer.SELECTION - 1)
        }
        revalidate()
        repaint()
    }

    /**
     * The reader clicked snippet line [line], 0-based, [clickCount] times. A click that selects
     * text, for example with Shift, does not open the code, so the reader can copy it. A
     * double-click selects a word first; it opens the code with the focus, without the selection.
     */
    internal fun lineClicked(line: Int, clickCount: Int, onLineNumbers: Boolean = false) {
        if (clickCount == 1 && !onLineNumbers && editor.selectionModel.hasSelection()) return
        if (clickCount > 1) editor.selectionModel.removeSelection()
        if (line in lines.text.indices) onLineClick(fileLine(line), clickCount > 1)
    }

    /** The file line of snippet line [line]: for a line that only a proposal has, the file line before it. */
    private fun fileLine(line: Int): Int? = (line downTo 0).firstNotNullOfOrNull { lines.numbers.getOrNull(it) }
        ?: lines.numbers.firstNotNullOfOrNull { it }

    private fun highlighter(file: VirtualFile?, fileName: String?): EditorHighlighter = ApplicationManager.getApplication().runReadAction(Computable {
        val factory = EditorHighlighterFactory.getInstance()
        if (file != null && file.isValid) factory.createEditorHighlighter(project, file)
        else factory.createEditorHighlighter(project, FileTypeManager.getInstance().getFileTypeByFileName(fileName ?: ""))
    })

    /** The timeline scrolls; a snippet is as tall as its code, so it has nothing to scroll. */
    private fun scrollTimeline(event: MouseWheelEvent) {
        val timeline = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane ?: return
        timeline.dispatchEvent(SwingUtilities.convertMouseEvent(event.component, event, timeline))
        event.consume()
    }

    private fun setHovered(value: Boolean) {
        if (hovered == value) return
        hovered = value
        updateBorder()
    }

    /** A click on the code opens it, so the border shows the accent color under the mouse. */
    private fun updateBorder() {
        border = JBUI.Borders.customLine(if (hovered) TimelineColors.ACCENT else JBColor.border(), 1)
    }

    // The width comes from the timeline. The height is the height of the code, with its wrapped lines.
    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(MIN_WIDTH), editor.contentComponent.preferredSize.height + insets.top + insets.bottom)

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_WIDTH), preferredSize.height)

    /** Line numbers from a list: one entry for each line of the snippet, `null` for no number. */
    private class Numbers(private val numbers: List<Int?>) : LineNumberConverter {
        override fun convert(editor: Editor, lineNumber: Int): Int? = numbers.getOrNull(lineNumber - 1)
        override fun getMaxLineNumber(editor: Editor): Int? = numbers.filterNotNull().maxOrNull()
    }

    private companion object {
        const val MIN_WIDTH = 120
    }
}
