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
)

val SupportedModels = listOf(
    ModelOption(ModelProvider.OpenAI, "gpt-4o-mini", 0.7),
    ModelOption(ModelProvider.OpenAI, "gpt-4o", 0.6),
    ModelOption(ModelProvider.AzureOpenAI, "gpt-4o-mini", 0.7),
    ModelOption(ModelProvider.AzureOpenAI, "gpt-4o", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-sonnet-4-6", 0.5),
    ModelOption(ModelProvider.Anthropic, "claude-haiku-4-5", 0.7),
    ModelOption(ModelProvider.Google, "gemini-2.5-flash", 0.7),
    ModelOption(ModelProvider.Google, "gemini-2.5-pro", 0.5),
    ModelOption(ModelProvider.DeepSeek, "deepseek-v4-flash", 0.4),
    ModelOption(ModelProvider.DeepSeek, "deepseek-v4-pro", 0.3),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-sonnet-4.6", 0.5),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-5.2", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-sonnet-4-6", 0.5),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-haiku-4-5-20251001-v1:0", 0.7),
    ModelOption(ModelProvider.Mistral, "mistral-medium-latest", 0.6),
    ModelOption(ModelProvider.Mistral, "devstral-medium-latest", 0.5),
    ModelOption(ModelProvider.Alibaba, "qwen3-max", 0.6),
    ModelOption(ModelProvider.Alibaba, "qwen3-coder-plus", 0.5),
    ModelOption(ModelProvider.Ollama, "llama3.2", 0.7),
    ModelOption(ModelProvider.Ollama, "qwen3:8b", 0.6),
    ModelOption(ModelProvider.GLM, "glm-5.1", 0.5),
    ModelOption(ModelProvider.GLM, "glm-4.7-flashx", 0.7),
)

fun modelsFor(provider: ModelProvider): List<ModelOption> =
    SupportedModels.filter { it.provider == provider }

fun modelOption(modelId: String?): ModelOption? =
    SupportedModels.firstOrNull { it.id == modelId }
