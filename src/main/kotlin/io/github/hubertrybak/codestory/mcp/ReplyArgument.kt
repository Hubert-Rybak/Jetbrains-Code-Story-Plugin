package io.github.hubertrybak.codestory.mcp

import com.intellij.mcpserver.annotations.McpDescription
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder

/**
 * One item of the `replies` parameter of `code_story_reply`. Like [StoryArgument], the tool schema
 * shows the typed reply ([ReplyShape]), and the story core checks the JSON as it is, so that each
 * problem names its reply and gives a fix hint.
 */
@Serializable(with = ReplyArgumentSerializer::class)
class ReplyArgument(val json: JsonElement)

internal object ReplyArgumentSerializer : KSerializer<ReplyArgument> {
    override val descriptor: SerialDescriptor = ReplyShape.serializer().descriptor

    override fun deserialize(decoder: Decoder): ReplyArgument {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Code Story reads replies only from JSON.")
        return ReplyArgument(json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: ReplyArgument) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Code Story writes replies only as JSON.")
        json.encodeJsonElement(value.json)
    }
}

@Serializable
internal class ReplyShape(
    @property:McpDescription("The threadId of the comment, from code_story_feedback.")
    val threadId: String,
    @property:McpDescription("The round of the comment, from code_story_feedback.")
    val round: Int,
    @property:McpDescription("answered for a question; done for a change or refresh request after all affected steps are updated or confirmed; declined with a reason.")
    val outcome: ReplyOutcomeShape,
    @property:McpDescription("Your answer: 1 to 5 sentences, or one lead sentence and up to 6 bullet items; 100 words at most. A reason to decline has 1 to 3 sentences.")
    val text: String,
    @property:McpDescription("Required for done: changed code files as repository-relative paths. Use [] for a story-only refresh. Summarize the changes in text.")
    val changedFiles: List<String>? = null,
)

@Serializable
internal enum class ReplyOutcomeShape {
    @SerialName("answered") ANSWERED,
    @SerialName("done") DONE,
    @SerialName("declined") DECLINED,
}
