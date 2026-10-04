package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.plugin.ui.api.PersistenceFailure
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A settings draft becomes execution configuration only after durable commit. */
internal class ApplicationSettingsSession(
    private val store: AppSettingsStore,
    private val owner: PluginOperationOwner,
) {
    var committed by mutableStateOf(StoredAppSettings())
        private set
    var draft by mutableStateOf(committed)
        private set
    var failure by mutableStateOf<PersistenceFailure?>(null)
        private set
    private val writes = Mutex()
    private var revision = 0L

    suspend fun load(onCommitted: (StoredAppSettings) -> Unit = {}) {
        val startedAt = revision
        try {
            owner.run {
                val stored = store.load()
                currentCoroutineContext().ensureActive()
                if (revision == startedAt) {
                    committed = stored
                    draft = stored
                    failure = null
                    onCommitted(stored)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (revision == startedAt) failure = PersistenceFailure(true, error.message) }
    }

    suspend fun save(value: StoredAppSettings, onCommitted: (StoredAppSettings) -> Unit = {}) {
        currentCoroutineContext().ensureActive()
        owner.requireOpen()
        val requestedAt = ++revision
        draft = value
        failure = null
        try {
            owner.run {
                writes.withLock {
                    if (revision != requestedAt) return@withLock
                    store.save(value)
                    currentCoroutineContext().ensureActive()
                    if (revision == requestedAt) {
                        committed = value
                        failure = null
                        onCommitted(value)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (revision == requestedAt) failure = PersistenceFailure(false, error.message) }
    }
}
