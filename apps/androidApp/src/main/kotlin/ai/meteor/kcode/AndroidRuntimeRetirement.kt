package ai.meteor.kcode

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application-owned cleanup survives Activity destruction without blocking the main looper. */
internal class AndroidRuntimeRetirement {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
            Log.e("KcodeRuntime", "Runtime cleanup failed", error)
        },
    )

    fun retire(owner: AgentRuntimeOwner): Job = scope.launch { owner.close() }
}
