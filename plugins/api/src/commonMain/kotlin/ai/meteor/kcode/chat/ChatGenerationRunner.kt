package ai.meteor.kcode.chat

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow

/** Generation capability supplied by a plugin; the provider owns its task scope. */
interface ChatGenerationRunner {
    val activeTasks: StateFlow<Int>

    fun launch(block: suspend CoroutineScope.() -> Unit): Job

    fun cancelAll()

    suspend fun requireCanClose() {
        check(currentCoroutineContext()[GenerationCall]?.runner !== this) {
            "A generation task cannot retire its own runner"
        }
    }

    companion object {
        suspend fun requireOutsideCall() {
            check(currentCoroutineContext()[GenerationCall] == null) {
                "Generation tasks cannot change or retire plugin runtimes"
            }
        }
    }
}

/** Shared call identity preserves retirement guards across private plugin class loaders. */
class GenerationCall(val runner: ChatGenerationRunner) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GenerationCall>
}
