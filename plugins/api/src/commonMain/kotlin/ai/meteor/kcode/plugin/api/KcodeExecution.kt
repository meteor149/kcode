package ai.meteor.kcode.plugin.api

import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

/** Product work must enter this boundary before allocating execution resources. */
interface ExecutionAdmission {
    suspend fun <T> run(block: suspend () -> T): T
}

/** Runtime-owned coordination; this service does not implement an agent or task scheduler. */
class KcodeExecution(ctx: Context, val admission: ExecutionAdmission) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeExecution>("executionAdmission") }
}
