@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.meteor.kcode

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import cnames.structs.Rcp_Session
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import platform.RemoteCommunicationKit.RemoteCommunication.HMS_Rcp_CloseSession
import platform.RemoteCommunicationKit.RemoteCommunication.HMS_Rcp_CreateHeaders
import platform.RemoteCommunicationKit.RemoteCommunication.HMS_Rcp_CreateRequest
import platform.RemoteCommunicationKit.RemoteCommunication.HMS_Rcp_CreateSession
import platform.RemoteCommunicationKit.RemoteCommunication.HMS_Rcp_DestroyHeaders
import platform.RemoteCommunicationKit.RemoteCommunication.HMS_Rcp_DestroyRequest
import platform.RemoteCommunicationKit.RemoteCommunication.HMS_Rcp_FetchSync
import platform.RemoteCommunicationKit.RemoteCommunication.HMS_Rcp_SetHeaderValue
import platform.RemoteCommunicationKit.RemoteCommunication.RCP_SESSION_TYPE_HTTP
import platform.RemoteCommunicationKit.RemoteCommunication.Rcp_ContentType.RCP_CONTENT_TYPE_STRING
import platform.RemoteCommunicationKit.RemoteCommunication.Rcp_RequestContent
import platform.RemoteCommunicationKit.RemoteCommunication.Rcp_SessionConfiguration

internal object OhosChatService : ChatService {
    override val availability = null

    override suspend fun reply(
        configuration: ModelConfiguration,
        history: List<ChatMessage>,
        prompt: String,
    ): String {
        require(configuration.provider == ModelProvider.Ollama || configuration.apiKey.isNotBlank()) {
            "请先在设置中添加 API Key。"
        }
        require(configuration.provider != ModelProvider.Bedrock) {
            "Amazon Bedrock 当前仅在桌面端可用，请选择其他模型服务。"
        }

        val providerRequest = createProviderRequest(configuration, history, prompt)
        val response = OhosHttpClient.post(
            url = providerRequest.url,
            headers = providerRequest.headers,
            body = providerRequest.body.toString(),
        )
        if (response.statusCode !in 200..299) {
            val detail = parseProviderError(response.body)
            error("模型服务请求失败（HTTP ${response.statusCode}）：$detail")
        }
        return providerRequest.readText(Json.parseToJsonElement(response.body))
            .trim()
            .ifEmpty { error("模型服务返回了空响应。") }
    }
}

private const val SystemPrompt = """
You are kcode, a reliable, clear, and friendly AI assistant.
Respond in the user's language by default. Give the direct answer first, then add detail when it is genuinely helpful.
Clearly state uncertainty, and never fabricate sources, capabilities, or execution results.
"""

private val Json = Json { ignoreUnknownKeys = true }

private data class ProviderRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: JsonObject,
    val readText: (JsonElement) -> String,
)

private data class HttpResponse(
    val statusCode: Int,
    val body: String,
)

private object OhosHttpClient {
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        withContext(Dispatchers.Default) {
            memScoped {
                val errorCode = alloc<UIntVar>()
                val sessionConfiguration = alloc<Rcp_SessionConfiguration> {
                    type = RCP_SESSION_TYPE_HTTP
                }
                val session = HMS_Rcp_CreateSession(sessionConfiguration.ptr, errorCode.ptr)
                    ?: error("无法创建网络会话（错误码 ${errorCode.value}）。")
                val sessionHolder = alloc<CPointerVar<Rcp_Session>> { value = session }
                val request = HMS_Rcp_CreateRequest(url)
                    ?: error("无法创建模型服务请求。")
                val requestHeaders = HMS_Rcp_CreateHeaders()
                    ?: error("无法创建请求头。")
                val bodyBytes = body.encodeToByteArray()
                val pinnedBody = bodyBytes.pin()

                try {
                    headers.forEach { (name, value) ->
                        check(HMS_Rcp_SetHeaderValue(requestHeaders, name, value) == 0u) {
                            "无法设置请求头 $name。"
                        }
                    }
                    check(HMS_Rcp_SetHeaderValue(requestHeaders, "Content-Type", "application/json") == 0u)

                    val requestContent = alloc<Rcp_RequestContent> {
                        type = RCP_CONTENT_TYPE_STRING
                        data.contentStr.buffer = pinnedBody.addressOf(0).reinterpret()
                        data.contentStr.length = bodyBytes.size.toUInt()
                    }
                    request.pointed.method = "POST".cstr.ptr
                    request.pointed.headers = requestHeaders
                    request.pointed.content = requestContent.ptr

                    errorCode.value = 0u
                    val response = HMS_Rcp_FetchSync(session, request, errorCode.ptr)
                        ?: error("网络请求失败（错误码 ${errorCode.value}）。")
                    try {
                        val responseBody = response.pointed.body.let { buffer ->
                            if (buffer.buffer == null || buffer.length == 0u) {
                                ""
                            } else {
                                buffer.buffer!!.readBytes(buffer.length.toInt()).decodeToString()
                            }
                        }
                        HttpResponse(response.pointed.statusCode.toInt(), responseBody)
                    } finally {
                        response.pointed.destroyResponse?.invoke(response)
                    }
                } finally {
                    pinnedBody.unpin()
                    request.pointed.content = null
                    request.pointed.headers = null
                    HMS_Rcp_DestroyHeaders(requestHeaders)
                    HMS_Rcp_DestroyRequest(request)
                    HMS_Rcp_CloseSession(sessionHolder.ptr)
                }
            }
        }
}

private fun createProviderRequest(
    configuration: ModelConfiguration,
    history: List<ChatMessage>,
    prompt: String,
): ProviderRequest = when (configuration.provider) {
    ModelProvider.Anthropic -> anthropicRequest(configuration, history, prompt)
    ModelProvider.Google -> googleRequest(configuration, history, prompt)
    ModelProvider.Bedrock -> error("Amazon Bedrock 当前仅在桌面端可用。")
    else -> openAiCompatibleRequest(configuration, history, prompt)
}

private fun openAiCompatibleRequest(
    configuration: ModelConfiguration,
    history: List<ChatMessage>,
    prompt: String,
): ProviderRequest {
    val (url, headers) = when (configuration.provider) {
        ModelProvider.OpenAI -> "https://api.openai.com/v1/chat/completions" to
            bearerHeaders(configuration.apiKey)
        ModelProvider.AzureOpenAI -> {
            require(configuration.endpoint.isNotBlank() && configuration.deployment.isNotBlank()) {
                "请先配置 Azure OpenAI Endpoint 和 Deployment。"
            }
            val version = configuration.apiVersion.ifBlank { "2024-10-21" }
            "${configuration.endpoint.trimEnd('/')}/openai/deployments/${configuration.deployment}/chat/completions?api-version=$version" to
                mapOf("api-key" to configuration.apiKey)
        }
        ModelProvider.DeepSeek -> "https://api.deepseek.com/chat/completions" to
            bearerHeaders(configuration.apiKey)
        ModelProvider.OpenRouter -> "https://openrouter.ai/api/v1/chat/completions" to
            bearerHeaders(configuration.apiKey)
        ModelProvider.Mistral -> "https://api.mistral.ai/v1/chat/completions" to
            bearerHeaders(configuration.apiKey)
        ModelProvider.Alibaba ->
            "${configuration.dashscopeRegion.baseUrl}compatible-mode/v1/chat/completions" to
                bearerHeaders(configuration.apiKey)
        ModelProvider.Ollama -> {
            val endpoint = configuration.endpoint.ifBlank { "http://127.0.0.1:11434" }
            "${endpoint.trimEnd('/')}/v1/chat/completions" to emptyMap()
        }
        ModelProvider.GLM -> "https://open.bigmodel.cn/api/paas/v4/chat/completions" to
            bearerHeaders(configuration.apiKey)
        else -> error("不支持的 OpenAI 兼容服务：${configuration.provider}")
    }
    val body = buildJsonObject {
        put("model", configuration.modelId)
        put("temperature", configuration.temperature)
        put("stream", false)
        put("messages", openAiMessages(history, prompt))
    }
    return ProviderRequest(url, headers, body, ::readOpenAiText)
}

private fun anthropicRequest(
    configuration: ModelConfiguration,
    history: List<ChatMessage>,
    prompt: String,
): ProviderRequest = ProviderRequest(
    url = "https://api.anthropic.com/v1/messages",
    headers = mapOf(
        "x-api-key" to configuration.apiKey,
        "anthropic-version" to "2023-06-01",
    ),
    body = buildJsonObject {
        put("model", configuration.modelId)
        put("system", SystemPrompt.trim())
        put("max_tokens", 4096)
        put("temperature", configuration.temperature)
        putJsonArray("messages") {
            history.filterNot { it.isError }.forEach { message ->
                addJsonMessage(message.role.providerRole(), message.content)
            }
            addJsonMessage("user", prompt)
        }
    },
    readText = { root ->
        root.jsonObject["content"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
    },
)

private fun googleRequest(
    configuration: ModelConfiguration,
    history: List<ChatMessage>,
    prompt: String,
): ProviderRequest = ProviderRequest(
    url = "https://generativelanguage.googleapis.com/v1beta/models/${configuration.modelId}:generateContent",
    headers = mapOf("x-goog-api-key" to configuration.apiKey),
    body = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", SystemPrompt.trim()) }) }
        }
        putJsonArray("contents") {
            history.filterNot { it.isError }.forEach { message ->
                addGoogleMessage(message.role.googleRole(), message.content)
            }
            addGoogleMessage("user", prompt)
        }
        putJsonObject("generationConfig") {
            put("temperature", configuration.temperature)
        }
    },
    readText = { root ->
        root.jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
    },
)

private fun openAiMessages(history: List<ChatMessage>, prompt: String): JsonArray = buildJsonArray {
    addJsonMessage("system", SystemPrompt.trim())
    history.filterNot { it.isError }.forEach { message ->
        addJsonMessage(message.role.providerRole(), message.content)
    }
    addJsonMessage("user", prompt)
}

private fun kotlinx.serialization.json.JsonArrayBuilder.addJsonMessage(role: String, content: String) {
    add(buildJsonObject {
        put("role", role)
        put("content", content)
    })
}

private fun kotlinx.serialization.json.JsonArrayBuilder.addGoogleMessage(role: String, content: String) {
    add(buildJsonObject {
        put("role", role)
        putJsonArray("parts") { add(buildJsonObject { put("text", content) }) }
    })
}

private fun MessageRole.providerRole(): String = if (this == MessageRole.User) "user" else "assistant"

private fun MessageRole.googleRole(): String = if (this == MessageRole.User) "user" else "model"

private fun bearerHeaders(apiKey: String): Map<String, String> =
    mapOf("Authorization" to "Bearer $apiKey")

private fun readOpenAiText(root: JsonElement): String {
    val content = root.jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("message")?.jsonObject?.get("content") ?: return ""
    return when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { part ->
            part.jsonObject["text"]?.let { text ->
                if (text is JsonPrimitive) text.contentOrNull else text.jsonObject["value"]?.jsonPrimitive?.contentOrNull
            }
        }.joinToString("")
        else -> ""
    }
}

private fun parseProviderError(body: String): String = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    val error = root["error"]
    when (error) {
        is JsonPrimitive -> error.contentOrNull
        is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
        else -> null
    }
}.getOrNull().orEmpty().ifBlank { body.take(500).ifBlank { "无响应正文" } }
