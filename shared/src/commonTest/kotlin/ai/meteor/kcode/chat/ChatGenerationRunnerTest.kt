package ai.meteor.kcode.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatGenerationRunnerTest {
    @Test
    fun backgroundAllowanceSpansAllConcurrentResponses() = runTest {
        val activeChanges = mutableListOf<Boolean>()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val runner = ChatGenerationRunner(
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
        val runner = ChatGenerationRunner(
            onActiveChanged = activeChanges::add,
            scope = this,
        )

        val response = runner.launch { gate.await() }
        runner.cancelAll()
        response.join()

        assertEquals(listOf(true, false), activeChanges)
    }
}
