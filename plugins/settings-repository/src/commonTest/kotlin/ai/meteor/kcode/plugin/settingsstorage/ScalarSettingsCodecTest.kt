package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScalarSettingsCodecTest {
    private fun document(encoded: String) = Json.parseToJsonElement(encoded) as JsonObject

    @Test
    fun legacySnapshotPreservesAbsenceExplicitValuesAndUnknownRootFields() {
        val access = MemoryAccess()
        val legacy = document("""{"provider":"private.gateway","language":"","temperature":0,"future":{"value":null},"modelApiKeys":{"disabled":""}}""")
        access.values["settings_snapshot.v1"] = legacy.toString()
        val migrated = ScalarSettingsCodec().load(access)
        assertEquals(legacy, migrated.legacyValues)
        assertTrue("modelId" !in migrated.legacyValues)
        assertEquals(emptyMap(), migrated.namespaces)
        access.values["settings_snapshot.v1"] = """{"provider":null}"""
        assertEquals(JsonNull, ScalarSettingsCodec().load(access).legacyValues["provider"])
    }

    @Test
    fun emptyStorageHasNoFeatureDefaultsAndScalarImportPreservesEmptyCredentials() {
        val access = MemoryAccess()
        assertEquals(StoredAppSettings(), ScalarSettingsCodec().load(access))
        access.values["web_search_api_key"] = "legacy-search"
        access.values["model_api_key.unloaded.provider"] = ""
        val migrated = ScalarSettingsCodec().load(access)
        assertEquals(document("""{"webSearchApiKey":"legacy-search","modelApiKeys":{"unloaded.provider":""}}"""), migrated.legacyValues)
        assertTrue("webSearchProvider" !in migrated.legacyValues)
    }

    @Test
    fun versionTwoCommitDoesNotRewriteHistoricalOrUnknownScalarKeys() {
        val access = MemoryAccess(mutableMapOf("model_provider" to "historical", "future_scalar" to "retain"))
        val previous = access.values.toMap()
        ScalarSettingsCodec().save(access, settings("new"))
        assertEquals(settings("new"), ScalarSettingsCodec().load(access))
        assertEquals(previous, access.values.filterKeys { it != "settings_snapshot.v2" })
        assertEquals(1, access.writes)
    }

    @Test
    fun failedSingleCommitPreservesLegacyAndCommittedDataExactlyAndAllowsRetry() {
        for (committed in listOf(false, true)) {
            for (failure in listOf("false", "throw", "cancel")) {
                val baseline = MemoryAccess(mutableMapOf("model_provider" to "legacy", "future_scalar" to "retain"))
                if (committed) ScalarSettingsCodec().save(baseline, settings("old"))
                val previous = ScalarSettingsCodec().load(baseline)
                val access = MemoryAccess(baseline.values.toMutableMap(), 1, failure)
                assertFailsWith<Exception> { ScalarSettingsCodec().save(access, settings("new")) }
                assertEquals(baseline.values, access.values)
                assertEquals(previous, ScalarSettingsCodec().load(access))
                access.failAt = null
                ScalarSettingsCodec().save(access, settings("new"))
                assertEquals(settings("new"), ScalarSettingsCodec().load(access))
            }
        }
    }

    @Test
    fun corruptVersionTwoCannotFallBackToValidHistoricalData() {
        val access = MemoryAccess(mutableMapOf("settings_snapshot.v1" to """{"provider":"legacy"}""",
            "settings_snapshot.v2" to "{broken"))
        assertFailsWith<IllegalArgumentException> { ScalarSettingsCodec().load(access) }
        access.values["settings_snapshot.v2"] = """{"namespaces":{"invalid":7}}"""
        assertFailsWith<IllegalArgumentException> { ScalarSettingsCodec().load(access) }
    }

    @Test
    fun versionOneNamespacesAndUnknownLegacyValuesSurviveCommitAndReopen() {
        val access = MemoryAccess(mutableMapOf("settings_snapshot.v1" to
            """{"namespaces":{"disabled.feature":{"secret":"fixture","future":[null,{}]}},"future.root":[null,{"key.with.dots":""}]}"""))
        val migrated = ScalarSettingsCodec().load(access)
        ScalarSettingsCodec().save(access, migrated)
        assertEquals(migrated, ScalarSettingsCodec().load(MemoryAccess(access.values)))
        assertTrue(access.values["settings_snapshot.v1"].toString().contains("future.root"))
    }

    @Test
    fun opaqueDocumentsPreserveTheLegacyFloatingPointDomain() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val access = MemoryAccess()
            val stored = StoredAppSettings(legacyValues = JsonObject(mapOf("temperature" to JsonPrimitive(value))))
            ScalarSettingsCodec().save(access, stored)
            assertEquals(stored, ScalarSettingsCodec().load(access))
        }
    }

    private fun settings(name: String) = StoredAppSettings(
        namespaces = mapOf("external.feature" to document("""{"token":"$name","future":[null,{"key.with.dots":""}]}""")),
        legacyValues = document("""{"provider":"$name","unknown":[1,false,null]}"""),
    )

    private class MemoryAccess(
        val values: MutableMap<String, Any> = mutableMapOf(),
        var failAt: Int? = null,
        private val failure: String = "false",
    ) : ScalarSettingsAccess {
        var writes = 0
        override val allKeys get() = values.keys.toList()
        override fun decodeString(key: String, default: String?) = values[key] as? String ?: default
        override fun decodeDouble(key: String, default: Double) = values[key] as? Double ?: default
        override fun encodeString(key: String, value: String) = write(key, value)
        override fun encodeDouble(key: String, value: Double) = write(key, value)
        private fun write(key: String, value: Any): Boolean {
            writes++
            if (writes == failAt) {
                when (failure) {
                    "throw" -> error("injected write failure")
                    "cancel" -> throw CancellationException("injected cancellation")
                    else -> return false
                }
            }
            values[key] = value
            return true
        }
    }
}
