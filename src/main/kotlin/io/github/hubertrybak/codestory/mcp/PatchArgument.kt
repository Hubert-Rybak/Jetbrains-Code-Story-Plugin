package io.github.hubertrybak.codestory.mcp

import com.intellij.mcpserver.annotations.McpDescription
import io.github.hubertrybak.codestory.core.StepKind
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

/** Typed MCP schema, with validation errors returned through the same report as code_story_show. */
@Serializable(with = PatchArgumentSerializer::class)
class PatchArgument(val json: JsonElement)

internal object PatchArgumentSerializer : KSerializer<PatchArgument> {
    override val descriptor: SerialDescriptor = PatchShape.serializer().descriptor
    override fun deserialize(decoder: Decoder): PatchArgument = PatchArgument(
        (decoder as? JsonDecoder ?: throw SerializationException("Code Story reads patches only from JSON.")).decodeJsonElement(),
    )
    override fun serialize(encoder: Encoder, value: PatchArgument) {
        (encoder as? JsonEncoder ?: throw SerializationException("Code Story writes patches only as JSON.")).encodeJsonElement(value.json)
    }
}

@Serializable
internal enum class PatchOperation {
    @SerialName("update") UPDATE,
    @SerialName("insert") INSERT,
    @SerialName("remove") REMOVE,
    @SerialName("confirm") CONFIRM,
}

@Serializable
internal class PatchShape(
    val op: PatchOperation,
    @property:McpDescription("For update, remove or confirm: the stable ID of the step from code_story_get.")
    val stepId: String? = null,
    @property:McpDescription("For insert: put the new step immediately after this step ID.")
    val afterStepId: String? = null,
    @property:McpDescription("For insert: the complete new step. Its ID can be omitted.")
    val step: StepShape? = null,
    @property:McpDescription("For update: replace the title. Omit to keep it.")
    val title: String? = null,
    @property:McpDescription("For update: replace the comment. Omit to keep it.")
    val comment: String? = null,
    @property:McpDescription("For update: replace the location, including anchorText. Omit to keep it; null makes the step text-only.")
    val location: LocationShape? = null,
    @property:McpDescription("For update: replace the kind: explanation, context or proposal. Omit to keep it. A step that is no longer a proposal also needs proposedCode null.")
    val kind: StepKind? = null,
    @property:McpDescription("For update: replace the code of a proposal. Omit to keep it; null removes it.")
    val proposedCode: String? = null,
)
