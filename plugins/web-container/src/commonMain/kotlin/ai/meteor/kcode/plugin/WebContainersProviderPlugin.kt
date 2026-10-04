package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.webcontainer.OwnedWebContainerController
import ai.meteor.kcode.webcontainer.WebContainerController
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

object WebContainersProviderPlugin : Plugin<WebContainerController?> {
    override val name = "kcode-web-containers-platform"
    override suspend fun apply(ctx: Context, config: WebContainerController?, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        val controller = config?.let { OwnedWebContainerController(it, owner) }
        effect.collect(Disposable { if (controller == null) owner.close() else controller.dispose() })
        KcodeWebContainers(ctx, controller)
    }
}
