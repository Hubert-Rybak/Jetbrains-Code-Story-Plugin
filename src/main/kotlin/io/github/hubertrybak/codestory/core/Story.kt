package io.github.hubertrybak.codestory.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A story that passed validation.
 *
 * File paths are relative to the story root (the Git repository root), with `/` between
 * folders. Line numbers are 1-based and inclusive.
 */
data class Story(
    val title: String,
    val type: StoryType,
    val summary: String,
    val request: String,
    val author: StoryAuthor,
    val steps: List<Step>,
    /** Assigned by the plugin when the story is created. */
    val createdAt: Instant? = null,
    val git: StoryGit = StoryGit(),
    /**
     * The commit that a story about a change compares the code with, set by the agent. The plugin
     * saves the full commit SHA when Git knows the commit. `null` for stories without change information.
     */
    val baseRef: String? = null,
)

@Serializable
enum class StoryType(val value: String) {
    @SerialName("change") CHANGE("change"),
    @SerialName("exploration") EXPLORATION("exploration"),
    @SerialName("bug-trace") BUG_TRACE("bug-trace"),
    @SerialName("review") REVIEW("review"),
}

data class StoryAuthor(val agent: String, val model: String)

/** Null fields mean Git is unavailable, the repository has no commits, or HEAD is detached. */
data class StoryGit(val branch: String? = null, val commit: String? = null)

data class Step(val title: String, val comment: String, val location: Location? = null, val id: String? = null)

/**
 * One range of lines. [anchorText] is the first line of the range without indentation; the plugin
 * uses it to find the range again after the code moves. [rangeText] is the code that the step
 * explains, with `\n` between lines; a step whose range has other code has "code changed" status.
 * Both are `null` in story files without anchors.
 */
data class Location(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val anchorText: String? = null,
    val rangeText: String? = null,
)

object StoryLimits {
    const val MAX_STEPS = 15
    const val MAX_FILE_BYTES = 256 * 1024
}
