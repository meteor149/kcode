package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.PluginCompositionStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Adapts managed runtime publication to an atomic Profile generation. No Loader bypass. */
class ProfileCompositionSession private constructor(
    private val repository: ProfileRepository,
    initial: CommittedProfileGeneration?,
    definition: ProfileDefinition,
    initialSnapshot: PluginCompositionSnapshot,
) : PluginCompositionStore {
    private val mutex = Mutex()
    private var generation = initial?.generation
    private var definition = definition
    private var snapshot = initial?.composition ?: initialSnapshot

    override suspend fun load(): PluginCompositionSnapshot = mutex.withLock { snapshot }

    override suspend fun save(snapshot: PluginCompositionSnapshot) = mutex.withLock {
        publish(definition, snapshot)
    }

    /** Called as the durable publisher inside a managed runtime transaction. */
    suspend fun commitDefinition(candidate: ProfileDefinition, snapshot: PluginCompositionSnapshot) = mutex.withLock {
        candidate.validate()
        require(candidate.id == definition.id) { "Switch profiles by rebuilding the runtime" }
        publish(candidate, snapshot)
    }

    private suspend fun publish(candidate: ProfileDefinition, snapshot: PluginCompositionSnapshot) {
        snapshot.validate()
        val next = CommittedProfileGeneration(
            generation = (generation ?: 0L) + 1L,
            definition = candidate,
            lock = profileLock(snapshot),
            composition = snapshot,
        )
        withContext(NonCancellable) {
            repository.commit(next, generation)
            this@ProfileCompositionSession.snapshot = snapshot
            definition = candidate
            generation = next.generation
        }
    }

    companion object {
        suspend fun open(repository: ProfileRepository, definition: ProfileDefinition,
            initialSnapshot: PluginCompositionSnapshot = PluginCompositionSnapshot()): ProfileCompositionSession {
            definition.validate()
            val previous = repository.loadCommitted(definition.id)
            return ProfileCompositionSession(repository, previous, previous?.definition ?: definition, initialSnapshot)
        }
    }
}
