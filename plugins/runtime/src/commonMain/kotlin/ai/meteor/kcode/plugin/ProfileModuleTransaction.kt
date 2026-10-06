package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.validatePluginApi
import org.cordis.loader.ModuleLoader
import org.cordis.loader.ReloadTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Candidate exports stay alive until the host finishes applying or restoring its tree. */
interface ProfileModuleTransaction {
    val exports: Map<String, Any?>
    /** Called once after durable publication, under the runtime's exclusive mutation owner. */
    fun commit()
    /** Called only after old tree exports have been restored. */
    suspend fun rollback()
}

/** Shared native protocol; descriptor construction and resource release remain platform-owned. */
suspend fun prepareProfileModules(
    previous: List<DynamicPluginSpec>,
    candidate: List<DynamicPluginSpec>,
    modules: ModuleLoader,
    moduleUrl: (String) -> String,
    register: suspend (DynamicPluginSpec) -> Unit,
    release: (String) -> Unit,
    unregister: (String) -> Unit,
    forget: (String) -> Unit,
    stageSpecs: (List<DynamicPluginSpec>) -> Unit,
): ProfileModuleTransaction {
    require(candidate.map { it.id }.distinct().size == candidate.size) { "Duplicate Profile package" }
    val before = previous.associateBy { it.id }
    val after = candidate.associateBy { it.id }
    candidate.forEach { it.validatePluginApi() }
    val changed = candidate.filter { spec -> spec.copy(config = Unit, enabled = true) != before[spec.id]?.copy(config = Unit, enabled = true) }
        .mapTo(mutableSetOf()) { it.id }
    val affected = changed.toMutableSet()
    fun dependencies(spec: DynamicPluginSpec) = spec.dependencies + spec.packageInstallation?.dependencies.orEmpty().keys
    while (true) {
        val size = affected.size
        candidate.filter { dependencies(it).any(affected::contains) || before[it.id]?.let(::dependencies)?.any(affected::contains) == true }
            .mapTo(affected) { it.id }
        if (affected.size == size) break
    }
    val registered = mutableListOf<String>()
    var reload: ReloadTransaction? = null
    suspend fun restore(error: Throwable) {
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
        }
        attempt { reload?.rollback() }
        registered.asReversed().filter { it !in before }.forEach { id -> attempt { unregister(id) } }
        previous.forEach { spec -> attempt { register(spec) } }
        stageSpecs(previous)
    }
    try {
        candidate.forEach { spec -> register(spec); registered += spec.id }
        val transaction = modules.beginReload(affected.mapTo(mutableSetOf(), moduleUrl)).also { reload = it }
        val exports = candidate.associate { spec ->
            val url = moduleUrl(spec.id)
            spec.id to if (spec.id in affected) transaction.import(url) else modules.import(url, null)
        }
        stageSpecs(candidate)
        return object : ProfileModuleTransaction {
            override val exports = exports
            private var complete = false
            override fun commit() {
                check(!complete) { "Profile module transaction completed" }
                transaction.commit()
                previous.asReversed().filter { it.id !in after }.forEach { spec -> release(spec.id) }
                previous.asReversed().filter { it.id !in after }.forEach { spec ->
                    unregister(spec.id)
                    forget(moduleUrl(spec.id))
                }
                complete = true
            }
            override suspend fun rollback() = withContext(NonCancellable) {
                if (complete) return@withContext
                // Attempt every release/metadata restoration even after one fails.
                val failure = IllegalStateException("Profile module rollback failed")
                restore(failure)
                complete = true
                if (failure.suppressedExceptions.isNotEmpty()) throw failure
            }
        }
    } catch (error: Throwable) {
        withContext(NonCancellable) { restore(error) }
        throw error
    }
}
