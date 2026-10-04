package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.AgentWorkspace
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

/** A provider-owned, scoped workspace; skill policy and caches belong to the skills provider. */
class KcodeSkillWorkspace(
    ctx: Context,
    val workspace: AgentWorkspace,
    val authorityId: String,
) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeSkillWorkspace>("skillWorkspace") }
}
