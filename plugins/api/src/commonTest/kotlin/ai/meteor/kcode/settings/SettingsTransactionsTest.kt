package ai.meteor.kcode.settings

import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.provider
import ai.meteor.kcode.test.modelId
import ai.meteor.kcode.test.searchApiKeys
import ai.meteor.kcode.test.language

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTransactionsTest {
    @Test
    fun concurrentMutationsReadThePrecedingDurableCommit() = runTest {
        val owner = PluginOperationOwner("settings")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : MemoryStore() {
            override suspend fun save(settings: StoredAppSettings) {
                if (settings.modelId == "model" && settings.language.isEmpty()) {
                    entered.complete(Unit)
                    release.await()
                }
                super.save(settings)
            }
        }
        val store = backend.withTransactions(owner)
        val first = async { store.transaction { commit(current.copy(modelId = "model")) } }
        entered.await()
        var secondEntered = false
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            store.transaction { secondEntered = true; commit(current.copy(language = "en")) }
        }
        assertFalse(secondEntered)
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(LegacySettings(modelId = "model", language = "en"), store.load())
        owner.close()
    }

    @Test
    fun withdrawalCancelsValidationAndJoinsCleanupBeforeReturning() = runTest {
        val owner = PluginOperationOwner("settings")
        val backend = MemoryStore()
        val store = backend.withTransactions(owner)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val validating = async {
            store.transaction {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        entered.await()
        val closing = async { owner.close() }
        cleaning.await()
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        closing.await(); validating.join()
        assertTrue(validating.isCancelled)
        assertEquals(StoredAppSettings(), backend.load())
        assertFailsWith<IllegalStateException> { store.transaction { commit(current) } }
    }

    @Test
    fun retainedCommitsAndReentrantWritesCannotEscapeTheirTransaction() = runTest {
        val owner = PluginOperationOwner("settings")
        val store = MemoryStore().withTransactions(owner)
        lateinit var retained: SettingsTransaction
        store.transaction {
            retained = this
            assertFailsWith<IllegalStateException> { store.save(current) }
            commit(current.copy(language = "en"))
            assertFailsWith<IllegalStateException> { commit(current.copy(language = "de")) }
        }
        store.transaction {
            assertFailsWith<IllegalStateException> { retained.commit(current.copy(language = "de")) }
        }
        assertEquals("en", store.load().language)
        owner.close()
    }

    @Test
    fun patchesDeleteOnlyTheEditedCredentialAndPreserveUnknownProviderKeys() {
        val before = LegacySettings(searchApiKeys = mapOf("known.provider" to "old", "remove.me" to "old"))
        val patch = SettingsPatch.between(before, before.copy(searchApiKeys = mapOf("known.provider" to "new")))
        val latest = before.copy(modelId = "command", searchApiKeys = before.searchApiKeys + ("unknown.provider" to "saved"))
        assertEquals(latest.copy(searchApiKeys = mapOf("known.provider" to "new", "unknown.provider" to "saved")), patch.apply(latest))
    }
}

private open class MemoryStore : AppSettingsStore {
    private var stored = StoredAppSettings()
    override val protection = SettingsProtection.Transient
    override suspend fun load() = stored
    override suspend fun save(settings: StoredAppSettings) { stored = settings }
}
