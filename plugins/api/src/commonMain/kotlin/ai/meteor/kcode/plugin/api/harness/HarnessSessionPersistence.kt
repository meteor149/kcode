package ai.meteor.kcode.plugin.api.harness

import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

@JvmInline
value class HarnessPersistenceRevision(val value: String)
data class HarnessSessionInspection(val metadata: HarnessSessionHeader, val events: List<HarnessSessionEventRecord>)
data class HarnessSessionPersistenceSnapshot(val header: HarnessSessionHeader, val revision: HarnessPersistenceRevision)
data class HarnessSessionLocation(val kind: String, val path: String)
data class HarnessSessionRawArtifact(val metadata: HarnessSessionHeader, val filename: String, val content: String)

/** Reserved ctx.sessionPersistence, independent of the existing conversation-row repository.
 * append resolves only after durable contiguous publication. Logical event format versions and
 * backend physical schemas are distinct; unsupported versions reject before current-shape validation.
 * No committed prefix rewriting. Detached immutable read graphs preserve unknown ignorable events.
 */
interface HarnessSessionPersistence {
    val supportsRawArtifacts: Boolean
    /** Location hint only, no read/create/flush/materialization and no authorization. */
    fun locate(metadata: HarnessSessionHeader): HarnessSessionLocation?
    /** Unsupported capability throws; null means a supported backend has no materialized artifact. */
    suspend fun readRaw(id: HarnessSessionId, signal: HarnessCancellationSignal? = null): HarnessSessionRawArtifact?
    /** May defer physical creation until first append; abandoned empty sessions may remain unlisted. */
    suspend fun create(metadata: HarnessSessionHeader)
    /** First seq equals stored next seq; lossless JSON canonical events persist in order, including chunks. */
    suspend fun append(id: HarnessSessionId, events: List<HarnessSessionEventRecord>)
    /** Exclusive unpublished exact object for resume; revision convergence and reservation owned here. */
    suspend fun prepare(id: HarnessSessionId, signal: HarnessCancellationSignal? = null): HarnessSessionPreparation
    /** Durably balances a complete interrupted cold tail with missing results/boundaries. Only torn
     * final physical records may be discarded. Never cold-repair a live owner; an open live turn rejects. */
    suspend fun load(id: HarnessSessionId): HarnessSessionInspection
    /** Pure logical inspection: synthetic cold-tail closers stay in memory. Live snapshots may be open. */
    suspend fun inspect(id: HarnessSessionId, signal: HarnessCancellationSignal? = null): HarnessSessionInspection
    /** Detached stored-prefix suffix only; no repair, synthetic closers, preparation or publication. */
    suspend fun readFrom(id: HarnessSessionId, fromSeq: Long, signal: HarnessCancellationSignal? = null): HarnessSessionInspection
    suspend fun list(signal: HarnessCancellationSignal? = null): List<HarnessSessionHeader>
    /** Cheap source-qualified change tokens; unchanged source stays stable, repairs change its revision. */
    suspend fun listSnapshots(signal: HarnessCancellationSignal? = null): List<HarnessSessionPersistenceSnapshot>
    /** Drain writes and release preparation reservations; failures reject instead of claiming durability. */
    suspend fun close()
}

class KcodeSessionPersistence(ctx: Context, val persistence: HarnessSessionPersistence) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeSessionPersistence>("sessionPersistence") }
}
