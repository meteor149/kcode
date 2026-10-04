package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.settings.AppSettingsStore

/** Fresh per-mount allocation; bounded creation must clean up any partially acquired resources. */
fun interface SettingsStoreFactory {
    suspend fun create(): SettingsStoreResource
}
class SettingsStoreResource(val store: AppSettingsStore, val close: suspend () -> Unit)
