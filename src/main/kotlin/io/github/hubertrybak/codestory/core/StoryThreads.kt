package io.github.hubertrybak.codestory.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * A reader's comment on a step or on the whole story, with the replies to it. Threads belong to
 * the plugin: readers and agents change them only through the plugin. The story file keeps them,
 * so they travel with a shared story.
 */
data class StoryThread(
    val id: String,
    val kind: ThreadKind,
    val state: ThreadState,
    val messages: List<ThreadMessage>,
    /** The title of the step that this thread was about, when a revision removed that step. The thread is then on the story. */
    val removedStep: String? = null,
    /** Plugin-owned feedback bookkeeping, cleared after the reply. */
    val storyBaseline: List<StoryStepSnapshot>? = null,
    val pendingStoryChanges: String? = null,
)

@Serializable
enum class ThreadKind(val value: String) {
    @SerialName("question") QUESTION("question"),

    /** A request to change the code. */
    @SerialName("change") CHANGE("change"),

    /** A request to update the steps whose code changed. */
    @SerialName("refresh") REFRESH("refresh"),
}

@Serializable
enum class ThreadState(val value: String) {
    /** The reader can withdraw the comment until an agent fetches it. */
    @SerialName("submitted") SUBMITTED("submitted"),

    /** An agent fetched the comment and works on it. */
    @SerialName("received") RECEIVED("received"),
    @SerialName("answered") ANSWERED("answered"),
    @SerialName("done") DONE("done"),
    @SerialName("declined") DECLINED("declined"),

    /** The reader closed the thread. */
    @SerialName("resolved") RESOLVED("resolved");

    /** The last comment waits for an agent. */
    val isPending: Boolean get() = this == SUBMITTED || this == RECEIVED
}

@Serializable
enum class MessageAuthor(val value: String) {
    @SerialName("reader") READER("reader"),
    @SerialName("agent") AGENT("agent"),
}

@Serializable
enum class ReplyOutcome(val value: String) {
    @SerialName("answered") ANSWERED("answered"),
    @SerialName("done") DONE("done"),
    @SerialName("declined") DECLINED("declined");

    val state: ThreadState get() = when (this) {
        ANSWERED -> ThreadState.ANSWERED
        DONE -> ThreadState.DONE
        DECLINED -> ThreadState.DECLINED
    }
}

/** [round] is the round of the comment that the message belongs to. Only agent replies have an [outcome]. */
data class ThreadMessage(
    val author: MessageAuthor,
    val round: Int,
    val time: Instant,
    val text: String,
    val outcome: ReplyOutcome? = null,
    /** Computed by the plugin, separate from the agent's answer and writing checks. */
    val storyChanges: String? = null,
)

/**
 * A comment that the reader did not submit yet: on the story, on the step [stepId], or a reply in
 * the thread [threadId]. Drafts stay in IDE storage, never in story files.
 */
data class DraftComment(
    val text: String,
    val stepId: String? = null,
    val threadId: String? = null,
    val kind: ThreadKind = ThreadKind.QUESTION,
)

/** An agent's reply to the comment of [round] in the thread [threadId]. */
data class AgentReply(val threadId: String, val round: Int, val outcome: ReplyOutcome, val text: String)

/** A comment that waits for an agent. [stepIndex] is `null` for a comment on the whole story. */
class PendingComment(val stepIndex: Int?, val thread: StoryThread) {
    /** The reader's messages after the last reply. */
    val comments: List<ThreadMessage> = thread.messages.takeLastWhile { it.author == MessageAuthor.READER }
    val earlier: List<ThreadMessage> = thread.messages.dropLast(comments.size)
    val round: Int = comments.last().round
    val time: Instant = comments.first().time
}

/** The thread state machine: submitted → received → answered or declined; a new comment opens a thread again. */
object StoryThreads {
    /** Every thread with the index of its step, or `null` for a thread about the whole story. */
    fun all(story: Story): List<Pair<Int?, StoryThread>> =
        story.threads.map { null to it } + story.steps.flatMapIndexed { index, step -> step.threads.map { index to it } }

    fun find(story: Story, threadId: String): StoryThread? = all(story).firstOrNull { it.second.id == threadId }?.second

    /** Rounds are numbered per story: one more than the highest round in it. */
    fun nextRound(story: Story): Int = (all(story).flatMap { it.second.messages }.maxOfOrNull { it.round } ?: 0) + 1

    /**
     * Writes [drafts] into the story as one new round. A draft in a thread adds a comment to that
     * thread and opens it again. Other drafts start threads on their step, or on the story when the
     * step is gone.
     */
    fun submit(story: Story, drafts: List<DraftComment>, time: Instant, newId: () -> String = { UUID.randomUUID().toString() }): Story {
        val round = nextRound(story)
        return drafts.fold(story) { result, draft ->
            val comment = ThreadMessage(MessageAuthor.READER, round, time, draft.text)
            val thread = draft.threadId?.let { find(result, it) }
            if (thread != null) {
                change(result, thread.id) { it.copy(state = ThreadState.SUBMITTED, messages = it.messages + comment) }
            } else {
                val ids = all(result).map { it.second.id }.toSet()
                add(result, draft.stepId, StoryThread(generateSequence(newId).first { it !in ids }, draft.kind, ThreadState.SUBMITTED, listOf(comment)))
            }
        }
    }

    /**
     * Takes back the last comment of a thread that no agent fetched: the story without it, and the
     * comment as a draft again. `null` when the comment cannot be withdrawn.
     */
    fun withdraw(story: Story, threadId: String): Pair<Story, DraftComment>? {
        val (stepIndex, thread) = all(story).firstOrNull { it.second.id == threadId } ?: return null
        val comment = thread.messages.lastOrNull()
        if (thread.state != ThreadState.SUBMITTED || comment?.author != MessageAuthor.READER) return null
        val rest = thread.messages.dropLast(1)
        val draft = DraftComment(comment.text, stepIndex?.let { story.steps[it].id }, threadId.takeIf { rest.isNotEmpty() }, thread.kind)
        val updated = if (rest.isEmpty()) mapThreads(story) { it.takeUnless { it.id == threadId } }
            else change(story, threadId) { it.copy(state = stateAfter(rest.last()), messages = rest) }
        return updated to draft
    }

    /** Closes a thread that an agent replied to. `null` while the thread waits for an agent. */
    fun resolve(story: Story, threadId: String): Story? {
        val thread = find(story, threadId) ?: return null
        if (thread.state.isPending || thread.state == ThreadState.RESOLVED) return null
        return change(story, threadId) { it.copy(state = ThreadState.RESOLVED) }
    }

    /** The comments that wait for an agent, oldest first. */
    fun pending(story: Story): List<PendingComment> = all(story)
        .filter { (_, thread) -> thread.state.isPending && thread.messages.lastOrNull()?.author == MessageAuthor.READER }
        .map { (stepIndex, thread) -> PendingComment(stepIndex, thread) }
        .sortedBy { it.time }

    /** An agent fetched the pending comments. They stay available until an agent replies. */
    fun markReceived(story: Story): Story =
        mapThreads(story) { if (it.state == ThreadState.SUBMITTED) it.copy(state = ThreadState.RECEIVED) else it }

    class ReplyResult(val story: Story, val saved: List<String>, val errors: List<ReplyIssue>)

    /**
     * Adds the agent's [replies], each to the comment that the agent fetched. The first reply to a
     * comment wins; a reply that does not fit its thread is rejected, and the other replies are saved.
     * The index of each reply is its position in the agent's list.
     */
    fun reply(story: Story, replies: List<IndexedValue<AgentReply>>, time: Instant): ReplyResult {
        val saved = mutableListOf<String>()
        val errors = mutableListOf<ReplyIssue>()
        val seen = mutableSetOf<String>()
        val result = replies.fold(story) { result, (index, reply) ->
            val problem = problem(result, reply, seen.add(reply.threadId))
            if (problem != null) {
                errors += ReplyIssue(index + 1, reply.threadId, problem.first.code, problem.second, problem.third)
                return@fold result
            }
            saved += reply.threadId
            val thread = checkNotNull(find(result, reply.threadId))
            val message = ThreadMessage(MessageAuthor.AGENT, reply.round, time, reply.text, reply.outcome, thread.pendingStoryChanges)
            change(result, reply.threadId) {
                it.copy(state = reply.outcome.state, messages = it.messages + message, storyBaseline = null, pendingStoryChanges = null)
            }
        }
        return ReplyResult(result, saved, errors)
    }

    /**
     * Keeps the threads of [previous] in its revision [revised]: each thread stays on its step, by
     * step ID. Threads of steps that the revision removed move to the story, with the step title.
     */
    fun carryOver(previous: Story, revised: Story): Story {
        val kept = revised.steps.mapNotNull { it.id }.toSet()
        val byStep = previous.steps.mapNotNull { step -> step.id?.let { it to step.threads } }.toMap()
        val moved = previous.steps.filter { it.id !in kept }
            .flatMap { step -> step.threads.map { it.copy(removedStep = it.removedStep ?: step.title) } }
        return revised.copy(
            threads = previous.threads + moved,
            steps = revised.steps.map { step -> step.copy(threads = step.id?.let(byStep::get).orEmpty()) },
        )
    }

    /** The story without comment threads: what its author wrote. */
    fun strip(story: Story): Story = story.copy(threads = emptyList(), steps = story.steps.map { it.copy(threads = emptyList()) })

    /** Why [reply] does not fit its thread, as code, message and hint, or `null` when it fits. */
    private fun problem(story: Story, reply: AgentReply, first: Boolean): Triple<ErrorCode, String, String>? {
        val id = reply.threadId
        if (!first) return Triple(ErrorCode.DUPLICATE_REPLY, "The replies have thread `$id` more than once.", "Send one reply for each thread.")
        val thread = find(story, id) ?: return Triple(
            ErrorCode.THREAD_NOT_FOUND, "The story has no thread `$id`.", "Use the threadId of a comment from code_story_feedback.",
        )
        val round = thread.messages.lastOrNull { it.author == MessageAuthor.READER }?.round ?: 0
        return when {
            !thread.state.isPending || reply.round < round -> Triple(
                ErrorCode.ALREADY_ANSWERED,
                if (reply.round < round) "Thread `$id` has a newer comment in round $round, so round ${reply.round} is already answered." else "Thread `$id` is already answered.",
                "Do not reply to this comment again. Call code_story_feedback for the comments that still wait.",
            )
            reply.round > round -> Triple(
                ErrorCode.INVALID_VALUE, "Thread `$id` has no comment in round ${reply.round}.", "Use the round of the comment from code_story_feedback.",
            )
            thread.state == ThreadState.SUBMITTED -> Triple(
                ErrorCode.NOT_FETCHED, "Thread `$id` has a comment that you did not fetch.", "Call code_story_feedback, then reply to the comments that it returns.",
            )
            reply.outcome == ReplyOutcome.ANSWERED && thread.kind != ThreadKind.QUESTION -> Triple(
                ErrorCode.OUTCOME_MISMATCH, "Thread `$id` is a ${thread.kind.value} request, not a question, so `answered` does not fit.",
                "Reply `declined` with the reason why you cannot do the request now.",
            )
            else -> null
        }
    }

    /** The state of a thread whose last message is [last]. */
    private fun stateAfter(last: ThreadMessage): ThreadState =
        if (last.author == MessageAuthor.AGENT) last.outcome?.state ?: ThreadState.ANSWERED else ThreadState.SUBMITTED

    private fun mapThreads(story: Story, transform: (StoryThread) -> StoryThread?): Story = story.copy(
        threads = story.threads.mapNotNull(transform),
        steps = story.steps.map { step -> if (step.threads.isEmpty()) step else step.copy(threads = step.threads.mapNotNull(transform)) },
    )

    private fun change(story: Story, threadId: String, transform: (StoryThread) -> StoryThread): Story =
        mapThreads(story) { if (it.id == threadId) transform(it) else it }

    private fun add(story: Story, stepId: String?, thread: StoryThread): Story {
        val index = story.steps.indexOfFirst { stepId != null && it.id == stepId }
        if (index < 0) return story.copy(threads = story.threads + thread)
        return story.copy(steps = story.steps.mapIndexed { i, step -> if (i == index) step.copy(threads = step.threads + thread) else step })
    }
}
