package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class MemoryAppSettingsStoreTest {
    @Test
    fun featureDocumentsDetachNestedCallerContainersAndReturnedSnapshots() = runTest {
        val fields = mutableMapOf("token" to JsonPrimitive("old"), "future" to JsonPrimitive(7))
        val document = JsonObject(mapOf("nested" to JsonObject(fields)))
        val namespaces = mutableMapOf("plugin.example" to document)
        val store = MemoryAppSettingsStore()
        store.save(StoredAppSettings(namespaces = namespaces))
        fields.clear()
        namespaces.clear()
        val loaded = store.load()
        assertEquals(JsonPrimitive("old"), (loaded.namespaces["plugin.example"]!!["nested"] as JsonObject)["token"])
        (loaded.namespaces as MutableMap<String, JsonObject>).clear()
        assertEquals(1, store.load().namespaces.size)
        store.close()
    }

    @Test
    fun instancesDoNotShareSettingsAndNeitherSaveNorLoadLeaksMutableKeys() = runTest {
        val first = MemoryAppSettingsStore()
        val second = MemoryAppSettingsStore()
        val keys = mutableMapOf("custom" to JsonPrimitive("original"), "other" to JsonPrimitive("retained"))
        val legacy = mutableMapOf("old.keys" to JsonObject(keys))
        first.save(StoredAppSettings(legacyValues = JsonObject(legacy)))
        keys.clear()
        legacy.clear()
        assertEquals(JsonPrimitive("original"), (first.load().legacyValues["old.keys"] as JsonObject)["custom"])
        assertEquals(2, (first.load().legacyValues["old.keys"] as JsonObject).size)
        assertEquals(defaultSettings(), second.load())
        first.close()
        assertFailsWith<IllegalStateException> { first.load() }
        assertFailsWith<IllegalStateException> { first.save(StoredAppSettings()) }
        second.close()
    }
}
