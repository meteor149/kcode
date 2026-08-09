package ai.meteor.kcode.model

enum class ModelProvider {
    OpenAI,
    AzureOpenAI,
    Anthropic,
    Google,
    DeepSeek,
    OpenRouter,
    Bedrock,
    Mistral,
    Alibaba,
    Ollama,
    GLM,
}

enum class DashscopeRegion(
    val code: String,
    val baseUrl: String,
) {
    ChinaMainland("china_mainland", "https://dashscope.aliyuncs.com/"),
    Singapore("singapore", "https://dashscope-intl.aliyuncs.com/"),
    UnitedStates("united_states", "https://dashscope-us.aliyuncs.com/");

    companion object {
        fun fromCode(code: String): DashscopeRegion = entries.firstOrNull { it.code == code } ?: ChinaMainland
    }
}

val ModelProvider.requiresApiKey: Boolean
    get() = this != ModelProvider.Ollama

val ModelProvider.requiresEndpoint: Boolean
    get() = this == ModelProvider.AzureOpenAI || this == ModelProvider.Ollama

val ModelProvider.requiresRegion: Boolean
    get() = this == ModelProvider.Bedrock

val ModelProvider.requiresDeployment: Boolean
    get() = this == ModelProvider.AzureOpenAI

data class ModelOption(
    val provider: ModelProvider,
    val id: String,
    val defaultTemperature: Double = 0.6,
)

data class ModelConfiguration(
    val provider: ModelProvider,
    val modelId: String,
    val apiKey: String,
    val temperature: Double = 0.6,
    val endpoint: String = "",
    val region: String = "",
    val deployment: String = "",
    val apiVersion: String = "",
    val dashscopeRegion: DashscopeRegion = DashscopeRegion.ChinaMainland,
)

private fun modelOptions(
    provider: ModelProvider,
    vararg ids: String,
): List<ModelOption> = ids.map { ModelOption(provider, it) }

private val OpenAIChatModelIds = arrayOf(
    "gpt-5.5",
    "gpt-5.5-pro",
    "gpt-5.4",
    "gpt-5.4-mini",
    "gpt-5.4-nano",
    "gpt-5.4-pro",
    "gpt-5.3-codex",
    "gpt-5.2",
    "gpt-5.2-pro",
    "gpt-5.2-codex",
    "gpt-5.1",
    "gpt-5.1-codex",
    "gpt-5.1-codex-max",
    "gpt-5",
    "gpt-5-mini",
    "gpt-5-nano",
    "gpt-5-codex",
    "gpt-5-pro",
    "o4-mini",
    "o3",
    "o3-mini",
    "o1",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4.1-nano",
    "gpt-4o",
    "gpt-4o-mini",
    "gpt-audio",
    "gpt-4o-audio-preview",
    "gpt-4o-mini-audio-preview",
)

val SupportedModels = buildList {
    addAll(modelOptions(ModelProvider.OpenAI, *OpenAIChatModelIds))
    addAll(modelOptions(ModelProvider.AzureOpenAI, *OpenAIChatModelIds))
    addAll(modelOptions(
        ModelProvider.Anthropic,
        "claude-fable-5",
        "claude-opus-4-7",
        "claude-opus-4-6",
        "claude-opus-4-5",
        "claude-opus-4-1",
        "claude-opus-4-0",
        "claude-sonnet-4-6",
        "claude-sonnet-4-5",
        "claude-sonnet-4-0",
        "claude-haiku-4-5",
    ))
    addAll(modelOptions(
        ModelProvider.Google,
        "gemini-3.5-flash",
        "gemini-3.1-pro-preview",
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-lite-preview",
        "gemini-3-flash-preview",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash-lite-001",
    ))
    add(ModelOption(ModelProvider.DeepSeek, "deepseek-v4-pro", 0.3))
    add(ModelOption(ModelProvider.DeepSeek, "deepseek-v4-flash", 0.4))
    addAll(modelOptions(
        ModelProvider.OpenRouter,
        "openai/gpt-5.2-pro",
        "openai/gpt-5.2",
        "openai/gpt-5",
        "openai/gpt-5-chat",
        "openai/gpt-5-mini",
        "openai/gpt-5-nano",
        "openai/gpt-oss-120b",
        "openai/gpt-4o",
        "openai/gpt-4o-mini",
        "openai/gpt-4-turbo",
        "openai/gpt-4",
        "openai/gpt-3.5-turbo",
        "anthropic/claude-opus-4.6",
        "anthropic/claude-sonnet-4.6",
        "anthropic/claude-opus-4.5",
        "anthropic/claude-sonnet-4.5",
        "anthropic/claude-haiku-4.5",
        "anthropic/claude-opus-4.1",
        "anthropic/claude-sonnet-4",
        "anthropic/claude-3.7-sonnet",
        "anthropic/claude-3.5-sonnet",
        "anthropic/claude-3-opus",
        "anthropic/claude-3-sonnet",
        "anthropic/claude-3-haiku",
        "anthropic/claude-3-opus-vision",
        "anthropic/claude-3-sonnet-vision",
        "anthropic/claude-3-haiku-vision",
        "google/gemini-2.5-pro",
        "google/gemini-2.5-flash",
        "google/gemini-2.5-flash-lite",
        "deepseek/deepseek-chat-v3-0324",
        "qwen/qwen3-vl-8b-instruct",
        "qwen/qwen-2.5-72b-instruct",
        "meta-llama/llama-3-70b-instruct",
        "meta-llama/llama-3-70b",
        "mistralai/mixtral-8x7b-instruct",
        "mistralai/mistral-7b-instruct",
        "microsoft/phi-4-reasoning:free",
    ))
    addAll(modelOptions(
        ModelProvider.Bedrock,
        "us.anthropic.claude-fable-5",
        "us.anthropic.claude-opus-4-7",
        "us.anthropic.claude-opus-4-6-v1",
        "us.anthropic.claude-opus-4-5-20251101-v1:0",
        "us.anthropic.claude-opus-4-1-20250805-v1:0",
        "us.anthropic.claude-opus-4-20250514-v1:0",
        "us.anthropic.claude-sonnet-4-6",
        "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
        "us.anthropic.claude-sonnet-4-20250514-v1:0",
        "us.anthropic.claude-haiku-4-5-20251001-v1:0",
        "us.amazon.nova-premier-v1:0",
        "us.amazon.nova-pro-v1:0",
        "us.amazon.nova-lite-v1:0",
        "us.amazon.nova-micro-v1:0",
        "moonshotai.kimi-k2.5",
        "moonshot.kimi-k2-thinking",
        "minimax.minimax-m2.5",
        "openai.gpt-oss-120b-1:0",
        "openai.gpt-oss-20b-1:0",
        "google.gemma-3-27b-it",
        "google.gemma-3-12b-it",
        "google.gemma-3-4b-it",
        "us.meta.llama3-3-70b-instruct-v1:0",
        "us.meta.llama3-2-90b-instruct-v1:0",
        "us.meta.llama3-2-11b-instruct-v1:0",
        "us.meta.llama3-2-3b-instruct-v1:0",
        "us.meta.llama3-2-1b-instruct-v1:0",
        "us.meta.llama3-1-405b-instruct-v1:0",
        "us.meta.llama3-1-70b-instruct-v1:0",
        "us.meta.llama3-1-8b-instruct-v1:0",
        "us.meta.llama3-70b-instruct-v1:0",
        "us.meta.llama3-8b-instruct-v1:0",
    ))
    addAll(modelOptions(
        ModelProvider.Mistral,
        "mistral-large-latest",
        "mistral-medium-latest",
        "mistral-small-latest",
        "magistral-medium-latest",
        "codestral-latest",
        "devstral-medium-latest",
    ))
    addAll(modelOptions(
        ModelProvider.Alibaba,
        "qwen3.8-max",
        "qwen3-max",
        "qwen3-coder-plus",
        "qwen3-coder-flash",
        "qwen-plus-latest",
        "qwen-plus",
        "qwen-flash",
        "qwen3-omni-flash",
    ))
    addAll(modelOptions(
        ModelProvider.Ollama,
        "qwen3.5:9b",
        "gpt-oss:20b",
        "deepseek-r1:1.5b",
        "qwen2.5-coder:32b",
        "qwq:32b",
        "qwen3:0.6b",
        "qwen2.5:0.5b",
        "llama4:latest",
        "llama4:scout",
        "llama3.2:latest",
        "llama3.2:3b",
        "llama3-groq-tool-use:70b",
        "llama3-groq-tool-use:8b",
        "granite3.2-vision",
    ))
    addAll(modelOptions(ModelProvider.GLM, "glm-5.2", "glm-5.1", "glm-4.7-flashx"))
}

fun modelsFor(provider: ModelProvider): List<ModelOption> =
    SupportedModels.filter { it.provider == provider }

fun modelOption(provider: ModelProvider, modelId: String?): ModelOption? =
    SupportedModels.firstOrNull { it.provider == provider && it.id == modelId }
