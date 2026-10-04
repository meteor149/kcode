package ai.meteor.kcode.plugin.api.harness

import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

const val HarnessManagedEnvironmentPrefix = "DSH_"
val HarnessSensitiveEnvironmentPattern = Regex("KEY|PASSWORD|SECRET|TOKEN", RegexOption.IGNORE_CASE)

/** Pure shared policy, not a process provider. Explicit env overlays (including null tombstones) follow. */
fun scrubHarnessParentEnvironment(environment: Map<String, String>): Map<String, String> = environment.filterKeys {
    !HarnessSensitiveEnvironmentPattern.containsMatchIn(it) && !it.startsWith(HarnessManagedEnvironmentPrefix, ignoreCase = true)
}

sealed interface HarnessStdinMode {
    data object Ignore : HarnessStdinMode
    data object Pipe : HarnessStdinMode
    data class Data(val text: String) : HarnessStdinMode
}
sealed interface HarnessOutputMode {
    data object Pipe : HarnessOutputMode
    data object Inherit : HarnessOutputMode
    data class Collect(val maxBytes: Long, val spillMaxBytes: Long?) : HarnessOutputMode
}
data class HarnessSubprocessStdio(val stdin: HarnessStdinMode, val stdout: HarnessOutputMode, val stderr: HarnessOutputMode)
data class HarnessSubprocessSpawnSpec(
    val argv: List<String>,
    val cwd: String,
    val stdio: HarnessSubprocessStdio,
    val graceMillis: Long,
    val environment: Map<String, String?>,
    /** Unlike PTY allocation cancellation, this signal also terminates a published process tree. */
    val signal: HarnessCancellationSignal? = null,
)
data class HarnessSubprocessOutcome(val exitCode: Int?, val signal: String?)
data class HarnessSubprocessOutputRead(val text: String, val nextOffset: Long, val lossy: Boolean, val spillPath: String?)
fun interface HarnessSubprocessOutputReader {
    /** Whole-stream byte offsets, non-consuming, readable after settlement. Negative offsets fail. */
    fun readFrom(fromByte: Long): HarnessSubprocessOutputRead
}
data class HarnessSubprocessCollectedOutputs(val stdout: HarnessSubprocessOutputReader?, val stderr: HarnessSubprocessOutputReader?)

interface HarnessSubprocessHandle {
    val pid: Long
    val stdin: HarnessByteSink?
    val stdout: HarnessByteSource?
    val stderr: HarnessByteSource?
    val collected: HarnessSubprocessCollectedOutputs
    /** Process-close exit facts only. Multiple waits are non-consuming; cancelling one waiter does not kill. */
    suspend fun done(): HarnessSubprocessOutcome
    /** Idempotently starts TERM -> explicit grace -> KILL escalation for the owned tree; no-op after exit. */
    fun terminate()
    /** Whole-tree liveness, never just the direct child. Cancellation stops only this wait. */
    suspend fun waitForExit()
}

enum class HarnessTerminalSignal { SIGINT, SIGTERM, SIGKILL, SIGTSTP, SIGHUP }
data class HarnessTerminalProcessSpawnSpec(
    val argv: List<String>,
    val cwd: String,
    val environment: Map<String, String>,
    val rows: Int,
    val columns: Int,
    val graceMillis: Long,
    /** Cancellation of unpublished allocation only; the returned handle owns its later lifetime. */
    val signal: HarnessCancellationSignal? = null,
)
data class HarnessTerminalForeground(val processGroupId: Long, val inputWaiting: Boolean)
interface HarnessTerminalProcessHandle {
    val pid: Long
    /** UTF-8 output in order, ending only after queued output drains on top-level process exit. */
    val output: HarnessByteSource
    /** Top-level exit facts; live transport failure rejects. Waiter cancellation does not kill the PTY. */
    suspend fun done(): HarnessSubprocessOutcome
    suspend fun write(text: String)
    suspend fun inspectForeground(): HarnessTerminalForeground?
    suspend fun signalForeground(signal: HarnessTerminalSignal): Long
    /** Await every observable captured session member and all in-flight calls; failure rejects.
     * Providers document observability limits. Ordinary pipes are not an implementation of this seam. */
    suspend fun terminate()
}

/**
 * Reserved ctx.subprocess. argv is never shell-interpreted. cwd, executable lookup and mounted fs
 * must describe the same execution world. Stdio/grace/limits/env are explicit consumer policy.
 * Collect retains a bounded tail with offset/lossy facts; a spill exceeding its bound is discarded.
 * Spawn-level failure is distinct from exit facts; this service never labels timeout/cancellation causes.
 */
interface HarnessSubprocessRuntime {
    /** Absolute paths verified, bare PATH names resolved; relative separator-containing commands rejected. */
    suspend fun resolveExecutable(command: String, environment: Map<String, String>, signal: HarnessCancellationSignal? = null): String
    fun spawn(spec: HarnessSubprocessSpawnSpec): HarnessSubprocessHandle
    suspend fun spawnTerminal(spec: HarnessTerminalProcessSpawnSpec): HarnessTerminalProcessHandle
    /** Terminates and awaits all managed trees/sessions; rejects cleanup failure and new allocations. */
    suspend fun close()
}

class KcodeSubprocess(ctx: Context, val runtime: HarnessSubprocessRuntime) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeSubprocess>("subprocess") }
}
