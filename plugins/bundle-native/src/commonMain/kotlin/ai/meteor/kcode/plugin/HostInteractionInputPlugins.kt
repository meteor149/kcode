package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.tools.permission.ToolCallApprover
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Explicit caller callbacks are borrowed inputs, never serialized package configuration. */
internal object HostInteractionInputPlugin : Plugin<InteractionPolicy> {
    override val name = "kcode-host-interaction-input"

    override suspend fun apply(ctx: Context, config: InteractionPolicy, effect: EffectScope) {
        bindInteractionInputs(ctx, effect, config)
    }
}

private fun bindInteractionInputs(ctx: Context, effect: EffectScope, inputs: InteractionPolicy) {
    val owner = PluginOperationOwner("host interaction inputs")
    effect.collect { owner.close() }
    KcodeInteraction(ctx, InteractionPolicy(
        permissionModeProvider = { owner.run { inputs.permissionModeProvider() } },
        approver = ToolCallApprover { request -> owner.run { inputs.approver.approve(request) } },
        settings = inputs.settings,
    ))
}
