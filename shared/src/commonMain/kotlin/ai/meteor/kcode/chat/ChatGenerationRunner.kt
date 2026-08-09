package ai.meteor.kcode.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Runs model responses independently from the lifetime of a Compose page.
 *
 * Mobile hosts use [onActiveChanged] to request the platform's background execution allowance
 * while at least one response is running.
 */
class ChatGenerationRunner(
    private val onActiveChanged: (Boolean) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private var activeTaskCount = 0

    fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            taskStarted()
            try {
                block()
            } finally {
                taskFinished()
            }
        }

    fun cancelAll() {
        scope.coroutineContext.cancelChildren()
    }

    private fun taskStarted() {
        activeTaskCount += 1
        if (activeTaskCount == 1) runCatching { onActiveChanged(true) }
    }

    private fun taskFinished() {
        activeTaskCount = (activeTaskCount - 1).coerceAtLeast(0)
        if (activeTaskCount == 0) runCatching { onActiveChanged(false) }
    }
}
