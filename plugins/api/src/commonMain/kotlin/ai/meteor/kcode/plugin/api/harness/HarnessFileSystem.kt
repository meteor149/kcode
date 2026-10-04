package ai.meteor.kcode.plugin.api.harness

import kotlinx.coroutines.flow.Flow
import org.cordis.Disposable

@JvmInline
value class HarnessFsTargetKey(val value: String)
@JvmInline
value class HarnessFsVersion(val value: String)
data class HarnessFsTarget(val targetKey: HarnessFsTargetKey, val displayPath: String)
enum class HarnessFsKind { File, Directory, Other }
enum class HarnessFsPathKind { File, Directory, Symlink, Other }
data class HarnessFsInfo(val version: HarnessFsVersion, val kind: HarnessFsKind, val size: Long?)
data class HarnessFsPathInfo(val version: HarnessFsVersion, val kind: HarnessFsPathKind, val size: Long?)
data class HarnessFsDirectoryEntry(val name: String, val kind: HarnessFsKind, val target: HarnessFsTarget, val version: HarnessFsVersion?, val size: Long?)
sealed interface HarnessFsObservation {
    data class Present(val version: HarnessFsVersion) : HarnessFsObservation
    data object Absent : HarnessFsObservation
}
sealed interface HarnessFsWriteIntent {
    data object CreateIfAbsent : HarnessFsWriteIntent
    data class ReplaceIfVersion(val version: HarnessFsVersion) : HarnessFsWriteIntent
}
data class HarnessFsEditGuard(val version: HarnessFsVersion)
data class HarnessFsEditRequest(val oldString: String, val newString: String, val replaceAll: Boolean)
enum class HarnessFsWriteOperation { Create, Update }
data class HarnessFsWriteOutcome(val operation: HarnessFsWriteOperation, val version: HarnessFsVersion, val before: String?, val after: String)
data class HarnessFsEditOutcome(val version: HarnessFsVersion, val before: String, val after: String)
enum class HarnessSandboxMode(val wireValue: String) {
    ReadOnly("read-only"), WorkspaceWrite("workspace-write"), DangerFullAccess("danger-full-access"),
}
data class HarnessSandboxExecutionPolicy(val mode: HarnessSandboxMode, val workspaceRoot: String, val sessionId: HarnessSessionId? = null)
enum class HarnessFsErrorCode(val wireValue: String) {
    NotFound("FS_NOT_FOUND"), NotDirectory("FS_NOT_DIRECTORY"), NotText("FS_NOT_TEXT"),
    NotRegularFile("FS_NOT_REGULAR_FILE"), TooLarge("FS_TOO_LARGE"), PermissionDenied("FS_PERMISSION_DENIED"),
    SandboxDenied("FS_SANDBOX_DENIED"), IoError("FS_IO_ERROR"), StaleVersion("FS_STALE_VERSION"),
    NotObserved("FS_NOT_OBSERVED"), AmbiguousEdit("FS_AMBIGUOUS_EDIT"), EditNotFound("FS_EDIT_NOT_FOUND"), Aborted("FS_ABORTED"),
}
class HarnessFsException(val code: HarnessFsErrorCode, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Reserved full ctx.fs semantics, optional beside KcodeFileSystem.backend. Target keys and versions
 * are opaque, aliases share identity, process paths/file URIs belong to the provider's execution world.
 * Regular-file text reads and literal edits reject binary/NUL/non-UTF8 content. New writes are text;
 * a previous binary/oversized value may have no contextual before basis. No model windowing or policy.
 * Every mutation remains atomic even with no version guard. Guard validation precedes literal matching.
 */
interface HarnessFileSystem {
    val sandboxMode: HarnessSandboxMode?
    suspend fun resolve(path: String, cwd: String? = null, signal: HarnessCancellationSignal? = null): HarnessFsTarget
    fun processPath(target: HarnessFsTarget): String
    fun fileUrl(target: HarnessFsTarget): String
    fun contains(parent: HarnessFsTarget, child: HarnessFsTarget): Boolean
    suspend fun stat(target: HarnessFsTarget, signal: HarnessCancellationSignal? = null): HarnessFsInfo?
    suspend fun lstat(path: String, cwd: String? = null, signal: HarnessCancellationSignal? = null): HarnessFsPathInfo?
    suspend fun readText(target: HarnessFsTarget, signal: HarnessCancellationSignal? = null): String
    /** Backend owns incremental UTF-8 decoding; consumer owns any stream byte ceiling. */
    fun streamText(target: HarnessFsTarget, signal: HarnessCancellationSignal? = null): Flow<String>
    /** Complete bounded bytes; overflow is TooLarge, never a truncated successful result. */
    suspend fun readBytes(target: HarnessFsTarget, maxBytes: Long, signal: HarnessCancellationSignal? = null): ByteArray
    /** Stable name order, direct children and metadata only; child permission/IO errors fail listing. */
    suspend fun listDir(target: HarnessFsTarget, signal: HarnessCancellationSignal? = null): List<HarnessFsDirectoryEntry>
    /** CreateIfAbsent uses no-replace publication; a racing creator is preserved. */
    suspend fun writeText(target: HarnessFsTarget, content: String, expected: HarnessFsWriteIntent? = null, signal: HarnessCancellationSignal? = null, sandboxPolicy: HarnessSandboxExecutionPolicy? = null): HarnessFsWriteOutcome
    /** Missing target is StaleVersion even without a guard. Match + rewrite share one critical section. */
    suspend fun editText(target: HarnessFsTarget, edit: HarnessFsEditRequest, expected: HarnessFsEditGuard? = null, signal: HarnessCancellationSignal? = null, sandboxPolicy: HarnessSandboxExecutionPolicy? = null): HarnessFsEditOutcome
}

/** Typed reservation for the three filesystem Cordis events. Actor is opaque identity, not a path or id.
 * A future tool emits them and an observation policy listens; the file backend owns neither policy.
 * Registration order is deterministic and registrations are collected by the calling plugin effect.
 */
interface HarnessFileObservationEvents {
    /** fs/write-intent, waterfall: next delegates; terminal default is an unconditional null intent. */
    suspend fun writeIntent(target: HarnessFsTarget, actor: Any?): HarnessFsWriteIntent?
    /** fs/edit-intent, waterfall: first decision owns the guard, never combines it with peer decisions. */
    suspend fun editIntent(target: HarnessFsTarget, actor: Any?): HarnessFsEditGuard?
    /** fs/observed, synchronous emit: recorder throws fail the tool call. It does not await async work. */
    fun observed(target: HarnessFsTarget, observation: HarnessFsObservation, actor: Any?)
    fun onWriteIntent(listener: suspend (HarnessFsTarget, Any?, next: suspend () -> HarnessFsWriteIntent?) -> HarnessFsWriteIntent?): Disposable
    fun onEditIntent(listener: suspend (HarnessFsTarget, Any?, next: suspend () -> HarnessFsEditGuard?) -> HarnessFsEditGuard?): Disposable
    fun onObserved(listener: (HarnessFsTarget, HarnessFsObservation, Any?) -> Unit): Disposable
}
