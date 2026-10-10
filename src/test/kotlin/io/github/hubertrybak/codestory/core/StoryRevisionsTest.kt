package io.github.hubertrybak.codestory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class StoryRevisionsTest {
    private val time = Instant.parse("2026-10-10T10:00:00Z")
    private val initial = Story(
        "Request flow", StoryType.EXPLORATION, "Follow the request.", "Explain the request.", StoryAuthor("Test", "Test"),
        listOf(Step("Entry", "The app starts here.", id = "entry"), Step("Service", "It checks the request.", id = "service")),
        createdAt = time,
    )

    @Test
    fun `a reply describes net changes after repeated updates and removes temporary additions`() {
        val received = StoryThreads.markReceived(StoryThreads.submit(initial, listOf(DraftComment("Explain?", stepId = "entry")), time) { "question" })
        val edited = revise(received, received.steps.map { if (it.id == "entry") it.copy(comment = "The controller starts here.") else it } + Step("Temporary", "Read this.", id = "temporary"))
        val again = revise(roundTrip(edited), edited.steps.filter { it.id != "temporary" }.map { if (it.id == "entry") it.copy(title = "Controller entry") else it })
        val answered = StoryThreads.reply(again, listOf(IndexedValue(0, AgentReply("question", 1, ReplyOutcome.ANSWERED, "The controller starts here."))), time).story
        assertEquals("Step 1 updated", answered.steps[0].threads.single().messages.last().storyChanges)
        assertEquals(answered, roundTrip(answered))
    }

    @Test
    fun `reverting edits adds no story changes line and reader activity alone adds none`() {
        val received = StoryThreads.markReceived(StoryThreads.submit(initial, listOf(DraftComment("Explain?", stepId = "entry")), time) { "question" })
        val edited = revise(received, received.steps.map { it.copy(comment = "A temporary explanation.") })
        val reverted = revise(edited, initial.steps)
        assertNull(reverted.steps[0].threads.single().pendingStoryChanges)
        val answered = StoryThreads.reply(reverted, listOf(IndexedValue(0, AgentReply("question", 1, ReplyOutcome.ANSWERED, "The explanation is unchanged."))), time).story
        assertNull(answered.steps[0].threads.single().messages.last().storyChanges)
        assertNull(answered.steps[0].threads.single().storyBaseline)
    }

    @Test
    fun `summaries use stable IDs revised numbers and original numbers for removals`() {
        val after = initial.copy(steps = listOf(Step("New", "A new overview.", id = "new"), initial.steps[0].copy(title = "Controller")))
        assertEquals("Step 2 updated, step 1 added, step 2 removed", StoryRevisions.summary(StoryRevisions.snapshot(initial), StoryRevisions.snapshot(after)))
        // Stored code is metadata, not an authored story edit.
        val withCode = initial.copy(steps = listOf(initial.steps[0].copy(location = Location("App.kt", 1, 2, "start", "original"))))
        val differentCode = withCode.copy(steps = withCode.steps.map { it.copy(location = it.location!!.copy(rangeText = "changed")) })
        assertNull(StoryRevisions.summary(StoryRevisions.snapshot(withCode), StoryRevisions.snapshot(differentCode)))
    }

    private fun revise(before: Story, steps: List<Step>) = StoryRevisions.record(before, StoryThreads.carryOver(before, before.copy(steps = steps)))
    private fun roundTrip(story: Story) = (StoryFormat.decode(StoryFormat.encode(story, "2026-10-10-story")) as StoryFormat.Decoded.Valid).story
}
