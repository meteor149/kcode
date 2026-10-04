package ai.meteor.kcode.model

/** Opaque provider route registered by an adapter; existing persisted route ids stay unchanged. */
data class ModelProvider(val id: String) {
    init {
        require(id.isNotBlank() && id == id.trim() && id.none(Char::isISOControl)) {
            "model provider id must be nonblank and contain no control characters"
        }
    }

    // Compatibility for callers that used the old enum name as a persisted key.
    val name: String get() = id
    override fun toString(): String = id

    companion object {
        val OpenAI = ModelProvider("OpenAI")
        val AzureOpenAI = ModelProvider("AzureOpenAI")
        val Anthropic = ModelProvider("Anthropic")
        val Google = ModelProvider("Google")
        val DeepSeek = ModelProvider("DeepSeek")
        val OpenRouter = ModelProvider("OpenRouter")
        val Bedrock = ModelProvider("Bedrock")
        val Mistral = ModelProvider("Mistral")
        val Alibaba = ModelProvider("Alibaba")
        val Ollama = ModelProvider("Ollama")
        val GLM = ModelProvider("GLM")
        /** Historical built-in routes, for native bundle composition and legacy alias parsing only. */
        val entries: List<ModelProvider> = listOf(
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
        )
    }
}

enum class DashscopeRegion(
    val code: String,
) {
    ChinaMainland("china_mainland"),
    Singapore("singapore"),
    UnitedStates("united_states");

    companion object {
        fun fromCode(code: String): DashscopeRegion? = entries.firstOrNull { it.code == code }
    }
}

data class ModelOption(
    val provider: ModelProvider,
    val id: String,
    val defaultTemperature: Double,
    val displayNames: Map<String, String> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
)

data class ModelConfiguration(
    val provider: ModelProvider,
    val modelId: String,
    val apiKey: String,
    val temperature: Double,
    val endpoint: String = "",
    val region: String = "",
    val deployment: String = "",
    val apiVersion: String = "",
    val dashscopeRegion: DashscopeRegion? = null,
)
