package io.github.hubertrybak.codestory.core

/** One line of a proposal beside the code that it changes. [line] is the 1-based file line of a kept or removed line. */
data class DiffLine(val change: Change, val text: String, val line: Int?) {
    enum class Change { KEPT, REMOVED, ADDED }
}

/** A proposal compared line by line with the code of its range, like a unified diff that shows every line. */
object ProposalDiff {
    /** Larger comparisons show every current line as removed, then every proposed line as added. */
    private const val MAX_CELLS = 1_000_000

    /** The lines of [proposed] code, split like range text. An empty proposal removes every line. */
    fun proposedLines(proposed: String): List<String> = if (proposed.isEmpty()) emptyList() else proposed.split("\n")

    /**
     * Compares the [current] lines, which start at file line [firstLine], with the [proposed] lines.
     * Lines that differ only in trailing spaces count as the same. In each changed part, the removed
     * lines come before the added lines.
     */
    fun lines(current: List<String>, proposed: List<String>, firstLine: Int): List<DiffLine> {
        val n = current.size
        val m = proposed.size
        if (n.toLong() * m > MAX_CELLS) {
            return current.mapIndexed { i, text -> DiffLine(DiffLine.Change.REMOVED, text, firstLine + i) } +
                proposed.map { DiffLine(DiffLine.Change.ADDED, it, null) }
        }
        fun same(i: Int, j: Int) = current[i].trimEnd() == proposed[j].trimEnd()
        // common[i][j] is the length of the longest common subsequence of current[i..] and proposed[j..].
        val common = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            common[i][j] = if (same(i, j)) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
        }
        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && same(i, j) -> {
                    result += DiffLine(DiffLine.Change.KEPT, proposed[j], firstLine + i)
                    i++; j++
                }
                i < n && (j == m || common[i + 1][j] >= common[i][j + 1]) -> {
                    result += DiffLine(DiffLine.Change.REMOVED, current[i], firstLine + i)
                    i++
                }
                else -> {
                    result += DiffLine(DiffLine.Change.ADDED, proposed[j], null)
                    j++
                }
            }
        }
        return result
    }
}
