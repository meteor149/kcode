package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class OwnedSettingsStoreTest {
    @Test
    fun settingsDisposalWaitsForSaveCleanupAndRejectsStaleReads() = runTest {
        val owner = PluginOperationOwner("settings")
        val gate = CleanupGate()
        val store = OwnedSettingsStore(object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = StoredAppSettings()
            override suspend fun save(settings: StoredAppSettings) = gate.waitForCancellation()
        }, owner)
        assertEquals(SettingsProtection.Transient, store.protection)
        exerciseDisposal(owner, gate, { store.save(StoredAppSettings()) }, { store.load() })
        assertFailsWith<IllegalStateException> { store.protection }
    }

}

private class CleanupGate {
    val entered = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()

    suspend fun waitForCancellation() {
        entered.complete(Unit)
        try { awaitCancellation() } finally {
            withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
            }
        }
    }
}

private suspend fun kotlinx.coroutines.CoroutineScope.exerciseDisposal(
    owner: PluginOperationOwner,
    gate: CleanupGate,
    call: suspend () -> Unit,
    staleCall: suspend () -> Unit,
) {
    val running = async { call() }
    gate.entered.await()
    val closing = async { owner.close() }
    gate.cleanupStarted.await()
    yield()
    assertFalse(closing.isCompleted)
    assertFailsWith<IllegalStateException> { staleCall() }
    gate.releaseCleanup.complete(Unit)
    closing.await()
    running.join()
    assertTrue(running.isCancelled)
    assertFailsWith<IllegalStateException> { staleCall() }
}
