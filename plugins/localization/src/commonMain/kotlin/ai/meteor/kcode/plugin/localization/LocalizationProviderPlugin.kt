package ai.meteor.kcode.plugin.localization

import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object LocalizationProviderPlugin : Plugin<Any?> {
    override val name = "provider.localization.default"
    override val config = ConfigValidator<Any?> { resolveDictionaryConfiguration(it) }

    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val catalog = DictionaryTranslationCatalog(config as DictionaryConfiguration)
        effect.collect(Disposable { catalog.close() })
        KcodeLocalization(ctx, catalog)
    }
}

object LocalizationUiContributionPlugin : Plugin<Unit> {
    override val name = "consumer.localization.ui"
    override val config = ConfigValidator<Unit> { it }
    override val inject = dependencies(KcodeLocalization.Key, KcodeUiSlots.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeUiSlots.Key).register(ApplicationSlots.Localization, ctx.require(KcodeLocalization.Key).catalog))
    }
}
