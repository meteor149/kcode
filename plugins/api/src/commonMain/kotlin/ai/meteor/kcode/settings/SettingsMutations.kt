package ai.meteor.kcode.settings

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Disposable

/** Feature validation; saved values outside the changed namespace remain opaque. */
fun interface SettingsMutationValidator {
    suspend fun validate(previous: StoredAppSettings, candidate: StoredAppSettings)
}

interface SettingsMutationRegistry {
    suspend fun register(namespace: String, validator: SettingsMutationValidator): Disposable
}

/** Shared infrastructure only: feature owners supply every schema and validation rule. */
internal class RegisteredSettingsMutations : SettingsMutationRegistry {
    private class Entry(val validator: SettingsMutationValidator) {
        val owner = PluginOperationOwner("Settings namespace validation")
    }

    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false

    override suspend fun register(namespace: String, validator: SettingsMutationValidator): Disposable {
        require(namespace.isNotBlank()) { "A settings namespace must have an identity" }
        val entry = Entry(validator)
        mutex.withLock {
            check(!closed) { "Settings mutations are closed" }
            require(namespace !in entries) { "Settings namespace is already owned: $namespace" }
            entries[namespace] = entry
        }
        return Disposable {
            entry.owner.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock { if (entries[namespace] === entry) entries.remove(namespace) }
                entry.owner.close()
            }
        }
    }

    suspend fun commit(previous: StoredAppSettings, candidate: StoredAppSettings, commit: suspend () -> Unit) {
        require(previous.legacyValues == candidate.legacyValues) { "Historical settings are read-only in feature mutations" }
        val selected = mutex.withLock {
            check(!closed) { "Settings mutations are closed" }
            (previous.namespaces.keys + candidate.namespaces.keys)
                .filter { previous.namespaces[it] != candidate.namespaces[it] }
                .map { namespace ->
                    requireNotNull(entries[namespace]) { "Enable the settings owner before changing: $namespace" }
                }
        }
        suspend fun validateAt(index: Int) {
            if (index == selected.size) {
                commit()
                return
            }
            val entry = selected[index]
            entry.owner.run {
                entry.validator.validate(previous, candidate)
                validateAt(index + 1)
            }
        }
        validateAt(0)
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

internal fun AppSettingsStore.withMutationValidation(registry: RegisteredSettingsMutations): AppSettingsStore {
    val delegate = this
    return object : AppSettingsStore {
        override val protection get() = delegate.protection
        override suspend fun load() = delegate.load()
        override suspend fun save(settings: StoredAppSettings) = transaction { commit(settings) }
        override suspend fun <T> transaction(block: suspend SettingsTransaction.() -> T): T = delegate.transaction {
            val transaction = this
            val live = MutableStateFlow(true)
            val commits = Mutex()
            var committed = false
            val validated = object : SettingsTransaction {
                override val current = transaction.current
                override suspend fun commit(settings: StoredAppSettings) {
                    commits.withLock {
                        check(live.value && !committed) { "Settings mutation is closed or already committed" }
                        committed = true
                        registry.commit(current, settings) { transaction.commit(settings) }
                    }
                }
            }
            try { validated.block() } finally { live.value = false }
        }
    }
}
