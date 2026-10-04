package ai.meteor.kcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRuntimeRetirementTest {
    @Test(timeout = 30_000)
    fun retirementSurvivesActivityScopeAndCanReturnToMainForCleanup() = runBlocking {
        val retirement = AndroidRuntimeRetirement()
        val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closedOnMain = AtomicBoolean(false)
        val task = CompletableDeferred<Job>()
        activityScope.launch {
            task.complete(retirement.retire(AgentRuntimeOwner {
                withContext(Dispatchers.IO) {
                    entered.complete(Unit)
                    release.await()
                }
                withContext(Dispatchers.Main.immediate) { closedOnMain.set(true) }
            }))
        }
        try {
            withTimeout(5_000) { entered.await() }
            activityScope.cancel()
            val cleanup = withTimeout(5_000) { task.await() }
            assertFalse(cleanup.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { cleanup.join() }
            assertTrue(closedOnMain.get())
        } finally {
            activityScope.cancel()
            release.complete(Unit)
            withTimeout(5_000) { task.await().join() }
        }
    }
}
