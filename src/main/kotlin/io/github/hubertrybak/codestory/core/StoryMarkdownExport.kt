package io.github.hubertrybak.codestory.core

/** A PR-ready walkthrough. Only the title, summary and steps are exported, never reader threads. */
object StoryMarkdownExport {
    fun render(story: Story): String = buildString {
        appendLine("# ${plain(story.title)}")
        appendLine()
        appendLine(story.summary)
        story.steps.forEachIndexed { index, step ->
            appendLine()
            // Context is a section of the step that it gives background for.
            val level = if (step.kind == StepKind.CONTEXT) "###" else "##"
            appendLine("$level ${index + 1}. ${label(step.kind)}${plain(step.title)}")
            step.location?.let { location ->
                appendLine()
                val range = "L${location.startLine}-L${location.endLine}"
                appendLine("[${plain(location.file)}:$range](${path(location.file)}#$range)")
            }
            appendLine()
            appendLine(step.comment)
            step.proposedCode?.let { code ->
                appendLine()
                append(proposal(step.location, code))
            }
        }
    }

    private fun label(kind: StepKind): String = when (kind) {
        StepKind.EXPLANATION -> ""
        StepKind.CONTEXT -> "Context: "
        StepKind.PROPOSAL -> "Proposal: "
    }

    /**
     * The proposed code as a diff of the code that the step explains, or as a plain code block when
     * the step stores no code. The fence is longer than any run of backticks in the code.
     */
    private fun proposal(location: Location?, code: String): String {
        val proposed = ProposalDiff.proposedLines(code)
        val current = location?.rangeText
        val diff = location != null && current != null
        val lines = if (!diff) proposed
            else ProposalDiff.lines(checkNotNull(current).split("\n"), proposed, checkNotNull(location).startLine).map { line ->
                when (line.change) {
                    DiffLine.Change.KEPT -> " "
                    DiffLine.Change.REMOVED -> "-"
                    DiffLine.Change.ADDED -> "+"
                } + line.text
            }
        val longest = lines.flatMap { Regex("`+").findAll(it).map { run -> run.value.length }.toList() }.maxOrNull() ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        return buildString {
            appendLine(fence + if (diff) "diff" else "")
            lines.forEach(::appendLine)
            appendLine(fence)
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
