package io.github.hubertrybak.codestory.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryGit
import java.awt.BorderLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JPanel

/** Story text is untrusted. A plain text component never interprets HTML or opens links. */
class StoryDetailsPanel : JPanel(BorderLayout()) {
    private val text = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        accessibleContext.accessibleName = "Story details"
    }

    val displayedText: String get() = text.text

    init {
        add(text, BorderLayout.CENTER)
        clear()
    }

    fun showStory(stored: StoredStory, currentGit: StoryGit?) {
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
            if ((story.git.branch != null || story.git.commit != null) && currentGit != null &&
                (currentGit.branch != null || currentGit.commit != null) && currentGit.branch != story.git.branch) {
                appendLine()
                append("Current branch is ${currentGit.branch ?: "detached HEAD"}; this story was created on $branch.")
            }
        }.trimEnd())
    }

    fun showStep(item: StepItem) = show("Step ${item.index + 1} of ${item.stored.story.steps.size}: ${item.step.title}\n\n${item.step.comment}")

    fun clear() = show("Select a story or step to see its details.")

    private fun show(value: String) {
        text.text = value
        text.caretPosition = 0
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    }
}
