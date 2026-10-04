package ai.meteor.kcode.plugin.nativefilesystem

import ai.meteor.kcode.plugin.provider.PlatformFileSystemProviderPlugin
import java.nio.file.Files
import java.nio.file.Path
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

class DesktopNativeFileSystemPlugin : Plugin<String> {
    override val name = "desktop-native-filesystem"
    override val config = ConfigValidator<String> { value ->
        require(value.isNotBlank()) { "Desktop FileSystem deployment path must not be blank" }
        require(Path.of(value).isAbsolute) { "Desktop FileSystem deployment path must be absolute" }
        value
    }


    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val requested = Path.of(config)
        require(requested.isAbsolute) { "Desktop workspace must be absolute" }
        val root = Files.createDirectories(requested).toRealPath()
        PlatformFileSystemProviderPlugin<Path>().apply(ctx, DesktopAgentWorkspaceFileSystem(root), effect)
        publishSkillWorkspace(ctx, effect, DesktopAgentWorkspace(root), "desktop-app-data")
    }
}
