package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProvider

/** Default labels are contributed by the adapter and disappear with its catalog. */
internal fun providerDisplayNames(provider: ModelProvider): Map<String, String> = when (provider) {
    ModelProvider.OpenAI -> labels("provider_openai_en", "provider_openai_zh")
    ModelProvider.AzureOpenAI -> labels("provider_azure_openai_en", "provider_azure_openai_zh")
    ModelProvider.Anthropic -> labels("provider_anthropic_en", "provider_anthropic_zh")
    ModelProvider.Google -> labels("provider_google_en", "provider_google_zh")
    ModelProvider.DeepSeek -> labels("provider_deepseek_en", "provider_deepseek_zh")
    ModelProvider.OpenRouter -> labels("provider_openrouter_en", "provider_openrouter_zh")
    ModelProvider.Bedrock -> labels("provider_bedrock_en", "provider_bedrock_zh")
    ModelProvider.Mistral -> labels("provider_mistral_en", "provider_mistral_zh")
    ModelProvider.Alibaba -> labels("provider_alibaba_en", "provider_alibaba_zh")
    ModelProvider.Ollama -> labels("provider_ollama_en", "provider_ollama_zh")
    ModelProvider.GLM -> labels("provider_glm_en", "provider_glm_zh")
    else -> emptyMap()
}

internal fun providerDescriptions(provider: ModelProvider): Map<String, String> = when (provider) {
    ModelProvider.OpenAI -> labels("provider_openai_note_en", "provider_openai_note_zh")
    ModelProvider.AzureOpenAI -> labels("provider_azure_openai_note_en", "provider_azure_openai_note_zh")
    ModelProvider.Anthropic -> labels("provider_anthropic_note_en", "provider_anthropic_note_zh")
    ModelProvider.Google -> labels("provider_google_note_en", "provider_google_note_zh")
    ModelProvider.DeepSeek -> labels("provider_deepseek_note_en", "provider_deepseek_note_zh")
    ModelProvider.OpenRouter -> labels("provider_openrouter_note_en", "provider_openrouter_note_zh")
    ModelProvider.Bedrock -> labels("provider_bedrock_note_en", "provider_bedrock_note_zh")
    ModelProvider.Mistral -> labels("provider_mistral_note_en", "provider_mistral_note_zh")
    ModelProvider.Alibaba -> labels("provider_alibaba_note_en", "provider_alibaba_note_zh")
    ModelProvider.Ollama -> labels("provider_ollama_note_en", "provider_ollama_note_zh")
    ModelProvider.GLM -> labels("provider_glm_note_en", "provider_glm_note_zh")
    else -> emptyMap()
}

internal fun ModelOption.withDefaultPresentation(): ModelOption = when (id) {
    "gpt-4o-mini" -> copy(
        displayNames = labels("model_gpt4o_mini_en", "model_gpt4o_mini_zh"),
        descriptions = labels("model_gpt4o_mini_desc_en", "model_gpt4o_mini_desc_zh"),
    )
    "gpt-4o" -> copy(
        displayNames = labels("model_gpt4o_en", "model_gpt4o_zh"),
        descriptions = labels("model_gpt4o_desc_en", "model_gpt4o_desc_zh"),
    )
    "deepseek-v4-flash" -> copy(
        displayNames = labels("model_deepseek_flash_en", "model_deepseek_flash_zh"),
        descriptions = labels("model_deepseek_flash_desc_en", "model_deepseek_flash_desc_zh"),
    )
    "deepseek-v4-pro" -> copy(
        displayNames = labels("model_deepseek_pro_en", "model_deepseek_pro_zh"),
        descriptions = labels("model_deepseek_pro_desc_en", "model_deepseek_pro_desc_zh"),
    )
    "glm-5.1" -> copy(
        displayNames = labels("model_glm_51_en", "model_glm_51_zh"),
        descriptions = labels("model_glm_51_desc_en", "model_glm_51_desc_zh"),
    )
    "glm-4.7-flashx" -> copy(
        displayNames = labels("model_glm_flash_en", "model_glm_flash_zh"),
        descriptions = labels("model_glm_flash_desc_en", "model_glm_flash_desc_zh"),
    )
    else -> this
}

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
