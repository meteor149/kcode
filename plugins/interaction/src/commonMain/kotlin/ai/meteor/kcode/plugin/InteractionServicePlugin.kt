package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

data class InteractionPluginConfig(val policy: InteractionPolicy)

object InteractionServicePlugin : Plugin<InteractionPluginConfig> {
    override val name = "kcode-interaction"

    override suspend fun apply(ctx: Context, config: InteractionPluginConfig, effect: EffectScope) {
        KcodeInteraction(ctx, config.policy)
    }
}
