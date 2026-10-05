package ai.meteor.kcode.plugin.nativefilesystem

import ai.meteor.kcode.AgentWorkspace
import ai.meteor.kcode.plugin.api.KcodeSkillWorkspace
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import org.cordis.Context
import org.cordis.EffectScope

internal fun publishSkillWorkspace(
    ctx: Context,
    effect: EffectScope,
    workspace: AgentWorkspace,
    authorityId: String,
) {
    val owner = PluginOperationOwner("native skill workspace")
    effect.collect { owner.close() }
    KcodeSkillWorkspace(ctx, object : AgentWorkspace {
        override suspend fun readText(path: String) = owner.run { workspace.readText(path) }
        override suspend fun writeText(path: String, content: String) = owner.run { workspace.writeText(path, content) }
        override suspend fun list(path: String) = owner.run { workspace.list(path) }
        override suspend fun canonicalize(path: String) = owner.run { workspace.canonicalize(path) }
    }, authorityId)
}
