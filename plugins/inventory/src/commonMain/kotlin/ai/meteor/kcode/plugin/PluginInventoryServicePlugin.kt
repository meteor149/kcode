package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodePluginInventory
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object PluginInventoryServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-plugin-inventory"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodePluginInventory(ctx)
    }
}
