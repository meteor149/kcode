package ai.meteor.kcode.plugin.settingscommands

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.plugin.api.SettingsUpdateTransform
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.searchApiKeys
import ai.meteor.kcode.test.webSearchProvider
import ai.meteor.kcode.test.temperature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegisteredSettingsUpdatesTest {
    @Test
    fun selfWithdrawalCannotPartiallyRemoveACommandRegistration() = runTest {
        val registry = RegisteredSettingsUpdates()
        lateinit var registration: org.cordis.Disposable
        var calls = 0
        registration = registry.register("self", setOf("self"), SettingsUpdateTransform { settings, _, _ ->
            calls++
            assertFailsWith<IllegalStateException> { registration.dispose() }
            assertFailsWith<IllegalStateException> { registry.close() }
            AppliedSettingsUpdate(settings, listOf("self"))
        })
        repeat(2) {
            registry.apply(StoredAppSettings(), SettingsUpdate(mapOf("self" to "test")), ModelCatalogSnapshot()) { }
        }
        assertEquals(2, calls)
        registration.dispose()
        registry.close()
    }
    @Test
    fun externalFieldRegistrationDispatchesWithoutChangingTheCommandSchema() = runTest {
        val registry = RegisteredSettingsUpdates()
        val field = "plugin.example/preferred-language"
        val registration = registry.register("plugin.example", setOf(field), SettingsUpdateTransform { settings, update, _ ->
            val document = settings.namespaces["plugin.example"].orEmpty() +
                ("preferred-language" to JsonPrimitive(update.values.getValue(field)))
            AppliedSettingsUpdate(settings.copy(namespaces = settings.namespaces +
                ("plugin.example" to JsonObject(document))), listOf(field))
        })
        val preserved = JsonObject(mapOf("future" to JsonPrimitive(7)))
        var stored = StoredAppSettings(namespaces = mapOf("disabled.plugin" to preserved,
            "plugin.example" to preserved))
        registry.apply(stored, SettingsUpdate(mapOf(field to "custom")), ModelCatalogSnapshot()) { stored = it.settings }
        assertEquals(JsonPrimitive("custom"), stored.namespaces["plugin.example"]!!["preferred-language"])
        assertEquals(preserved, stored.namespaces["disabled.plugin"])
        assertEquals(JsonPrimitive(7), stored.namespaces["plugin.example"]!!["future"])
        registration.dispose()
        assertFailsWith<IllegalArgumentException> {
            registry.apply(stored, SettingsUpdate(mapOf(field to "late")), ModelCatalogSnapshot()) { stored = it.settings }
        }
        assertEquals(JsonPrimitive("custom"), stored.namespaces["plugin.example"]!!["preferred-language"])
        assertEquals(preserved, stored.namespaces["disabled.plugin"])
        assertEquals(JsonPrimitive(7), stored.namespaces["plugin.example"]!!["future"])
        registry.close()
    }

    private val model = SettingsUpdateTransform { settings, update, _ ->
        AppliedSettingsUpdate(settings.copy(temperature = update.values["temperature"]!!.toDouble()), listOf("temperature"))
    }
    private val search = SettingsUpdateTransform { settings, update, _ ->
        AppliedSettingsUpdate(settings.copy(webSearchProvider = update.values["search-provider"]!!), listOf("search-provider"))
    }

    @Test
    fun featureWithdrawalPreservesUnrelatedCommandsAndRejectsMixedUpdatesBeforeSaving() = runTest {
        val registry = RegisteredSettingsUpdates()
        val modelRegistration = registry.register("model", setOf("temperature"), model)
        val oldSearch = registry.register("search", setOf("search-provider"), search)
        val initial = LegacySettings(searchApiKeys = mapOf("custom" to "saved"))
        var saved = initial
        try {
            oldSearch.dispose()
            registry.apply(initial, SettingsUpdate(mapOf("temperature" to "0.4")), ModelCatalogSnapshot()) { saved = it.settings }
            assertEquals(0.4, saved.temperature)
            assertEquals(initial.searchApiKeys, saved.searchApiKeys)
            val committed = saved
            assertFailsWith<IllegalArgumentException> {
                registry.apply(saved, SettingsUpdate(mapOf("temperature" to "0.8", "search-provider" to "exa")), ModelCatalogSnapshot()) {
                    saved = it.settings
                }
            }
            assertEquals(committed, saved)
            val replacement = registry.register("search", setOf("search-provider"), search)
            oldSearch.dispose()
            registry.apply(saved, SettingsUpdate(mapOf("search-provider" to "exa")), ModelCatalogSnapshot()) { saved = it.settings }
            assertEquals("exa", saved.webSearchProvider)
            replacement.dispose()
            modelRegistration.dispose()
        } finally {
            registry.close()
        }
    }

    @Test
    fun withdrawalCancelsAndJoinsTheCommitAndRetainedSnapshotsRejectWork() = runTest {
        val registry = RegisteredSettingsUpdates()
        val registration = registry.register("search", setOf("search-provider"), search)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saving = async {
            registry.apply(StoredAppSettings(), SettingsUpdate(mapOf("search-provider" to "exa")), ModelCatalogSnapshot()) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        entered.await()
        val withdrawal = async { registration.dispose() }
        cleaning.await()
        assertFalse(withdrawal.isCompleted)
        assertFailsWith<IllegalArgumentException> {
            registry.apply(StoredAppSettings(), SettingsUpdate(mapOf("search-provider" to "exa")), ModelCatalogSnapshot()) { }
        }
        release.complete(Unit)
        withdrawal.await()
        assertTrue(saving.isCancelled)
        registry.close()
    }

    @Test
    fun failedPersistenceLeavesTheContributionAvailableAndDuplicatesAreRejected() = runTest {
        val registry = RegisteredSettingsUpdates()
        registry.register("model", setOf("temperature"), model)
        try {
            assertFailsWith<IllegalArgumentException> { registry.register("other", setOf("temperature"), model) }
            assertFailsWith<IllegalStateException> {
                registry.apply(StoredAppSettings(), SettingsUpdate(mapOf("temperature" to "0.4")), ModelCatalogSnapshot()) {
                    error("Persistence failed")
                }
            }
            val applied = registry.apply(StoredAppSettings(), SettingsUpdate(mapOf("temperature" to "0.6")), ModelCatalogSnapshot()) { }
            assertEquals(0.6, applied.settings.temperature)
        } finally {
            registry.close()
        }
        assertFailsWith<IllegalStateException> { registry.register("late", setOf("temperature"), model) }
    }
}
