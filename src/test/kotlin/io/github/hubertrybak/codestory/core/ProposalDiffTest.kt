package io.github.hubertrybak.codestory.core

import io.github.hubertrybak.codestory.core.DiffLine.Change.ADDED
import io.github.hubertrybak.codestory.core.DiffLine.Change.KEPT
import io.github.hubertrybak.codestory.core.DiffLine.Change.REMOVED
import org.junit.Assert.assertEquals
import org.junit.Test

class ProposalDiffTest {
    @Test
    fun `keeps common lines and puts removed lines before added lines with file line numbers`() {
        val current = listOf("val result = provider.charge(card)", "log(result)", "return result")
        val proposed = listOf("val result = retry {", "    provider.charge(card)", "}", "log(result)", "return result")
        assertEquals(
            listOf(
                DiffLine(REMOVED, "val result = provider.charge(card)", 20),
                DiffLine(ADDED, "val result = retry {", null),
                DiffLine(ADDED, "    provider.charge(card)", null),
                DiffLine(ADDED, "}", null),
                DiffLine(KEPT, "log(result)", 21),
                DiffLine(KEPT, "return result", 22),
            ),
            ProposalDiff.lines(current, proposed, 20),
        )
    }

    @Test
    fun `an empty proposal removes every line and new code adds every line`() {
        assertEquals(emptyList<String>(), ProposalDiff.proposedLines(""))
        assertEquals(listOf("a", "", "b"), ProposalDiff.proposedLines("a\n\nb"))
        assertEquals(listOf(DiffLine(REMOVED, "a", 3), DiffLine(REMOVED, "b", 4)), ProposalDiff.lines(listOf("a", "b"), emptyList(), 3))
        assertEquals(listOf(DiffLine(ADDED, "a", null)), ProposalDiff.lines(emptyList(), listOf("a"), 1))
    }

    @Test
    fun `trailing spaces do not count as a change and the proposed text is shown`() {
        assertEquals(listOf(DiffLine(KEPT, "call()", 7)), ProposalDiff.lines(listOf("call()   "), listOf("call()"), 7))
        assertEquals(listOf(DiffLine(REMOVED, "call()", 7), DiffLine(ADDED, "    call()", null)), ProposalDiff.lines(listOf("call()"), listOf("    call()"), 7))
    }

    @Test
    fun `very large comparisons fall back to removing then adding every line`() {
        val current = List(1500) { "old $it" }
        val proposed = List(1000) { "old $it" }
        val lines = ProposalDiff.lines(current, proposed, 1)
        assertEquals(2500, lines.size)
        assertEquals(List(1500) { REMOVED } + List(1000) { ADDED }, lines.map { it.change })
    }
}
