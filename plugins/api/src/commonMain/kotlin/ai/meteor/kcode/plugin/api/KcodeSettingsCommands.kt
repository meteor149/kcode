package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

fun interface SettingsCommandHandler {
    /** Use the host's committed catalog; never a static built-in provider/model table. */
    suspend fun apply(update: SettingsUpdate, catalog: ModelCatalogSnapshot): AppliedSettingsUpdate
}

class KcodeSettingsCommands(ctx: Context, val handler: SettingsCommandHandler) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeSettingsCommands>("settingsCommands") }
}
