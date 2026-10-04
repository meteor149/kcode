package ai.meteor.kcode.plugin.api.harness

import org.cordis.Context
import org.cordis.Disposable
import org.cordis.Service
import org.cordis.ServiceKey

/** Registry-minted id; parsing an id never grants authority. Names are owner-local display metadata. */
@JvmInline
value class HarnessTerminalSessionId(val value: String) {
    init { require(value.isNotBlank()) { "Terminal session id must not be blank" } }
}

enum class HarnessTerminalErrorCode(val wireValue: String) {
    DuplicateBackend("DUPLICATE_BACKEND"),
    DuplicateName("DUPLICATE_NAME"),
    ForeignSession("FOREIGN_SESSION"),
    NoBackend("NO_BACKEND"),
    NoSession("NO_SESSION"),
    OwnerNotLive("OWNER_NOT_LIVE"),
    SendActive("SEND_ACTIVE"),
    ServiceDisposing("SERVICE_DISPOSING"),
}
class HarnessTerminalException(val code: HarnessTerminalErrorCode, message: String) : IllegalStateException(message)

/** Retain both facts even when the caller receives its original cancellation reason. */
class HarnessTerminalBackendCleanupException(
    val spawnFailure: Throwable,
    val cleanupFailure: Throwable,
) : IllegalStateException("Terminal startup and cleanup both failed", spawnFailure) {
    init { addSuppressed(cleanupFailure) }
}

enum class HarnessTerminalWaitReason { StdinRead, InferredIdle, Timeout, SessionExit }
sealed interface HarnessTerminalSessionStatus {
    data object Running : HarnessTerminalSessionStatus
    data class Exited(val exitCode: Int?, val signal: String?) : HarnessTerminalSessionStatus
}
data class HarnessTerminalSpawnRequest(val type: String, val name: String? = null, val cwd: String? = null)
data class HarnessTerminalBackendSpawnSpec(
    val sessionId: HarnessTerminalSessionId,
    val owner: HarnessAgentOwner,
    val request: HarnessTerminalSpawnRequest,
    /** Combines caller allocation cancellation with service/owner unpublished-setup cancellation. */
    val signal: HarnessCancellationSignal,
)
data class HarnessTerminalSendRequest(
    val text: String,
    val submit: Boolean,
    /** Cancel the wait and interrupt the foreground command; it does not close the session. */
    val signal: HarnessCancellationSignal? = null,
)
data class HarnessTerminalSendRead(val delta: String, val truncated: Boolean)
data class HarnessTerminalSendResult(
    val viewport: String,
    val waitReason: HarnessTerminalWaitReason,
    val sessionStatus: HarnessTerminalSessionStatus,
    val truncated: Boolean,
)
interface HarnessTerminalSendOperation {
    /** Readiness/timeout/cancellation/top-level exit result; wait reason and session status are independent. */
    suspend fun done(): HarnessTerminalSendResult
    fun readOutput(): HarnessTerminalSendRead
    /** Request SIGINT; returns false after operation settlement. */
    fun cancel(): Boolean
}
data class HarnessTerminalReadRequest(val offset: Int? = null, val count: Int? = null)
data class HarnessTerminalReadResult(val text: String, val totalLines: Int, val lineBegin: Int, val lineEnd: Int, val truncated: Boolean)
/** Construct only after actual delivery to a verified foreground group. */
data class HarnessTerminalSignalResult(val targetProcessGroupId: Long)
data class HarnessTerminalSessionSnapshot(
    val sessionId: HarnessTerminalSessionId,
    val name: String?,
    val type: String,
    val pid: Long?,
    val status: HarnessTerminalSessionStatus,
)
data class HarnessTerminalSpawnResult(val session: HarnessTerminalSessionSnapshot, val motd: String)

interface HarnessTerminalBackendSession {
    val motd: String
    val pid: Long?
    fun startSend(request: HarnessTerminalSendRequest): HarnessTerminalSendOperation
    fun read(request: HarnessTerminalReadRequest): HarnessTerminalReadResult
    suspend fun signal(signal: HarnessTerminalSignal): HarnessTerminalSignalResult
    fun status(): HarnessTerminalSessionStatus
    /** Idempotent awaited tree cleanup. Failed close clears its fence so a later close can retry. */
    suspend fun close(reason: String)
}
interface HarnessTerminalBackend {
    val type: String
    /** Unpublished setup. Every failed/cancelled allocation rolls back partial resources; cleanup
     * failure uses HarnessTerminalBackendCleanupException. Caller cancellation preserves exact reason. */
    suspend fun spawn(spec: HarnessTerminalBackendSpawnSpec): HarnessTerminalBackendSession
}

/**
 * Reserved ctx.terminals. Every operation authorizes the exact currently live owner object, not
 * merely a matching id/session/path. Session publication commits once after setup and liveness
 * revalidation. One session permits one live send; reads/signals may observe that send.
 * Owner/service disposal cancels unpublished setup, awaits its rollback and published tree close.
 * Cleanup failures remain owner activity until consumed/reported by disposal; never claim quiescence.
 * Caller cancellation retains its exact reason, while lifecycle rollback failures reject disposal
 * and the affected pending spawn. Captured close attempts cannot clear a newer close's fence.
 */
interface HarnessTerminalSessionService {
    fun registerBackend(backend: HarnessTerminalBackend): Disposable
    fun listBackends(): List<String>
    suspend fun spawn(owner: HarnessAgentOwner, request: HarnessTerminalSpawnRequest, signal: HarnessCancellationSignal? = null): HarnessTerminalSpawnResult
    /** Includes unpublished setup, cleanup failure and closing work, not just published sessions. */
    fun hasOwnerActivity(owner: HarnessAgentOwner): Boolean
    fun startSend(owner: HarnessAgentOwner, id: HarnessTerminalSessionId, request: HarnessTerminalSendRequest): HarnessTerminalSendOperation
    fun read(owner: HarnessAgentOwner, id: HarnessTerminalSessionId, request: HarnessTerminalReadRequest): HarnessTerminalReadResult
    suspend fun signal(owner: HarnessAgentOwner, id: HarnessTerminalSessionId, signal: HarnessTerminalSignal): HarnessTerminalSignalResult
    /** Await quiescence; false for an already absent session, foreign-session access still fails. */
    suspend fun kill(owner: HarnessAgentOwner, id: HarnessTerminalSessionId, reason: String): Boolean
    fun list(owner: HarnessAgentOwner): List<HarnessTerminalSessionSnapshot>
    suspend fun close()
}

class KcodeTerminals(ctx: Context, val sessions: HarnessTerminalSessionService) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeTerminals>("terminals") }
}
