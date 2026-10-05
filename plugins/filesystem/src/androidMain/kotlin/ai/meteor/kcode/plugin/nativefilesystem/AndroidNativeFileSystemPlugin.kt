package ai.meteor.kcode.plugin.nativefilesystem

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.plugin.provider.PlatformFileSystemProviderPlugin
import java.nio.file.Files
import java.nio.file.Path
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

class AndroidNativeFileSystemPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "android-native-filesystem"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
            "Android filesystem requires native host inputs"
        }
        val root = Files.createDirectories(inputs.applicationContext().filesDir.toPath().resolve("agent_workspace")).toRealPath()
        PlatformFileSystemProviderPlugin<Path>().apply(ctx, AndroidAgentFileSystem(root), effect)
        publishSkillWorkspace(ctx, effect, AndroidPrivateAgentWorkspace(root), "android-app-data")
    }
}
