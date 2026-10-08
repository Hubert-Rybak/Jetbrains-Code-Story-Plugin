package io.github.hubertrybak.codestory.core

/** A PR-ready walkthrough. Only the title, summary and steps are exported, never reader threads. */
object StoryMarkdownExport {
    fun render(story: Story): String = buildString {
        appendLine("# ${plain(story.title)}")
        appendLine()
        appendLine(story.summary)
        story.steps.forEachIndexed { index, step ->
            appendLine()
            appendLine("## ${index + 1}. ${plain(step.title)}")
            step.location?.let { location ->
                appendLine()
                val range = "L${location.startLine}-L${location.endLine}"
                appendLine("[${plain(location.file)}:$range](${path(location.file)}#$range)")
            }
            appendLine()
            appendLine(step.comment)
        }
    }

    /** Titles and labels are plain text; summary and comments already use the story Markdown subset. */
    private fun plain(text: String): String = text.replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("[\\\\`*_{}\\[\\]()#+.!<>|~-]")) { "\\${it.value}" }

    /** Encode UTF-8 bytes, including fragment characters, spaces and parentheses, but preserve slashes. */
    private fun path(path: String): String = buildString {
        for (byte in path.toByteArray(Charsets.UTF_8)) {
            val n = byte.toInt() and 0xff
            val c = n.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~/") append(c)
            else append("%%%02X".format(n))
        }
    }
}
