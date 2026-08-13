package ai.meteor.kcode

import ai.meteor.kcode.chat.ToolUseEvent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.ToolResultKind
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKoogJSONObject
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.tools.permission.authorizeToolCall

internal class StreamingToolStrategy(
    private val tools: ToolRegistry,
    private val model: LLModel,
    private val permissionModeProvider: suspend () -> ToolPermissionMode,
    private val approver: ToolCallApprover,
    private val onToolUse: suspend (ToolUseEvent) -> Unit,
    private val onDelta: suspend (String) -> Unit,
    private val additionalContextProvider: suspend () -> String = { "" },
    private val continuationAfterResponse: suspend () -> String? = { null },
    private val onUsage: suspend (Int) -> Unit = {},
) {
    fun create() = functionalStrategy<String, String>("streaming_single_run") { input ->
        val visibleText = StringBuilder()
        var pendingInput: String? = input
        var pendingToolResults: List<ReceivedToolResult>? = null
        var toolRound = 0

        while (true) {
            val currentInput = pendingInput
            val stream = if (currentInput != null) {
                val additionalContext = additionalContextProvider()
                requestLLMStreaming(
                    if (additionalContext.isBlank()) currentInput else "$currentInput\n\n$additionalContext",
                ).also { pendingInput = null }
            } else {
                val results = checkNotNull(pendingToolResults)
                val additionalContext = additionalContextProvider()
                appendPrompt {
                    user {
                        results.forEach { result ->
                            val (toolResultPart, mediaAttachments) = portableMediaResult(result)
                            toolResult(toolResultPart)
                            mediaAttachments.forEach { media ->
                                attachment(media.source, media.cacheControl)
                            }
                        }
                        if (additionalContext.isNotBlank()) text(additionalContext)
                    }
                }
                llm.writeSession { requestLLMStreaming() }
            }

            val frames = mutableListOf<StreamFrame>()
            var receivedDelta = false
            val toolCallTracker = StreamingToolCallTracker(toolRound)
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

                    is StreamFrame.ToolCallDelta,
                    is StreamFrame.ToolCallComplete,
                    -> toolCallTracker.onFrame(frame)?.let { event -> onToolUse(event) }

                    is StreamFrame.End -> frame.metaInfo.totalTokensCount?.let { onUsage(it) }

                    else -> Unit
                }
            }

            val response = frames.toMessageResponse()
            appendPrompt { message(response) }
            val calls = response.parts
                .filterIsInstance<MessagePart.Tool.Call>()
            if (calls.isEmpty()) {
                val continuation = continuationAfterResponse()
                if (!continuation.isNullOrBlank()) {
                    pendingInput = continuation
                    continue
                }
                return@functionalStrategy visibleText.toString().ifBlank {
                    response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
                }
            }

            val eventIds = calls.mapIndexed { index, call ->
                val finalized = toolCallTracker.finalize(index, call)
                finalized.event?.let { event -> onToolUse(event) }
                finalized.id
            }

            pendingToolResults = try {
                calls.map { call ->
                    val descriptor = tools.getToolOrNull(call.tool)?.descriptor
                    val approved = call.tool in InternalToolNames || authorizeToolCall(
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
                            output = error.message.orEmpty(),
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

    private suspend fun reportResults(
        results: List<ReceivedToolResult>,
        eventIds: List<String>,
        toolRound: Int,
    ) {
        results.forEachIndexed { index, result ->
            onToolUse(
                ToolUseEvent.Finished(
                    id = eventIds.getOrElse(index) {
                        "${result.tool}:${toolRound}:$index"
                    },
                    output = result.output,
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

    private fun portableMediaResult(
        result: ReceivedToolResult,
    ): Pair<MessagePart.Tool.Result, List<MessagePart.Attachment>> {
        val toolResult = result.toMessagePart()
        val media = toolResult.parts.filterIsInstance<MessagePart.Attachment>().filter {
            it.source is AttachmentSource.Image || it.source is AttachmentSource.Video
        }
        if (media.isEmpty()) return toolResult to emptyList()

        val (supported, unsupported) = media.partition { attachment ->
            when (attachment.source) {
                is AttachmentSource.Image -> model.supports(LLMCapability.Vision.Image)
                is AttachmentSource.Video ->
                    model.provider in setOf(LLMProvider.Google, LLMProvider.Alibaba) &&
                        model.supports(LLMCapability.Vision.Video)
                else -> false
            }
        }
        val textParts = toolResult.parts.filterNot { it in media }.toMutableList()
        unsupported.forEach { attachment ->
            val kind = if (attachment.source is AttachmentSource.Video) "video" else "image"
            textParts += MessagePart.Text(
                "The $kind attachment was not sent because model '${model.id}' through provider " +
                    "'${model.provider}' does not support this media input path.",
            )
        }
        val providerAttachments = supported.map { attachment ->
            val video = attachment.source as? AttachmentSource.Video
            if (model.provider == LLMProvider.Alibaba && video != null) {
                attachment.copy(
                    source = AttachmentSource.Image(
                        content = video.content,
                        format = video.format,
                        mimeType = video.mimeType,
                        fileName = video.fileName,
                    ),
                )
            } else {
                attachment
            }
        }
        return toolResult.copy(parts = textParts) to providerAttachments
    }
}

internal class StreamingToolCallTracker(
    private val toolRound: Int,
) {
    private val calls = mutableListOf<StreamingToolCall>()
    private val callsByIndex = mutableMapOf<Int, StreamingToolCall>()
    private val callsById = mutableMapOf<String, StreamingToolCall>()

    fun onFrame(frame: StreamFrame): ToolUseEvent? = when (frame) {
        is StreamFrame.ToolCallDelta -> update(
            index = frame.index,
            id = frame.id,
            name = frame.name,
            input = frame.content,
            inputIsComplete = false,
        )

        is StreamFrame.ToolCallComplete -> update(
            index = frame.index,
            id = frame.id,
            name = frame.name,
            input = frame.content,
            inputIsComplete = true,
        )

        else -> null
    }

    fun finalize(index: Int, call: MessagePart.Tool.Call): FinalizedToolCall {
        val state = callsByIndex[index]
            ?: call.id?.let(callsById::get)
            ?: calls.getOrNull(index)
            ?: newCall()
        state.id = state.id ?: call.id
        state.name = state.name ?: call.tool
        state.input = call.args.trackedToolInput()
        val event = state.nextEvent(index, allowFallbackId = true)
        return FinalizedToolCall(
            id = state.eventId ?: call.id ?: "${call.tool}:$toolRound:$index",
            event = event,
        )
    }

    private fun update(
        index: Int?,
        id: String?,
        name: String?,
        input: String?,
        inputIsComplete: Boolean,
    ): ToolUseEvent? {
        val normalizedId = id?.takeIf(String::isNotBlank)
        val state = index?.let(callsByIndex::get)
            ?: normalizedId?.let(callsById::get)
            ?: (if (index == null && normalizedId == null) calls.lastOrNull() else null)
            ?: newCall()
        index?.let { callsByIndex[it] = state }
        normalizedId?.let { callsById[it] = state }
        state.id = state.id ?: normalizedId
        state.name = state.name ?: name?.takeIf(String::isNotBlank)
        if (input != null) {
            state.input = if (inputIsComplete) {
                input.trackedToolInput()
            } else {
                (state.input + input).trackedToolInput()
            }
        }
        return state.nextEvent(index ?: calls.indexOf(state))
    }

    private fun newCall(): StreamingToolCall = StreamingToolCall().also(calls::add)

    private fun StreamingToolCall.nextEvent(
        index: Int,
        allowFallbackId: Boolean = false,
    ): ToolUseEvent? {
        val toolName = name ?: return null
        val stableId = eventId ?: id ?: if (allowFallbackId || input.isNotEmpty()) {
            "$toolName:$toolRound:$index"
        } else {
            return null
        }
        eventId = stableId
        val visibleInput = input.limitToolText(4_096)
        return if (!started) {
            started = true
            lastEmittedInput = visibleInput
            ToolUseEvent.Started(stableId, toolName, visibleInput)
        } else if (visibleInput != lastEmittedInput) {
            lastEmittedInput = visibleInput
            ToolUseEvent.Updated(stableId, visibleInput)
        } else {
            null
        }
    }

    internal data class FinalizedToolCall(
        val id: String,
        val event: ToolUseEvent?,
    )

    private data class StreamingToolCall(
        var id: String? = null,
        var eventId: String? = null,
        var name: String? = null,
        var input: String = "",
        var lastEmittedInput: String = "",
        var started: Boolean = false,
    )
}

private fun String.trackedToolInput(): String = take(4_097)

private fun String.limitToolText(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength) + "\n… (truncated)"

private val InternalToolNames = setOf(
    "spawn_agent",
    "send_message",
    "followup_task",
    "interrupt_agent",
    "list_agents",
    "wait_agent",
    "get_goal",
    "create_goal",
    "update_goal",
    "schedule_task",
    "complete_scheduled_task",
)
