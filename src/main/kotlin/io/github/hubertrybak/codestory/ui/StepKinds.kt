package io.github.hubertrybak.codestory.ui

import com.intellij.ui.JBColor
import io.github.hubertrybak.codestory.core.StepKind
import java.awt.Color

/** The color of each step kind. Steps that explain code use the accent of the gutter step badges. */
object StepKindColors {
    val ACCENT: Color = JBColor.namedColor("CodeStory.stepBadgeBackground", JBColor(0x3574F0, 0x3574F0))
    val PROPOSAL: Color = JBColor.namedColor("CodeStory.proposalAccent", JBColor(0x208A3C, 0x5FAD65))
    val CONTEXT: Color = JBColor.namedColor("CodeStory.contextAccent", JBColor(0x818594, 0x868A91))

    fun of(kind: StepKind): Color = when (kind) {
        StepKind.EXPLANATION -> ACCENT
        StepKind.CONTEXT -> CONTEXT
        StepKind.PROPOSAL -> PROPOSAL
    }
}

fun stepKindLabel(kind: StepKind): String = when (kind) {
    StepKind.EXPLANATION -> "Explanation"
    StepKind.CONTEXT -> "Context"
    StepKind.PROPOSAL -> "Proposal"
}
