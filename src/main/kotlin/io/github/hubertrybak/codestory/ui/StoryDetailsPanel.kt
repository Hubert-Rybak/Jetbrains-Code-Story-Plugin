package io.github.hubertrybak.codestory.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import io.github.hubertrybak.codestory.StoryChanges
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryGit
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable

/**
 * Story text is untrusted. A plain text component never interprets HTML or opens links. Below the
 * text, [comments] shows the comment threads of the story or step, when the tool window has them.
 */
class StoryDetailsPanel(comments: StoryComments? = null, private val showDiff: (StepItem) -> Unit = {}) : JPanel(VerticalLayout(0)), Scrollable {
    private val text = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        accessibleContext.accessibleName = "Story details"
    }
    private var step: StepItem? = null

    /** Shown for a step with code when Git knows the story's base. */
    val diffLink = ActionLink("Show diff") { step?.let(showDiff) }

    private val actions = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 8, 0, 8)
        add(diffLink)
        isVisible = false
    }

    val comments: CommentsPanel? = comments?.let { CommentsPanel(it) }?.apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 12, 8)
    }

    val displayedText: String get() = text.text

    init {
        add(actions)
        add(text)
        this.comments?.let(::add)
        clear()
    }

    // Looks like one page of text, in every theme. Swing asks for the background before the text area exists.
    @Suppress("USELESS_CAST")
    override fun getBackground(): Color? = (text as JBTextArea?)?.background ?: super.getBackground()

    fun showStory(stored: StoredStory, currentGit: StoryGit?, changes: StoryChanges? = null) {
        val story = stored.story
        val date = DATE_FORMAT.format(checkNotNull(story.createdAt))
        val branch = story.git.branch ?: if (story.git.commit != null) "Detached HEAD" else "Unavailable"
        show(null, buildString {
            appendLine(story.title)
            appendLine("Type: ${story.type.value}")
            appendLine()
            appendLine(story.summary)
            appendLine()
            appendLine("Request: ${story.request}")
            appendLine()
            appendLine("Written by ${story.author.agent} (${story.author.model})")
            appendLine("Date: $date")
            appendLine("Branch: $branch")
            appendLine("Commit: ${story.git.commit ?: "Unavailable"}")
            story.baseRef?.let { base ->
                append("Diff base: $base")
                when (val commit = changes?.commit) {
                    null -> if (changes != null) append(" (Git does not know this commit)")
                    base -> Unit
                    else -> append(" ($commit)")
                }
                appendLine()
            }
            if ((story.git.branch != null || story.git.commit != null) && currentGit != null &&
                (currentGit.branch != null || currentGit.commit != null) && currentGit.branch != story.git.branch) {
                appendLine()
                append("Current branch is ${currentGit.branch ?: "detached HEAD"}; this story was created on $branch.")
            }
        }.trimEnd())
        comments?.show(stored, null)
    }

    fun showStep(item: StepItem) {
        show(item, buildString {
            append("Step ${item.index + 1} of ${item.stored.story.steps.size}: ${item.step.title}")
            statusNote(item.stored.status(item.index))?.let { append("\n\n").append(it) }
            append("\n\n").append(item.step.comment)
        })
        comments?.show(item.stored, item.index)
    }

    fun clear() {
        show(null, "Select a story or step to see its details.")
        comments?.show(null, null)
    }

    private fun show(step: StepItem?, value: String) {
        this.step = step
        actions.isVisible = step?.canShowDiff == true
        text.text = value
        text.caretPosition = 0
        revalidate()
        repaint()
    }

    // Wrapped text follows the width of the tool window; the page fills its height.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = (parent as? JViewport)?.let { it.height > preferredSize.height } == true

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    }
}
