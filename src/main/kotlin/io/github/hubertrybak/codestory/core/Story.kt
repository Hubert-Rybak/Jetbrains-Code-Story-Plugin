package io.github.hubertrybak.codestory.core

/**
 * A story that passed validation.
 *
 * File paths are relative to the story root (the Git repository root), with `/` between
 * folders. Line numbers are 1-based and inclusive.
 */
data class Story(val title: String, val steps: List<Step>)

data class Step(val title: String, val comment: String, val location: Location)

data class Location(val file: String, val startLine: Int, val endLine: Int)

object StoryLimits {
    const val MAX_STEPS = 15
    const val MAX_FILE_BYTES = 256 * 1024
}
