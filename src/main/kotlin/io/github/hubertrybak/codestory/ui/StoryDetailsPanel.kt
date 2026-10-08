package io.github.hubertrybak.codestory.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.hubertrybak.codestory.StoryChanges
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryGit
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JPanel

/** Story text is untrusted. A plain text component never interprets HTML or opens links. */
class StoryDetailsPanel(private val showDiff: (StepItem) -> Unit = {}) : JPanel(BorderLayout()) {
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

    private val actions = object : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {
        // Looks like a part of the text, in every theme.
        override fun getBackground(): Color = text.background
    }.apply {
        border = JBUI.Borders.empty(8, 8, 0, 8)
        add(diffLink)
        isVisible = false
    }

    val displayedText: String get() = text.text

    init {
        add(actions, BorderLayout.NORTH)
        add(text, BorderLayout.CENTER)
        clear()
    }

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
    }

    fun showStep(item: StepItem) = show(item, buildString {
        append("Step ${item.index + 1} of ${item.stored.story.steps.size}: ${item.step.title}")
        statusNote(item.stored.status(item.index))?.let { append("\n\n").append(it) }
        append("\n\n").append(item.step.comment)
    })

    fun clear() = show(null, "Select a story or step to see its details.")

    private fun show(step: StepItem?, value: String) {
        this.step = step
        actions.isVisible = step?.canShowDiff == true
        text.text = value
        text.caretPosition = 0
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    }
}
