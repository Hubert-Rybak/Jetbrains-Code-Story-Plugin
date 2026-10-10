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
    STEP_NOT_FOUND("step-not-found"),
    NO_STEPS("no-steps"),
    TOO_MANY_STEPS("too-many-steps"),
    STORY_TOO_LARGE("story-too-large"),
    STORY_NOT_FOUND("story-not-found"),
    INVALID_STORY_ID("invalid-story-id"),
    INVALID_REPOSITORY("invalid-repository"),
    REPOSITORY_REQUIRED("repository-required"),
    CROSS_REPOSITORY("cross-repository"),
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
    ANCHOR_NOT_FOUND("anchor-not-found"),
    ANCHOR_AMBIGUOUS("anchor-ambiguous"),
    DUPLICATE_THREAD_ID("duplicate-thread-id"),
    THREAD_NOT_FOUND("thread-not-found"),
    DUPLICATE_REPLY("duplicate-reply"),
    ALREADY_ANSWERED("already-answered"),
    NOT_FETCHED("not-fetched"),
    OUTCOME_MISMATCH("outcome-mismatch"),
    STALE_STEPS("stale-steps"),
}

/** Machine-readable codes of changes that the plugin made to keep a story. */
enum class CorrectionCode(val code: String) {
    LINES_SHIFTED("lines-shifted"),
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
            "Make the comments shorter, point at fewer lines, or use fewer steps.",
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
    /** Line numbers that the plugin corrected by means of the anchor text. Corrections never reject a story. */
    val corrections: List<ReportItem>,
    /** Advice for the author only. Warnings never reject a story or appear in reader UI. */
    val warnings: List<ReportItem>,
    /** What the agent does next. */
    val next: String,
) {
    companion object {
        fun accepted(
            storyId: String,
            file: String,
            warnings: List<ReportItem> = emptyList(),
            corrections: List<ReportItem> = emptyList(),
        ) = ShowReport(
            status = ShowStatus.ACCEPTED,
            storyId = storyId,
            file = file,
            errors = emptyList(),
            corrections = corrections,
            warnings = warnings,
            next = when {
                warnings.isNotEmpty() && corrections.isNotEmpty() ->
                    "Fix the warnings once, then call code_story_show with the whole story, the corrected lines and storyId \"$storyId\" to replace it."
                warnings.isNotEmpty() -> "Fix the warnings once, then call code_story_show with the whole story and storyId \"$storyId\" to replace it."
                corrections.isNotEmpty() -> "The plugin corrected some line numbers. Tell the developer that the story is in the Code Story tool window. Next: call code_story_feedback when the reader submits comments."
                else -> "Tell the developer that the story is in the Code Story tool window. Next: call code_story_feedback when the reader submits comments."
            },
        )

        fun rejected(errors: List<ReportItem>, warnings: List<ReportItem> = emptyList(), corrections: List<ReportItem> = emptyList()) = ShowReport(
            status = ShowStatus.REJECTED,
            storyId = null,
            file = null,
            errors = errors,
            corrections = corrections,
            warnings = warnings,
            next = "Nothing was saved. Next: fix all errors, then call code_story_show again with the whole story.",
        )
    }
}
