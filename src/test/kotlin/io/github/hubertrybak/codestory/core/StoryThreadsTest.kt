package io.github.hubertrybak.codestory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The thread state machine: submitted → received → answered or declined, and reopening with a new round. */
class StoryThreadsTest {
    private val t1 = Instant.parse("2026-10-08T10:00:00Z")
    private val t2 = Instant.parse("2026-10-08T11:00:00Z")
    private val t3 = Instant.parse("2026-10-08T12:00:00Z")
    private val story = Story(
        "Payment flow", StoryType.CHANGE, "The service charges the card.", "Explain the payment flow", StoryAuthor("Test Agent", "test-model"),
        listOf(Step("Entry point", "The app starts here.", Location("src/App.kt", 2, 4), "entry"), Step("Overview", "Start here.", id = "overview")),
    )
    private var ids = 0
    private fun id() = "thread-${++ids}"

    private fun submit(story: Story, vararg drafts: DraftComment, time: Instant = t1) = StoryThreads.submit(story, drafts.toList(), time, ::id)
    private fun reply(story: Story, vararg replies: AgentReply, time: Instant = t2) = StoryThreads.reply(story, replies.withIndex().toList(), time)
    private fun thread(story: Story, id: String) = StoryThreads.find(story, id)!!

    @Test
    fun `submit writes all drafts as one numbered round on their steps or the story`() {
        val submitted = submit(story, DraftComment("Why here?", stepId = "entry"), DraftComment("What is missing?"), DraftComment("And this?", stepId = "entry"))
        assertEquals(listOf("thread-1", "thread-3"), submitted.steps[0].threads.map { it.id })
        assertEquals(listOf("thread-2"), submitted.threads.map { it.id })
        for ((_, thread) in StoryThreads.all(submitted)) {
            assertEquals(ThreadState.SUBMITTED, thread.state)
            assertEquals(ThreadKind.QUESTION, thread.kind)
            assertEquals(listOf(1), thread.messages.map { it.round })
            assertEquals(MessageAuthor.READER, thread.messages.single().author)
            assertEquals(t1, thread.messages.single().time)
        }
        assertEquals("Why here?", thread(submitted, "thread-1").messages.single().text)
        assertEquals(2, StoryThreads.nextRound(submitted))
        assertEquals(story, StoryThreads.strip(submitted))
    }

    @Test
    fun `drafts of removed steps and threads still arrive`() {
        val submitted = submit(story, DraftComment("Old step?", stepId = "removed"), DraftComment("Old thread?", stepId = "entry", threadId = "gone"))
        assertEquals("Old step?", submitted.threads.single().messages.single().text)
        assertEquals("Old thread?", submitted.steps[0].threads.single().messages.single().text)
    }

    @Test
    fun `fetching marks comments received and they stay pending until a reply`() {
        val submitted = submit(story, DraftComment("Why here?", stepId = "entry"), DraftComment("Overview?"))
        val received = StoryThreads.markReceived(submitted)
        assertTrue(StoryThreads.all(received).all { it.second.state == ThreadState.RECEIVED })
        assertEquals(StoryThreads.pending(submitted).map { it.thread.id }, StoryThreads.pending(received).map { it.thread.id })
        assertEquals(received, StoryThreads.markReceived(received))
        val pending = StoryThreads.pending(received).first { it.stepIndex == 0 }
        assertEquals(1, pending.round)
        assertEquals("Why here?", pending.comments.single().text)
        assertTrue(pending.earlier.isEmpty())
    }

    @Test
    fun `an answer closes a fetched question and the first reply wins`() {
        val received = StoryThreads.markReceived(submit(story, DraftComment("Why here?", stepId = "entry")))
        val first = reply(received, AgentReply("thread-1", 1, ReplyOutcome.ANSWERED, "Because the controller starts here."))
        assertEquals(listOf("thread-1"), first.saved)
        assertTrue(first.errors.isEmpty())
        val answered = thread(first.story, "thread-1")
        assertEquals(ThreadState.ANSWERED, answered.state)
        assertEquals(ThreadMessage(MessageAuthor.AGENT, 1, t2, "Because the controller starts here.", ReplyOutcome.ANSWERED), answered.messages.last())
        assertTrue(StoryThreads.pending(first.story).isEmpty())

        val second = reply(first.story, AgentReply("thread-1", 1, ReplyOutcome.ANSWERED, "Another answer."), time = t3)
        assertEquals(first.story, second.story)
        assertEquals(listOf(ErrorCode.ALREADY_ANSWERED.code), second.errors.map { it.code })
        assertEquals(1, second.errors.single().reply)
        assertEquals("thread-1", second.errors.single().threadId)
    }

    @Test
    fun `replies that do not fit their thread are rejected one by one`() {
        // The first comment was fetched; the second arrived after the fetch.
        val received = StoryThreads.markReceived(submit(story, DraftComment("Fetched?", stepId = "entry")))
        val fetched = submit(received, DraftComment("Not fetched?"), time = t2)
        val change = fetched.copy(threads = fetched.threads + StoryThread("change", ThreadKind.CHANGE, ThreadState.RECEIVED,
            listOf(ThreadMessage(MessageAuthor.READER, 1, t1, "Rename it."))))
        val result = reply(
            change,
            AgentReply("thread-1", 1, ReplyOutcome.ANSWERED, "Saved."),
            AgentReply("thread-1", 1, ReplyOutcome.ANSWERED, "Twice."),
            AgentReply("missing", 1, ReplyOutcome.ANSWERED, "Nowhere."),
            AgentReply("thread-2", 2, ReplyOutcome.ANSWERED, "Too early."),
            AgentReply("change", 1, ReplyOutcome.ANSWERED, "Wrong outcome."),
            AgentReply("change", 3, ReplyOutcome.DECLINED, "Repeated thread."),
        )
        assertEquals(listOf("thread-1"), result.saved)
        assertEquals(
            listOf(2 to ErrorCode.DUPLICATE_REPLY, 3 to ErrorCode.THREAD_NOT_FOUND, 4 to ErrorCode.NOT_FETCHED, 5 to ErrorCode.OUTCOME_MISMATCH, 6 to ErrorCode.DUPLICATE_REPLY),
            result.errors.map { it.reply to ErrorCode.entries.single { code -> code.code == it.code } },
        )
        assertTrue(result.errors.all { it.message.isNotBlank() && it.hint.isNotBlank() })
        val declined = reply(change, AgentReply("change", 1, ReplyOutcome.DECLINED, "The change needs a new ticket."))
        assertEquals(ThreadState.DECLINED, thread(declined.story, "change").state)
        assertEquals(ErrorCode.INVALID_VALUE.code, reply(change, AgentReply("change", 3, ReplyOutcome.DECLINED, "Wrong round.")).errors.single().code)
    }

    @Test
    fun `a new reader comment reopens a thread in a new round`() {
        val answered = reply(StoryThreads.markReceived(submit(story, DraftComment("Why here?", stepId = "entry"))),
            AgentReply("thread-1", 1, ReplyOutcome.ANSWERED, "Because.")).story
        val reopened = submit(answered, DraftComment("Why exactly?", stepId = "entry", threadId = "thread-1"), time = t3)
        val thread = thread(reopened, "thread-1")
        assertEquals(ThreadState.SUBMITTED, thread.state)
        assertEquals(listOf(1, 1, 2), thread.messages.map { it.round })
        val pending = StoryThreads.pending(StoryThreads.markReceived(reopened)).single()
        assertEquals(2, pending.round)
        assertEquals(listOf("Why here?", "Because."), pending.earlier.map { it.text })
        assertEquals(t3, pending.time)
        // A reply for the earlier round arrives late.
        val late = reply(StoryThreads.markReceived(reopened), AgentReply("thread-1", 1, ReplyOutcome.ANSWERED, "Late."))
        assertEquals(ErrorCode.ALREADY_ANSWERED.code, late.errors.single().code)
        assertEquals(listOf("thread-1"), reply(StoryThreads.markReceived(reopened), AgentReply("thread-1", 2, ReplyOutcome.ANSWERED, "Exactly so.")).saved)
    }

    @Test
    fun `withdraw takes back an unfetched comment as a draft`() {
        val submitted = submit(story, DraftComment("Why here?", stepId = "entry"))
        val (withdrawn, draft) = StoryThreads.withdraw(submitted, "thread-1")!!
        assertEquals(story, withdrawn)
        assertEquals(DraftComment("Why here?", stepId = "entry"), draft)
        assertNull(StoryThreads.withdraw(StoryThreads.markReceived(submitted), "thread-1"))
        assertNull(StoryThreads.withdraw(submitted, "missing"))

        // Withdrawing a reply restores the thread as the agent left it.
        val answered = reply(StoryThreads.markReceived(submitted), AgentReply("thread-1", 1, ReplyOutcome.DECLINED, "No.")).story
        val reopened = submit(answered, DraftComment("Please?", stepId = "entry", threadId = "thread-1"), time = t3)
        val (restored, reply) = StoryThreads.withdraw(reopened, "thread-1")!!
        assertEquals(answered, restored)
        assertEquals(DraftComment("Please?", stepId = "entry", threadId = "thread-1"), reply)
    }

    @Test
    fun `resolve closes only threads with a reply`() {
        val submitted = submit(story, DraftComment("Why here?"))
        assertNull(StoryThreads.resolve(submitted, "thread-1"))
        assertNull(StoryThreads.resolve(StoryThreads.markReceived(submitted), "thread-1"))
        val answered = reply(StoryThreads.markReceived(submitted), AgentReply("thread-1", 1, ReplyOutcome.ANSWERED, "Because.")).story
        val resolved = StoryThreads.resolve(answered, "thread-1")!!
        assertEquals(ThreadState.RESOLVED, thread(resolved, "thread-1").state)
        assertNull(StoryThreads.resolve(resolved, "thread-1"))
        // A new comment opens a resolved thread again.
        assertEquals(ThreadState.SUBMITTED, thread(submit(resolved, DraftComment("More?", threadId = "thread-1")), "thread-1").state)
    }

    @Test
    fun `a revision keeps threads by step ID and moves threads of removed steps to the story`() {
        val commented = submit(story, DraftComment("Entry?", stepId = "entry"), DraftComment("Overview?", stepId = "overview"), DraftComment("Story?"))
        val revised = story.copy(title = "Revised", steps = listOf(story.steps[0].copy(title = "Revised entry"), Step("New", "New step.", id = "new")))
        val kept = StoryThreads.carryOver(commented, revised)
        assertEquals("Revised", kept.title)
        assertEquals(listOf("Entry?"), kept.steps[0].threads.map { it.messages.single().text })
        assertTrue(kept.steps[1].threads.isEmpty())
        assertEquals(listOf("Story?", "Overview?"), kept.threads.map { it.messages.single().text })
        assertEquals(listOf(null, "Overview"), kept.threads.map { it.removedStep })
        // A revision with the same steps changes no thread.
        assertEquals(kept, StoryThreads.carryOver(kept, StoryThreads.strip(kept)))
        assertEquals(StoryThreads.all(commented).size, StoryThreads.all(kept).size)
    }
}
