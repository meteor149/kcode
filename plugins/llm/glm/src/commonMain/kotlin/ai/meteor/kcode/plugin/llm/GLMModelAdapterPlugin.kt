package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.glm.BuiltinModelLabels

object GLMModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.GLM) {
    override fun createAdapter(): ModelAdapter = nativeGLMAdapter()
}

internal fun nativeGLMAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.GLM",
    supports = { it.provider == ModelProvider.GLM },
    create = ::createGLMModelRuntime,
    catalog = ModelProviderSpec(
        provider = ModelProvider.GLM,
        models = GLMModels.map { it.presented() },
        iconId = "Glm",
        order = 10,
        displayNames = labels("provider_glm_en", "provider_glm_zh"),
        descriptions = labels("provider_glm_note_en", "provider_glm_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = true,
            endpoint = false,
            region = false,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(),
    ),
)

private val GLMModels = listOf(
    ModelOption(ModelProvider.GLM, "glm-5.2", 0.6),
    ModelOption(ModelProvider.GLM, "glm-5.1", 0.6),
    ModelOption(ModelProvider.GLM, "glm-4.7-flashx", 0.6),
)

private fun ModelOption.presented(): ModelOption = when (id) {
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
