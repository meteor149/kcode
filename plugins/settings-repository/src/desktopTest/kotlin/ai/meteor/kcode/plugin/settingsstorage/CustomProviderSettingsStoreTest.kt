package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings


import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CustomProviderSettingsStoreTest {
    @Test
    fun arbitraryProviderKeysPersistEncryptedAndCanBeRemoved(): Unit = runBlocking {
        val directory = Files.createTempDirectory("kcode-provider-settings")
        val file = directory.resolve("settings.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file.toFile() })
        val store = DataStoreAppSettingsStore(storage, SecretCodec { "protected:$it" },
            SecretCodec { it.removePrefix("protected:") }, SettingsProtection.DesktopAppData)
        val settings = StoredAppSettings(provider = "acme.gateway:v1", modelId = "private-model",
            modelApiKeys = mapOf("OpenAI" to "legacy", "acme.gateway:v1" to "fixture"),
            searchApiKeys = mapOf("custom.search:v2" to "search-secret", "cleared.route" to ""))
        try {
            assertEquals(defaultSettings(), store.load())
            store.save(settings)
            assertEquals(settings, store.load())
            assertEquals("protected:fixture", storage.data.first()[stringPreferencesKey("model_api_key.acme.gateway:v1")])
            assertEquals("protected:search-secret", storage.data.first()[stringPreferencesKey("search_api_key.custom.search:v2")])
            store.save(settings.copy(modelApiKeys = emptyMap(), searchApiKeys = emptyMap()))
            assertEquals(emptyMap(), store.load().modelApiKeys)
            assertEquals(emptyMap(), store.load().searchApiKeys)
            assertFalse(storage.data.first().asMap().keys.any { it.name.startsWith("search_api_key.") })
            assertFalse(storage.data.first().asMap().keys.any { it.name.startsWith("model_api_key.") })
        } finally {
            checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
