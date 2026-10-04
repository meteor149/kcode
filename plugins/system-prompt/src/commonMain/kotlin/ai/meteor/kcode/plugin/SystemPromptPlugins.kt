package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.prompt.buildKcodeSystemPrompt
import ai.meteor.kcode.plugin.api.KcodeSystemPrompt
import ai.meteor.kcode.plugin.api.PromptSection
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object SystemPromptServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-system-prompt"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeSystemPrompt(ctx)
    }
}

object DefaultSystemPromptPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-prompt-default"
    override val inject: Dependencies = dependencies(KcodeSystemPrompt.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeSystemPrompt.Key).register(
                PromptSection(
                    id = "kcode/default",
                    render = { request ->
                        buildKcodeSystemPrompt(
                            request.skillCatalogInstructions,
                            request.multiAgentInstructions,
                        )
                    },
                ),
            ),
        )
    }
}
