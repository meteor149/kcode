package ai.meteor.kcode.plugin.contributions

import ai.meteor.kcode.plugin.api.KcodeUiContributions
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.UiContributionSource
import ai.meteor.kcode.plugin.api.UiContributionsSnapshot
import ai.meteor.kcode.plugin.api.UiSlotKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Disposable

internal class OwnedUiContributions(ctx: Context) : KcodeUiContributions(ctx) {

    private class Registration(id: String, val source: UiContributionSource<*>) {
        val owner = PluginOperationOwner("UI contribution '$id'")
    }
    private val owner = PluginOperationOwner("UI contributions")
    private val mutex = Mutex()
    private val registrations = mutableMapOf<String, Registration>()

    override suspend fun <T : Any> registerProjection(key: UiSlotKey<T>, source: UiContributionSource<T>): Disposable = owner.run {
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

    override suspend fun snapshot(): UiContributionsSnapshot = owner.run {
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
    override suspend fun <T : Any> snapshot(key: UiSlotKey<T>): T? = owner.run {
        val registration = mutex.withLock { registrations[key.id] } ?: return@run null
        registration.owner.run { registration.source.snapshot() as T }
    }

    override suspend fun close() {
        owner.close()
        withContext(NonCancellable) {
            val retired = mutex.withLock {
                registrations.values.toList().also { registrations.clear() }
            }
            retired.forEach { it.owner.close() }
        }
    }
}
