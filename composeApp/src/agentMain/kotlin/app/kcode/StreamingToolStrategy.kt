package app.kcode

import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.ToolResultKind
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKoogJSONObject
import app.kcode.settings.ToolPermissionMode
import kotlinx.coroutines.flow.collect

internal class StreamingToolStrategy(
    private val tools: ToolRegistry,
    private val permissionModeProvider: suspend () -> ToolPermissionMode,
    private val approver: ToolCallApprover,
    private val onToolUse: suspend (ToolUseEvent) -> Unit,
    private val onDelta: suspend (String) -> Unit,
) {
    fun create() = functionalStrategy<String, String>("streaming_single_run") { input ->
        val visibleText = StringBuilder()
        var pendingInput: String? = input
        var pendingToolResults: List<ReceivedToolResult>? = null
        var toolRound = 0

        while (true) {
            val currentInput = pendingInput
            val stream = if (currentInput != null) {
                requestLLMStreaming(currentInput).also { pendingInput = null }
            } else {
                val results = checkNotNull(pendingToolResults)
                appendPrompt {
                    user { results.forEach { toolResult(it.toMessagePart()) } }
                }
                llm.writeSession { requestLLMStreaming() }
            }

            val frames = mutableListOf<StreamFrame>()
            var receivedDelta = false
            stream.collect { frame ->
                frames += frame
                when (frame) {
                    is StreamFrame.TextDelta -> {
                        receivedDelta = true
                        visibleText.append(frame.text)
                        onDelta(frame.text)
                    }

                    is StreamFrame.TextComplete -> if (!receivedDelta && frame.text.isNotEmpty()) {
                        visibleText.append(frame.text)
                        onDelta(frame.text)
                    }

                    else -> Unit
                }
            }

            val response = frames.toMessageResponse()
            appendPrompt { message(response) }
            val calls = response.parts
                .filterIsInstance<MessagePart.Tool.Call>()
                .map(::normalizeArguments)
            if (calls.isEmpty()) {
                return@functionalStrategy visibleText.toString().ifBlank {
                    response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
                }
            }

            val eventIds = calls.mapIndexed { index, call ->
                call.id ?: "${call.tool}:${toolRound}:$index"
            }
            calls.forEachIndexed { index, call ->
                onToolUse(
                    ToolUseEvent.Started(
                        id = eventIds[index],
                        name = call.tool,
                        input = call.args.limitToolText(4_096),
                    ),
                )
            }

            pendingToolResults = try {
                calls.map { call ->
                    val descriptor = tools.getToolOrNull(call.tool)?.descriptor
                    val approved = authorizeToolCall(
                        mode = permissionModeProvider(),
                        request = ToolApprovalRequest(
                            name = call.tool,
                            input = call.args.limitToolText(4_096),
                            description = descriptor?.description.orEmpty(),
                        ),
                        approver = approver,
                    )
                    if (approved) executeTool(call) else deniedToolResult(call)
                }
                    .also { results -> reportResults(results, eventIds, toolRound) }
            } catch (error: Throwable) {
                eventIds.forEach { id ->
                    onToolUse(
                        ToolUseEvent.Finished(
                            id = id,
                            output = error.message.orEmpty().limitToolText(16_384),
                            isError = true,
                        ),
                    )
                }
                throw error
            }
            toolRound++
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable tool loop exit")
    }

    private fun normalizeArguments(call: MessagePart.Tool.Call): MessagePart.Tool.Call {
        val descriptor = tools.getToolOrNull(call.tool)?.descriptor ?: return call
        val requiredNames = descriptor.requiredParameters.mapTo(mutableSetOf()) { it.name }
        val parameterNames = descriptor.optionalParameters
            .mapTo(requiredNames.toMutableSet()) { it.name }
        return call.copy(
            args = normalizeToolArguments(
                raw = call.args,
                parameterNames = parameterNames,
                requiredParameterNames = requiredNames,
            ),
        )
    }

    private suspend fun reportResults(
        results: List<ReceivedToolResult>,
        eventIds: List<String>,
        toolRound: Int,
    ) {
        results.forEachIndexed { index, result ->
            onToolUse(
                ToolUseEvent.Finished(
                    id = result.id ?: eventIds.getOrElse(index) {
                        "${result.tool}:${toolRound}:$index"
                    },
                    output = result.output.limitToolText(16_384),
                    isError = result.toMessagePart().isError,
                ),
            )
        }
    }

    private fun deniedToolResult(call: MessagePart.Tool.Call): ReceivedToolResult = ReceivedToolResult(
        id = call.id,
        tool = call.tool,
        toolArgs = runCatching { call.argsJson.toKoogJSONObject() }.getOrElse { JSONObject(emptyMap()) },
        toolDescription = null,
        output = "Tool call denied by the user's permission policy and was not executed.",
        resultKind = ToolResultKind.Failure(null),
        result = null,
    )
}

private fun String.limitToolText(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength) + "\n… (truncated)"
