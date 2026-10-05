package ai.meteor.kcode.plugin.settingscommands

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.SettingsUpdateRegistry
import ai.meteor.kcode.plugin.api.SettingsUpdateTransform
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Disposable

internal class RegisteredSettingsUpdates : SettingsUpdateRegistry {
    private class Entry(val id: String, val fields: Set<String>, val transform: SettingsUpdateTransform) {
        val owner = PluginOperationOwner("Settings contribution $id")
    }

    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false

    override suspend fun register(id: String, fields: Set<String>, transform: SettingsUpdateTransform): Disposable {
        require(id.isNotBlank() && fields.isNotEmpty() && fields.all { it.isNotBlank() })
        val entry = Entry(id, fields.toSet(), transform)
        mutex.withLock {
            check(!closed) { "Settings commands are closed" }
            require(id !in entries && entries.values.none { it.fields.any(fields::contains) }) {
                "Settings contribution or fields are already registered"
            }
            entries[id] = entry
        }
        return Disposable {
            entry.owner.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock { if (entries[id] === entry) entries.remove(id) }
                entry.owner.close()
            }
        }
    }

    suspend fun apply(
        settings: StoredAppSettings,
        update: SettingsUpdate,
        catalog: ModelCatalogSnapshot,
        commit: suspend (AppliedSettingsUpdate) -> Unit,
    ): AppliedSettingsUpdate {
        require(!update.isEmpty) { "No settings were supplied" }
        val selected = mutex.withLock {
            check(!closed) { "Settings commands are closed" }
            val fields = update.suppliedFields.toSet()
            val supported = entries.values.flatMap { it.fields }.toSet()
            require(fields.all(supported::contains)) {
                "Enable settings feature providers before configuring: ${(fields - supported).joinToString()}"
            }
            entries.values.filter { entry -> entry.fields.any(fields::contains) }.toList()
        }
        // Nest contribution lifetimes around the entire transform/save transaction.
        suspend fun applyAt(index: Int, current: AppliedSettingsUpdate): AppliedSettingsUpdate {
            if (index == selected.size) {
                val ordered = current.copy(changedFields = (update.suppliedFields + current.changedFields).distinct())
                commit(ordered)
                return ordered
            }
            val entry = selected[index]
            return entry.owner.run {
                val applied = entry.transform.apply(current.settings, update, catalog)
                applyAt(index + 1, applied.copy(changedFields = current.changedFields + applied.changedFields))
            }
        }
        return applyAt(0, AppliedSettingsUpdate(settings, emptyList()))
    }

    suspend fun close() {
        val previous = mutex.withLock {
            entries.values.forEach { it.owner.requireCanClose() }
            closed = true
            entries.values.toList().also { entries.clear() }
        }
        var failure: Throwable? = null
        for (entry in previous) {
            try { entry.owner.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
