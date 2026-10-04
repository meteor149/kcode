package ai.meteor.kcode.plugin.notifications

import ai.meteor.kcode.plugin.api.DesktopPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Native entry usable by both the built-in bundle and persisted external packages. */
class DesktopNativeNotificationsPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "desktop-native-notifications"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? DesktopPluginHostInputs) {
            "Desktop notifications require native host inputs"
        }
        ScheduledTaskNotificationsProviderPlugin.apply(ctx, desktopScheduledTaskNotificationsFactory(inputs::applicationWindow), effect)
    }
}
