package io.github.hubertrybak.codestory.mcp

import io.github.hubertrybak.codestory.core.StoryType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The saved JSON with an explicit object schema, as required by the IDE's MCP reflection API. */
@Serializable(with = SavedStoryResultSerializer::class)
class SavedStoryResult(val json: JsonObject)

internal object SavedStoryResultSerializer : KSerializer<SavedStoryResult> {
    override val descriptor: SerialDescriptor = SavedStoryShape.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SavedStoryResult) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Code Story writes stories only as JSON.")
        json.encodeJsonElement(value.json)
    }

    override fun deserialize(decoder: Decoder): SavedStoryResult {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Code Story reads stories only from JSON.")
        return SavedStoryResult(json.decodeJsonElement().jsonObject)
    }
}

@Serializable
internal class SavedStoryShape(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val type: StoryType,
    val summary: String,
    val request: String,
    val author: AuthorShape,
    val createdAt: String,
    val git: GitShape,
    val steps: List<SavedStepShape>,
)

@Serializable
internal class GitShape(val branch: String?, val commit: String?)

@Serializable
internal class SavedStepShape(val id: String, val title: String, val comment: String, val location: LocationShape? = null)
