package ai.meteor.kcode.plugin.artifacts

import java.nio.file.Path
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Allocates and releases native storage for each activation. */
class DesktopNativeArtifactsPlugin : Plugin<String> {
    override val name = "desktop-native-artifacts"
    override val config = ConfigValidator<String> { value ->
        require(value.isNotBlank()) { "Desktop Artifacts deployment path must not be blank" }
        require(Path.of(value).isAbsolute) { "Desktop Artifacts deployment path must be absolute" }
        value
    }


    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val path = Path.of(config)
        require(path.isAbsolute) { "Desktop artifacts requires an absolute deployment path" }
        FactoryFileArtifactsProviderPlugin.apply(ctx, desktopArtifactFileStoreFactory(path), effect)
    }
}
