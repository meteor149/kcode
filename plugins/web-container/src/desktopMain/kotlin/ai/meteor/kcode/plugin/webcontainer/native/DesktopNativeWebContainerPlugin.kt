package ai.meteor.kcode.plugin.webcontainer.native

import ai.meteor.kcode.plugin.WebContainersProviderPlugin
import java.nio.file.Files
import java.nio.file.Path
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Configuration is the deployment workspace path; no prebuilt host engine is retained. */
class DesktopNativeWebContainerPlugin : Plugin<String> {
    override val name = "kcode-desktop-native-web-containers"
    override val config = ConfigValidator<String> { value ->
        require(value.isNotBlank()) { "Desktop WebContainer deployment path must not be blank" }
        require(Path.of(value).isAbsolute) { "Desktop WebContainer deployment path must be absolute" }
        value
    }

    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        require(config.isNotBlank()) { "Web workspace path must not be blank" }
        val workspace = Path.of(config)
        require(workspace.isAbsolute) { "Web workspace path must be absolute" }
        val root = Files.createDirectories(workspace).toRealPath()
        WebContainersProviderPlugin.apply(ctx, DesktopWebContainerLauncher(root), effect)
    }
}
