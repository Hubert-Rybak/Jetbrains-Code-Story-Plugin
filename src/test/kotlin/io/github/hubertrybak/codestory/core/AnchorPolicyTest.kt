package io.github.hubertrybak.codestory.core

import io.github.hubertrybak.codestory.core.StepStatus.CODE_CHANGED
import io.github.hubertrybak.codestory.core.StepStatus.LOST
import io.github.hubertrybak.codestory.core.StepStatus.MOVED
import io.github.hubertrybak.codestory.core.StepStatus.OK
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** One case of finding saved code again: the current lines and what the policy must find there. */
class ResolveCase(
    private val name: String,
    val lines: List<String>,
    val location: Location,
    val status: StepStatus,
    val startLine: Int,
    val endLine: Int,
    val live: IntRange? = null,
    val anchorText: String? = location.anchorText,
) {
    override fun toString() = name
}

/** One case of the check on arrival: an agent's start line and anchor text in the current lines. */
class VerifyCase(
    private val name: String,
    val lines: List<String>,
    val startLine: Int,
    val anchorText: String,
    val expected: AnchorPolicy.Verification,
) {
    override fun toString() = name
}

object AnchorCases {
    private val header = listOf("class Billing {")
    private val footer = listOf("}")
    private val method = listOf(
        "    fun charge(amount: Int) {",
        "        check(amount > 0)",
        "        gateway.send(amount)",
        "    }",
    )
    private val changed = listOf(method[0], "        log(amount)", method[2], method[3])
    private val editedAnchor = listOf("    fun charge(amount: Long) {") + method.drop(1)
    private val saved = Location("src/Billing.kt", 2, 5, "fun charge(amount: Int) {", method.joinToString("\n"))

    private fun lines(vararg parts: List<String>): List<String> = parts.toList().flatten()
    private fun filler(count: Int): List<String> = (1..count).map { "val value$it = $it" }
    private fun at(start: Int) = saved.copy(startLine = start, endLine = start + 3)

    val resolve: List<ResolveCase> = listOf(
        ResolveCase("exact, in place", lines(header, method, footer), saved, OK, 2, 5),
        ResolveCase("shifted down", lines(header, filler(3), method, footer), saved, MOVED, 5, 8),
        ResolveCase("shifted up", lines(method, footer), saved, MOVED, 1, 4),
        ResolveCase("other indentation and trailing spaces", lines(header, method.map { "\t" + it.trim() + "   " }, footer), saved, OK, 2, 5),
        ResolveCase("CRLF file", TextFiles.lines(lines(header, method, footer).joinToString("\r\n", postfix = "\r\n")), saved, OK, 2, 5),
        ResolveCase("stored code with CRLF", lines(header, method, footer), saved.copy(rangeText = method.joinToString("\r\n")), OK, 2, 5),
        ResolveCase("moved far, the only match", lines(header, filler(100), method, footer), saved, MOVED, 102, 105),
        ResolveCase("moved far and changed, the only match", lines(header, filler(100), changed, footer), saved, CODE_CHANGED, 102, 105),
        ResolveCase("moved far, two matches without the stored code", lines(header, filler(40), changed, filler(40), changed, footer), saved, LOST, 2, 5),
        ResolveCase("moved far, two matches, one with the stored code", lines(header, filler(40), changed, filler(40), method, footer), saved, MOVED, 86, 89),
        ResolveCase("duplicate candidates nearby: the nearest", lines(header, filler(1), changed, filler(3), changed, footer), saved, CODE_CHANGED, 3, 6),
        ResolveCase("duplicate candidates at the same distance: the later", lines(filler(6), changed, filler(2), changed, footer), at(10), CODE_CHANGED, 13, 16),
        ResolveCase("duplicate candidates: the stored code before a nearer match", lines(header, filler(1), changed, filler(10), method, footer), saved, MOVED, 17, 20),
        ResolveCase("in place, code changed", lines(header, changed, footer), saved, CODE_CHANGED, 2, 5),
        ResolveCase("in place before a copy of the stored code", lines(header, changed, filler(5), method, footer), saved, CODE_CHANGED, 2, 5),
        ResolveCase("moved and code changed", lines(header, filler(3), changed, footer), saved, CODE_CHANGED, 5, 8),
        ResolveCase("first line edited: not found", lines(header, editedAnchor, footer), saved, LOST, 2, 5),
        ResolveCase("code deleted: not found", lines(header, filler(4), footer), saved, LOST, 2, 5),
        ResolveCase("file ends inside the range", lines(header, method.take(3)), saved, CODE_CHANGED, 2, 4),
        ResolveCase("largest end line shifted down is clamped to the file", lines(header, method, footer), saved.copy(startLine = 1, endLine = Int.MAX_VALUE), CODE_CHANGED, 2, 6),
        ResolveCase("largest end line without stored code is clamped to the file", lines(header, method, footer), saved.copy(startLine = 1, endLine = Int.MAX_VALUE, rangeText = null), MOVED, 2, 6),
        ResolveCase("empty file", emptyList(), saved, LOST, 2, 5),
        ResolveCase("no anchor, range in the file", lines(header, changed, footer), Location("src/Billing.kt", 2, 5), OK, 2, 5),
        ResolveCase("no anchor, range after the end", lines(header, method), Location("src/Billing.kt", 2, 6), LOST, 2, 6),
        ResolveCase("anchor without stored code, shifted", lines(header, filler(3), changed, footer), saved.copy(rangeText = null), MOVED, 5, 8),
        ResolveCase("anchor without stored code, in place", lines(header, changed, footer), saved.copy(rangeText = null), OK, 2, 5),
        ResolveCase("live, in place", lines(header, method, footer), saved, OK, 2, 5, live = 2..5),
        ResolveCase("live, moved", lines(header, filler(3), method, footer), saved, MOVED, 5, 8, live = 5..8),
        ResolveCase("live, first line edited", lines(header, editedAnchor, footer), saved, CODE_CHANGED, 2, 5, live = 2..5, anchorText = "fun charge(amount: Long) {"),
        ResolveCase("live, line added inside", lines(header, method.take(2), listOf("        audit(amount)"), method.drop(2), footer), saved, CODE_CHANGED, 2, 6, live = 2..6),
        ResolveCase("live, first line emptied", lines(header, listOf(""), method.drop(1), footer), saved, CODE_CHANGED, 2, 5, live = 2..5),
        ResolveCase("live, after the end of a shorter file", lines(header, method.take(2)), saved, CODE_CHANGED, 2, 3, live = 2..5),
    )

    private val numbered = (1..60).map { "line $it" }
    private val largest = object : AbstractList<String>() {
        override val size = Int.MAX_VALUE
        override fun get(index: Int) = "line ${index + 1}"
    }
    private val twice = listOf("x", "dup", "x", "x", "x", "dup", "x", "x")

    val verify: List<VerifyCase> = listOf(
        VerifyCase("exact", numbered, 10, "line 10", AnchorPolicy.Verification.InPlace),
        VerifyCase("other indentation and spaces", numbered, 10, "  line 10\t", AnchorPolicy.Verification.InPlace),
        VerifyCase("CRLF file", TextFiles.lines("a\r\nb\r\nc\r\n"), 2, "b", AnchorPolicy.Verification.InPlace),
        VerifyCase("byte order mark", listOf("﻿package app", "class App"), 1, "package app", AnchorPolicy.Verification.InPlace),
        VerifyCase("shifted down", numbered, 10, "line 13", AnchorPolicy.Verification.Shifted(13)),
        VerifyCase("shifted up", numbered, 10, "line 7", AnchorPolicy.Verification.Shifted(7)),
        VerifyCase("20 lines away", numbered, 10, "line 30", AnchorPolicy.Verification.Shifted(30)),
        VerifyCase("21 lines away", numbered, 10, "line 31", AnchorPolicy.Verification.NotFound),
        VerifyCase("not found", numbered, 10, "fun missing()", AnchorPolicy.Verification.NotFound),
        VerifyCase("start line after the end", numbered, 70, "line 55", AnchorPolicy.Verification.Shifted(55)),
        VerifyCase("largest start line beyond the file", numbered, Int.MAX_VALUE, "line 55", AnchorPolicy.Verification.NotFound),
        VerifyCase("nearby anchor at the largest line numbers", largest, Int.MAX_VALUE, "line ${Int.MAX_VALUE - 1}", AnchorPolicy.Verification.Shifted(Int.MAX_VALUE - 1)),
        VerifyCase("duplicate candidates: the nearest", twice, 5, "dup", AnchorPolicy.Verification.Shifted(6)),
        VerifyCase("duplicate candidates at the same distance", twice, 4, "dup", AnchorPolicy.Verification.Ambiguous(listOf(2, 6))),
        VerifyCase("empty file", emptyList(), 1, "line 1", AnchorPolicy.Verification.NotFound),
    )
}

@RunWith(Parameterized::class)
class AnchorResolveTest(private val case: ResolveCase) {
    @Test
    fun resolve() {
        val result = AnchorPolicy.resolve(case.location, case.lines, case.live)
        assertEquals(case.status, result.status)
        assertEquals(case.startLine to case.endLine, result.location.startLine to result.location.endLine)
        assertEquals(case.anchorText, result.location.anchorText)
        // The code that the step explains never changes when the plugin finds it again.
        assertEquals(case.location.copy(startLine = 1, endLine = 1, anchorText = null), result.location.copy(startLine = 1, endLine = 1, anchorText = null))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<ResolveCase> = AnchorCases.resolve
    }
}

@RunWith(Parameterized::class)
class AnchorVerifyTest(private val case: VerifyCase) {
    @Test
    fun verify() {
        assertEquals(case.expected, AnchorPolicy.verify(case.lines, case.startLine, case.anchorText))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<VerifyCase> = AnchorCases.verify
    }
}

class StoryAnchorsTest {
    private val story = Story(
        "Payment flow", StoryType.CHANGE, "The controller charges the card.", "Explain the payment flow", StoryAuthor("Test Agent", "test-model"),
        listOf(
            Step("Overview", "Start here.", id = "overview"),
            Step("Entry", "The app starts.", Location("src/App.kt", 2, 3, "line 2", "line 2\nline 3"), "entry"),
            Step("Gone", "The file is gone.", Location("src/Gone.kt", 1, 1, "gone", "gone"), "gone"),
            Step("Later", "The app ends.", Location("src/App.kt", 5, 5, "line 5", "line 5"), "later"),
        ),
    )

    private val files = object : StoryFiles {
        override val rootLabel = "repo"
        override fun lookup(path: String): FileLookup = when (path) {
            "src/App.kt" -> FileLookup.Found(path, "inserted\n" + (1..6).joinToString("\n") { "line $it" })
            else -> FileLookup.NotFound
        }
    }

    @Test
    fun `finds every step and keeps text-only steps and missing files`() {
        val resolution = StoryAnchors.resolve(story, files)
        assertEquals(listOf(OK, MOVED, LOST, MOVED), resolution.statuses)
        assertEquals(listOf(null, 3 to 4, 1 to 1, 6 to 6), resolution.story.steps.map { step -> step.location?.let { it.startLine to it.endLine } })
        assertEquals(story.steps.map { it.id to it.title }, resolution.story.steps.map { it.id to it.title })
    }

    @Test
    fun `a live range counts only for the code that its marker was made for`() {
        val live = mapOf(
            "entry" to LiveRange("src/App.kt", "line 2\nline 3", 3, 4),
            "later" to LiveRange("src/App.kt", "line 4", 1, 1),
        )
        val resolution = StoryAnchors.resolve(story, files, live)
        assertEquals(3 to 4, resolution.story.steps[1].location!!.let { it.startLine to it.endLine })
        assertEquals(6 to 6, resolution.story.steps[3].location!!.let { it.startLine to it.endLine })
    }
}
