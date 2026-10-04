package ai.meteor.kcode.plugin.api.harness

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.cordis.Disposable

const val HarnessSessionLogFormatVersion = 0

@Serializable
enum class HarnessSessionOrigin { @SerialName("subagent") Subagent }

@Serializable
enum class HarnessRequestHeaderReason {
    @SerialName("initial") Initial,
    @SerialName("resume") Resume,
    @SerialName("change") Change,
}

@Serializable
enum class HarnessTodoStatus {
    @SerialName("pending") Pending,
    @SerialName("in_progress") InProgress,
    @SerialName("completed") Completed,
}

@Serializable
enum class HarnessMessageRole {
    @SerialName("system") System,
    @SerialName("user") User,
    @SerialName("assistant") Assistant,
}

/** Identified lossless model message; content blocks/source kinds remain open plugin-owned JSON.
 * Model sources require provider/model provenance and retain adapter-private replay state.
 * Tool results remain user-role messages with a correlated tool-result block and tool source.
 */
@Serializable
data class HarnessLoggedMessage(val id: String, val role: HarnessMessageRole, val content: List<JsonObject>, val source: JsonObject)

@Serializable
data class HarnessSessionHeader(
    val version: Int,
    val id: HarnessSessionId,
    val createdAt: Long,
    val cwd: String? = null,
    val parentSession: HarnessSessionId? = null,
    val seedLength: Long? = null,
    val origin: HarnessSessionOrigin? = null,
    val delegationDepth: Int? = null,
    val agentPreset: String? = null,
)
sealed interface HarnessSurfaceOperation {
    data object Append : HarnessSurfaceOperation
    /** Inclusive positional range of current surface nodes; both endpoints must exist. */
    data class Replace(val start: Long, val end: Long) : HarnessSurfaceOperation
}
data class HarnessSurfaceIntent(val operation: HarnessSurfaceOperation, val sourceEventSeqs: List<Long>? = null)
data class HarnessSessionEventRecord(
    val type: String,
    val seq: Long,
    val time: Long,
    val data: JsonElement,
    val surface: HarnessSurfaceIntent? = null,
    /** false encodes absence. Unknown required types refuse reconstruction; unknown ignorable types survive. */
    val ignorable: Boolean = false,
)
data class HarnessSessionSurface(val nodes: List<Long>, val replaceGeneration: Long)

/** Typed extension of the open log vocabulary. Consumers pass this key rather than an untyped cast.
 * Only the three core message-producing types are surface-eligible; plugin-added log-only types
 * supply validation through their serializer. The future provider snapshots the accepted JSON once.
 */
class HarnessSessionEventType<T : Any>(val name: String, val serializer: KSerializer<T>) {
    init { require(name.isNotBlank()) { "Session event type must not be blank" } }
}

@Serializable data class HarnessTurnStart(val turn: Long)
@Serializable data class HarnessTurnEnd(val turn: Long, val reason: JsonObject)
@Serializable data class HarnessStepBoundary(val turn: Long, val step: Long)
@Serializable data class HarnessAssistantChunk(val turn: Long, val step: Long, val chunk: JsonObject)
@Serializable data class HarnessAssistantMessage(val turn: Long, val step: Long, val message: HarnessLoggedMessage, val usage: JsonObject? = null)
@Serializable data class HarnessToolCall(val turn: Long, val step: Long, val callId: String, val name: String, val arguments: String)
@Serializable data class HarnessToolResult(val turn: Long, val step: Long, val message: HarnessLoggedMessage, val error: JsonObject? = null, val meta: JsonElement? = null)
@Serializable data class HarnessRequestHeader(val config: JsonObject, val adapterDefaults: JsonObject? = null, val system: String? = null, val tools: List<JsonObject>? = null)
@Serializable data class HarnessRequestHeaderEvent(val header: HarnessRequestHeader, val reason: HarnessRequestHeaderReason)
@Serializable data class HarnessRequestContext(val provider: String, val model: String, val contextWindow: Long? = null)
@Serializable data class HarnessTodoItem(val content: String, val status: HarnessTodoStatus)
@Serializable data class HarnessTodoWrite(val todos: List<HarnessTodoItem>)

/** Vocabulary only. Defining keys does not mount a session store, repair logs or implement projections. */
object HarnessSessionEvents {
    val TurnStart = HarnessSessionEventType("turn/start", HarnessTurnStart.serializer())
    val TurnEnd = HarnessSessionEventType("turn/end", HarnessTurnEnd.serializer())
    val StepStart = HarnessSessionEventType("step/start", HarnessStepBoundary.serializer())
    val StepEnd = HarnessSessionEventType("step/end", HarnessStepBoundary.serializer())
    val UserMessage = HarnessSessionEventType("user/message", HarnessLoggedMessage.serializer())
    val AssistantChunk = HarnessSessionEventType("assistant/chunk", HarnessAssistantChunk.serializer())
    val AssistantMessage = HarnessSessionEventType("assistant/message", HarnessAssistantMessage.serializer())
    val ToolCall = HarnessSessionEventType("tool/call", HarnessToolCall.serializer())
    val ToolResult = HarnessSessionEventType("tool/result", HarnessToolResult.serializer())
    val RequestHeader = HarnessSessionEventType("request/header", HarnessRequestHeaderEvent.serializer())
    val RequestContext = HarnessSessionEventType("request/context", HarnessRequestContext.serializer())
    val TodoWrite = HarnessSessionEventType("todo/write", HarnessTodoWrite.serializer())
    /** session/end-seed is constructor-owned, not a public append key. */
    const val EndSeed = "session/end-seed"
}

data class HarnessSessionCreateOptions(
    val seed: List<HarnessSessionEventRecord> = emptyList(),
    val metadata: HarnessSessionMetadata = HarnessSessionMetadata(),
)
data class HarnessSessionMetadata(
    val cwd: String? = null,
    val parentSession: HarnessSessionId? = null,
    val createdAt: Long? = null,
    val seedLength: Long? = null,
    val origin: HarnessSessionOrigin? = null,
    val delegationDepth: Int? = null,
    val agentPreset: String? = null,
)
/** Captured calling-fiber scope, not authority inferred from mutable session/header metadata. */
interface HarnessSessionScope {
    fun contains(session: HarnessEventSession): Boolean
    fun onDisposing(cleanup: suspend () -> Unit): Disposable
}

/**
 * Append-only source of truth. Validate/copy/freeze event data, header, cited seqs and surface changes
 * before commit. No mutable borrowed graph may enter or leave live state. Contiguous seq includes
 * chunks; JSON rejects unsupported/nonfinite numbers and negative zero before normalization.
 * Reentrant attached append rejects. Commit is synchronous and observer failure cannot undo it.
 */
interface HarnessEventSession {
    val id: HarnessSessionId
    val header: HarnessSessionHeader
    val seq: Long
    val firstLiveSeq: Long
    val events: List<HarnessSessionEventRecord>
    val surface: HarnessSessionSurface
    fun <T : Any> append(type: HarnessSessionEventType<T>, data: T, surface: HarnessSurfaceIntent? = null, ignorable: Boolean = false): HarnessSessionEventRecord
    /** Incremental surface-only projection; no raw-log fallback, ids/provenance are never reminted. */
    fun deriveMessages(): List<HarnessLoggedMessage>
    fun deriveEventMessage(event: HarnessSessionEventRecord): List<HarnessLoggedMessage>
}

/** Reservation retains one exact unpublished session. Release after publication/rollback is idempotent. */
interface HarnessSessionPreparation : Disposable { val session: HarnessEventSession }

enum class HarnessSessionErrorCode { InvalidLog, UnsupportedVersion, UnknownRequiredEvent, DuplicateId, StaleSession, AlreadyAnnounced, ReentrantAppend, ServiceDisposing }
class HarnessSessionException(val code: HarnessSessionErrorCode, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Reserved event-sourced part of ctx.sessions, beside the existing UI projection factory.
 * Create = prepare + enter + announce; scope owns detach. Concurrent same-id prepares may exist,
 * one enter wins. Returned detach is entry-bound: it cannot remove a newer same-id replacement.
 * Replacement requires complete source-node coverage; tool-result replacement changes content only.
 */
interface HarnessSessionStore {
    fun registerEventType(type: HarnessSessionEventType<*>): Disposable
    fun create(scope: HarnessSessionScope, id: HarnessSessionId? = null, options: HarnessSessionCreateOptions = HarnessSessionCreateOptions()): HarnessEventSession
    fun prepare(id: HarnessSessionId? = null, options: HarnessSessionCreateOptions = HarnessSessionCreateOptions()): HarnessSessionPreparation
    fun enter(session: HarnessEventSession, scope: HarnessSessionScope): Disposable
    /** Single creation edge. Synchronous veto rolls back with paired disposal; reentry/repeats reject.
     * Detach during this dispatch is deferred; unannounced entries emit neither lifecycle edge. */
    fun announce(session: HarnessEventSession)
    fun get(id: HarnessSessionId): HarnessEventSession?
    fun list(): List<HarnessEventSession>
    /** Inclusive completed-turn prefix, preserving lineage/seed metadata; an open-turn boundary rejects. */
    fun fork(scope: HarnessSessionScope, source: HarnessEventSession, boundary: Long? = null, childId: HarnessSessionId? = null): HarnessEventSession
    /** session/flush, parallel: start every scoped listener, await all settlements, then report failure.
     * Only the exact entered live object is accepted; detached/unpublished/stale sessions reject. */
    suspend fun flush(session: HarnessEventSession)
    /** session/created, emit: synchronous throws veto; no suspend listener can retroactively veto. */
    fun onCreated(scope: HarnessSessionScope, listener: (HarnessEventSession) -> Unit): Disposable
    /** session/disposed, emit: paired edge, listener failures contained. */
    fun onDisposed(scope: HarnessSessionScope, listener: (HarnessEventSession) -> Unit): Disposable
    /** session/event, emit: post-commit contained observers; capture listener snapshot before commit. */
    fun onEvent(scope: HarnessSessionScope, listener: (HarnessEventSession, HarnessSessionEventRecord) -> Unit): Disposable
    fun onFlush(scope: HarnessSessionScope, listener: suspend (HarnessEventSession) -> Unit): Disposable
    suspend fun close()
}
