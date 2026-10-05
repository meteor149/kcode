package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.json.JsonPrimitive

/** Read legacy values only through the migration key supplied by the selected owner. */
internal fun resolveModelRegion(settings: StoredAppSettings, specification: ModelProviderSpec): String {
    val values = ModelSettingsDocument.read(settings)
    if (values.modelRegion.isNotBlank() && values.provider == specification.provider.id) return values.modelRegion
    if (specification.regionChoices.isEmpty()) return ""
    val document = settings.namespaces["feature.model-settings"] ?: settings.legacyValues
    val historical = specification.regionMigrationKey?.let { (document[it] as? JsonPrimitive)?.content }
    return historical?.takeIf { old -> specification.regionChoices.any { it.value == old } }
        ?: specification.defaults.region
}
