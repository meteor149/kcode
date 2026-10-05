package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.deepseek.BuiltinModelLabels

object DeepSeekModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.DeepSeek) {
    override fun createAdapter(): ModelAdapter = nativeDeepSeekAdapter()
}

internal fun nativeDeepSeekAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.DeepSeek",
    supports = { it.provider == ModelProvider.DeepSeek },
    create = ::createDeepSeekModelRuntime,
    catalog = ModelProviderSpec(
        provider = ModelProvider.DeepSeek,
        models = DeepSeekModels.map { it.presented() },
        iconId = "DeepSeek",
        order = 4,
        displayNames = labels("provider_deepseek_en", "provider_deepseek_zh"),
        descriptions = labels("provider_deepseek_note_en", "provider_deepseek_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = true,
            endpoint = false,
            region = false,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(),
    ),
)

private val DeepSeekModels = listOf(
    ModelOption(ModelProvider.DeepSeek, "deepseek-v4-pro", 0.3),
    ModelOption(ModelProvider.DeepSeek, "deepseek-v4-flash", 0.4),
)

private fun ModelOption.presented(): ModelOption = when (id) {
    "deepseek-v4-flash" -> copy(
        displayNames = labels("model_deepseek_flash_en", "model_deepseek_flash_zh"),
        descriptions = labels("model_deepseek_flash_desc_en", "model_deepseek_flash_desc_zh"),
    )
    "deepseek-v4-pro" -> copy(
        displayNames = labels("model_deepseek_pro_en", "model_deepseek_pro_zh"),
        descriptions = labels("model_deepseek_pro_desc_en", "model_deepseek_pro_desc_zh"),
    )
    else -> this
}

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
