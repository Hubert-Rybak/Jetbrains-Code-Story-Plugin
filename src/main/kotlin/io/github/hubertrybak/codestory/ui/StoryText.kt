package io.github.hubertrybak.codestory.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.core.StoryMarkdown
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

/**
 * Story text in the Markdown subset of stories. Only [StoryMarkdown] produces its HTML, so story
 * files cannot add markup or remote resources. A link does nothing until the reader activates it.
 * New text does not scroll the panel around the pane; see [keepCaretOnTextChange].
 */
class StoryTextPane(private val openLink: (String) -> Unit) : JEditorPane() {
    /** The Markdown that the pane shows. */
    var markdown: String = ""
        set(value) {
            if (field == value) return
            field = value
            render()
        }

    init {
        isEditable = false
        isOpaque = false
        keepCaretOnTextChange()
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) openLink(it.description) }
        refreshTheme()
    }

    // The superclass constructor calls this before the pane is read-only; init sets the caret again.
    override fun updateUI() {
        super.updateUI()
        keepCaretOnTextChange()
    }

    /** The label font and colors of the current theme. */
    fun refreshTheme() {
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getLabelForeground()
        val kit = HTMLEditorKit()
        val textColor = "#%06x".format(foreground.rgb and 0xffffff)
        kit.styleSheet.addRule("body { color: $textColor; font-family: '${font.family}'; font-size: ${font.size}pt; }")
        kit.styleSheet.addRule("p { margin-top: 0; margin-bottom: 6px; }")
        kit.styleSheet.addRule("ul { margin-top: 0; margin-bottom: 6px; margin-left: 16px; }")
        val linkColor = "#%06x".format(JBUI.CurrentTheme.Link.Foreground.ENABLED.rgb and 0xffffff)
        kit.styleSheet.addRule("a { color: $linkColor; }")
        editorKit = kit
        render()
    }

    private fun render() {
        text = "<html><body>${StoryMarkdown.render(markdown)}</body></html>"
        caretPosition = 0
    }
}

/** Opens a link of story text: a web page in the browser, or a file of the story's repository at its lines. */
fun openStoryLink(project: Project, root: Path, target: String) {
    if (StoryMarkdown.isWebLink(target)) {
        BrowserUtil.browse(target)
        return
    }
    val link = StoryMarkdown.fileLink(target) ?: return
    // Project links cannot escape the repository, also through a symbolic link.
    val path = root.resolve(link.path)
    val safe = runCatching { Files.isRegularFile(path) && path.toRealPath().startsWith(root.toRealPath()) }.getOrDefault(false)
    if (!safe) return
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
    OpenFileDescriptor(project, file, link.startLine - 1, 0)
        .setUsePreviewTab(UISettings.getInstance().openInPreviewTabIfPossible)
        .navigateInEditor(project, true)
}
