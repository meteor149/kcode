package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.ExecutionAdmission
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One runtime owns this boundary; stale references remain closed after retirement. */
internal class ProductExecutionAdmission(initiallyPublished: Boolean = true) : ExecutionAdmission {
    private class Call(val owner: ProductExecutionAdmission) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Call>
    }

    private val mutex = Mutex()
    private val jobs = mutableMapOf<Job, Int>()
    private var published = initiallyPublished
    private var paused = false
    private var retired = false

    suspend fun requireOutsideCall() {
        check(currentCoroutineContext()[Call]?.owner !== this) { "Product work cannot change or retire its own runtime" }
    }

    override suspend fun <T> run(block: suspend () -> T): T {
        val job = checkNotNull(currentCoroutineContext()[Job])
        mutex.withLock {
            if (!published || paused || retired) throw CancellationException("Product execution admission is closed")
            currentCoroutineContext().ensureActive()
            jobs[job] = (jobs[job] ?: 0) + 1
        }
        try { return withContext(Call(this)) { block() } }
        finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val count = jobs.getValue(job) - 1
                    if (count == 0) jobs.remove(job) else jobs[job] = count
                }
            }
        }
    }

    suspend fun pause(cancelActive: Boolean) {
        requireOutsideCall()
        val active = mutex.withLock {
            check(!paused && !retired) { "Product execution admission is already closed" }
            check(cancelActive || jobs.isEmpty()) { "Finish or cancel active product work before changing composition" }
            paused = true
            jobs.keys.toList()
        }
        // Once admission closes, cancellation/join must finish before a caller can resume it.
        withContext(NonCancellable) {
            active.forEach { it.cancel() }
            active.forEach { it.join() }
        }
    }

    /** Publication is independent of temporary composition pauses. */
    suspend fun publish() = mutex.withLock {
        check(!retired) { "Product execution admission was retired" }
        published = true
    }

    suspend fun resume() = mutex.withLock {
        check(!retired) { "Product execution admission was retired" }
        paused = false
    }

    suspend fun retire() {
        requireOutsideCall()
        val active = mutex.withLock {
            retired = true
            paused = true
            jobs.keys.toList()
        }
        withContext(NonCancellable) {
            active.forEach { it.cancel() }
            active.forEach { it.join() }
        }
    }
}
