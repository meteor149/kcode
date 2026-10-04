package ai.meteor.kcode.plugin.nativeexecution

import ai.meteor.kcode.AgentShellExecutor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Own allocation, blocking wait, output collection and exit cleanup as one foreground operation. */
internal suspend fun executeAndroidProcess(start: () -> Process): AgentShellExecutor.ExecutionResult = coroutineScope {
    var allocated: Process? = null
    try {
        val process = runInterruptible(Dispatchers.IO) { start().also { allocated = it } }
        val output = async(Dispatchers.IO) {
            try {
                process.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val available = input.available()
                        if (available > 0) {
                            val count = input.read(buffer, 0, minOf(available, buffer.size))
                            if (count < 0) break
                            bytes.write(buffer, 0, count)
                        } else if (!process.isAlive) {
                            break
                        } else {
                            delay(10)
                        }
                    }
                    bytes.toByteArray().decodeToString()
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
        val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
        AgentShellExecutor.ExecutionResult(output.await(), exitCode)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            allocated?.let { process ->
                try {
                    if (process.isAlive) process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "Android shell process did not exit during cleanup" }
                } finally {
                    process.inputStream.close()
                    process.outputStream.close()
                    process.errorStream.close()
                }
            }
        }
    }
}

/** Cancellation must reach the remote service before waiting for a blocking Binder call to unwind. */
internal suspend fun <T> executePrivilegedShellCall(
    cancelRemote: () -> Unit,
    prepareRemote: () -> Unit = {},
    finishRemote: () -> Unit = {},
    execute: () -> T,
): T = coroutineScope {
    try {
        // Registration must survive caller cancellation so the finalizer can cancel the same request.
        withContext(NonCancellable + Dispatchers.IO) { prepareRemote() }
        currentCoroutineContext().ensureActive()
        val invocation = async(Dispatchers.IO) { execute() }
        try {
            while (!invocation.isCompleted) delay(25)
            invocation.await()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (!invocation.isCompleted) cancelRemote()
                } finally {
                    invocation.join()
                }
            }
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { finishRemote() }
    }
}
