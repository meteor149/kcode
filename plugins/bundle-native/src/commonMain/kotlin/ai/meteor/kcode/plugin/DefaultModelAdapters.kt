package ai.meteor.kcode.plugin

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.PluginDescriptor
import org.cordis.Plugin
import ai.meteor.kcode.plugin.llm.OpenAIModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.AzureOpenAIModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.AnthropicModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.GoogleModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.DeepSeekModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.OpenRouterModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.BedrockModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.MistralModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.AlibabaModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.OllamaModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.GLMModelAdapterPlugin

/** Resolve only linked fallback entries; package-owned providers are never touched. */
fun defaultModelAdapterPlugins(excludedIds: Set<String> = emptySet()): List<KcodePluginMount> = buildList {
    fun addProvider(provider: ModelProvider, entry: () -> Plugin<Unit>) {
        val id = "provider.llm.koog.${provider.name}"
        if (id !in excludedIds) {
            add(kcodePlugin(PluginDescriptor(id, "builtin", "built-in", setOf("llm")), entry(), Unit))
        }
    }
    addProvider(ModelProvider.OpenAI) { OpenAIModelAdapterPlugin }
    addProvider(ModelProvider.AzureOpenAI) { AzureOpenAIModelAdapterPlugin }
    addProvider(ModelProvider.Anthropic) { AnthropicModelAdapterPlugin }
    addProvider(ModelProvider.Google) { GoogleModelAdapterPlugin }
    addProvider(ModelProvider.DeepSeek) { DeepSeekModelAdapterPlugin }
    addProvider(ModelProvider.OpenRouter) { OpenRouterModelAdapterPlugin }
    if (nativeBedrockAvailable()) addProvider(ModelProvider.Bedrock) { BedrockModelAdapterPlugin }
    addProvider(ModelProvider.Mistral) { MistralModelAdapterPlugin }
    addProvider(ModelProvider.Alibaba) { AlibabaModelAdapterPlugin }
    addProvider(ModelProvider.Ollama) { OllamaModelAdapterPlugin }
    addProvider(ModelProvider.GLM) { GLMModelAdapterPlugin }
}

internal expect fun nativeBedrockAvailable(): Boolean
