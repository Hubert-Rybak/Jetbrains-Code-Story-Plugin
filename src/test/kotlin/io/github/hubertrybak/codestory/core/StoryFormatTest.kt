package io.github.hubertrybak.codestory.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class StoryFormatTest {
    private val story = Story(
        title = "Explain the payment flow",
        type = StoryType.EXPLORATION,
        summary = "The controller checks the request. The billing service charges the card.",
        request = "Explain the payment flow",
        author = StoryAuthor("Test Agent", "test-model"),
        steps = listOf(
            Step(
                "Entry point", "The `PaymentController` takes the request.",
                Location("src/Payment.kt", 12, 14, "class PaymentController(", "class PaymentController(\n    private val billing: Billing,\n) {"), "entry",
            ),
            Step("What to check", "Line one.\n- item", id = "checks"),
        ),
        createdAt = Instant.parse("2026-10-05T12:34:56.123Z"),
        git = StoryGit("feature/payments", "0123456789abcdef"),
        baseRef = "89abcdef0123456789abcdef0123456789abcdef",
    )

    @Test
    fun `writes schema version 1, pretty-printed, with a fixed field order`() {
        val expected = """
            {
              "schemaVersion": 1,
              "id": "2026-10-05-explain-the-payment-flow",
              "title": "Explain the payment flow",
              "type": "exploration",
              "summary": "The controller checks the request. The billing service charges the card.",
              "request": "Explain the payment flow",
              "author": {
                "agent": "Test Agent",
                "model": "test-model"
              },
              "createdAt": "2026-10-05T12:34:56.123Z",
              "git": {
                "branch": "feature/payments",
                "commit": "0123456789abcdef"
              },
              "baseRef": "89abcdef0123456789abcdef0123456789abcdef",
              "steps": [
                {
                  "id": "entry",
                  "title": "Entry point",
                  "comment": "The `PaymentController` takes the request.",
                  "location": {
                    "file": "src/Payment.kt",
                    "startLine": 12,
                    "endLine": 14,
                    "anchorText": "class PaymentController(",
                    "rangeText": "class PaymentController(\n    private val billing: Billing,\n) {"
                  }
                },
                {
                  "id": "checks",
                  "title": "What to check",
                  "comment": "Line one.\n- item"
                }
              ]
            }

        """.trimIndent()
        assertEquals(expected, StoryFormat.encode(story, "2026-10-05-explain-the-payment-flow"))
    }

    @Test
    fun `round trips every story type, metadata, stable IDs and optional locations`() {
        for (type in StoryType.entries) {
            for (git in listOf(story.git, StoryGit(), StoryGit("unborn", null), StoryGit(null, "detached-commit"))) {
                for (baseRef in listOf(story.baseRef, null, "HEAD")) {
                    val original = story.copy(type = type, git = git, baseRef = baseRef)
                    assertEquals(original, (StoryFormat.decode(StoryFormat.encode(original, "id")) as StoryFormat.Decoded.Valid).story)
                }
            }
        }
    }

    @Test
    fun `a story without a base has no baseRef field`() {
        val text = StoryFormat.encode(story.copy(baseRef = null), "id")
        assertFalse(text, text.contains("baseRef"))
    }

    @Test
    fun `null locations and reserved fields do not affect v1 steps`() {
        val text = StoryFormat.encode(story, "id").replace(
            "\"id\": \"checks\",",
            "\"id\": \"checks\", \"location\": null, \"children\": [{}], \"links\": [\"later\"],",
        )
        val decoded = (StoryFormat.decode(text) as StoryFormat.Decoded.Valid).story
        assertEquals(story, decoded)
        val encoded = StoryFormat.encode(decoded, "id")
        assertFalse(encoded.contains("\"children\""))
        assertFalse(encoded.contains("\"links\""))
    }

    @Test
    fun `reads story files without anchors`() {
        val text = StoryFormat.encode(story, "id")
            .replace(",\n        \"anchorText\": \"class PaymentController(\"", "")
            .replace(",\n        \"rangeText\": \"class PaymentController(\\n    private val billing: Billing,\\n) {\"", "")
        assertFalse(text, text.contains("anchorText") || text.contains("rangeText"))
        val decoded = (StoryFormat.decode(text) as StoryFormat.Decoded.Valid).story
        assertEquals(Location("src/Payment.kt", 12, 14), decoded.steps.first().location)
        assertEquals(text, StoryFormat.encode(decoded, "id"))
    }

    @Test
    fun `rejects unsupported schemas and malformed stored fields`() {
        val text = StoryFormat.encode(story, "id")
        val invalid = listOf(
            text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            text.replace("\"schemaVersion\": 1,", ""),
            text.replace("\"title\": \"Entry point\",", ""),
            text.replace("\"id\": \"entry\",", ""),
            text.replace("2026-10-05T12:34:56.123Z", "yesterday"),
            text.replace("\"feature/payments\"", "42"),
            text.replace("\"agent\": \"Test Agent\",", ""),
            text.replace("\"exploration\"", "\"tour\""),
            text.replace("\"anchorText\": \"class PaymentController(\"", "\"anchorText\": \" \""),
            text.replace("\"anchorText\": \"class PaymentController(\"", "\"anchorText\": \"one\\ntwo\""),
            text.replace("\"rangeText\": \"class PaymentController(", "\"rangeText\": 7, \"x\": \"("),
            text.replace("\"89abcdef0123456789abcdef0123456789abcdef\"", "\"--output=/tmp/story\""),
            text.replace("\"89abcdef0123456789abcdef0123456789abcdef\"", "42"),
            "[1, 2]",
            "{ broken",
        )
        for (file in invalid) assertTrue(file, StoryFormat.decode(file) is StoryFormat.Decoded.Invalid)
    }

    @Test
    fun `published and bundled schemas describe required and optional fields in write order`() {
        val published = Files.readString(Path.of("schema/story-v1.schema.json"))
        assertEquals(published, Files.readString(Path.of("skills/code-story/references/story-v1.schema.json")))
        val schema = Json.parseToJsonElement(published).jsonObject
        val definitions = schema.getValue("\$defs").jsonObject
        val file = Json.parseToJsonElement(StoryFormat.encode(story, "2026-10-05-explain-the-payment-flow")).jsonObject
        val steps = file.getValue("steps").jsonArray.map { it.jsonObject }

        assertFields(schema, file)
        steps.forEach { assertFields(definitions.getValue("step").jsonObject, it) }
        assertFields(definitions.getValue("location").jsonObject, steps.first().getValue("location").jsonObject)
        assertFields(definitions.getValue("author").jsonObject, file.getValue("author").jsonObject)
        assertFields(definitions.getValue("git").jsonObject, file.getValue("git").jsonObject)
        val properties = schema.getValue("properties").jsonObject
        assertEquals(StoryFormat.SCHEMA_VERSION, properties.getValue("schemaVersion").jsonObject.getValue("const").jsonPrimitive.int)
        assertEquals(StoryLimits.MAX_STEPS, properties.getValue("steps").jsonObject.getValue("maxItems").jsonPrimitive.int)
        assertEquals(StoryType.entries.map { it.value }, properties.getValue("type").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("id", "title", "comment"), definitions.getValue("step").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    private val time = Instant.parse("2026-10-08T12:00:00Z")
    private val question = StoryThread("q1", ThreadKind.QUESTION, ThreadState.ANSWERED, listOf(
        ThreadMessage(MessageAuthor.READER, 1, time, "Why `retry`?"),
        ThreadMessage(MessageAuthor.AGENT, 1, time.plusSeconds(60), "The provider can time out.", ReplyOutcome.ANSWERED),
    ))
    private val threaded = story.copy(
        steps = listOf(story.steps[0].copy(threads = listOf(question)), story.steps[1]),
        threads = listOf(StoryThread("q2", ThreadKind.QUESTION, ThreadState.SUBMITTED,
            listOf(ThreadMessage(MessageAuthor.READER, 2, time, "What is missing?")), removedStep = "Old step")),
    )

    @Test
    fun `writes comment threads after the location and the steps`() {
        val text = StoryFormat.encode(threaded, "id")
        val expected = """
            |      "threads": [
            |        {
            |          "id": "q1",
            |          "kind": "question",
            |          "state": "answered",
            |          "messages": [
            |            {
            |              "author": "reader",
            |              "round": 1,
            |              "time": "2026-10-08T12:00:00Z",
            |              "text": "Why `retry`?"
            |            },
            |            {
            |              "author": "agent",
            |              "round": 1,
            |              "time": "2026-10-08T12:01:00Z",
            |              "outcome": "answered",
            |              "text": "The provider can time out."
            |            }
            |          ]
            |        }
            |      ]
        """.trimMargin()
        assertTrue(text, text.contains(expected))
        val file = Json.parseToJsonElement(text).jsonObject
        assertEquals(listOf("steps", "threads"), file.keys.toList().takeLast(2))
        assertEquals("Old step", file.getValue("threads").jsonArray.single().jsonObject.getValue("removedStep").jsonPrimitive.content)
        assertFalse(StoryFormat.encode(story, "id").contains("threads"))
    }

    @Test
    fun `round trips threads of every kind, state and outcome`() {
        for (kind in ThreadKind.entries) {
            for (state in ThreadState.entries) {
                for (outcome in ReplyOutcome.entries) {
                    val thread = question.copy(kind = kind, state = state, messages = question.messages.map { if (it.outcome == null) it else it.copy(outcome = outcome) })
                    val original = threaded.copy(steps = listOf(threaded.steps[0].copy(threads = listOf(thread)), threaded.steps[1]))
                    assertEquals(original, (StoryFormat.decode(StoryFormat.encode(original, "id")) as StoryFormat.Decoded.Valid).story)
                }
            }
        }
    }

    @Test
    fun `agents cannot set threads`() {
        val json = Json.parseToJsonElement(StoryFormat.encode(threaded, "id"))
        val checked = StoryValidator(null).validate(json) as ValidationResult.Valid
        assertEquals(StoryThreads.strip(checked.story), checked.story)
    }

    @Test
    fun `rejects malformed threads`() {
        val text = StoryFormat.encode(threaded, "id")
        val invalid = listOf(
            text.replace("\"kind\": \"question\"", "\"kind\": \"chat\""),
            text.replace("\"state\": \"answered\"", "\"state\": \"open\""),
            text.replace("\"author\": \"reader\"", "\"author\": \"bot\""),
            text.replace("\"outcome\": \"answered\",", ""),
            text.replace("\"round\": 1,", "\"round\": 0,"),
            text.replace("\"round\": 2,", "\"round\": \"2\","),
            text.replace("2026-10-08T12:00:00Z\",\n              \"text\": \"Why", "later\",\n              \"text\": \"Why"),
            text.replace("\"text\": \"What is missing?\"", "\"text\": \" \""),
            text.replace("\"id\": \"q2\"", "\"id\": \"q1\""),
            text.replace("\"id\": \"q2\",", ""),
            text.replace(Regex("\"messages\": \\[\\s*\\{\\s*\"author\": \"reader\",\\s*\"round\": 2,[^\\]]*]"), "\"messages\": []"),
            text.replace("\"removedStep\": \"Old step\"", "\"removedStep\": 3"),
            text.replaceFirst("\"threads\": [", "\"threads\": 7, \"x\": ["),
        )
        for (file in invalid) {
            assertTrue(file, file != text)
            assertTrue(file, StoryFormat.decode(file) is StoryFormat.Decoded.Invalid)
        }
    }

    @Test
    fun `thread IDs are unique in the whole story`() {
        val text = StoryFormat.encode(threaded, "id").replace("\"id\": \"q2\"", "\"id\": \"q1\"")
        val result = StoryValidator(null, readMetadata = true).validate(Json.parseToJsonElement(text)) as ValidationResult.Invalid
        assertEquals(listOf(ErrorCode.DUPLICATE_THREAD_ID.code), result.errors.map { it.code })
    }

    @Test
    fun `published schema describes threads and messages in write order`() {
        val schema = Json.parseToJsonElement(Files.readString(Path.of("schema/story-v1.schema.json"))).jsonObject
        val definitions = schema.getValue("\$defs").jsonObject
        val file = Json.parseToJsonElement(StoryFormat.encode(threaded, "id")).jsonObject
        val step = file.getValue("steps").jsonArray.first().jsonObject
        assertFields(schema, file)
        assertFields(definitions.getValue("step").jsonObject, step)
        val threads = step.getValue("threads").jsonArray.map { it.jsonObject } + file.getValue("threads").jsonArray.map { it.jsonObject }
        threads.forEach { assertFields(definitions.getValue("thread").jsonObject, it) }
        threads.flatMap { it.getValue("messages").jsonArray }.forEach { assertFields(definitions.getValue("message").jsonObject, it.jsonObject) }
        val thread = definitions.getValue("thread").jsonObject.getValue("properties").jsonObject
        assertEquals(ThreadKind.entries.map { it.value }, thread.getValue("kind").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ThreadState.entries.map { it.value }, thread.getValue("state").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        val message = definitions.getValue("message").jsonObject.getValue("properties").jsonObject
        assertEquals(MessageAuthor.entries.map { it.value }, message.getValue("author").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ReplyOutcome.entries.map { it.value }, message.getValue("outcome").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
    }

    private fun assertFields(schema: JsonObject, written: JsonObject) {
        val properties = schema.getValue("properties").jsonObject.keys.toList()
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(written.keys.toList(), properties.filter { it in written })
        assertTrue(required.all { it in written })
    }
}
