package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.pages.web.WebBackgroundContainersOverlay
import ai.meteor.kcode.plugin.pages.web.WebContainersUiActions
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import org.cordis.Disposable
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.plugin

object WebContainersUiPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "web-containers-ui"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeWebContainers.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val controller = ctx.require(KcodeWebContainers.Key).controller ?: return
        val actions = WebContainersUiActions(controller)
        effect.collect(Disposable { actions.close() })
        effect.collect(ctx.require(KcodeUiSlots.Key).register(ApplicationSlots.WebContainers, UiRenderer { request ->
            if (actions.isAvailable) WebBackgroundContainersOverlay(actions, request.hazeState, request.modifier)
        }))
    }
}

fun webContainersUiPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.ui.web-containers", "builtin", "built-in", setOf("uiSlots", "web-containers.overlay")),
    WebContainersUiPlugin,
    Unit,
)
