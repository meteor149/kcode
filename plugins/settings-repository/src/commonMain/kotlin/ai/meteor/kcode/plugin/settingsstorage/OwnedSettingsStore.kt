package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.plugin.api.PluginOperationOwner

/** Calls belong to the mounted provider; the mounting strategy determines resource ownership. */
internal class OwnedSettingsStore(
    private val delegate: AppSettingsStore,
    private val owner: PluginOperationOwner,
) : AppSettingsStore {
    override val protection get() = delegate.protection.also { owner.requireOpen() }
    override suspend fun load(): StoredAppSettings = owner.run { delegate.load() }
    override suspend fun save(settings: StoredAppSettings) = owner.run { delegate.save(settings) }
}
