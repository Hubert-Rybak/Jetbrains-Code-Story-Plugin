package io.github.hubertrybak.codestory.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** Applies operations in order. The caller validates and saves the complete result atomically. */
object StoryPatch {
    class Result(val story: JsonObject, val errors: List<ReportItem>, val updatedIds: Set<String>)

    fun apply(story: Story, storyId: String, operations: List<JsonElement>): Result {
        val json = Json.parseToJsonElement(StoryFormat.encode(story, storyId)).jsonObject
        val steps = (json.getValue("steps") as JsonArray).map { it.jsonObject }.toMutableList()
        val errors = mutableListOf<ReportItem>()
        val updatedIds = mutableSetOf<String>()
        if (operations.isEmpty()) errors += ReportItem(null, ErrorCode.MISSING_FIELD, "The patch has no operations.", "Send at least one update, insert or remove operation.")
        operations.forEachIndexed { index, element ->
            fun error(code: ErrorCode, message: String, step: Int? = null) {
                errors += ReportItem(step, code, "Operation ${index + 1}: $message", "Read code_story_get for step IDs, then retry code_story_patch.")
            }
            val operation = element as? JsonObject
            if (operation == null) {
                error(ErrorCode.WRONG_TYPE, "Each operation must be an object.")
                return@forEachIndexed
            }
            fun text(name: String) = (operation[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            val op = text("op")
            val fields = when (op) {
                "update" -> setOf("op", "stepId", "title", "comment", "location")
                "insert" -> setOf("op", "afterStepId", "step")
                "remove" -> setOf("op", "stepId")
                else -> {
                    error(ErrorCode.INVALID_VALUE, "Use op update, insert or remove.")
                    return@forEachIndexed
                }
            }
            if (operation.keys.any { it !in fields }) {
                error(ErrorCode.INVALID_VALUE, "Unknown fields for $op: ${(operation.keys - fields).joinToString()}.")
                return@forEachIndexed
            }
            val id = text(if (op == "insert") "afterStepId" else "stepId")
            val target = steps.indexOfFirst { (it["id"] as? JsonPrimitive)?.content?.trim() == id }
            if (id == null || target < 0) {
                error(ErrorCode.STEP_NOT_FOUND, "The target step ${id?.let { "`$it`" } ?: "ID"} was not found.")
                return@forEachIndexed
            }
            when (op) {
                "remove" -> steps.removeAt(target)
                "update" -> {
                    val changes = operation.filterKeys { it in setOf("title", "comment", "location") }
                    if (changes.isEmpty()) error(ErrorCode.MISSING_FIELD, "An update needs title, comment or location.", target + 1)
                    else {
                        steps[target] = JsonObject(steps[target] + changes)
                        updatedIds += id
                    }
                }
                "insert" -> {
                    val step = operation["step"] as? JsonObject
                    if (step == null) error(ErrorCode.WRONG_TYPE, "An insert needs a step object.")
                    else {
                        val assigned = if (step["id"] == null || step["id"] == JsonNull) {
                            val newId = generateSequence { UUID.randomUUID().toString() }.first { candidate -> steps.none { it["id"] == JsonPrimitive(candidate) } }
                            JsonObject(step + ("id" to JsonPrimitive(newId)))
                        } else step
                        steps.add(target + 1, assigned)
                    }
                }
            }
        }
        return Result(JsonObject(json + ("steps" to JsonArray(steps))), errors, updatedIds)
    }
}
