package io.github.hubertrybak.codestory.core

/** How the lines of a step differ from the base commit of a story about a change. The tree shows [badge]. */
enum class StepChange(val badge: String) {
    /** Every line of the range is new since the base. */
    ADDED("A"),

    /** Some lines of the range are new or changed, or lines inside the range were removed. */
    MODIFIED("M"),
}

/**
 * One block of lines that is different in the base text and the current text, like a diff hunk.
 * Line numbers are 0-based and the ends are exclusive. A block without current lines
 * ([start] equals [end]) is a removal before current line [start].
 */
data class LineChange(val baseStart: Int, val baseEnd: Int, val start: Int, val end: Int)

object StepChanges {
    private const val MAX_REVISION_LENGTH = 200
    private val revision = Regex("[A-Za-z0-9_][A-Za-z0-9._/~^-]*")

    /**
     * The change of lines [startLine] to [endLine] (1-based, inclusive) of the current text, from
     * the [changes] between the base text and the current text. `null` when the lines did not change.
     */
    fun of(startLine: Int, endLine: Int, changes: List<LineChange>): StepChange? {
        val first = startLine - 1
        val end = endLine
        var changed = 0
        var removedInside = false
        for (change in changes) {
            changed += maxOf(0, minOf(change.end, end) - maxOf(change.start, first))
            // A removal at the first line or after the last line is outside the range.
            if (change.start == change.end && change.start > first && change.start < end) removedInside = true
        }
        return when {
            changed == end - first -> StepChange.ADDED
            changed > 0 || removedInside -> StepChange.MODIFIED
            else -> null
        }
    }

    /**
     * Whether a `baseRef` is safe to give to Git as a revision: a commit SHA, `HEAD`, a branch or
     * a tag, optionally with `~` or `^`. Story files can come from other people, so a value that Git
     * could read as an option or a file path is never used.
     */
    fun isRevision(ref: String): Boolean = ref.length <= MAX_REVISION_LENGTH && revision.matches(ref)
}
