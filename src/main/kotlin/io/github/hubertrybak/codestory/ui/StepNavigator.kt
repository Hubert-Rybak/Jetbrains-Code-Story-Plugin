package io.github.hubertrybak.codestory.ui

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.hubertrybak.codestory.CodeStorySettings
import io.github.hubertrybak.codestory.core.Location
import java.lang.ref.WeakReference
import java.nio.file.Path

/** Opens the code of a step: the file, scrolled to the range, with the range highlighted. */
@Service(Service.Level.PROJECT)
class StepNavigator(private val project: Project) {
    /** Weak, so that a closed editor is not kept in memory. */
    private var highlighter = WeakReference<RangeHighlighter>(null)

    /** Returns the editor, or `null` when the file cannot be opened as text. Call on the EDT. */
    fun open(root: Path, location: Location): Editor? {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(location.file))
            ?.takeUnless { it.isDirectory }
            ?: return null
        // The descriptor scrolls with ScrollType.CENTER, which puts the line in the upper third of the editor.
        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(
                OpenFileDescriptor(project, file, location.startLine - 1, 0)
                    .setUsePreviewTab(UISettings.getInstance().openInPreviewTabIfPossible), false,
            )
            ?: return null
        highlight(editor, location)
        return editor
    }

    /** The range highlighted last, while its editor is open. */
    val currentHighlighter: RangeHighlighter? get() = highlighter.get()?.takeIf { it.isValid }

    fun clear() {
        highlighter.get()?.dispose()
        highlighter.clear()
    }

    private fun highlight(editor: Editor, location: Location) {
        clear()
        val document = editor.document
        if (document.lineCount == 0) return
        // The file can have changed since the agent wrote the story.
        val lastLine = document.lineCount - 1
        val startLine = (location.startLine - 1).coerceIn(0, lastLine)
        val endLine = (location.endLine - 1).coerceIn(startLine, lastLine)
        highlighter = WeakReference(
            editor.markupModel.addRangeHighlighter(
                document.getLineStartOffset(startLine),
                document.getLineEndOffset(endLine),
                HighlighterLayer.SELECTION - 1,
                TextAttributes().apply { backgroundColor = CodeStorySettings.getInstance().rangeBackground },
                HighlighterTargetArea.LINES_IN_RANGE,
            ),
        )
    }

    companion object {
        fun getInstance(project: Project): StepNavigator = project.service()
    }
}
