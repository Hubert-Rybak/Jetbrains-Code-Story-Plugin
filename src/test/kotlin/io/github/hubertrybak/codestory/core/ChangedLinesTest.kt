package io.github.hubertrybak.codestory.core

import io.github.hubertrybak.codestory.core.StepChange.ADDED
import io.github.hubertrybak.codestory.core.StepChange.MODIFIED
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** One case of a step range in a changed file: the diff hunks, the 1-based range and the badge it must get. */
class ChangeCase(
    private val name: String,
    val changes: List<LineChange>,
    val startLine: Int,
    val endLine: Int,
    val expected: StepChange?,
    val lines: List<String> = ChangeCases.lines,
) {
    override fun toString() = name
}

object ChangeCases {
    /** Line 6 is empty. */
    val lines = (1..10).map { if (it == 6) "" else "line $it" }

    /** Current lines [start] to [end] (1-based, inclusive) replace [baseLines] lines of the base. */
    private fun added(start: Int, end: Int, baseLines: Int = 0) = LineChange(start - 1, start - 1 + baseLines, start - 1, end)

    /** Base lines were removed after current line [after]. */
    private fun removedAfter(after: Int) = LineChange(after, after + 2, after, after)

    val all = listOf(
        ChangeCase("no change", emptyList(), 2, 4, null),
        ChangeCase("inserted range", listOf(added(2, 4)), 2, 4, ADDED),
        ChangeCase("range inside inserted lines", listOf(added(1, 8)), 3, 5, ADDED),
        ChangeCase("every line replaced", listOf(added(2, 4, baseLines = 3)), 2, 4, ADDED),
        ChangeCase("one changed line", listOf(added(3, 3, baseLines = 1)), 2, 4, MODIFIED),
        ChangeCase("inserted lines at the end of the range", listOf(added(4, 6)), 2, 5, MODIFIED),
        ChangeCase("changes before and after the range", listOf(added(1, 1), added(5, 5)), 2, 4, null),
        ChangeCase("lines removed inside the range", listOf(removedAfter(3)), 2, 4, MODIFIED),
        ChangeCase("lines removed before the range", listOf(removedAfter(1)), 2, 4, null),
        ChangeCase("lines removed after the range", listOf(removedAfter(4)), 2, 4, null),
        ChangeCase("removed and inserted lines inside an added range", listOf(added(2, 3), removedAfter(3), added(4, 4)), 2, 4, ADDED),
        ChangeCase("an unchanged empty line in added code", listOf(added(4, 5), added(7, 8)), 4, 8, ADDED),
        ChangeCase("only an added empty line", listOf(added(6, 6)), 5, 7, MODIFIED),
        ChangeCase("one-line range", listOf(added(7, 7, baseLines = 1)), 7, 7, ADDED),
        ChangeCase("range after the end of the file", listOf(added(9, 10)), 9, 12, ADDED),
        ChangeCase("new file", ChangedLines.newFile(lines), 4, 8, ADDED),
    )
}

@RunWith(Parameterized::class)
class ChangedLinesTest(private val case: ChangeCase) {
    @Test
    fun stepChange() {
        assertEquals(case.expected, ChangedLines.stepChange(case.changes, case.lines, case.startLine, case.endLine))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<ChangeCase> = ChangeCases.all
    }
}
