package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Native permission decisions consume the mounted settings provider; hosts supply only approval UI. */
object SettingsInteractionProviderPlugin : Plugin<ToolCallApprover> {
    override val name = "kcode-settings-interaction"
    override val inject = dependencies(KcodeSettings.Key)
    override suspend fun apply(ctx: Context, config: ToolCallApprover, effect: EffectScope) {
        val store = ctx.require(KcodeSettings.Key).store
        val owner = PluginOperationOwner(name)
        effect.collect { owner.close() }
        KcodeInteraction(ctx, InteractionPolicy(
            permissionModeProvider = { owner.run { ToolPermissionMode.fromCode(store.load().toolPermissionMode) ?: ToolPermissionMode.Ask } },
            approver = ToolCallApprover { request -> owner.run { config.approve(request) } },
        ))
    }
}
