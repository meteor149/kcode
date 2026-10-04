package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.localization.LocalTranslationCatalog
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import androidx.compose.runtime.Composable

@Composable
private fun catalogText(values: Map<String, String>): String? = values[LocalAppLanguage.current.code]
    ?: LocalTranslationCatalog.current?.snapshot()?.fallbackLanguages?.firstNotNullOfOrNull { values[it.code] }

/** Projection of the committed catalog; no built-in labels are restored for missing adapters. */
@Composable
fun providerName(provider: ModelProvider): String {
    val active = LocalModelCatalog.current.provider(provider) ?: return provider.id
    return catalogText(active.displayNames) ?: active.displayName ?: provider.id
}

@Composable
fun providerNote(provider: ModelProvider): String {
    val active = LocalModelCatalog.current.provider(provider) ?: return ""
    return catalogText(active.descriptions) ?: active.description.orEmpty()
}

@Composable
fun modelName(model: ModelOption): String {
    val active = LocalModelCatalog.current.modelOption(model.provider, model.id) ?: return model.id
    return catalogText(active.displayNames) ?: active.id
}

@Composable
fun modelDescription(model: ModelOption): String {
    val active = LocalModelCatalog.current.modelOption(model.provider, model.id) ?: return ""
    return catalogText(active.descriptions) ?: providerNote(model.provider)
}
