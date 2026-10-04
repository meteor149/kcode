package ai.meteor.kcode.settings

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelConfiguration

/** Model selection and persisted configuration policy belongs to its active provider. */
interface ModelSettingsPolicy {
    fun resolve(settings: StoredAppSettings, catalog: ModelCatalogSnapshot): ModelConfiguration?
    fun update(settings: StoredAppSettings, configuration: ModelConfiguration): StoredAppSettings
}
