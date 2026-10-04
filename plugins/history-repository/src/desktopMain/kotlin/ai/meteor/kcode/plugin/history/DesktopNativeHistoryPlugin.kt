package ai.meteor.kcode.plugin.history

import java.nio.file.Path
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Allocates and releases native storage for each activation. */
class DesktopNativeHistoryPlugin : Plugin<String> {
    override val name = "desktop-native-history"
    override val config = ConfigValidator<String> { value ->
        require(value.isNotBlank()) { "Desktop History deployment path must not be blank" }
        require(Path.of(value).isAbsolute) { "Desktop History deployment path must be absolute" }
        value
    }


    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val path = Path.of(config)
        require(path.isAbsolute) { "Desktop history requires an absolute deployment path" }
        FactoryHistoryProviderPlugin.apply(ctx, desktopHistoryRepositoryFactory(path), effect)
    }
}
