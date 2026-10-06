package ai.meteor.kcode.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductExecutionAdmissionTest {
    @Test
    fun pauseWithoutCancellationRejectsActiveWorkAndLeavesAdmissionOpen(): Unit = runBlocking {
        val gate = ProductExecutionAdmission()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val work = launch { gate.run { started.complete(Unit); finish.await() } }
        started.await()
        assertFailsWith<IllegalStateException> { gate.pause(cancelActive = false) }
        assertEquals("open", gate.run { "open" })
        finish.complete(Unit)
        work.join()
        gate.pause(cancelActive = false)
        assertFailsWith<CancellationException> { gate.run { error("Must not run") } }
        gate.resume()
        assertEquals("resumed", gate.run { "resumed" })
    }

    @Test
    fun cancellationWaitsForOwnedCleanupWhileRejectingNewWork(): Unit = runBlocking {
        val gate = ProductExecutionAdmission()
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val work = launch {
            gate.run {
                started.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
            }
        }
        started.await()
        val pause = async { gate.pause(cancelActive = true) }
        cleanup.await()
        assertFalse(pause.isCompleted)
        assertFailsWith<CancellationException> { gate.run { error("Must not run") } }
        release.complete(Unit)
        pause.await()
        assertTrue(work.isCompleted)
        gate.resume()
        gate.run { Unit }
    }

    @Test
    fun retirementRejectsStaleReferencesAndCannotBeResumed(): Unit = runBlocking {
        val gate = ProductExecutionAdmission()
        gate.retire()
        gate.retire()
        assertFailsWith<CancellationException> { gate.run { error("Must not run") } }
        assertFailsWith<IllegalStateException> { gate.resume() }
    }

    @Test
    fun nestedCallsReleaseCountsAndCannotRetireTheirOwnRuntime(): Unit = runBlocking {
        val gate = ProductExecutionAdmission()
        gate.run {
            gate.run {
                assertFailsWith<IllegalStateException> { gate.pause(cancelActive = true) }
                assertFailsWith<IllegalStateException> { gate.retire() }
            }
        }
        gate.pause(cancelActive = false)
        gate.resume()
    }

    @Test
    fun failedExecutionDoesNotLeakAdmission(): Unit = runBlocking {
        val gate = ProductExecutionAdmission()
        assertFailsWith<IllegalArgumentException> { gate.run { throw IllegalArgumentException("failure") } }
        gate.pause(cancelActive = false)
        gate.resume()
    }
}
