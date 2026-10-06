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
            Step("Entry point", "The `PaymentController` takes the request.", Location("src/Payment.kt", 12, 30), "entry"),
            Step("What to check", "Line one.\n- item", id = "checks"),
        ),
        createdAt = Instant.parse("2026-10-05T12:34:56.123Z"),
        git = StoryGit("feature/payments", "0123456789abcdef"),
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
              "steps": [
                {
                  "id": "entry",
                  "title": "Entry point",
                  "comment": "The `PaymentController` takes the request.",
                  "location": {
                    "file": "src/Payment.kt",
                    "startLine": 12,
                    "endLine": 30
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
                val original = story.copy(type = type, git = git)
                assertEquals(original, (StoryFormat.decode(StoryFormat.encode(original, "id")) as StoryFormat.Decoded.Valid).story)
            }
        }
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

    private fun assertFields(schema: JsonObject, written: JsonObject) {
        val properties = schema.getValue("properties").jsonObject.keys.toList()
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(written.keys.toList(), properties.filter { it in written })
        assertTrue(required.all { it in written })
    }
}
