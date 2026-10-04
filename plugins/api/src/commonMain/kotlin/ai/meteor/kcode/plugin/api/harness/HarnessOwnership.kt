package ai.meteor.kcode.plugin.api.harness

import org.cordis.Disposable
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class HarnessSessionId(val value: String) {
    init { require(value.isNotBlank()) { "Session id must not be blank" } }
}

/**
 * Future agent-runtime identity, never a path-based authorization token. Providers validate that
 * this exact object is the currently registered live agent. Implementing this interface or copying
 * its ids does not grant authority. No existing ChatService is claimed to implement this contract.
 */
interface HarnessAgentOwner {
    val agentId: String
    val sessionId: HarnessSessionId
    val isDisposed: Boolean

    /** Owner disposal awaits registered cleanup; cleanup failures reject the disposing lifecycle. */
    fun onDisposing(cleanup: suspend () -> Unit): Disposable
}

/** Kernel-issued registration scope, corresponding to the registering Cordis composition scope. */
interface HarnessOwnerScope {
    /** Unscoped host registrations serve all owners; agent compositions serve only their owners. */
    fun serves(owner: HarnessAgentOwner): Boolean
}

/**
 * AbortSignal counterpart. [reason] is the caller's exact Throwable, not a rewritten error.
 * Registration is atomic with cancellation: an already cancelled signal invokes the listener
 * immediately, and otherwise invokes it once. Listener exceptions are contained by the issuer.
 * Disposing the subscription prevents future invocation. Cancelling a waiter is separate from
 * signalling a resource; suspending wait APIs use ordinary coroutine cancellation.
 */
interface HarnessCancellationSignal {
    val reason: Throwable?
    fun onCancellation(listener: (Throwable) -> Unit): Disposable
    fun throwIfCancelled() { reason?.let { throw it } }
}

/** Suspends for bytes; null is EOF, returned nonempty arrays are fresh and caller-owned. */
interface HarnessByteSource {
    suspend fun read(maxBytes: Int): ByteArray?
    suspend fun close()
}

interface HarnessByteSink {
    /** Writes the complete byte sequence without framing or newline conversion. */
    suspend fun write(bytes: ByteArray)
    suspend fun close()
}
