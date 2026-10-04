package ai.meteor.kcode.plugin.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UiContributionsTest {
    @Test
    fun targetedSnapshotDoesNotInvokeUnrelatedProjectionsAndAllowsNestedReads() = runTest {
        val context = Context()
        val registry = KcodeUiContributions(context)
        val key = UiSlotKey<String>("alternative.target")
        val dependency = UiSlotKey<String>("alternative.dependency")
        try {
            registry.register(dependency, "dependency")
            registry.registerProjection(key, UiContributionSource {
                "selected ${registry.snapshot(dependency)}"
            })
            registry.registerProjection(UiSlotKey<String>("alternative.unrelated"), UiContributionSource {
                error("Unrelated projection must not run")
            })
            assertEquals("selected dependency", registry.snapshot(UiSlotKey<String>(key.id)))
            assertNull(registry.snapshot(UiSlotKey<String>("alternative.absent")))
            registry.close()
            assertFailsWith<IllegalStateException> { registry.snapshot(key) }
        } finally {
            registry.close()
            context.fiber.dispose()
        }
    }

    @Test
    fun targetedSnapshotIsCancelledAndJoinedWhenItsContributionIsWithdrawn() = runTest {
        val context = Context()
        val registry = KcodeUiContributions(context)
        val key = UiSlotKey<String>("alternative.target")
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val contribution = registry.registerProjection(key, UiContributionSource {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    release.await()
                }
            }
        })
        try {
            val preparing = async { registry.snapshot(key) }
            entered.await()
            val disposal = async { contribution.dispose() }
            cleaning.await()
            assertFalse(disposal.isCompleted)
            assertNull(registry.snapshot(key))
            registry.register(key, "replacement")
            assertEquals("replacement", registry.snapshot(key))
            release.complete(Unit)
            disposal.await()
            assertEquals(true, preparing.isCancelled)
            contribution.dispose()
            assertEquals("replacement", registry.snapshot(key))
        } finally {
            release.complete(Unit)
            registry.close()
            context.fiber.dispose()
        }
    }

    @Test
    fun contributionWithdrawalJoinsItsProjectionWithoutClosingTheRegistry() = runTest {
        val context = Context()
        val registry = KcodeUiContributions(context)
        val key = UiSlotKey<String>("alternative.workspace.snapshot")
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val contribution = registry.registerProjection(key, UiContributionSource {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
        })
        try {
            val preparing = async { registry.snapshot() }
            entered.await()
            val disposal = async { contribution.dispose() }
            testScheduler.runCurrent()
            assertFalse(disposal.isCompleted)
            cleaning.await()
            assertFalse(preparing.isCompleted)
            val replacement = registry.register(key, "replacement")
            assertEquals("replacement", registry.snapshot()[key])
            release.complete(Unit)
            disposal.await()
            assertEquals(true, preparing.isCancelled)
            assertEquals("replacement", registry.snapshot()[key])
            replacement.dispose()
        } finally { release.complete(Unit); registry.close(); context.fiber.dispose() }
    }

    @Test
    fun arbitraryPluginKeysHaveDetachedSnapshotsAndExactRegistrationCleanup() = runTest {
        val context = Context()
        val registry = KcodeUiContributions(context)
        val key = UiSlotKey<String>("alternative.console.prompt")
        try {
            val original = registry.register(key, "first")
            val captured = registry.snapshot()
            assertEquals(setOf(key.id), captured.ids)
            assertFailsWith<IllegalArgumentException> { registry.register(UiSlotKey<String>(key.id), "duplicate") }
            original.dispose()
            val replacement = registry.register(UiSlotKey<String>(key.id), "second")
            original.dispose()
            assertEquals("first", captured[key])
            assertEquals("second", registry.snapshot()[key])
            replacement.dispose()
            assertEquals(emptySet(), registry.snapshot().ids)
            registry.close()
            assertFailsWith<IllegalStateException> { registry.snapshot() }
            assertFailsWith<IllegalStateException> { registry.register(key, "retired") }
        } finally { registry.close(); context.fiber.dispose() }
    }

    @Test
    fun registryDisposalCancelsAndWaitsForPreparingContributionCleanup() = runTest {
        val context = Context()
        val registry = KcodeUiContributions(context)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registry.registerProjection(UiSlotKey<String>("alternative.workspace.snapshot"), UiContributionSource {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
        })
        try {
            val preparing = async { registry.snapshot() }
            entered.await()
            val disposal = async { registry.close() }
            cleaning.await()
            assertFalse(disposal.isCompleted)
            assertFalse(preparing.isCompleted)
            release.complete(Unit)
            disposal.await()
            assertEquals(true, preparing.isCancelled)
        } finally { release.complete(Unit); registry.close(); context.fiber.dispose() }
    }
}
