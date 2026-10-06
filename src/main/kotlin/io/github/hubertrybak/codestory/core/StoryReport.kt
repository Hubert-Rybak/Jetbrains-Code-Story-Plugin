package io.github.hubertrybak.codestory.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Machine-readable codes of the problems that reject a story. */
enum class ErrorCode(val code: String) {
    MISSING_FIELD("missing-field"),
    WRONG_TYPE("wrong-type"),
    EMPTY_TEXT("empty-text"),
    INVALID_VALUE("invalid-value"),
    DUPLICATE_STEP_ID("duplicate-step-id"),
    NO_STEPS("no-steps"),
    TOO_MANY_STEPS("too-many-steps"),
    STORY_TOO_LARGE("story-too-large"),
    ABSOLUTE_PATH("absolute-path"),
    PARENT_PATH("parent-path"),
    INVALID_PATH("invalid-path"),
    OUTSIDE_PROJECT("outside-project"),
    FILE_NOT_FOUND("file-not-found"),
    NOT_A_FILE("not-a-file"),
    NOT_A_TEXT_FILE("not-a-text-file"),
    INVALID_LINE("invalid-line"),
    REVERSED_RANGE("reversed-range"),
    LINES_OUTSIDE_FILE("lines-outside-file"),
}

@Serializable
data class ReportItem(
    /** The 1-based step number, or `null` for a problem of the whole story. */
    val step: Int?,
    val code: String,
    val message: String,
    val hint: String,
) {
    constructor(step: Int?, code: ErrorCode, message: String, hint: String) : this(step, code.code, message, hint)

    companion object {
        fun storyTooLarge(bytes: Int) = ReportItem(
            null, ErrorCode.STORY_TOO_LARGE,
            "The story is ${(bytes + 1023) / 1024} KB. The limit is ${StoryLimits.MAX_FILE_BYTES / 1024} KB.",
            "Make the comments shorter, or use fewer steps.",
        )
    }
}

@Serializable
enum class ShowStatus {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("rejected")
    REJECTED,
}

/** The answer to `code_story_show`. */
@Serializable
data class ShowReport(
    val status: ShowStatus,
    /** The ID of the saved story, or `null` when the story was rejected. */
    val storyId: String?,
    /** The saved story file, relative to the repository root, or `null` when the story was rejected. */
    val file: String?,
    val errors: List<ReportItem>,
    /** What the agent does next. */
    val next: String,
) {
    companion object {
        fun accepted(storyId: String, file: String) = ShowReport(
            status = ShowStatus.ACCEPTED,
            storyId = storyId,
            file = file,
            errors = emptyList(),
            next = "Tell the developer that the story is in the Code Story tool window.",
        )

        fun rejected(errors: List<ReportItem>) = ShowReport(
            status = ShowStatus.REJECTED,
            storyId = null,
            file = null,
            errors = errors,
            next = "Nothing was saved. Fix all errors, then call code_story_show again with the whole story.",
        )
    }
}
