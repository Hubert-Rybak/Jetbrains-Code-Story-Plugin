package io.github.hubertrybak.codestory.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The story file format: `schema/story-v1.schema.json` in the repository. */
object StoryFormat {
    const val SCHEMA_VERSION = 1
    const val FILE_SUFFIX = ".story.json"
    const val MCP_DELIVERY = "mcp"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Pretty-printed JSON with a fixed field order, so that diffs stay readable. */
    fun encode(story: Story, id: String): String {
        val file = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("id", id)
            put("title", story.title)
            put("type", story.type.value)
            put("summary", story.summary)
            put("request", story.request)
            putJsonObject("author") {
                put("agent", story.author.agent)
                put("model", story.author.model)
            }
            put("createdAt", checkNotNull(story.createdAt).toString())
            putJsonObject("git") {
                put("branch", story.git.branch?.let(::JsonPrimitive) ?: JsonNull)
                put("commit", story.git.commit?.let(::JsonPrimitive) ?: JsonNull)
            }
            story.baseRef?.let { put("baseRef", it) }
            story.delivery?.let { put("delivery", it) }
            putJsonArray("steps") {
                for (step in story.steps) {
                    addJsonObject {
                        put("id", checkNotNull(step.id))
                        // Files from older versions have no kinds, so an explanation has none either.
                        if (step.kind != StepKind.EXPLANATION) put("kind", step.kind.value)
                        put("title", step.title)
                        put("comment", step.comment)
                        step.location?.let { location ->
                            putJsonObject("location") {
                                put("file", location.file)
                                put("startLine", location.startLine)
                                put("endLine", location.endLine)
                                location.anchorText?.let { put("anchorText", it) }
                                location.rangeText?.let { put("rangeText", it) }
                            }
                        }
                        step.proposedCode?.let { put("proposedCode", it) }
                        threads(step.threads)
                    }
                }
            }
            threads(story.threads)
        }
        return json.encodeToString(JsonElement.serializer(), file) + "\n"
    }

    /** Stories without comments have no `threads` fields. */
    private fun JsonObjectBuilder.threads(threads: List<StoryThread>) {
        if (threads.isEmpty()) return
        putJsonArray("threads") {
            for (thread in threads) {
                addJsonObject {
                    put("id", thread.id)
                    put("kind", thread.kind.value)
                    put("state", thread.state.value)
                    thread.removedStep?.let { put("removedStep", it) }
                    thread.storyBaseline?.let { put("storyBaseline", Json.encodeToJsonElement(it)) }
                    thread.pendingStoryChanges?.let { put("pendingStoryChanges", it) }
                    thread.previousKind?.let { put("previousKind", it.value) }
                    if (thread.stepsToSync.isNotEmpty()) put("stepsToSync", Json.encodeToJsonElement(thread.stepsToSync))
                    if (thread.confirmedSteps.isNotEmpty()) put("confirmedSteps", Json.encodeToJsonElement(thread.confirmedSteps))
                    putJsonArray("messages") {
                        for (message in thread.messages) {
                            addJsonObject {
                                put("author", message.author.value)
                                put("round", message.round)
                                put("time", message.time.toString())
                                message.outcome?.let { put("outcome", it.value) }
                                message.storyChanges?.let { put("storyChanges", it) }
                                if (message.changedFiles.isNotEmpty() || message.outcome == ReplyOutcome.DONE) put("changedFiles", Json.encodeToJsonElement(message.changedFiles))
                                put("text", message.text)
                            }
                        }
                    }
                }
            }
        }
    }

    sealed interface Decoded {
        class Valid(val story: Story) : Decoded
        class Invalid(val reason: String) : Decoded
    }

    /** Reads a story file. Story files can come from other people, so the structure is checked again. */
    fun decode(text: String): Decoded {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return Decoded.Invalid("The file is not valid JSON: ${e.message}")
        }
        val version = ((element as? JsonObject)?.get("schemaVersion") as? JsonPrimitive)?.intOrNull
        if (version != SCHEMA_VERSION) return Decoded.Invalid("Unsupported schemaVersion: $version")
        return when (val result = StoryValidator(files = null, readMetadata = true).validate(element)) {
            is ValidationResult.Valid -> Decoded.Valid(result.story)
            is ValidationResult.Invalid -> Decoded.Invalid(result.errors.joinToString(" ") { it.message })
        }
    }
}
