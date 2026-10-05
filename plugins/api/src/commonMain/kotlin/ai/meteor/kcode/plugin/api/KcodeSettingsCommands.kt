package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import org.cordis.Disposable
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

fun interface SettingsCommandHandler {
    /** Use the host's committed catalog; never a static built-in provider/model table. */
    suspend fun apply(update: SettingsUpdate, catalog: ModelCatalogSnapshot): AppliedSettingsUpdate
}

/** Feature-owned transform. The registry holds its lifetime through the durable commit. */
fun interface SettingsUpdateTransform {
    suspend fun apply(settings: StoredAppSettings, update: SettingsUpdate, catalog: ModelCatalogSnapshot): AppliedSettingsUpdate
}

interface SettingsUpdateRegistry {
    suspend fun register(id: String, fields: Set<String>, transform: SettingsUpdateTransform): Disposable
}

class KcodeSettingsCommands(
    ctx: Context,
    val handler: SettingsCommandHandler,
    val updates: SettingsUpdateRegistry,
) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeSettingsCommands>("settingsCommands") }
}
