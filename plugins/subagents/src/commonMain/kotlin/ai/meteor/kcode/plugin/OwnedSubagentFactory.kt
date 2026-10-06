package ai.meteor.kcode.plugin

import ai.meteor.kcode.MultiAgentCoordinator
import ai.meteor.kcode.DefaultMaxAgentConcurrency
import ai.meteor.kcode.SubAgentLaunch
import ai.meteor.kcode.SubagentCoordinator
import ai.meteor.kcode.SubagentCoordinatorFactory
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.ExecutionAdmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Factory ownership includes every coordinator and its child agent jobs. */
internal class OwnedSubagentFactory(
    private val capacity: Int = DefaultMaxAgentConcurrency,
    private val admission: ExecutionAdmission? = null,
) : SubagentCoordinatorFactory {
    init { require(capacity > 0) { "Subagent capacity must be positive" } }

    override val maxConcurrency: Int? get() = capacity.takeIf { state.value.live }

    private data class State(val live: Boolean = true, val coordinators: Set<OwnedCoordinator> = emptySet())
    private val state = MutableStateFlow(State())
    private val completion = CompletableDeferred<Unit>()

    fun requireOpen() = check(state.value.live) { "Subagent provider has been disposed" }

    override fun create(
        scope: CoroutineScope,
        rootContext: String,
        runAgent: suspend (SubAgentLaunch) -> String,
        onEvent: suspend (SubAgentEvent) -> Unit,
    ): SubagentCoordinator {
        requireOpen()
        val job = SupervisorJob(scope.coroutineContext[Job])
        val ownedScope = CoroutineScope(scope.coroutineContext + job)
        val coordinator = OwnedCoordinator(
            MultiAgentCoordinator(ownedScope, rootContext, maxConcurrency = capacity, runAgent = runAgent,
                onEvent = onEvent, admission = admission),
            job,
        )
        while (true) {
            val previous = state.value
            if (!previous.live) { job.cancel(); error("Subagent provider has been disposed") }
            if (state.compareAndSet(previous, previous.copy(coordinators = previous.coordinators + coordinator))) return coordinator
        }
    }

    suspend fun close() {
        state.value.coordinators.forEach { it.requireCanShutdown() }
        withContext(NonCancellable) {
            val coordinators: Set<OwnedCoordinator>
            while (true) {
                val previous = state.value
                if (!previous.live) {
                    completion.await()
                    return@withContext
                }
                if (state.compareAndSet(previous, previous.copy(live = false))) {
                    coordinators = previous.coordinators
                    break
                }
            }
            try {
                val failures = mutableListOf<Throwable>()
                coordinators.forEach { coordinator ->
                    runCatching { coordinator.shutdown() }.exceptionOrNull()?.let(failures::add)
                }
                if (failures.isNotEmpty()) throw PluginCleanupException("Subagent provider", failures)
                completion.complete(Unit)
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
                throw error
            }
        }
    }

    private fun release(coordinator: OwnedCoordinator) {
        while (true) {
            val previous = state.value
            if (state.compareAndSet(previous, previous.copy(coordinators = previous.coordinators - coordinator))) return
        }
    }

    private inner class OwnedCoordinator(
        private val delegate: SubagentCoordinator,
        private val scopeJob: Job,
    ) : SubagentCoordinator {
        private val owner = PluginOperationOwner("Subagent coordinator")
        private val closing = MutableStateFlow(false)
        private val completion = CompletableDeferred<Unit>()
        private suspend fun <T> call(block: suspend () -> T): T = owner.run {
            requireOpen()
            if (admission == null) block() else admission.run { requireOpen(); block() }
        }
        override suspend fun spawn(callerPath: String, taskName: String, message: String, forkTurns: String?) =
            call { delegate.spawn(callerPath, taskName, message, forkTurns) }
        override suspend fun sendMessage(callerPath: String, target: String, message: String) =
            call { delegate.sendMessage(callerPath, target, message) }
        override suspend fun followupTask(callerPath: String, target: String, message: String) =
            call { delegate.followupTask(callerPath, target, message) }
        override suspend fun interrupt(callerPath: String, target: String) = call { delegate.interrupt(callerPath, target) }
        override suspend fun list(callerPath: String, pathPrefix: String?) = call { delegate.list(callerPath, pathPrefix) }
        override suspend fun waitForUpdate(callerPath: String) = call { delegate.waitForUpdate(callerPath) }
        override suspend fun drainMailbox(agentPath: String) = call { delegate.drainMailbox(agentPath) }
        override suspend fun continuationAfterRootResponse() = call { delegate.continuationAfterRootResponse() }
        override suspend fun onToolUse(agentPath: String, event: ToolUseEvent) = call { delegate.onToolUse(agentPath, event) }

        suspend fun requireCanShutdown() {
            val caller = currentCoroutineContext()[Job]
            check(caller == null || !scopeJob.contains(caller)) { "A subagent cannot dispose its own coordinator" }
            owner.requireCanClose()
        }

        override suspend fun shutdown() {
            requireCanShutdown()
            withContext(NonCancellable) {
                if (!closing.compareAndSet(false, true)) {
                    completion.await()
                    return@withContext
                }
                try {
                    val ownerFailure = runCatching { owner.close() }.exceptionOrNull()
                    scopeJob.cancel()
                    val delegateFailure = runCatching { delegate.shutdown() }.exceptionOrNull()
                    scopeJob.join()
                    release(this@OwnedCoordinator)
                    val failures = listOfNotNull(ownerFailure, delegateFailure)
                    if (failures.isNotEmpty()) throw PluginCleanupException("Subagent coordinator", failures)
                    completion.complete(Unit)
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                    throw error
                }
            }
        }

    }
}

private fun Job.contains(target: Job): Boolean = this === target || children.any { it.contains(target) }
