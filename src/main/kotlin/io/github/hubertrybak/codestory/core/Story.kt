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
     * Only for a story about a change: the commit before the change. The plugin saves the full
     * commit SHA when Git knows it. `HEAD` means the current commit, for uncommitted work.
     */
    val baseRef: String? = null,
    /** MCP stories play only in the receiving IDE. File-drop stories have no marker. */
    val delivery: String? = null,
    /** Comment threads about the whole story. They belong to the plugin; an agent cannot set them. */
    val threads: List<StoryThread> = emptyList(),
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

/**
 * [threads] are the comment threads about this step. They belong to the plugin, like the story's threads.
 * [proposedCode] is only for a [StepKind.PROPOSAL]: the code that the proposal puts in place of the
 * lines of [location], with `\n` between lines, or new code for a step without a location.
 */
data class Step(
    val title: String,
    val comment: String,
    val location: Location? = null,
    val id: String? = null,
    val threads: List<StoryThread> = emptyList(),
    val kind: StepKind = StepKind.EXPLANATION,
    val proposedCode: String? = null,
)

/** What a step does for the reader. Story files omit the kind of an explanation. */
@Serializable
enum class StepKind(val value: String) {
    /** Explains the code of the step, or introduces or closes the story. */
    @SerialName("explanation") EXPLANATION("explanation"),

    /** Gives background for the step before it, for example a method that it calls or a setting that it reads. */
    @SerialName("context") CONTEXT("context"),

    /** Suggests a change that the agent did not make. */
    @SerialName("proposal") PROPOSAL("proposal"),
}

/**
 * The index of the step that the context step [index] gives background for: the nearest earlier
 * step that is not context. `null` for other kinds, and for context before the first such step,
 * which is background for the whole story.
 */
fun Story.contextOwner(index: Int): Int? {
    if (steps[index].kind != StepKind.CONTEXT) return null
    return (index - 1 downTo 0).firstOrNull { steps[it].kind != StepKind.CONTEXT }
}

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
