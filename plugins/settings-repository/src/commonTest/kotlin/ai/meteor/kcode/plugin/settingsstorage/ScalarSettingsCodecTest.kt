package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScalarSettingsCodecTest {
    @Test
    fun partialLegacySnapshotsUseProviderDefaultsAndPreserveExplicitEmptyValues() {
        val access = MemoryAccess()
        access.values["settings_snapshot.v1"] = """{"provider":"private.gateway","language":"en"}"""
        assertEquals(defaultSettings().copy(provider = "private.gateway", language = "en"), ScalarSettingsCodec().load(access))
        access.values["settings_snapshot.v1"] = """{"provider":"","modelId":"","language":"","temperature":0}"""
        assertEquals(defaultSettings().copy(provider = "", modelId = "", language = "", temperature = 0.0),
            ScalarSettingsCodec().load(access))
        access.values["settings_snapshot.v1"] = """{"provider":null}"""
        assertFailsWith<IllegalArgumentException> { ScalarSettingsCodec().load(access) }
    }

    @Test
    fun legacyDefaultsAndEveryFieldRoundTripThroughAFreshCodec() {
        val access = MemoryAccess()
        assertEquals(defaultSettings(), ScalarSettingsCodec().load(access))
        access.values["web_search_api_key"] = "legacy-search"
        assertEquals("bright_data", ScalarSettingsCodec().load(access).webSearchProvider)
        val complete = settings("first")
        ScalarSettingsCodec().save(access, complete)
        assertEquals(complete, ScalarSettingsCodec().load(access))
        assertEquals("first", access.values["model_provider"])
        assertEquals("fixture-first", access.values["model_api_key.acme:v1"])
        ScalarSettingsCodec().save(access, complete.copy(modelApiKeys = emptyMap(), searchApiKeys = emptyMap()))
        assertEquals(emptyMap(), ScalarSettingsCodec().load(access).modelApiKeys)
        assertEquals("", access.values["model_api_key.acme:v1"])
        assertEquals(emptyMap(), ScalarSettingsCodec().load(access).searchApiKeys)
        assertEquals("", access.values["search_api_key.custom.route"])
    }

    @Test
    fun everyFailedWritePreservesTheCompleteLegacyOrCommittedSnapshotAndAllowsRetry() {
        for (committed in listOf(false, true)) {
            for (failure in listOf("false", "throw", "cancel")) {
                val baseline = MemoryAccess()
                if (committed) ScalarSettingsCodec().save(baseline, settings("old"))
                else {
                    baseline.values["model_provider"] = "legacy"
                    baseline.values["model_api_key.obsolete"] = "previous-secret"
                    baseline.values["shell_execution_mode"] = "root"
                }
                val previous = ScalarSettingsCodec().load(baseline)
                val probe = MemoryAccess(baseline.values.toMutableMap())
                ScalarSettingsCodec().save(probe, settings("new"))
                for (position in 1..probe.writes) {
                    val access = MemoryAccess(baseline.values.toMutableMap(), position, failure)
                    assertFailsWith<Exception>("$committed/$failure/$position") {
                        ScalarSettingsCodec().save(access, settings("new"))
                    }
                    assertEquals(previous, ScalarSettingsCodec().load(access), "$committed/$failure/$position")
                    access.failAt = null
                    ScalarSettingsCodec().save(access, settings("new"))
                    assertEquals(settings("new"), ScalarSettingsCodec().load(access))
                }
            }
        }
    }

    @Test
    fun failedCommitLeavesRealPartialScalarChangesInvisibleAfterReopen() {
        val access = MemoryAccess()
        val previous = settings("old")
        ScalarSettingsCodec().save(access, previous)
        val restarted = MemoryAccess(access.values, failAt = 1, failure = "commit")
        assertFailsWith<IllegalStateException> { ScalarSettingsCodec().save(restarted, settings("new")) }
        assertEquals("new", access.values["model_provider"])
        assertEquals(previous, ScalarSettingsCodec().load(MemoryAccess(access.values)))
        assertTrue(access.values["settings_snapshot.v1"].toString().contains("old"))
    }

    @Test
    fun corruptCommittedSnapshotsFailInsteadOfReadingPartialLegacyKeys() {
        val access = MemoryAccess()
        ScalarSettingsCodec().save(access, settings("old"))
        access.values["model_provider"] = "partial"
        access.values["settings_snapshot.v1"] = "{broken"
        assertFailsWith<IllegalArgumentException> { ScalarSettingsCodec().load(access) }
    }

    @Test
    fun committedSnapshotsPreserveTheNativeScalarDoubleDomain() {
        for (temperature in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val access = MemoryAccess()
            val stored = settings("special").copy(temperature = temperature)
            ScalarSettingsCodec().save(access, stored)
            assertEquals(stored, ScalarSettingsCodec().load(access))
        }
    }

    private fun settings(name: String) = StoredAppSettings(
        provider = name, modelId = "model-$name", modelApiKeys = mapOf("acme:v1" to "fixture-$name"),
        modelEndpoint = "https://fixture.invalid/$name", modelRegion = "region-$name",
        modelDeployment = "deployment-$name", modelApiVersion = "version-$name",
        dashscopeRegion = "region-$name", webSearchApiKey = "search-$name", exaSearchApiKey = "exa-$name",
        searchApiKeys = mapOf("custom.route" to "custom-$name", "empty.route" to ""),
        webSearchProvider = "exa", temperature = 1.2, language = "en", shellExecutionMode = "adb",
        toolPermissionMode = "deny",
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
            if ((failure == "commit" && key == "settings_snapshot.v1") || (failure != "commit" && writes == failAt)) {
                when (failure) {
                    "throw", "commit" -> error("injected write failure")
                    "cancel" -> throw CancellationException("injected cancellation")
                    else -> return false
                }
            }
            values[key] = value
            return true
        }
    }
}
