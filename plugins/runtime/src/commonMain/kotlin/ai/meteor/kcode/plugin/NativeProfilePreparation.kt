package ai.meteor.kcode.plugin

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Host metadata preparation allocates no product and retries failures on explicit activation. */
class NativeProfilePreparation(private val configure: suspend () -> KcodePluginRuntimeConfig) {
    data class Snapshot(val configuration: KcodePluginRuntimeConfig, val modules: Set<String>)

    private val lock = Mutex()
    private val prepared = atomic<Snapshot?>(null)

    val snapshot: Snapshot get() = checkNotNull(prepared.value) { "Native Profile catalogue is unavailable; retry preparation" }

    suspend fun prepare(): Snapshot = lock.withLock {
        prepared.value ?: run {
            val configuration = configure()
            val modules = KcodePluginRuntime.prepareProfileModuleCatalogue(configuration).associateBy { it.descriptor.id }
            // The first product consumes the definitions already validated by preflight.
            // Later products still obtain fresh definitions from the caller's factories.
            val factories = configuration.profileModuleFactories.mapValues { (id, factory) ->
                val first = atomic<KcodePluginMount?>(modules.getValue(id))
                val next: () -> KcodePluginMount = { first.getAndSet(null) ?: factory() }
                next
            }
            currentCoroutineContext().ensureActive()
            Snapshot(configuration.copy(profileModuleFactories = factories), modules.keys.toSet()).also { prepared.value = it }
        }
    }
}
