package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeUiContributions
import ai.meteor.kcode.plugin.api.UiContributionSource
import ai.meteor.kcode.plugin.defaultui.OwnedUiSlots
import ai.meteor.kcode.plugin.ui.api.DefaultUiSnapshotKey
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Optional default UI vocabulary projected onto the neutral contribution registry. */
object UiSlotsServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-ui-slots"
    override val inject = dependencies(KcodeUiContributions.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val defaults = OwnedUiSlots(ctx)
        effect.collect { defaults.close() }
        effect.collect(ctx.require(KcodeUiContributions.Key).registerProjection(
            DefaultUiSnapshotKey,
            UiContributionSource { defaults.snapshot() },
        ))
    }
}
