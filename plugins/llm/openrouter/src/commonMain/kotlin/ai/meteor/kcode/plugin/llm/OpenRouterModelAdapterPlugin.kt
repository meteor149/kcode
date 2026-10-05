package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.openrouter.BuiltinModelLabels

object OpenRouterModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.OpenRouter) {
    override fun createAdapter(): ModelAdapter = nativeOpenRouterAdapter()
}

internal fun nativeOpenRouterAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.OpenRouter",
    supports = { it.provider == ModelProvider.OpenRouter },
    create = ::createOpenRouterModelRuntime,
    catalog = ModelProviderSpec(
        provider = ModelProvider.OpenRouter,
        models = OpenRouterModels,
        iconId = "Google",
        order = 5,
        displayNames = labels("provider_openrouter_en", "provider_openrouter_zh"),
        descriptions = labels("provider_openrouter_note_en", "provider_openrouter_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = true,
            endpoint = false,
            region = false,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(),
    ),
)

private val OpenRouterModels = listOf(
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-5.2-pro", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-5.2", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-5", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-5-chat", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-5-mini", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-5-nano", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-oss-120b", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-4o", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-4o-mini", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-4-turbo", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-4", 0.6),
    ModelOption(ModelProvider.OpenRouter, "openai/gpt-3.5-turbo", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-opus-4.6", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-sonnet-4.6", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-opus-4.5", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-sonnet-4.5", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-haiku-4.5", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-opus-4.1", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-sonnet-4", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-3.7-sonnet", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-3.5-sonnet", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-3-opus", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-3-sonnet", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-3-haiku", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-3-opus-vision", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-3-sonnet-vision", 0.6),
    ModelOption(ModelProvider.OpenRouter, "anthropic/claude-3-haiku-vision", 0.6),
    ModelOption(ModelProvider.OpenRouter, "google/gemini-2.5-pro", 0.6),
    ModelOption(ModelProvider.OpenRouter, "google/gemini-2.5-flash", 0.6),
    ModelOption(ModelProvider.OpenRouter, "google/gemini-2.5-flash-lite", 0.6),
    ModelOption(ModelProvider.OpenRouter, "deepseek/deepseek-chat-v3-0324", 0.6),
    ModelOption(ModelProvider.OpenRouter, "qwen/qwen3-vl-8b-instruct", 0.6),
    ModelOption(ModelProvider.OpenRouter, "qwen/qwen-2.5-72b-instruct", 0.6),
    ModelOption(ModelProvider.OpenRouter, "meta-llama/llama-3-70b-instruct", 0.6),
    ModelOption(ModelProvider.OpenRouter, "meta-llama/llama-3-70b", 0.6),
    ModelOption(ModelProvider.OpenRouter, "mistralai/mixtral-8x7b-instruct", 0.6),
    ModelOption(ModelProvider.OpenRouter, "mistralai/mistral-7b-instruct", 0.6),
    ModelOption(ModelProvider.OpenRouter, "microsoft/phi-4-reasoning:free", 0.6),
)

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
