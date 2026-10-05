package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.provider
import ai.meteor.kcode.test.modelId
import ai.meteor.kcode.test.searchApiKeys
import ai.meteor.kcode.test.language
import ai.meteor.kcode.settings.withTransactions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ApplicationSettingsSessionTest {
    @Test
    fun failedSaveKeepsTheExecutionConfigurationAndPlatformModeUnchanged() = runTest {
        val owner = PluginOperationOwner("settings")
        val session = ApplicationSettingsSession(object : TestSettingsStore() {
            override suspend fun save(settings: StoredAppSettings) { error("disk unavailable") }
        }.withTransactions(owner), owner)
        val commits = mutableListOf<StoredAppSettings>()
        val original = session.committed
        val proposal = original.copy(language = "en", shellExecutionMode = "root", toolPermissionMode = "bypass")
        session.save(proposal, commits::add)
        assertEquals(proposal, session.draft)
        assertEquals(original, session.committed)
        assertTrue(commits.isEmpty())
        assertNotNull(session.failure)
        owner.close()
    }

    @Test
    fun queuedDraftsSerializeWritesAndPublishOnlyTheLatestCommittedRequest() = runTest {
        val owner = PluginOperationOwner("settings")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<StoredAppSettings>()
        val session = ApplicationSettingsSession(object : TestSettingsStore() {
            override suspend fun save(settings: StoredAppSettings) {
                writes += settings
                if (writes.size == 1) { entered.complete(Unit); release.await() }
            }
        }.withTransactions(owner), owner)
        val commits = mutableListOf<StoredAppSettings>()
        val original = session.committed
        val first = async { session.save(original.copy(language = "en"), commits::add) }
        entered.await()
        assertEquals(original, session.committed)
        assertTrue(commits.isEmpty())
        val second = async(start = CoroutineStart.UNDISPATCHED) { session.save(original.copy(language = "ja"), commits::add) }
        val latest = original.copy(language = "de")
        val third = async(start = CoroutineStart.UNDISPATCHED) { session.save(latest, commits::add) }
        release.complete(Unit)
        first.await(); second.await(); third.await()
        assertEquals(listOf("en", "de"), writes.map { it.language })
        assertEquals(listOf(latest), commits)
        assertEquals(latest, session.committed)
        owner.close()
    }

    @Test
    fun withdrawalWaitsForSaveCleanupWithoutPublishingCancelledSettings() = runTest {
        val owner = PluginOperationOwner("settings")
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = ApplicationSettingsSession(object : TestSettingsStore() {
            override suspend fun save(settings: StoredAppSettings) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }.withTransactions(owner), owner)
        val commits = mutableListOf<StoredAppSettings>()
        val original = session.committed
        val saving = async { session.save(original.copy(language = "en"), commits::add) }
        entered.await()
        val closing = async { owner.close() }
        cleaning.await()
        val closedBeforeCleanup = closing.isCompleted
        release.complete(Unit)
        closing.await(); saving.join()
        assertFalse(closedBeforeCleanup)
        assertTrue(saving.isCancelled)
        assertEquals(original, session.committed)
        assertTrue(commits.isEmpty())
        assertFailsWith<IllegalStateException> { session.save(original) }
    }

    @Test
    fun aSlowStartupReadCannotReplaceANewerCommittedChange() = runTest {
        val owner = PluginOperationOwner("settings")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = ApplicationSettingsSession(object : TestSettingsStore() {
            private var firstRead = true
            override suspend fun load(): StoredAppSettings {
                if (!firstRead) return super.load()
                firstRead = false
                entered.complete(Unit); release.await()
                return LegacySettings(language = "old")
            }
        }.withTransactions(owner), owner)
        val loading = async { session.load() }
        entered.await()
        val newest = LegacySettings(language = "en")
        session.save(newest)
        release.complete(Unit); loading.await()
        assertEquals(newest, session.committed)
        assertEquals(newest, session.draft)
        owner.close()
    }

    @Test
    fun aUiDraftPreservesCommandChangesAndIndependentCredentialEntries() = runTest {
        val owner = PluginOperationOwner("settings")
        val store = TestSettingsStore().withTransactions(owner)
        val session = ApplicationSettingsSession(store, owner)
        session.load()
        val draft = session.draft
        store.transaction {
            commit(current.copy(modelId = "command-model", searchApiKeys = mapOf("custom.provider" to "command-key")))
        }
        session.save(draft.copy(language = "en", searchApiKeys = mapOf("ui.provider" to "ui-key")))
        assertEquals("command-model", session.committed.modelId)
        assertEquals("en", session.committed.language)
        assertEquals(mapOf("custom.provider" to "command-key", "ui.provider" to "ui-key"), session.committed.searchApiKeys)
        assertEquals(session.committed, store.load())
        owner.close()
    }

    @Test
    fun anInitialFeatureDraftPreservesTheDocumentCreatedByACommand() = runTest {
        fun document(value: String) = Json.parseToJsonElement(value) as JsonObject
        val owner = PluginOperationOwner("settings")
        val store = TestSettingsStore().withTransactions(owner)
        val session = ApplicationSettingsSession(store, owner)
        session.load()
        val draft = session.draft.copy(namespaces = mapOf("feature.web-search" to document(
            """{"provider":"exa","apiKeys":{"ui.route":""}}""",
        )))
        store.transaction {
            commit(current.copy(namespaces = mapOf("feature.web-search" to document(
                """{"apiKeys":{"command.route":"saved"},"future":null}""",
            ))))
        }
        session.save(draft)
        assertEquals(document(
            """{"provider":"exa","apiKeys":{"command.route":"saved","ui.route":""},"future":null}""",
        ), session.committed.namespaces["feature.web-search"])
        assertEquals(session.committed, store.load())
        owner.close()
    }

    @Test
    fun skippingAnIntermediateWriteRetainsItsIndependentDraftFields() = runTest {
        val owner = PluginOperationOwner("settings")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val store = object : TestSettingsStore() {
            override suspend fun save(settings: StoredAppSettings) {
                if (++writes == 1) { entered.complete(Unit); release.await() }
                super.save(settings)
            }
        }.withTransactions(owner)
        val session = ApplicationSettingsSession(store, owner)
        session.load()
        val first = async { session.save(session.draft.copy(language = "en")) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            session.save(session.draft.copy(modelId = "edited-model"))
        }
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            session.save(session.draft.copy(temperature = 0.7))
        }
        release.complete(Unit)
        first.await(); second.await(); third.await()
        assertEquals(2, writes)
        assertEquals(LegacySettings(language = "en", modelId = "edited-model", temperature = 0.7), session.committed)
        assertEquals(session.committed, store.load())
        owner.close()
    }
}

private open class TestSettingsStore : AppSettingsStore {
    private var stored = StoredAppSettings()
    override val protection = SettingsProtection.Transient
    override suspend fun load() = stored
    override suspend fun save(settings: StoredAppSettings) { stored = settings }
}
