package ai.meteor.kcode.plugin.execution

import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.GenerationCall

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
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
class OwnedChatGenerationRunner(
    private val onActiveChanged: (Boolean) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : ChatGenerationRunner {
    private val taskCount = MutableStateFlow(0)
    override val activeTasks: StateFlow<Int> = taskCount.asStateFlow()

    override fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        check(scope.coroutineContext[Job]?.isActive != false) { "Generation runner is closed" }
        return scope.launch(context = GenerationCall(this), start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().ensureActive()
            taskStarted()
            try {
                block()
            } finally {
                taskFinished()
            }
        }
    }

    override fun cancelAll() {
        scope.coroutineContext.cancelChildren()
    }

    private fun taskStarted() {
        if (taskCount.updateAndGet { it + 1 } == 1) runCatching { onActiveChanged(true) }
    }

    private fun taskFinished() {
        if (taskCount.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) runCatching { onActiveChanged(false) }
    }
}
