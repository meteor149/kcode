package ai.meteor.kcode.settings

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsMutationsTest {
    private fun document(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun setting(value: String) = StoredAppSettings(namespaces = mapOf("extension.fake" to document(value)))

    @Test
    fun changedNamespacesRequireOwnersButUnknownSavedDocumentsSurvive() = runTest {
        val backend = MutationMemoryStore(StoredAppSettings(namespaces = mapOf("disabled" to document("""{"credential":"saved","future":null}"""))))
        val owner = PluginOperationOwner("test")
        val registry = RegisteredSettingsMutations()
        val store = backend.withTransactions(owner).withMutationValidation(registry)
        var validated: StoredAppSettings? = null
        val registration = registry.register("extension.fake", SettingsMutationValidator { _, candidate -> validated = candidate })
        val candidate = backend.load().copy(namespaces = backend.load().namespaces + setting("""{"value":1}""").namespaces)
        store.save(candidate)
        assertEquals(candidate, validated)
        assertEquals(candidate, backend.load())
        registration.dispose()
        assertFailsWith<IllegalArgumentException> { store.save(candidate.copy(namespaces = candidate.namespaces - "extension.fake")) }
        assertFailsWith<IllegalArgumentException> { store.save(candidate.copy(legacyValues = document("""{"language":"en"}"""))) }
        assertEquals(candidate, backend.load())
        registry.close(); owner.close()
    }

    @Test
    fun failedValidationNeverCommitsAndOldCleanupCannotRemoveReplacement() = runTest {
        val backend = MutationMemoryStore()
        val owner = PluginOperationOwner("test")
        val registry = RegisteredSettingsMutations()
        val store = backend.withTransactions(owner).withMutationValidation(registry)
        val old = registry.register("extension.fake", SettingsMutationValidator { _, _ -> error("invalid") })
        assertFailsWith<IllegalStateException> { store.save(setting("""{"value":1}""")) }
        assertEquals(StoredAppSettings(), backend.load())
        old.dispose()
        registry.register("extension.fake", SettingsMutationValidator { _, _ -> })
        old.dispose()
        store.save(setting("""{"value":2}"""))
        assertEquals(setting("""{"value":2}"""), backend.load())
        registry.close(); owner.close()
    }

    @Test
    fun withdrawalCancelsAndJoinsValidationWithoutPublishingCandidate() = runTest {
        val backend = MutationMemoryStore()
        val owner = PluginOperationOwner("test")
        val registry = RegisteredSettingsMutations()
        val store = backend.withTransactions(owner).withMutationValidation(registry)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val registration = registry.register("extension.fake", SettingsMutationValidator { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
        })
        val writing = async { store.save(setting("""{"value":1}""")) }
        entered.await()
        val withdrawing = async { registration.dispose() }
        cleaning.await()
        assertFalse(withdrawing.isCompleted)
        release.complete(Unit)
        withdrawing.await(); writing.join()
        assertTrue(writing.isCancelled)
        assertEquals(StoredAppSettings(), backend.load())
        registry.close(); owner.close()
    }

    @Test
    fun featureLifetimeIncludesBlockedDurableSaveAndAtomicMultiFeatureValidation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val backend = object : MutationMemoryStore() {
            override suspend fun save(settings: StoredAppSettings) { entered.complete(Unit); awaitCancellation() }
        }
        val owner = PluginOperationOwner("test")
        val registry = RegisteredSettingsMutations()
        val store = backend.withTransactions(owner).withMutationValidation(registry)
        val first = registry.register("extension.fake", SettingsMutationValidator { _, _ -> })
        val second = registry.register("other", SettingsMutationValidator { _, _ -> error("invalid second feature") })
        assertFailsWith<IllegalStateException> {
            store.save(setting("""{"value":1}""").copy(namespaces = setting("""{"value":1}""").namespaces + ("other" to document("{}"))))
        }
        assertFalse(entered.isCompleted)
        second.dispose()
        val writing = async { store.save(setting("""{"value":2}""")) }
        entered.await()
        first.dispose(); writing.join()
        assertTrue(writing.isCancelled)
        assertEquals(StoredAppSettings(), backend.load())
        registry.close(); owner.close()
    }

    @Test
    fun escapedCommitAndSelfWithdrawalCannotInvokeOrRemoveValidation() = runTest {
        val backend = MutationMemoryStore()
        val owner = PluginOperationOwner("test")
        val registry = RegisteredSettingsMutations()
        val store = backend.withTransactions(owner).withMutationValidation(registry)
        var calls = 0
        lateinit var registration: org.cordis.Disposable
        registration = registry.register("extension.fake", SettingsMutationValidator { _, _ ->
            calls++
            assertFailsWith<IllegalStateException> { registration.dispose() }
            assertFailsWith<IllegalStateException> { registry.close() }
        })
        lateinit var retained: SettingsTransaction
        store.transaction { retained = this; commit(setting("""{"value":1}""")) }
        assertFailsWith<IllegalStateException> { retained.commit(setting("""{"value":2}""")) }
        assertEquals(1, calls)
        store.save(setting("""{"value":3}"""))
        assertEquals(2, calls)
        registry.close(); owner.close()
    }
}

private open class MutationMemoryStore(private var stored: StoredAppSettings = StoredAppSettings()) : AppSettingsStore {
    override val protection = SettingsProtection.Transient
    override suspend fun load() = stored
    override suspend fun save(settings: StoredAppSettings) { stored = settings }
}
