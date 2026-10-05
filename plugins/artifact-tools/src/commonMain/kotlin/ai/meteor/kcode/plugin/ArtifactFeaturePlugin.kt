package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.feature.ArtifactToolConsumerPlugin
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Artifact tools and optional page/navigation share one feature lifetime. */
object ArtifactFeaturePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "feature.artifacts"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        listOf(ArtifactToolConsumerPlugin, DefaultArtifactsUiPlugin, DefaultArtifactsNavigationPlugin).forEach { child ->
            val fiber = ctx.plugin(child, Unit)
            effect.collect { fiber.dispose() }
        }
    }
}
