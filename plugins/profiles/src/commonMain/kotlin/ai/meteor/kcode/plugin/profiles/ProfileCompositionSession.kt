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
    initialBundles: List<ProfileBundle>,
    private var switchRevision: Long? = null,
) : PluginCompositionStore {
    private val mutex = Mutex()
    private var generation = initial?.generation
    private var definition = definition
    private var snapshot = initial?.composition ?: initialSnapshot
    private var bundles = initial?.bundles?.takeIf { initial.formatVersion == 2 || it.isNotEmpty() } ?: initialBundles
    private var staged: CommittedProfileGeneration? = null
    private var discarded = false
    private val original = initial

    override suspend fun load(): PluginCompositionSnapshot = mutex.withLock { snapshot }

    override suspend fun save(snapshot: PluginCompositionSnapshot) = mutex.withLock {
        publish(definition, snapshot)
    }

    /** Called as the durable publisher inside a managed runtime transaction. */
    suspend fun commitDefinition(candidate: ProfileDefinition, snapshot: PluginCompositionSnapshot,
        bundles: List<ProfileBundle> = this.bundles) = mutex.withLock {
        candidate.validate()
        require(candidate.id == definition.id) { "Switch profiles by rebuilding the runtime" }
        publish(candidate, snapshot, bundles)
    }

    private suspend fun publish(candidate: ProfileDefinition, snapshot: PluginCompositionSnapshot,
        bundles: List<ProfileBundle> = this.bundles) {
        check(!discarded) { "Prepared Profile session was discarded" }
        snapshot.validate()
        val next = CommittedProfileGeneration(
            generation = (generation ?: 0L) + 1L,
            definition = candidate,
            lock = profileLock(snapshot),
            composition = snapshot,
            bundles = bundles,
        )
        next.validate()
        withContext(NonCancellable) {
            if (switchRevision != null) staged = next else {
                repository.commit(next, generation)
                generation = next.generation
            }
            this@ProfileCompositionSession.snapshot = snapshot
            definition = candidate
            this@ProfileCompositionSession.bundles = bundles
        }
    }

    /** Publish only after candidate allocation, settling and frame preparation have succeeded. */
    suspend fun requirePreparedSwitch() = mutex.withLock {
        check(!discarded && switchRevision != null) { "Profile session is not preparing a switch" }
    }

    suspend fun publishPreparedSwitch(): CommittedProfileGeneration = mutex.withLock {
        check(!discarded) { "Prepared Profile session was discarded" }
        val revision = checkNotNull(switchRevision) { "Profile session is not preparing a switch" }
        val candidate = checkNotNull(staged) { "Target runtime has not prepared a generation" }
        withContext(NonCancellable) {
            (repository as ProfileGenerationRepository).commitAndSelect(candidate, generation, revision)
            generation = candidate.generation
            switchRevision = null
            staged = null
        }
        candidate
    }

    /** The host must close candidate resources; discarding metadata does not dispose a runtime. */
    suspend fun discardPreparedSwitch() = mutex.withLock {
        check(switchRevision != null) { "Profile session is not preparing a switch" }
        discarded = true
        staged = null
    }

    /** Reconstructed old resources resume without publishing a new generation or selection. */
    suspend fun resumePreparedRestoration() = mutex.withLock {
        check(!discarded && switchRevision != null) { "Profile session is not preparing restoration" }
        val previous = checkNotNull(original) { "Restoration requires a committed Profile" }
        val candidate = checkNotNull(staged) { "Restored runtime has not prepared a generation" }
        check(candidate.definition == previous.definition && candidate.lock == previous.lock && candidate.bundles == previous.bundles) {
            "Restoration changed locked Profile intent"
        }
        withContext(NonCancellable) {
            check(repository.loadCommitted(previous.definition.id) == previous) { "Restoration generation changed" }
            switchRevision = null
            staged = null
        }
    }

    companion object {
        suspend fun open(repository: ProfileRepository, definition: ProfileDefinition,
            initialSnapshot: PluginCompositionSnapshot = PluginCompositionSnapshot(),
            initialBundles: List<ProfileBundle> = emptyList()): ProfileCompositionSession {
            definition.validate()
            val previous = repository.loadCommitted(definition.id)
            return ProfileCompositionSession(repository, previous, previous?.definition ?: definition, initialSnapshot, initialBundles)
        }

        /** Capture expectedRevision before reading/resolving the target, not after allocation. */
        suspend fun prepareSwitch(repository: ProfileGenerationRepository, definition: ProfileDefinition,
            expectedRevision: Long,
            initialSnapshot: PluginCompositionSnapshot = PluginCompositionSnapshot(),
            initialBundles: List<ProfileBundle> = emptyList()): ProfileCompositionSession {
            definition.validate()
            require(expectedRevision >= 0) { "Invalid Profile repository revision" }
            val previous = repository.loadCommitted(definition.id)
            return ProfileCompositionSession(repository, previous, previous?.definition ?: definition, initialSnapshot, initialBundles, expectedRevision)
        }
    }
}
