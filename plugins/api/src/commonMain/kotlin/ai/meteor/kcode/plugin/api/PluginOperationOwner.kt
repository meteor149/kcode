package ai.meteor.kcode.plugin.api

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns provider calls while preserving caller cancellation and joining teardown cleanup. */
class PluginOperationOwner(private val name: String) {
    private class ActiveCalls(val owners: Set<PluginOperationOwner>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ActiveCalls>
    }

    companion object {
        /** Tree mutations must originate outside provider callbacks, before switching cleanup contexts. */
        suspend fun requireOutsideCall() {
            check(currentCoroutineContext()[ActiveCalls] == null) {
                "plugin runtime cannot be changed or closed from an active provider call"
            }
        }
    }

    private suspend fun activeCalls(): ActiveCalls =
        ActiveCalls(currentCoroutineContext()[ActiveCalls]?.owners.orEmpty() + this)

    private val live = MutableStateFlow(true)
    private val mutex = Mutex()
    private class Operation(
        var executingJob: Job? = null,
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
    )
    private val operations = mutableMapOf<Job, Operation>()
    private val cleanupFailures = mutableListOf<Throwable>()

    fun requireOpen() = check(live.value) { "$name has been disposed" }

    suspend fun <T> run(block: suspend () -> T): T {
        val calls = activeCalls()
        val parent = currentCoroutineContext()[Job]
        val operation = mutex.withLock {
            requireOpen()
            Job(parent).also { operations[it] = Operation() }
        }
        return execute(operation, calls, block)
    }

    /** Late completion observers quietly withdraw; already admitted work is cancelled and joined. */
    suspend fun <T> runIfOpen(block: suspend () -> T): T? {
        val calls = activeCalls()
        val parent = currentCoroutineContext()[Job]
        val operation = mutex.withLock {
            if (!live.value) return null
            Job(parent).also { operations[it] = Operation() }
        }
        return execute(operation, calls, block)
    }

    private suspend fun <T> execute(
        operation: CompletableJob,
        calls: ActiveCalls,
        block: suspend () -> T,
    ): T {
        var failure: Throwable? = null
        try {
            return withContext(operation + calls) {
                mutex.withLock { operations[operation]?.executingJob = currentCoroutineContext()[Job] }
                block()
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            finish(operation, failure)
        }
    }

    /** Bounded acquisition transfers ownership only on success; cancellation joins discarded cleanup. */
    suspend fun <T : Any> acquire(
        acquire: suspend () -> T,
        discard: suspend (T) -> Unit,
    ): T {
        val calls = activeCalls()
        val parent = currentCoroutineContext()[Job]
        val operation = mutex.withLock {
            requireOpen()
            Job(parent).also { operations[it] = Operation() }
        }
        var retained: T? = null
        try {
            val resource = withContext(operation + calls) {
                mutex.withLock { operations[operation]?.executingJob = currentCoroutineContext()[Job] }
                val allocated = withContext(NonCancellable) {
                    mutex.withLock { operations[operation]?.executingJob = currentCoroutineContext()[Job] }
                    acquire().also { retained = it }
                }
                mutex.withLock { operations[operation]?.executingJob = currentCoroutineContext()[Job] }
                currentCoroutineContext().ensureActive()
                allocated
            }
            retained = null
            return resource
        } finally {
            withContext(NonCancellable + calls) {
                mutex.withLock { operations[operation]?.executingJob = currentCoroutineContext()[Job] }
                var failure: Throwable? = null
                try {
                    retained?.let { discard(it) }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    finish(operation, failure, discardedResource = true)
                }
            }
        }
    }

    private suspend fun finish(
        operation: CompletableJob,
        failure: Throwable?,
        discardedResource: Boolean = false,
    ) = withContext(NonCancellable) {
        operation.complete()
        mutex.withLock {
            if ((!live.value || discardedResource) && failure != null && failure !is CancellationException) {
                cleanupFailures += failure
            }
            operations.remove(operation)?.finished?.complete(Unit)
        }
        Unit
    }

    /** Preflight before mutating provider state; active callbacks cannot dispose their own owner. */
    suspend fun requireCanClose() {
        check(this !in currentCoroutineContext()[ActiveCalls]?.owners.orEmpty()) {
            "$name cannot dispose itself during a call"
        }
        val caller = currentCoroutineContext()[Job]
        mutex.withLock {
            check(caller == null || operations.values.none { it.executingJob === caller }) {
                "$name cannot dispose itself during a call"
            }
        }
    }

    suspend fun close() {
        requireCanClose()
        val caller = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            val running = mutex.withLock {
                check(caller == null || operations.values.none { it.executingJob === caller }) { "$name cannot dispose itself during a call" }
                live.value = false
                operations.toList().also { entries -> entries.forEach { (operation, _) -> operation.cancel() } }
            }
            running.forEach { (_, operation) -> operation.finished.await() }
            val failures = mutex.withLock { cleanupFailures.toList() }
            if (failures.isNotEmpty()) {
                throw PluginCleanupException(name, failures)
            }
        }
    }
}
