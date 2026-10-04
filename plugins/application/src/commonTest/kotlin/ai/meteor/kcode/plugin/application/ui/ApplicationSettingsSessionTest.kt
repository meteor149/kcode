package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
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
        }, owner)
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
        }, owner)
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
        }, owner)
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
            override suspend fun load(): StoredAppSettings {
                entered.complete(Unit); release.await()
                return StoredAppSettings(language = "old")
            }
        }, owner)
        val loading = async { session.load() }
        entered.await()
        val newest = StoredAppSettings(language = "en")
        session.save(newest)
        release.complete(Unit); loading.await()
        assertEquals(newest, session.committed)
        assertEquals(newest, session.draft)
        owner.close()
    }
}

private open class TestSettingsStore : AppSettingsStore {
    override val protection = SettingsProtection.Transient
    override suspend fun load() = StoredAppSettings()
    override suspend fun save(settings: StoredAppSettings) = Unit
}
