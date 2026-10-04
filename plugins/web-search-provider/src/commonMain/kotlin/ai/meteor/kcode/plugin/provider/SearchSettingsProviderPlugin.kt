package ai.meteor.kcode.plugin.provider

import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.plugin.searchhttp.HttpSearchSettingsPolicy
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

/** Metadata/configuration can remain mounted independently of HTTP transport and credentials. */
object SearchSettingsProviderPlugin : Plugin<Unit> {
    override val name = "provider.search-settings.http"
    override val config = ConfigValidator<Unit> { it }
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val policy = HttpSearchSettingsPolicy()
        effect.collect(Disposable { policy.close() })
        KcodeSearchSettings(ctx, policy)
    }
}
