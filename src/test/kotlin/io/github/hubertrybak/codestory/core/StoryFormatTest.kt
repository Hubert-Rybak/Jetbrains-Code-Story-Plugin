package io.github.hubertrybak.codestory.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class StoryFormatTest {
    private val story = Story(
        "Explain the payment flow",
        listOf(
            Step("Entry point", "The `PaymentController` takes the request.", Location("src/Payment.kt", 12, 30)),
            Step("Charge \"card\"", "Line one.\n- item", Location("src/Card.kt", 5, 5)),
        ),
    )

    @Test
    fun `writes schema version 1, pretty-printed, with a fixed field order`() {
        val expected = """
            {
              "schemaVersion": 1,
              "id": "2026-10-05-explain-the-payment-flow",
              "title": "Explain the payment flow",
              "steps": [
                {
                  "title": "Entry point",
                  "comment": "The `PaymentController` takes the request.",
                  "location": {
                    "file": "src/Payment.kt",
                    "startLine": 12,
                    "endLine": 30
                  }
                },
                {
                  "title": "Charge \"card\"",
                  "comment": "Line one.\n- item",
                  "location": {
                    "file": "src/Card.kt",
                    "startLine": 5,
                    "endLine": 5
                  }
                }
              ]
            }

        """.trimIndent()
        assertEquals(expected, StoryFormat.encode(story, "2026-10-05-explain-the-payment-flow"))
    }

    @Test
    fun `reads what it writes`() {
        val decoded = StoryFormat.decode(StoryFormat.encode(story, "id"))
        assertEquals(story, (decoded as StoryFormat.Decoded.Valid).story)
    }

    @Test
    fun `rejects other schema versions and broken files`() {
        val text = StoryFormat.encode(story, "id")
        assertTrue(StoryFormat.decode(text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) is StoryFormat.Decoded.Invalid)
        assertTrue(StoryFormat.decode(text.replace("\"schemaVersion\": 1,", "")) is StoryFormat.Decoded.Invalid)
        assertTrue(StoryFormat.decode(text.replace("\"title\": \"Entry point\",", "")) is StoryFormat.Decoded.Invalid)
        assertTrue(StoryFormat.decode("[1, 2]") is StoryFormat.Decoded.Invalid)
        assertTrue(StoryFormat.decode("{ broken") is StoryFormat.Decoded.Invalid)
    }

    @Test
    fun `published JSON Schema matches the written fields`() {
        val schema = Json.parseToJsonElement(Files.readString(Path.of("schema/story-v1.schema.json"))).jsonObject
        val definitions = schema.getValue("\$defs").jsonObject
        val file = Json.parseToJsonElement(StoryFormat.encode(story, "2026-10-05-explain-the-payment-flow")).jsonObject
        val step = file.getValue("steps").jsonArray.first().jsonObject

        assertFields(schema, file)
        assertFields(definitions.getValue("step").jsonObject, step)
        assertFields(definitions.getValue("location").jsonObject, step.getValue("location").jsonObject)
        val properties = schema.getValue("properties").jsonObject
        assertEquals(StoryFormat.SCHEMA_VERSION, properties.getValue("schemaVersion").jsonObject.getValue("const").jsonPrimitive.int)
        assertEquals(StoryLimits.MAX_STEPS, properties.getValue("steps").jsonObject.getValue("maxItems").jsonPrimitive.int)
    }

    /** The schema lists the same fields in the same order as the file, and all of them are required. */
    private fun assertFields(schema: JsonObject, written: JsonObject) {
        val properties = schema.getValue("properties").jsonObject.keys.toList()
        val required = (schema.getValue("required") as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(written.keys.toList(), properties)
        assertEquals(written.keys.toList(), required)
    }
}
