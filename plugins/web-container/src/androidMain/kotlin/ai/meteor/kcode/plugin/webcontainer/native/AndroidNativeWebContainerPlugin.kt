package ai.meteor.kcode.plugin.webcontainer.native

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginCodeOrigin
import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.webcontainer.OwnedWebContainerController
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

/** Product implementation and resource lifetime belong to each imported APK generation. */
class AndroidNativeWebContainerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-android-native-web-containers"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
            "Android Web containers require native host inputs"
        }
        val resources = AndroidWebPluginResources(inputs.applicationContext(), PluginCodeOrigin.current(ctx))
        val launcher = AndroidWebContainerLauncher(inputs.applicationContext(), inputs.windows(), resources)
        val owner = PluginOperationOwner(name)
        effect.collect(Disposable {
            val failures = mutableListOf<Throwable>()
            runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
            runCatching { launcher.dispose() }.exceptionOrNull()?.let(failures::add)
            runCatching { resources.close() }.exceptionOrNull()?.let(failures::add)
            if (failures.isNotEmpty()) throw PluginCleanupException(name, failures)
        })
        KcodeWebContainers(ctx, OwnedWebContainerController(launcher, owner))
    }
}
