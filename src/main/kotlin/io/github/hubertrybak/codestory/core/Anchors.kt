package io.github.hubertrybak.codestory.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** How the code at a step's location compares with the code that the step explains. */
@Serializable
enum class StepStatus(val value: String) {
    @SerialName("ok") OK("ok"),

    /** The same code, at other lines. */
    @SerialName("moved") MOVED("moved"),

    /** The range was found, but its code is different. */
    @SerialName("code-changed") CODE_CHANGED("code-changed"),

    /** The code was not found. The step keeps its stored lines. */
    @SerialName("lost") LOST("lost"),
}

/** Lines that a live marker in the IDE followed, for the range with [rangeText]. 1-based and inclusive. */
data class LiveRange(val file: String, val rangeText: String?, val startLine: Int, val endLine: Int)

/**
 * Finds the code of a step by its anchor text, the first line of the range. Comparisons ignore
 * leading and trailing whitespace and line endings.
 */
object AnchorPolicy {
    /** How far from the given line the anchor text counts as nearby, in lines. */
    const val NEARBY_LINES = 20

    sealed interface Verification {
        data object InPlace : Verification
        data class Shifted(val startLine: Int) : Verification

        /** Two matches at the same distance, one above and one below the given line. */
        data class Ambiguous(val startLines: List<Int>) : Verification
        data object NotFound : Verification
    }

    data class Resolved(val location: Location, val status: StepStatus)

    /** Checks an agent's location on arrival: the anchor text at [startLine], or the nearest match nearby. */
    fun verify(lines: List<String>, startLine: Int, anchorText: String): Verification {
        val anchor = clean(anchorText)
        if (startLine in 1..lines.size && clean(lines[startLine - 1]) == anchor) return Verification.InPlace
        val first = maxOf(1L, startLine.toLong() - NEARBY_LINES).toInt()
        val last = minOf(lines.size.toLong(), startLine.toLong() + NEARBY_LINES).toInt()
        val hits = (first..last)
            .filter { clean(lines[it - 1]) == anchor }
        if (hits.isEmpty()) return Verification.NotFound
        val distance = hits.minOf { abs(it - startLine) }
        val nearest = hits.filter { abs(it - startLine) == distance }
        return nearest.singleOrNull()?.let { Verification.Shifted(it) } ?: Verification.Ambiguous(nearest)
    }

    /**
     * Finds the code of a saved [location] in the current [lines] when a story opens or plays.
     * With a [live] range, the code is where a live marker followed it. Otherwise the anchor text
     * is searched: at the stored line, then the nearest match whose whole range has the stored
     * code, then the nearest match nearby, then a match elsewhere in the file if it is the only one.
     */
    fun resolve(location: Location, lines: List<String>, live: IntRange? = null): Resolved {
        if (lines.isEmpty()) return Resolved(location, StepStatus.LOST)
        if (live != null) return followed(location, lines, live)
        // Without an anchor, only the lines can be checked.
        val anchor = location.anchorText?.let(::clean)
            ?: return Resolved(location, if (location.endLine <= lines.size) StepStatus.OK else StepStatus.LOST)
        val length = location.endLine.toLong() - location.startLine
        fun at(start: Int) = location.copy(startLine = start, endLine = minOf(start + length, lines.size.toLong()).toInt())
        val hits = (1..lines.size).filter { clean(lines[it - 1]) == anchor }
        if (hits.isEmpty()) return Resolved(location, StepStatus.LOST)
        val start = location.startLine
        val exact by lazy { if (location.rangeText == null) emptyList() else hits.filter { sameCode(location, at(it), lines) } }
        val nearby by lazy { hits.filter { abs(it - start) <= NEARBY_LINES } }
        val found = when {
            start in hits -> start
            exact.isNotEmpty() -> nearest(exact, start)
            nearby.isNotEmpty() -> nearest(nearby, start)
            // With several matches far away, the plugin cannot tell which one has the step's code.
            else -> hits.singleOrNull() ?: return Resolved(location, StepStatus.LOST)
        }
        val result = at(found)
        return Resolved(result, status(location, result, lines))
    }

    /** The code of lines [start] to [end], as a story stores it. */
    fun rangeText(lines: List<String>, start: Int, end: Int): String = lines.subList(start - 1, end).joinToString("\n")

    /**
     * The code where a live marker followed it. The reader can edit the first line, so the anchor
     * text follows that line; the stored code keeps the change visible.
     */
    private fun followed(location: Location, lines: List<String>, live: IntRange): Resolved {
        val start = live.first.coerceIn(1, lines.size)
        val end = live.last.coerceIn(start, lines.size)
        val first = clean(lines[start - 1])
        val anchor = if (location.anchorText == null || first.isEmpty()) location.anchorText else first
        val result = location.copy(startLine = start, endLine = end, anchorText = anchor)
        return Resolved(result, status(location, result, lines))
    }

    private fun status(stored: Location, found: Location, lines: List<String>): StepStatus = when {
        !sameCode(stored, found, lines) -> StepStatus.CODE_CHANGED
        found.startLine == stored.startLine && found.endLine == stored.endLine -> StepStatus.OK
        else -> StepStatus.MOVED
    }

    /** Whether the range [found] has the stored code of [stored]. Without stored code, any range has it. */
    private fun sameCode(stored: Location, found: Location, lines: List<String>): Boolean {
        val code = (stored.rangeText ?: return true).split("\r\n", "\n", "\r")
        return found.endLine - found.startLine + 1 == code.size &&
            code.indices.all { clean(code[it]) == clean(lines[found.startLine - 1 + it]) }
    }

    /** The match closest to [start]; on a tie the later one, because code usually moves down when code is added above it. */
    private fun nearest(matches: List<Int>, start: Int): Int = matches.minWith(compareBy<Int> { abs(it - start) }.thenByDescending { it })

    private fun clean(text: String): String = text.trim().removePrefix("﻿").trim()
}

/** A story whose anchors were found again in the current code, with the status of each step by index. */
class AnchorResolution(val story: Story, val statuses: List<StepStatus>)

object StoryAnchors {
    /**
     * Finds the code of every step in the current [files]. [live] has the lines that live markers
     * followed, by step ID; a live range counts only while its step explains the same code.
     */
    fun resolve(story: Story, files: StoryFiles, live: Map<String, LiveRange> = emptyMap()): AnchorResolution {
        val texts = HashMap<String, List<String>?>()
        val resolved = story.steps.map { step ->
            val location = step.location ?: return@map step to StepStatus.OK
            val lines = if (location.file in texts) texts[location.file] else {
                (files.lookup(location.file) as? FileLookup.Found)?.let { TextFiles.lines(it.text) }.also { texts[location.file] = it }
            }
            // A file that is gone, is not text or leads out of the project has no code to find.
            if (lines == null) return@map step to StepStatus.LOST
            val range = step.id?.let { live[it] }?.takeIf { it.file == location.file && it.rangeText == location.rangeText }
            val result = AnchorPolicy.resolve(location, lines, range?.let { it.startLine..it.endLine })
            step.copy(location = result.location) to result.status
        }
        return AnchorResolution(story.copy(steps = resolved.map { it.first }), resolved.map { it.second })
    }
}
