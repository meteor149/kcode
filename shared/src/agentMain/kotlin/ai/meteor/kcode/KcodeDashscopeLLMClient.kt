package ai.meteor.kcode

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.dashscope.DashscopeClientSettings
import ai.koog.prompt.executor.clients.dashscope.DashscopeParams
import ai.koog.prompt.executor.clients.dashscope.models.DashscopeChatCompletionResponse
import ai.koog.prompt.executor.clients.dashscope.models.DashscopeChatCompletionStreamResponse
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIBaseLLMRequest
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIResponseFormat
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.serialization.AdditionalPropertiesFlatteningSerializer
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Koog 1.1.1-beta's DashScope client emits empty text deltas while a Qwen tool call is being
 * assembled. StreamFrameFlowBuilder treats that as a content-part boundary and completes the
 * tool call after every argument fragment. This client is the upstream implementation with the
 * empty text delta filtered at the provider boundary.
 */
internal class KcodeDashscopeLLMClient(
    apiKey: String,
    private val settings: DashscopeClientSettings,
    httpClientFactory: KoogHttpClient.Factory,
) : AbstractOpenAILLMClient<DashscopeChatCompletionResponse, DashscopeChatCompletionStreamResponse>(
    apiKey = apiKey,
    settings = settings,
    httpClientFactory = httpClientFactory,
    clientName = ClientName,
    logger = Logger,
    toolsConverter = OpenAICompatibleToolDescriptorSchemaGenerator(),
) {
    override val clientName: String = ClientName

    override fun llmProvider(): LLMProvider = LLMProvider.Alibaba

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean,
    ): String {
        val dashscopeParams = params.asDashscopeParams()
        val request = KcodeDashscopeChatCompletionRequest(
            messages = messages,
            model = model.id,
            maxTokens = dashscopeParams.maxTokens,
            responseFormat = createResponseFormat(params.schema, model),
            stream = stream,
            temperature = dashscopeParams.temperature,
            toolChoice = dashscopeParams.toolChoice?.toOpenAIToolChoice(),
            tools = tools?.takeIf { it.isNotEmpty() },
            logprobs = dashscopeParams.logprobs,
            topLogprobs = dashscopeParams.topLogprobs,
            topP = dashscopeParams.topP,
            frequencyPenalty = dashscopeParams.frequencyPenalty,
            presencePenalty = dashscopeParams.presencePenalty,
            stop = dashscopeParams.stop,
            enableSearch = dashscopeParams.enableSearch,
            parallelToolCalls = dashscopeParams.parallelToolCalls,
            enableThinking = dashscopeParams.enableThinking,
        )
        val payload = json.encodeToString(KcodeDashscopeChatCompletionRequestSerializer, request)
        return rewriteDashscopeVideoContent(payload)
    }

    override fun processProviderChatResponse(response: DashscopeChatCompletionResponse): LLMChoice {
        require(response.choices.isNotEmpty()) { "Empty choices in response" }
        return response.choices.map {
            it.message.toMessageResponse(it.finishReason, createMetaInfo(response.usage))
        }
    }

    override fun decodeStreamingResponse(data: String): DashscopeChatCompletionStreamResponse =
        json.decodeFromString(data)

    override fun decodeResponse(data: String): DashscopeChatCompletionResponse =
        json.decodeFromString(data)

    override fun processStreamingResponse(
        response: Flow<DashscopeChatCompletionStreamResponse>,
    ): Flow<StreamFrame> = buildStreamFrameFlow {
        var finishReason: String? = null
        var metaInfo: ResponseMetaInfo? = null

        response.collect { chunk ->
            chunk.choices.firstOrNull()?.let { choice ->
                choice.delta.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emitTextDelta(it) }

                choice.delta.toolCalls?.forEach { toolCall ->
                    emitToolCallDelta(
                        id = toolCall.id?.takeIf { it.isNotEmpty() },
                        name = toolCall.function?.name,
                        args = toolCall.function?.arguments,
                        index = toolCall.index,
                    )
                }
                choice.finishReason?.let { finishReason = it }
            }
            chunk.usage?.let { metaInfo = createMetaInfo(it) }
        }

        emitEnd(finishReason, metaInfo)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("Moderation is not supported by DashScope API.")

    override suspend fun embed(text: String, model: LLModel): List<Double> =
        throw UnsupportedOperationException("Embedding is not supported by DashScope API.")

    override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> =
        throw UnsupportedOperationException("Embedding is not supported by DashScope API.")

    private companion object {
        const val ClientName = "DashscopeLLMClient"
        val Logger = KotlinLogging.logger { }
    }
}

private fun LLMParams.asDashscopeParams(): DashscopeParams =
    this as? DashscopeParams ?: DashscopeParams(
        temperature = temperature,
        maxTokens = maxTokens,
        numberOfChoices = numberOfChoices,
        speculation = speculation,
        schema = schema,
        toolChoice = toolChoice,
        user = user,
        additionalProperties = additionalProperties,
    )

@Serializable
private class KcodeDashscopeChatCompletionRequest(
    val messages: List<OpenAIMessage>,
    override val model: String,
    val enableThinking: Boolean? = null,
    override val stream: Boolean? = null,
    override val temperature: Double? = null,
    val tools: List<OpenAITool>? = null,
    val toolChoice: OpenAIToolChoice? = null,
    override val topP: Double? = null,
    override val topLogprobs: Int? = null,
    val logprobs: Boolean? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val responseFormat: OpenAIResponseFormat? = null,
    val stop: List<String>? = null,
    val parallelToolCalls: Boolean? = null,
    val enableSearch: Boolean? = null,
    val additionalProperties: Map<String, JsonElement>? = null,
) : OpenAIBaseLLMRequest

private object KcodeDashscopeChatCompletionRequestSerializer :
    AdditionalPropertiesFlatteningSerializer<KcodeDashscopeChatCompletionRequest>(
        KcodeDashscopeChatCompletionRequest.serializer(),
    )

/**
 * Koog 1.1.1 has no OpenAI-compatible video content part. Alibaba accepts the same Base64 data URL
 * using its `video_url` extension, so video attachments are carried through Koog's image content
 * part and corrected at the final provider serialization boundary.
 */
internal fun rewriteDashscopeVideoContent(payload: String): String {
    val element = Json.parseToJsonElement(payload).rewriteVideoContent()
    return Json.encodeToString(JsonElement.serializer(), element)
}

private fun JsonElement.rewriteVideoContent(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map(JsonElement::rewriteVideoContent))
    is JsonObject -> {
        val imageUrl = this["image_url"] as? JsonObject
        val url = imageUrl?.get("url")?.jsonPrimitive?.contentOrNull
        if (this["type"]?.jsonPrimitive?.contentOrNull == "image_url" && url?.startsWith("data:video/") == true) {
            JsonObject(
                mapValues { (key, value) ->
                    when (key) {
                        "type" -> JsonPrimitive("video_url")
                        "image_url" -> value.rewriteVideoContent()
                        else -> value.rewriteVideoContent()
                    }
                }.mapKeys { (key, _) -> if (key == "image_url") "video_url" else key },
            )
        } else {
            JsonObject(mapValues { (_, value) -> value.rewriteVideoContent() })
        }
    }
    else -> this
}
