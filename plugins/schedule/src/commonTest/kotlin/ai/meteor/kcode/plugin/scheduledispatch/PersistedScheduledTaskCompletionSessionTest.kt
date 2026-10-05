package ai.meteor.kcode.plugin.scheduledispatch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersistedScheduledTaskCompletionSessionTest {
    @Test
    fun concurrentCompletionPersistsOnlyTheFirstResult() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        val session = PersistedScheduledTaskCompletionSession {
            entered.complete(Unit)
            release.await()
            persisted += it
        }
        val first = async { session.complete("  first  ") }
        entered.await()
        val second = async { runCatching { session.complete("second") } }
        runCurrent()
        assertNull(session.result())
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.await()
        assertEquals("first", session.result())
        assertEquals(listOf("first"), persisted)
        assertEquals(IllegalStateException::class, second.await().exceptionOrNull()!!::class)
    }

    @Test
    fun failedPersistenceDoesNotPublishCompletionAndCanBeRetried() = runTest {
        var fail = true
        val persisted = mutableListOf<String>()
        val session = PersistedScheduledTaskCompletionSession {
            if (fail) error("storage unavailable")
            persisted += it
        }
        assertFailsWith<IllegalArgumentException> { session.complete("  ") }
        assertFailsWith<IllegalStateException> { session.complete("result") }
        assertNull(session.result())
        assertEquals(emptyList(), persisted)
        fail = false
        session.complete("  result  ")
        assertEquals("result", session.result())
        assertEquals(listOf("result"), persisted)
    }
}
