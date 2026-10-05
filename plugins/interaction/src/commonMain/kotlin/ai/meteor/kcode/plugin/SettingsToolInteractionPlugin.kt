package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeToolApprovals
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.tools.permission.ToolCallApprover
import org.cordis.ConfigValidator
import ai.meteor.kcode.settings.SettingsMutationValidator
import kotlinx.serialization.json.JsonPrimitive
import ai.meteor.kcode.settings.ToolPermissionMode
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

class SettingsToolInteractionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-settings-tool-interaction"
    override val inject = dependencies(KcodeSettings.Key, KcodeToolApprovals.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val approver = ctx.require(KcodeToolApprovals.Key).approver
        bindSettingsInteraction(ctx, effect, approver)
    }
}

/** Composition entry for a borrowed caller approver; the feature still owns its settings. */
object SettingsApproverInteractionPlugin : Plugin<ToolCallApprover> {
    override val name = "kcode-settings-approver-interaction"
    override val inject = dependencies(KcodeSettings.Key)

    override suspend fun apply(ctx: Context, config: ToolCallApprover, effect: EffectScope) {
        bindSettingsInteraction(ctx, effect, config)
    }
}

private suspend fun bindSettingsInteraction(ctx: Context, effect: EffectScope, approver: ToolCallApprover) {
    val store = ctx.require(KcodeSettings.Key).store
    val owner = PluginOperationOwner("settings interaction")
    val settingsPolicy = StoredToolPermissionSettingsPolicy()
    effect.collect(ctx.require(KcodeSettings.Key).mutations.register("feature.interaction-settings", SettingsMutationValidator { previous, candidate ->
        settingsPolicy.resolve(candidate)
        val before = previous.namespaces["feature.interaction-settings"]?.get("mode")
        val after = candidate.namespaces["feature.interaction-settings"]?.get("mode")
        if (before != after && after != null) {
            require(after is JsonPrimitive && after.isString && ToolPermissionMode.fromCode(after.content) != null) {
                "Permission/execution mode is not supported"
            }
        }
    }))
    effect.collect {
        settingsPolicy.close()
        owner.close()
    }
    KcodeInteraction(ctx, InteractionPolicy(
        permissionModeProvider = { owner.run { settingsPolicy.resolve(store.load()) } },
        approver = ToolCallApprover { request -> owner.run { approver.approve(request) } },
        settings = settingsPolicy,
    ))
    val ui = ctx.plugin(ToolPermissionUiPlugin, Unit)
    effect.collect { ui.dispose() }
}
