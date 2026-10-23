package io.github.hubertrybak.codestory.mcp

import com.intellij.mcpserver.annotations.McpDescription
import io.github.hubertrybak.codestory.core.StepKind
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
    @property:McpDescription("Only for a story about a change: the SHA of the commit before the change, or HEAD for uncommitted work. Omit it for other stories.")
    val baseRef: String? = null,
    @property:McpDescription("3 to 12 steps (15 at most), in reading order. Each step explains one idea.")
    val steps: List<StepShape>,
)

@Serializable
internal class StepShape(
    @property:McpDescription("A stable, unique step ID. Omit it to let the plugin assign one; keep it on revisions.")
    val id: String? = null,
    @property:McpDescription("Omit it for an explanation of code, an overview or a what-to-check step. Use context for background that the step before it needs, such as a method that it calls or a setting that it reads. Use proposal for a change that you suggest but did not make.")
    val kind: StepKind? = null,
    @property:McpDescription("The step title: 8 words or fewer.")
    val title: String,
    @property:McpDescription("What the code does and why it matters: 1 to 3 sentences, or one lead sentence and up to 4 bullet items; 60 words at most. Instructions have at most 20 words per sentence; descriptions have at most 25.")
    val comment: String,
    @property:McpDescription("One code range, usually 30 lines or fewer. Omit it for an overview or a what-to-check step. For a proposal, the lines that the proposal changes.")
    val location: LocationShape? = null,
    @property:McpDescription("Only for a proposal: the code that replaces the lines of location, with \\n between lines and the indentation of the file. Use an empty text to propose removing the lines. Without a location, it is new code. Omit it for a proposal in words only.")
    val proposedCode: String? = null,
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
    @property:McpDescription("The exact text of line startLine, copied from the file. Indentation and trailing spaces do not matter. The IDE uses it to check the lines and to find the code again after it moves.")
    val anchorText: String,
)
