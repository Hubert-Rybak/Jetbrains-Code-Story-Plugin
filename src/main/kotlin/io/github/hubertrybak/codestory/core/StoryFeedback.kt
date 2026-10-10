package io.github.hubertrybak.codestory.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The answer to `code_story_feedback`: the comments that wait for an agent, oldest round first. */
@Serializable
data class FeedbackReport(
    val comments: List<FeedbackComment>,
    /** Steps whose code changed or is lost, in the stories of the comments or in the requested story. */
    val staleSteps: List<StaleStep>,
    val next: String,
)

@Serializable
data class FeedbackComment(
    val storyId: String,
    val storyTitle: String,
    val repositoryPath: String,
    /** Pass it back with the reply. */
    val round: Int,
    val threadId: String,
    val kind: ThreadKind,
    /** `story` for a comment on the whole story, or `step`. */
    val target: String,
    /** The step of the comment, or `null` for a comment on the whole story. */
    val step: FeedbackStep?,
    /** The title of the step that the thread was about, when a revision removed that step. */
    val removedStep: String?,
    /** The reader's comment. */
    val text: String,
    /** The earlier comments and replies of the thread, oldest first. */
    val earlierMessages: List<FeedbackMessage>,
)

/** [number] is 1-based. [location] has the current lines of the step's code. */
@Serializable
data class FeedbackStep(
    val id: String?,
    val number: Int,
    val title: String,
    val comment: String,
    val location: FeedbackLocation?,
    val status: StepStatus,
)

@Serializable
data class FeedbackLocation(val file: String, val startLine: Int, val endLine: Int)

@Serializable
data class FeedbackMessage(val author: MessageAuthor, val round: Int, val outcome: ReplyOutcome?, val text: String, val storyChanges: String? = null)

@Serializable
data class StaleStep(
    val storyId: String,
    val repositoryPath: String,
    val stepId: String?,
    val step: Int,
    val title: String,
    val status: StepStatus,
    val location: FeedbackLocation?,
)

/** A problem of one reply, or of the whole call when [reply] is `null`. */
@Serializable
data class ReplyIssue(
    /** The 1-based position of the reply in the call. */
    val reply: Int?,
    val threadId: String?,
    val code: String,
    val message: String,
    val hint: String,
)

/** The answer to `code_story_reply`. Each reply is saved or rejected on its own. */
@Serializable
data class ReplyReport(
    val storyId: String?,
    /** The threads whose replies were saved. */
    val saved: List<String>,
    /** Problems that kept a reply from being saved. */
    val errors: List<ReplyIssue>,
    /** Writing advice for saved replies. Warnings never block a reply. */
    val warnings: List<ReplyIssue>,
    val next: String,
) {
    companion object {
        fun of(storyId: String?, saved: List<String>, errors: List<ReplyIssue>, warnings: List<ReplyIssue>) = ReplyReport(
            storyId, saved, errors, warnings,
            next = when {
                errors.isNotEmpty() && saved.isEmpty() -> "Nothing was saved. Next: fix the errors and call code_story_reply again. Do not reply to comments that are already answered."
                errors.isNotEmpty() -> "The other replies are saved. Next: fix the errors and call code_story_reply with only the rejected replies. Do not reply to comments that are already answered."
                warnings.isNotEmpty() -> "The replies are saved. Use the writing rules in your next replies. Tell the developer that the answers are in the Code Story tool window. Next: call code_story_feedback for pending comments."
                else -> "Tell the developer that the answers are in the Code Story tool window. Next: call code_story_feedback for pending comments."
            },
        )

        fun failed(error: ReplyIssue) = of(null, emptyList(), listOf(error), emptyList())
    }
}

object StoryFeedback {
    /**
     * The comments of [stories] that wait for an agent, oldest first, and the "code changed" and
     * "lost" steps of the stories with comments. With [requested], every story lists its stale steps.
     */
    fun report(stories: List<StoredStory>, requested: Boolean): FeedbackReport {
        val pending = stories.flatMap { stored -> StoryThreads.pending(stored.story).map { stored to it } }
            .sortedWith(compareBy({ it.second.time }, { it.first.id }, { it.first.root.toString() }))
        val comments = pending.map { (stored, comment) ->
            val step = comment.stepIndex?.let { step(stored, it) }
            FeedbackComment(
                stored.id, stored.story.title, stored.root.toString(), comment.round, comment.thread.id, comment.thread.kind,
                if (step == null) "story" else "step", step, comment.thread.removedStep,
                comment.comments.joinToString("\n\n") { it.text },
                comment.earlier.map { FeedbackMessage(it.author, it.round, it.outcome, it.text, it.storyChanges) },
            )
        }
        val withComments = pending.map { it.first.key }.toSet()
        val stale = stories.filter { requested || it.key in withComments }.flatMap { stored ->
            stored.story.steps.indices.filter { stored.status(it) == StepStatus.CODE_CHANGED || stored.status(it) == StepStatus.LOST }.map { index ->
                val step = stored.story.steps[index]
                StaleStep(stored.id, stored.root.toString(), step.id, index + 1, step.title, stored.status(index), step.location?.let(::location))
            }
        }
        val next = if (comments.isEmpty()) "No comments wait for an agent. Tell the developer that there is no feedback to answer. Next: call code_story_feedback after the reader submits a round."
            else "Next: answer every comment with code_story_reply: pass storyId, and for each comment its threadId, round, " +
                "the outcome answered (or declined with a reason) and your text. Never change code for a question. You may clarify the story with code_story_patch before replying."
        return FeedbackReport(comments, stale, next)
    }

    private fun step(stored: StoredStory, index: Int): FeedbackStep {
        val step = stored.story.steps[index]
        return FeedbackStep(step.id, index + 1, step.title, step.comment, step.location?.let(::location), stored.status(index))
    }

    private fun location(location: Location) = FeedbackLocation(location.file, location.startLine, location.endLine)
}

/** Reads the agent's replies. Each reply is an object with `threadId`, `round`, `outcome` and `text`. */
object ReplyInput {
    private const val OUTCOME_HINT = "Use `answered` for a question, or `declined` with the reason."

    fun parse(replies: List<JsonElement>): Pair<List<IndexedValue<AgentReply>>, List<ReplyIssue>> {
        val errors = mutableListOf<ReplyIssue>()
        val parsed = replies.mapIndexedNotNull { index, element ->
            val number = index + 1
            val reply = element as? JsonObject
            if (reply == null) {
                errors += ReplyIssue(number, null, ErrorCode.WRONG_TYPE.code, "Reply $number must be a JSON object.", "Send each reply as an object with `threadId`, `round`, `outcome` and `text`.")
                return@mapIndexedNotNull null
            }
            val (threadId, threadProblem) = text(reply, "threadId")
            fun error(code: ErrorCode, message: String, hint: String): Nothing? {
                errors += ReplyIssue(number, threadId, code.code, message, hint)
                return null
            }
            threadProblem?.let { error(it, "Reply $number has no valid `threadId`.", "Copy the `threadId` of the comment from code_story_feedback.") }
            val round = (reply["round"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 1 }
            if (round == null) {
                error(if (reply["round"] == null || reply["round"] == JsonNull) ErrorCode.MISSING_FIELD else ErrorCode.INVALID_VALUE,
                    "Reply $number has no valid `round`.", "Copy the `round` of the comment from code_story_feedback.")
            }
            val (outcomeText, outcomeProblem) = text(reply, "outcome")
            val outcome = when (outcomeText) {
                ReplyOutcome.ANSWERED.value -> ReplyOutcome.ANSWERED
                ReplyOutcome.DECLINED.value -> ReplyOutcome.DECLINED
                null -> error(checkNotNull(outcomeProblem), "Reply $number has no valid `outcome`.", OUTCOME_HINT)
                else -> error(ErrorCode.INVALID_VALUE, "Reply $number has an unknown `outcome`: `${outcomeText.take(40)}`.", OUTCOME_HINT)
            }
            val (text, textProblem) = text(reply, "text")
            textProblem?.let { error(it, "Reply $number has no `text`.", "Write the answer, or the reason why you decline, in `text`.") }
            if (threadId == null || round == null || outcome == null || text == null) null else IndexedValue(index, AgentReply(threadId, round, outcome, text))
        }
        return parsed to errors
    }

    /** The trimmed text of [name], or the code of its problem. */
    private fun text(reply: JsonObject, name: String): Pair<String?, ErrorCode?> {
        val element = reply[name]
        return when {
            element == null || element is JsonNull -> null to ErrorCode.MISSING_FIELD
            element !is JsonPrimitive || !element.isString -> null to ErrorCode.WRONG_TYPE
            element.content.isBlank() -> null to ErrorCode.EMPTY_TEXT
            else -> element.content.trim() to null
        }
    }
}
