package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomProviderSettingsStoreTest {
    private fun document(encoded: String) = Json.parseToJsonElement(encoded) as JsonObject

    @Test
    fun arbitraryDocumentsAreProtectedAsOneCommitWithoutScalarFanout(): Unit = fixture { storage ->
        var rejectProtection = false
        val store = DataStoreAppSettingsStore(storage, SecretCodec {
            check(!rejectProtection) { "injected protection failure" }
            "protected:$it"
        }, SecretCodec { it.removePrefix("protected:") }, SettingsProtection.DesktopAppData)
        val saved = StoredAppSettings(namespaces = mapOf("external.feature" to document(
            """{"credential":"fixture","future":[null,{"key.with.dots":""}]}""")),
            legacyValues = document("""{"unknown.root":[false,null]}"""))
        assertEquals(StoredAppSettings(), store.load())
        store.save(saved)
        val committed = storage.data.first()
        assertEquals(setOf("settings_snapshot.v2"), committed.asMap().keys.map { it.name }.toSet())
        assertTrue(requireNotNull(committed[stringPreferencesKey("settings_snapshot.v2")]).startsWith("protected:"))
        val reopened = DataStoreAppSettingsStore(storage, SecretCodec { "protected:$it" },
            SecretCodec { it.removePrefix("protected:") }, SettingsProtection.DesktopAppData)
        assertEquals(saved, reopened.load())
        rejectProtection = true
        assertFailsWith<IllegalStateException> { store.save(StoredAppSettings()) }
        assertEquals(committed, storage.data.first())
        assertEquals(saved, reopened.load())
    }

    @Test
    fun historicalScalarsAndSnapshotsImportWithoutDefaultsOrUnknownDataLoss(): Unit = fixture { storage ->
        val store = DataStoreAppSettingsStore(storage, SecretCodec { "protected:$it" },
            SecretCodec { it.removePrefix("protected:") }, SettingsProtection.DesktopAppData)
        storage.edit {
            it[stringPreferencesKey("model_provider")] = ""
            it[stringPreferencesKey("model_api_key.unloaded:v2")] = "protected:"
            it[stringPreferencesKey("search_api_key.custom.route")] = "protected:search-fixture"
            it[stringPreferencesKey("shell_permission_mode")] = "bypass"
            it[doublePreferencesKey("temperature")] = 0.0
            it[stringPreferencesKey("future_scalar")] = "retained"
        }
        val imported = store.load()
        assertEquals(document("""{"provider":"","modelApiKeys":{"unloaded:v2":""},"searchApiKeys":{"custom.route":"search-fixture"},"toolPermissionMode":"bypass","temperature":0.0}"""), imported.legacyValues)
        assertTrue("modelId" !in imported.legacyValues)
        val oldValues = storage.data.first().asMap()
        store.save(imported)
        assertEquals(oldValues, storage.data.first().asMap().filterKeys { it.name != "settings_snapshot.v2" })
        assertEquals(imported, store.load())
        // Version one snapshots take priority over stale scalar values, including unknown root fields.
        val oldSnapshot = document("""{"language":null,"future":[null,{}],"namespaces":{"disabled":{"secret":"fixture"}}}""")
        storage.edit {
            it.remove(stringPreferencesKey("settings_snapshot.v2"))
            it[stringPreferencesKey("settings_snapshot.v1")] = "protected:$oldSnapshot"
        }
        val migrated = store.load()
        assertEquals(JsonObject(oldSnapshot - "namespaces"), migrated.legacyValues)
        assertEquals(mapOf("disabled" to document("""{"secret":"fixture"}""")), migrated.namespaces)
        store.save(migrated)
        assertEquals(migrated, store.load())
        assertEquals("retained", storage.data.first()[stringPreferencesKey("future_scalar")])
    }

    @Test
    fun corruptCommittedDocumentCannotResurrectValidOlderConfiguration(): Unit = fixture { storage ->
        val store = DataStoreAppSettingsStore(storage, SecretCodec { it }, SecretCodec { it }, SettingsProtection.DesktopAppData)
        storage.edit {
            it[stringPreferencesKey("settings_snapshot.v1")] = """{"provider":"historical"}"""
            it[stringPreferencesKey("settings_snapshot.v2")] = "{broken"
        }
        assertFailsWith<IllegalArgumentException> { store.load() }
    }

    private fun fixture(block: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("kcode-generic-settings")
        val file = directory.resolve("settings.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file.toFile() })
        try { block(storage) } finally {
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
