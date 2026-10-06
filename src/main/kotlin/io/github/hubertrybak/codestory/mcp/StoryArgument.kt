package io.github.hubertrybak.codestory.mcp

import com.intellij.mcpserver.annotations.McpDescription
import io.github.hubertrybak.codestory.core.StoryType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder

/**
 * The `story` parameter of `code_story_show`. The tool schema shows the typed story structure
 * ([StoryShape]), but the plugin takes the JSON as it is, so that the story core reports
 * structure problems with step numbers and fix hints instead of a deserialization failure.
 */
@Serializable(with = StoryArgumentSerializer::class)
class StoryArgument(val json: JsonElement)

internal object StoryArgumentSerializer : KSerializer<StoryArgument> {
    override val descriptor: SerialDescriptor = StoryShape.serializer().descriptor

    override fun deserialize(decoder: Decoder): StoryArgument {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Code Story reads stories only from JSON.")
        return StoryArgument(json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: StoryArgument) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Code Story writes stories only as JSON.")
        json.encodeJsonElement(value.json)
    }
}

@Serializable
internal class StoryShape(
    @property:McpDescription("The story title: 8 words or fewer.")
    val title: String,
    @property:McpDescription("The story type: change, exploration, bug-trace or review.")
    val type: StoryType,
    @property:McpDescription("A summary in 1 to 3 short sentences.")
    val summary: String,
    @property:McpDescription("The original Code Story request text.")
    val request: String,
    @property:McpDescription("The agent and model that wrote the story.")
    val author: AuthorShape,
    @property:McpDescription("3 to 12 steps (15 at most), in reading order. Each step explains one idea.")
    val steps: List<StepShape>,
)

@Serializable
internal class StepShape(
    @property:McpDescription("A stable, unique step ID. Omit it to let the plugin assign one; keep it on revisions.")
    val id: String? = null,
    @property:McpDescription("The step title: 8 words or fewer.")
    val title: String,
    @property:McpDescription("What the code does and why it matters: 1 to 3 short sentences in simple English.")
    val comment: String,
    @property:McpDescription("One code range, usually 30 lines or fewer. Omit it for an overview or a what-to-check step.")
    val location: LocationShape? = null,
)

@Serializable
internal class AuthorShape(
    @property:McpDescription("Your agent name.")
    val agent: String,
    @property:McpDescription("Your model name.")
    val model: String,
)

@Serializable
internal class LocationShape(
    @property:McpDescription("The file path relative to the Git repository root, with / between folders.")
    val file: String,
    @property:McpDescription("The first line of the range. The first line of the file is 1.")
    val startLine: Int,
    @property:McpDescription("The last line of the range. The range includes this line.")
    val endLine: Int,
)
