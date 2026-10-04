package ai.meteor.kcode.plugin.api

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.Service
import org.cordis.ServiceKey

/**
 * Plugins define their own presentation vocabulary; the kernel does not enumerate destinations.
 * The ID is the lookup identity. Its owning contract must use the same value type at every call site.
 */
class UiSlotKey<T : Any>(val id: String) {
    init { require(id.isNotBlank() && id == id.trim()) { "UI contribution id must be nonblank" } }
}

fun interface UiContributionSource<T : Any> {
    suspend fun snapshot(): T
}

/** A detached lookup of opaque values. Only the owning UI contract interprets these values. */
class UiContributionsSnapshot(values: Map<String, Any> = emptyMap()) {
    private val values = values.toMap()
    val ids: Set<String> get() = values.keys.toSet()

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: UiSlotKey<T>): T? = values[key.id] as T?
}

class KcodeUiContributions(ctx: Context) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeUiContributions>("uiContributions") }

    private class Registration(id: String, val source: UiContributionSource<*>) {
        val owner = PluginOperationOwner("UI contribution '$id'")
    }
    private val owner = PluginOperationOwner("UI contributions")
    private val mutex = Mutex()
    private val registrations = mutableMapOf<String, Registration>()

    suspend fun <T : Any> register(key: UiSlotKey<T>, value: T): Disposable =
        registerProjection(key, UiContributionSource { value })

    suspend fun <T : Any> registerProjection(key: UiSlotKey<T>, source: UiContributionSource<T>): Disposable = owner.run {
        val registration = Registration(key.id, source)
        mutex.withLock {
            require(key.id !in registrations) { "UI contribution '${key.id}' is already registered" }
            registrations[key.id] = registration
        }
        Disposable {
            registration.owner.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock {
                    if (registrations[key.id] === registration) registrations.remove(key.id)
                }
                registration.owner.close()
            }
            Unit
        }
    }

    suspend fun snapshot(): UiContributionsSnapshot = owner.run {
        val captured = mutex.withLock { registrations.toMap() }
        val values = linkedMapOf<String, Any>()
        captured.forEach { (id, registration) ->
            values[id] = registration.owner.run { registration.source.snapshot() }
        }
        UiContributionsSnapshot(values)
    }

    /**
     * Prepare only the requested contribution, without invoking unrelated providers.
     * Missing keys return null. Withdrawal cancels and joins an admitted projection as with snapshot().
     * Provider code runs outside the registry mutex, so it may read other contributions.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> snapshot(key: UiSlotKey<T>): T? = owner.run {
        val registration = mutex.withLock { registrations[key.id] } ?: return@run null
        registration.owner.run { registration.source.snapshot() as T }
    }

    suspend fun close() {
        owner.close()
        withContext(NonCancellable) {
            val retired = mutex.withLock {
                registrations.values.toList().also { registrations.clear() }
            }
            retired.forEach { it.owner.close() }
        }
    }
}
