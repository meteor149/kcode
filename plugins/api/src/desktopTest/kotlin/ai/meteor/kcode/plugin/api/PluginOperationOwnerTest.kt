package ai.meteor.kcode.plugin.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest

class PluginOperationOwnerTest {
    @Test
    fun lateCompletionAfterWithdrawalDoesNotRunAndAdmittedCleanupIsJoined() = runTest {
        val owner = PluginOperationOwner("completion observer")
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        var callbacks = 0
        val callback = async {
            owner.runIfOpen {
                callbacks++
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            }
        }
        entered.await()
        val closing = async { owner.close() }
        cleanupEntered.await()
        assertFalse(closing.isCompleted)
        assertEquals(null, owner.runIfOpen { callbacks++ })
        cleanupRelease.complete(Unit)
        closing.await()
        callback.join()
        assertTrue(callback.isCancelled)
        assertEquals(null, owner.runIfOpen { callbacks++ })
        assertEquals(1, callbacks)
        assertFailsWith<IllegalStateException> { owner.run { callbacks++ } }
    }

    @Test
    fun nestedAndNonCancellableCallbacksCannotCloseTheirOuterOwner() = runTest {
        val outer = PluginOperationOwner("outer")
        val inner = PluginOperationOwner("inner")
        outer.run {
            inner.run {
                withContext(NonCancellable) {
                    assertFailsWith<IllegalStateException> { outer.requireCanClose() }
                    assertFailsWith<IllegalStateException> { outer.close() }
                    assertFailsWith<IllegalStateException> { PluginOperationOwner.requireOutsideCall() }
                }
            }
        }
        assertEquals(42, outer.run { 42 })
        PluginOperationOwner.requireOutsideCall()
        inner.close()
        outer.close()
    }

    @Test
    fun discardedResourceCallbackRetainsItsOwnerAcrossCleanupContextSwitch() = runTest {
        val owner = PluginOperationOwner("allocated")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var discarded = false
        val allocation = async {
            owner.acquire({ entered.complete(Unit); release.await(); Any() }, {
                assertFailsWith<IllegalStateException> { owner.close() }
                assertFailsWith<IllegalStateException> { PluginOperationOwner.requireOutsideCall() }
                discarded = true
            })
        }
        entered.await()
        allocation.cancel()
        release.complete(Unit)
        allocation.join()
        assertTrue(discarded)
        PluginOperationOwner.requireOutsideCall()
        owner.close()
    }

    @Test
    fun withdrawalWaitsForCancelledAcquisitionAndItsDiscardCleanup() = runTest {
        val owner = PluginOperationOwner("resource")
        val allocated = Any()
        val entered = CompletableDeferred<Unit>()
        val finishAcquire = CompletableDeferred<Unit>()
        val discardStarted = CompletableDeferred<Unit>()
        val finishDiscard = CompletableDeferred<Unit>()
        var discarded: Any? = null
        val acquiring = async {
            owner.acquire({ entered.complete(Unit); finishAcquire.await(); allocated }, {
                discardStarted.complete(Unit)
                finishDiscard.await()
                discarded = it
            })
        }
        entered.await()
        val closing = async { owner.close() }
        kotlinx.coroutines.yield()
        assertFalse(closing.isCompleted)
        finishAcquire.complete(Unit)
        discardStarted.await()
        val closedBeforeDiscard = closing.isCompleted
        finishDiscard.complete(Unit)
        closing.await()
        assertFalse(closedBeforeDiscard)
        acquiring.join()
        assertTrue(acquiring.isCancelled)
        assertSame(allocated, discarded)
    }

    @Test
    fun successfullyTransferredResourceIsOwnedByCallerAfterProviderWithdrawal() = runTest {
        val owner = PluginOperationOwner("resource")
        val resource = Any()
        var discarded = 0
        assertSame(resource, owner.acquire({ resource }, { discarded += 1 }))
        owner.close()
        assertEquals(0, discarded)
    }
    @Test
    fun withdrawalReportsFinalizerFailureAfterOtherOperationsFinish() = runTest {
        supervisorScope {
            val owner = PluginOperationOwner("resource")
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failure = IllegalStateException("native signal denied")
            val failing = async {
                owner.run {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { throw failure }
                }
            }
            val other = async {
                owner.run {
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            release.await()
                            cleaned.complete(Unit)
                        }
                    }
                }
            }
            entered.await()
            kotlinx.coroutines.yield()
            val closing = async { runCatching { owner.close() }.exceptionOrNull() }
            kotlinx.coroutines.yield()
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            val error = closing.await()!!
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === failure })
            assertTrue(cleaned.isCompleted)
            failing.join()
            other.join()
            assertTrue(generateSequence<Throwable>(assertFailsWith<IllegalStateException> { owner.close() }) { it.cause }.any { it === failure })
        }
    }

    @Test
    fun withdrawalReportsFailedDiscardWithoutLosingItsCleanupToken() = runTest {
        supervisorScope {
            val owner = PluginOperationOwner("allocation")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failure = IllegalStateException("discard failed")
            val acquiring = async {
                owner.acquire({ entered.complete(Unit); release.await(); Any() }, { throw failure })
            }
            entered.await()
            val closing = async { runCatching { owner.close() }.exceptionOrNull() }
            kotlinx.coroutines.yield()
            release.complete(Unit)
            assertTrue(generateSequence<Throwable>(closing.await()!!) { it.cause }.any { it === failure })
            acquiring.join()
        }
    }

    @Test
    fun callerCancellationDiscardFailureRemainsVisibleAtLaterWithdrawal() = runTest {
        supervisorScope {
            val owner = PluginOperationOwner("allocation")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failure = IllegalStateException("discard failed before withdrawal")
            val acquiring = async {
                owner.acquire({ entered.complete(Unit); release.await(); Any() }, { throw failure })
            }
            entered.await()
            acquiring.cancel()
            release.complete(Unit)
            acquiring.join()
            val error = assertFailsWith<IllegalStateException> { owner.close() }
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === failure })
        }
    }

}
