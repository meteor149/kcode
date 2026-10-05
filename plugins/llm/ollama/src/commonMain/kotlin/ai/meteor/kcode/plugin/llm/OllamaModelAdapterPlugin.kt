package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.ollama.BuiltinModelLabels

object OllamaModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Ollama) {
    override fun createAdapter(): ModelAdapter = nativeOllamaAdapter()
}

internal fun nativeOllamaAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.Ollama",
    supports = { it.provider == ModelProvider.Ollama },
    create = ::createOllamaModelRuntime,
    catalog = ModelProviderSpec(
        provider = ModelProvider.Ollama,
        models = OllamaModels,
        iconId = "Device",
        order = 9,
        displayNames = labels("provider_ollama_en", "provider_ollama_zh"),
        descriptions = labels("provider_ollama_note_en", "provider_ollama_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = false,
            endpoint = true,
            region = false,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(endpoint = "http://localhost:11434"),
    ),
)

private val OllamaModels = listOf(
    ModelOption(ModelProvider.Ollama, "qwen3.5:9b", 0.6),
    ModelOption(ModelProvider.Ollama, "gpt-oss:20b", 0.6),
    ModelOption(ModelProvider.Ollama, "deepseek-r1:1.5b", 0.6),
    ModelOption(ModelProvider.Ollama, "qwen2.5-coder:32b", 0.6),
    ModelOption(ModelProvider.Ollama, "qwq:32b", 0.6),
    ModelOption(ModelProvider.Ollama, "qwen3:0.6b", 0.6),
    ModelOption(ModelProvider.Ollama, "qwen2.5:0.5b", 0.6),
    ModelOption(ModelProvider.Ollama, "llama4:latest", 0.6),
    ModelOption(ModelProvider.Ollama, "llama4:scout", 0.6),
    ModelOption(ModelProvider.Ollama, "llama3.2:latest", 0.6),
    ModelOption(ModelProvider.Ollama, "llama3.2:3b", 0.6),
    ModelOption(ModelProvider.Ollama, "llama3-groq-tool-use:70b", 0.6),
    ModelOption(ModelProvider.Ollama, "llama3-groq-tool-use:8b", 0.6),
    ModelOption(ModelProvider.Ollama, "granite3.2-vision", 0.6),
)

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
