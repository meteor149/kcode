package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeTools
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object ToolsServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-tools"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeTools(ctx)
    }
}
