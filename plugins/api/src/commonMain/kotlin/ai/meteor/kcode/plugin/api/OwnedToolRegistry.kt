package ai.meteor.kcode.plugin.api

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer

/** Preserve the backend's schema, metadata and codecs while revoking every published tool handle. */
internal fun ownTools(registry: ToolRegistry, owner: PluginOperationOwner): ToolRegistry =
    ToolRegistry { registry.tools.forEach { tool(OwnedTool(it, owner)) } }

private class OwnedTool(
    delegate: ToolBase<*, *>,
    private val owner: PluginOperationOwner,
) : ToolBase<Any?, Any?>(
    argsType = delegate.argsType,
    resultType = delegate.resultType,
    descriptor = delegate.descriptor,
    metadata = delegate.metadata.toMap(),
) {
    @Suppress("UNCHECKED_CAST")
    private val delegate = delegate as ToolBase<Any?, Any?>

    override suspend fun execute(args: Any?, metadata: ToolCallMetadata): Any? = owner.run {
        delegate.execute(args, metadata)
    }

    private fun <T> codec(block: () -> T): T {
        owner.requireOpen()
        return block()
    }

    override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer) = codec { delegate.decodeArgs(rawArgs, serializer) }
    override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer) = codec { delegate.decodeResult(rawResult, serializer) }
    override fun encodeArgs(args: Any?, serializer: JSONSerializer) = codec { delegate.encodeArgs(args, serializer) }
    override fun encodeResult(result: Any?, serializer: JSONSerializer) = codec { delegate.encodeResult(result, serializer) }
    override fun encodeResultToString(result: Any?, serializer: JSONSerializer) = codec { delegate.encodeResultToString(result, serializer) }
    override fun encodeResultToParts(result: Any?, serializer: JSONSerializer) = codec { delegate.encodeResultToParts(result, serializer) }
}
