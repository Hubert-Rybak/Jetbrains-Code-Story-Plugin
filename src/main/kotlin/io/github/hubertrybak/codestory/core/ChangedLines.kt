package io.github.hubertrybak.codestory.core

/** How the code of a step differs from the code at the story's base commit. */
enum class StepChange(val badge: String) {
    /** Every line of the range is new since the base. */
    ADDED("A"),

    /** Some lines of the range are new or changed since the base. */
    MODIFIED("M"),
}

/**
 * One difference between the base text of a file and its current text: base lines
 * [baseStart, baseEnd) became current lines [start, end). Line indexes are 0-based, like in a
 * diff hunk. An empty current side means that base lines were removed before current line [start].
 */
data class LineChange(val baseStart: Int, val baseEnd: Int, val start: Int, val end: Int)

object ChangedLines {
    /**
     * The change of lines [startLine] to [endLine] (1-based, inclusive) of the current [lines].
     * A line is added when the diff marks it as new, like a `+` line in `git diff`. A diff can match
     * an empty line with any empty line of the base, so empty lines do not prevent an "A". Lines
     * removed between two lines of the range change the range too.
     */
    fun stepChange(changes: List<LineChange>, lines: List<String>, startLine: Int, endLine: Int): StepChange? {
        val range = (startLine - 1) until minOf(endLine, lines.size)
        val added = range.filter { line -> changes.any { line >= it.start && line < it.end } }.toSet()
        val removedInside = changes.any { it.start == it.end && it.start in startLine until endLine }
        return when {
            added.isNotEmpty() && range.all { it in added || lines[it].isBlank() } -> StepChange.ADDED
            added.isNotEmpty() || removedInside -> StepChange.MODIFIED
            else -> null
        }
    }

    /** The changes of a file that did not exist at the base: every line is added. */
    fun newFile(lines: List<String>): List<LineChange> = listOf(LineChange(0, 0, 0, lines.size))
}
