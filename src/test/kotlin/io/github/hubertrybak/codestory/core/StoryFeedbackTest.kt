package io.github.hubertrybak.codestory.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class StoryFeedbackTest {
    private val root = Path.of("/repo")
    private val t1 = Instant.parse("2026-10-08T10:00:00Z")
    private val t2 = Instant.parse("2026-10-08T11:00:00Z")

    private fun stored(id: String, story: Story, statuses: List<StepStatus> = emptyList()) =
        StoredStory(id, root, root.resolve(".codestory/local/$id.story.json"), story, FileTime.fromMillis(0), statuses)

    private fun story(title: String) = Story(
        title, StoryType.CHANGE, "The service charges the card.", "Explain the payment flow", StoryAuthor("Test Agent", "test-model"),
        listOf(Step("Entry point", "The app starts here.", Location("src/App.kt", 2, 4), "entry"), Step("Overview", "Start here.", id = "overview")),
    )

    @Test
    fun `comments come oldest first with their step, text and earlier messages`() {
        var ids = 0
        val older = StoryThreads.submit(story("Older"), listOf(DraftComment("On the story?")), t1) { "a${++ids}" }
        val answered = StoryThreads.reply(StoryThreads.markReceived(StoryThreads.submit(story("Newer"), listOf(DraftComment("Why?", stepId = "entry")), t1) { "b${++ids}" }),
            listOf(IndexedValue(0, AgentReply("b2", 1, ReplyOutcome.ANSWERED, "Because."))), t1).story
        val newer = StoryThreads.submit(answered, listOf(DraftComment("Why exactly?", stepId = "entry", threadId = "b2")), t2)
        val report = StoryFeedback.report(listOf(stored("2026-10-08-newer", newer, listOf(StepStatus.CODE_CHANGED, StepStatus.OK)), stored("2026-10-08-older", older)), requested = false)

        assertEquals(listOf("2026-10-08-older", "2026-10-08-newer"), report.comments.map { it.storyId })
        val (story, step) = report.comments
        assertEquals("story", story.target)
        assertNull(story.step)
        assertEquals("On the story?", story.text)
        assertEquals("step", step.target)
        assertEquals(FeedbackStep("entry", 1, "Entry point", "The app starts here.", FeedbackLocation("src/App.kt", 2, 4), StepStatus.CODE_CHANGED), step.step)
        assertEquals(2, step.round)
        assertEquals("Why exactly?", step.text)
        assertEquals(listOf(FeedbackMessage(MessageAuthor.READER, 1, null, "Why?"), FeedbackMessage(MessageAuthor.AGENT, 1, ReplyOutcome.ANSWERED, "Because.")), step.earlierMessages)
        assertEquals("/repo", step.repositoryPath)
        assertEquals(listOf(StaleStep("2026-10-08-newer", "/repo", "entry", 1, "Entry point", StepStatus.CODE_CHANGED, FeedbackLocation("src/App.kt", 2, 4))), report.staleSteps)
        assertTrue(report.next.contains("code_story_reply"))
    }

    @Test
    fun `stale steps come with comments or for the requested story`() {
        val quiet = stored("2026-10-08-quiet", story("Quiet"), listOf(StepStatus.LOST, StepStatus.OK))
        val none = StoryFeedback.report(listOf(quiet), requested = false)
        assertTrue(none.comments.isEmpty() && none.staleSteps.isEmpty())
        assertTrue(none.next.contains("No comments"))
        assertEquals(listOf(StepStatus.LOST), StoryFeedback.report(listOf(quiet), requested = true).staleSteps.map { it.status })
    }

    @Test
    fun `reply input names the problem of each reply`() {
        val replies = Json.parseToJsonElement("""[
            {"threadId": "a", "round": 1, "outcome": "answered", "text": " Because. "},
            {"threadId": "b", "round": 2, "outcome": "declined", "text": "Not now."},
            {"round": 1, "outcome": "answered", "text": "No thread."},
            {"threadId": "c", "round": 0, "outcome": "answered", "text": "Round zero."},
            {"threadId": "d", "round": "1", "outcome": "answered", "text": "Round text."},
            {"threadId": "e", "round": 1, "outcome": "done", "text": "Done is not for questions."},
            {"threadId": "f", "round": 1, "outcome": "answered", "text": "  "},
            {"threadId": " ", "round": 1, "text": "No outcome."},
            7
        ]""").jsonArray
        val (parsed, errors) = ReplyInput.parse(replies)
        assertEquals(listOf(IndexedValue(0, AgentReply("a", 1, ReplyOutcome.ANSWERED, "Because.")), IndexedValue(1, AgentReply("b", 2, ReplyOutcome.DECLINED, "Not now."))), parsed)
        assertEquals(listOf(
            3 to ErrorCode.MISSING_FIELD, 4 to ErrorCode.INVALID_VALUE, 5 to ErrorCode.INVALID_VALUE, 6 to ErrorCode.INVALID_VALUE,
            7 to ErrorCode.EMPTY_TEXT, 8 to ErrorCode.EMPTY_TEXT, 8 to ErrorCode.MISSING_FIELD, 9 to ErrorCode.WRONG_TYPE,
        ), errors.map { it.reply to ErrorCode.entries.single { code -> code.code == it.code } })
        assertEquals(listOf(null, "c", "d", "e", "f", null, null, null), errors.map { it.threadId })
        assertTrue(errors.all { it.message.isNotBlank() && it.hint.isNotBlank() })
    }

    @Test
    fun `answers may be longer than step comments and reasons are short`() {
        val checks = StoryWritingChecks()
        fun codes(text: String, outcome: ReplyOutcome = ReplyOutcome.ANSWERED) = checks.reply(text, outcome).map { it.code }.toSet()
        val sentence = "The service checks the card."
        assertEquals(emptySet<String>(), codes(List(5) { sentence }.joinToString(" ")))
        assertEquals(setOf(WarningCode.COMMENT_SENTENCES.code), codes(List(6) { sentence }.joinToString(" ")))
        assertEquals(emptySet<String>(), codes("Check these cases.\n" + List(6) { "- Case $it" }.joinToString("\n")))
        assertEquals(setOf(WarningCode.COMMENT_LIST.code), codes("Check these cases.\n" + List(7) { "- Case $it" }.joinToString("\n")))
        val words = { count: Int -> List(count / 20) { "The" + " word".repeat(19) + "." }.joinToString(" ") }
        assertEquals(emptySet<String>(), codes(words(100)))
        assertEquals(setOf(WarningCode.COMMENT_TOO_LONG.code, WarningCode.COMMENT_SENTENCES.code), codes(words(120)))
        assertEquals(emptySet<String>(), codes(List(3) { sentence }.joinToString(" "), ReplyOutcome.DECLINED))
        assertEquals(setOf(WarningCode.COMMENT_SENTENCES.code), codes(List(4) { sentence }.joinToString(" "), ReplyOutcome.DECLINED))
        assertEquals(setOf(WarningCode.SEMICOLON.code, WarningCode.CONTRACTION.code), codes("It doesn't fail; it waits."))
        val warning = checks.reply(List(6) { sentence }.joinToString(" "), ReplyOutcome.ANSWERED).single()
        assertNull(warning.step)
        assertTrue(warning.message, warning.message.startsWith("The reply `text`"))
        assertTrue(warning.hint, warning.hint.contains("1 to 5 sentences") && warning.hint.contains("6 list items"))
    }
}
