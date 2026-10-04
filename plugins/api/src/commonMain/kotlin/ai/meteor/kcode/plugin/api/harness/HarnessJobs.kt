package ai.meteor.kcode.plugin.api.harness

import org.cordis.Context
import org.cordis.Disposable
import org.cordis.Service
import org.cordis.ServiceKey

@JvmInline
value class HarnessJobId(val value: String) {
    init { require(value.isNotBlank()) { "Job id must not be blank" } }
}

/** Open producer namespace; adding a kind never requires changing a kernel enum. */
@JvmInline
value class HarnessJobKind(val value: String) {
    init { require(value.isNotBlank()) { "Job kind must not be blank" } }
}

enum class HarnessJobStatus { Running, Stopping, Completed, Killed, Failed }
enum class HarnessJobTerminalStatus { Completed, Killed, Failed }
enum class HarnessJobKillResult { Requested, AlreadyFinished }
enum class HarnessJobErrorCode { InvalidRequest, UnknownJob, ForeignSession, OwnerNotLive, NoController, ServiceDisposing }
class HarnessJobException(val code: HarnessJobErrorCode, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

data class HarnessJobOutcome(val status: HarnessJobTerminalStatus, val detail: String? = null, val output: String? = null)

interface HarnessJobHooks {
    /** Synchronous idempotent request; a throw leaves registry status unchanged. */
    fun cancel(reason: String?)
    /** Returns only after producer resource cleanup. Rejections are converted to Failed records. */
    suspend fun done(): HarnessJobOutcome
    /** Null means final-output mode; stream mode has exactly one consuming cursor per job. */
    val readOutput: (() -> String)?
}

data class HarnessJobStart(
    val kind: HarnessJobKind,
    val label: String,
    val owner: HarnessAgentOwner? = null,
    val outputLimitBytes: Long? = null,
    val run: () -> HarnessJobHooks,
)

data class HarnessJobSnapshot(
    val id: HarnessJobId,
    val kind: HarnessJobKind,
    val label: String,
    val outputLimitBytes: Long?,
    val ownerSession: HarnessSessionId?,
    val status: HarnessJobStatus,
    val detail: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val reported: Boolean,
)
data class HarnessJobRead(val text: String, val snapshot: HarnessJobSnapshot)

/**
 * Reserved ctx.jobs contract, not a mounted implementation. All calls validate exact live owners;
 * owned reads are fenced by session id, while unowned jobs are open. Registration preflight checks
 * owner cleanup, positive output limits, scoped controller presence and admission before run().
 * run() is invoked once. A throw leaves no record and the producer cleans partial resources; after
 * hooks return, publication has no further failable/cancellable step. Settlement is first-wins.
 */
interface HarnessJobRegistry {
    fun start(spec: HarnessJobStart): HarnessJobId
    fun list(caller: HarnessAgentOwner? = null): List<HarnessJobSnapshot>
    fun get(id: HarnessJobId, caller: HarnessAgentOwner? = null): HarnessJobSnapshot
    /** Stream read consumes one cursor; terminal final-output read is idempotent and marks reported. */
    fun read(id: HarnessJobId, caller: HarnessAgentOwner? = null): HarnessJobRead
    /** Successful producer cancellation commits Stopping and reported; cancellation throw commits neither. */
    fun kill(id: HarnessJobId, caller: HarnessAgentOwner? = null, reason: String? = null): HarnessJobKillResult
    /** Positive bounded wait. Timeout returns a live snapshot; coroutine cancellation cancels only waiting.
     * Committed terminal delivery wins a concurrent waiter cancellation and marks reported. */
    suspend fun wait(id: HarnessJobId, timeoutMillis: Long, caller: HarnessAgentOwner? = null): HarnessJobSnapshot
    /** Contained, not awaited; one invocation per terminal record, after settlement's other observers. */
    fun onJobDone(scope: HarnessOwnerScope, listener: suspend (HarnessJobSnapshot, HarnessAgentOwner?) -> Unit): Disposable
    /** Owner-granular visible-set notification; null means every caller's set changed. No delivery claim. */
    fun onJobsChanged(scope: HarnessOwnerScope, listener: (HarnessAgentOwner?) -> Unit): Disposable
    fun attachController(name: String, scope: HarnessOwnerScope): Disposable
    /** Cancel and await compliant producers, remove records, publish emptying, reject new starts.
     * Throwing teardown cancellation may force-fail a record but must not claim producer quiescence. */
    suspend fun close()
}

class KcodeJobs(ctx: Context, val registry: HarnessJobRegistry) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeJobs>("jobs") }
}
