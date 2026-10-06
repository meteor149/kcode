package ai.meteor.kcode.plugin.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ai.meteor.kcode.plugin.api.ExecutionAdmission
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatGenerationRunnerTest {
    @Test
    fun admissionAndActivityRemainOwnedUntilChildWorkSettles() = runTest {
        var admitted = 0
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T {
                admitted++
                try { return block() } finally { admitted-- }
            }
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = OwnedChatGenerationRunner(scope = this, admission = admission)
        val task = runner.launch {
            launch { entered.complete(Unit); release.await() }
        }
        entered.await()
        assertEquals(1, admitted)
        assertEquals(1, runner.activeTasks.value)
        release.complete(Unit)
        task.join()
        assertEquals(0, admitted)
        assertEquals(0, runner.activeTasks.value)
    }

    @Test
    fun rejectedAdmissionCancelsWithoutStartingWorkOrBackgroundAllowance() = runTest {
        val activeChanges = mutableListOf<Boolean>()
        var executions = 0
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T = throw CancellationException("Closed")
        }
        val runner = OwnedChatGenerationRunner(scope = this, admission = admission, onActiveChanged = activeChanges::add)
        val task = runner.launch { executions++ }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(0, executions)
        assertEquals(emptyList(), activeChanges)
        assertEquals(0, runner.activeTasks.value)
    }

    @Test
    fun backgroundAllowanceSpansAllConcurrentResponses() = runTest {
        val activeChanges = mutableListOf<Boolean>()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val runner = OwnedChatGenerationRunner(
            onActiveChanged = activeChanges::add,
            scope = this,
        )

        val first = runner.launch { firstGate.await() }
        val second = runner.launch { secondGate.await() }
        assertEquals(listOf(true), activeChanges)

        firstGate.complete(Unit)
        first.join()
        assertEquals(listOf(true), activeChanges)

        secondGate.complete(Unit)
        second.join()
        assertEquals(listOf(true, false), activeChanges)
    }

    @Test
    fun cancellingResponsesReleasesBackgroundAllowance() = runTest {
        val activeChanges = mutableListOf<Boolean>()
        val gate = CompletableDeferred<Unit>()
        val runner = OwnedChatGenerationRunner(
            onActiveChanged = activeChanges::add,
            scope = this,
        )

        val response = runner.launch { gate.await() }
        runner.cancelAll()
        response.join()

        assertEquals(listOf(true, false), activeChanges)
    }
}
