package ai.meteor.kcode.plugin.settingsstorage

import java.nio.file.Path
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Allocates and releases native storage for each activation. */
class DesktopNativeSettingsPlugin : Plugin<String> {
    override val name = "desktop-native-settings"
    override val config = ConfigValidator<String> { value ->
        require(value.isNotBlank()) { "Desktop Settings deployment path must not be blank" }
        require(Path.of(value).isAbsolute) { "Desktop Settings deployment path must be absolute" }
        value
    }


    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val path = Path.of(config)
        require(path.isAbsolute) { "Desktop settings requires an absolute deployment path" }
        FactorySettingsProviderPlugin.apply(ctx, desktopSettingsStoreFactory(path), effect)
    }
}
