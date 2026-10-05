package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelConnectionChoice
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.alibaba.BuiltinModelLabels

object AlibabaModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Alibaba) {
    override fun createAdapter(): ModelAdapter = nativeAlibabaAdapter()
}

internal fun nativeAlibabaAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.Alibaba",
    supports = { it.provider == ModelProvider.Alibaba },
    create = ::createAlibabaModelRuntime,
    catalog = ModelProviderSpec(
        provider = ModelProvider.Alibaba,
        models = AlibabaModels,
        iconId = "Glm",
        order = 8,
        displayNames = labels("provider_alibaba_en", "provider_alibaba_zh"),
        descriptions = labels("provider_alibaba_note_en", "provider_alibaba_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = true,
            endpoint = false,
            region = true,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(region = "china_mainland"),
        regionMigrationKey = "dashscopeRegion",
        regionChoices = listOf(
            ModelConnectionChoice("china_mainland", labels("region_china_en", "region_china_zh")),
            ModelConnectionChoice("singapore", labels("region_singapore_en", "region_singapore_zh")),
            ModelConnectionChoice("united_states", labels("region_us_en", "region_us_zh")),
        ),
    ),
)

private val AlibabaModels = listOf(
    ModelOption(ModelProvider.Alibaba, "qwen3.8-max", 0.6),
    ModelOption(ModelProvider.Alibaba, "qwen3-max", 0.6),
    ModelOption(ModelProvider.Alibaba, "qwen3-coder-plus", 0.6),
    ModelOption(ModelProvider.Alibaba, "qwen3-coder-flash", 0.6),
    ModelOption(ModelProvider.Alibaba, "qwen-plus-latest", 0.6),
    ModelOption(ModelProvider.Alibaba, "qwen-plus", 0.6),
    ModelOption(ModelProvider.Alibaba, "qwen-flash", 0.6),
    ModelOption(ModelProvider.Alibaba, "qwen3-omni-flash", 0.6),
)

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
