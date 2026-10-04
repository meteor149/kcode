package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.plugin.api.SettingsStoreFactory
import ai.meteor.kcode.plugin.api.SettingsStoreResource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun memorySettingsStoreFactory(): SettingsStoreFactory = SettingsStoreFactory {
    val store = MemoryAppSettingsStore()
    SettingsStoreResource(store, store::close)
}

/** Ephemeral state belongs to one provider mount. Snapshots never expose its mutable key map. */
internal class MemoryAppSettingsStore : AppSettingsStore {
    override val protection = SettingsProtection.Transient
    private val mutex = Mutex()
    private var value = defaultSettings()
    private var closed = false
    override suspend fun load(): StoredAppSettings = mutex.withLock {
        check(!closed) { "memory settings storage is closed" }
        value.copy(modelApiKeys = value.modelApiKeys.toMap(), searchApiKeys = value.searchApiKeys.toMap())
    }
    override suspend fun save(settings: StoredAppSettings) = mutex.withLock {
        check(!closed) { "memory settings storage is closed" }
        value = settings.copy(modelApiKeys = settings.modelApiKeys.toMap(), searchApiKeys = settings.searchApiKeys.toMap())
    }
    suspend fun close() = mutex.withLock {
        closed = true
        value = defaultSettings()
    }
}
