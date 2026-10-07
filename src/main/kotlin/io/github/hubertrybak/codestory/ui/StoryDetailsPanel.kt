package io.github.hubertrybak.codestory.ui

import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.hubertrybak.codestory.StoryChanges
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryGit
import java.awt.BorderLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JPanel

/**
 * Story text is untrusted. A plain text component never interprets HTML or opens links.
 * [showDiff] opens the diff of the step that the pane shows.
 */
class StoryDetailsPanel(private val showDiff: (StepItem) -> Unit = {}) : JPanel(BorderLayout()) {
    private val text = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        accessibleContext.accessibleName = "Story details"
    }
    private var diffStep: StepItem? = null

    /** Visible for a step of a story about a change, when Git knows the base commit. */
    val showDiffLink = ActionLink("Show diff") { diffStep?.let(showDiff) }.apply {
        border = JBUI.Borders.empty(4, 8, 8, 8)
        isVisible = false
    }

    val displayedText: String get() = text.text

    init {
        add(ScrollPaneFactory.createScrollPane(text, true), BorderLayout.CENTER)
        // Outside the scroll pane, so the link stays visible below a long comment.
        add(showDiffLink, BorderLayout.SOUTH)
        clear()
    }

    /** [changes] is `null` when the change information is not read yet. */
    fun showStory(stored: StoredStory, currentGit: StoryGit?, changes: StoryChanges? = null) {
        val story = stored.story
        val date = DATE_FORMAT.format(checkNotNull(story.createdAt))
        val branch = story.git.branch ?: if (story.git.commit != null) "Detached HEAD" else "Unavailable"
        show(buildString {
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
                val unknown = if (changes == StoryChanges.UnknownBase) " (Git does not know this commit)" else ""
                appendLine("Diff base: $base$unknown")
            }
            if ((story.git.branch != null || story.git.commit != null) && currentGit != null &&
                (currentGit.branch != null || currentGit.commit != null) && currentGit.branch != story.git.branch) {
                appendLine()
                append("Current branch is ${currentGit.branch ?: "detached HEAD"}; this story was created on $branch.")
            }
        }.trimEnd())
    }

    /** [diff] shows "Show diff" for the step. */
    fun showStep(item: StepItem, diff: Boolean = false) = show(buildString {
        append("Step ${item.index + 1} of ${item.stored.story.steps.size}: ${item.step.title}")
        statusNote(item.stored.status(item.index))?.let { append("\n\n").append(it) }
        append("\n\n").append(item.step.comment)
    }, item.takeIf { diff })

    fun clear() = show("Select a story or step to see its details.")

    private fun show(value: String, diffStep: StepItem? = null) {
        text.text = value
        text.caretPosition = 0
        this.diffStep = diffStep
        showDiffLink.isVisible = diffStep != null
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    }
}
