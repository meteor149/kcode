package ai.meteor.kcode

import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.stageBundledPackageCatalog
import ai.meteor.kcode.plugin.BundledPluginPackage
import ai.meteor.kcode.plugin.packages.desktopPackageHost

import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import java.awt.Frame
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.plugin.DesktopDynamicPluginController
import ai.meteor.kcode.plugin.DynamicPluginControllerFactory
import ai.meteor.kcode.plugin.FilePluginCompositionStore
import ai.meteor.kcode.plugin.KcodePluginProfile
import ai.meteor.kcode.plugin.KcodePluginRuntime
import ai.meteor.kcode.plugin.KcodePluginRuntimeConfig
import ai.meteor.kcode.plugin.api.DesktopPluginHostInputs
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

fun createDesktopKoogChatService(settingsStore: AppSettingsStore? = null): ChatService =
    createDesktopKoogChatRuntime(settingsStore).chatService

fun createDesktopKoogChatRuntime(
    settingsStore: AppSettingsStore? = null,
    historyRepository: ConversationHistoryRepository? = null,
    profile: KcodePluginProfile = KcodePluginProfile(),
    applicationWindow: () -> Frame? = { null },
): KcodeAgentRuntime {
    val workspace = Files.createDirectories(
        Path.of(System.getProperty("user.home"), ".kcode", "workspace"),
    ).toRealPath()
    val pluginDirectory = Files.createDirectories(
        Path.of(System.getProperty("user.home"), ".kcode", "plugins"),
    ).toFile()
    val nativeProfile = profile
    val featurePlugins = listOf(
        kcodePlugin(
            PluginDescriptor("provider.plugin-packages.platform", "builtin", "native", setOf("pluginPackages")),
            NativePluginPackagesPlugin(pluginDirectory, desktopPackageHost()), Unit,
        ),
    )
    val pluginRuntime = runBlocking {
        KcodePluginRuntime.create(
            KcodePluginRuntimeConfig(
                bundledPackages = if (!profile.includeDefaults) emptyList() else stageBundledPackageCatalog(pluginDirectory, host = desktopPackageHost()) { name ->
                    checkNotNull(NativePluginPackagesPlugin::class.java.classLoader.getResourceAsStream(name)) { "Missing bundled plugin resource '$name'" }
                }
                .filter { it.id != "provider.settings.platform" || settingsStore == null }
                .filter { it.id != "provider.history.platform" || historyRepository == null }.map {
                    val release = when (it.id) {
                        "provider.shell.platform" -> it.release.copy(configuration = StoredPluginConfiguration.encode(workspace.toString()))
                        "feature.web-container" -> it.release.copy(configuration = StoredPluginConfiguration.encode(workspace.toString()))
                        "provider.artifacts.platform" -> it.release.copy(configuration = StoredPluginConfiguration.encode(workspace.toString()))
                        "provider.fs.platform" -> it.release.copy(configuration = StoredPluginConfiguration.encode(workspace.toString()))
                        "provider.settings.platform" -> it.release.copy(configuration = StoredPluginConfiguration.encode(
                            Path.of(System.getProperty("user.home"), ".kcode", "settings.preferences_pb").toAbsolutePath().toString(),
                        ))
                        "provider.history.platform" -> it.release.copy(configuration = StoredPluginConfiguration.encode(
                            Path.of(System.getProperty("user.home"), ".kcode", "history.db").toAbsolutePath().toString(),
                        ))
                        else -> it.release
                    }
                    BundledPluginPackage(it.id, release)
                },
                interactionPolicy = InteractionPolicy(
                    approver = ToolCallApprover { false },
                ),
                hostInputs = DesktopPluginHostInputs(applicationWindow),
                settingsBackedInteraction = true,
                profile = nativeProfile,
                settingsStore = settingsStore,
                historyRepository = historyRepository,
                featurePlugins = featurePlugins,
                pluginCompositionStore = FilePluginCompositionStore(pluginDirectory),
                dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                    DesktopDynamicPluginController(context, loader, inventory, pluginDirectory)
                },
            ),
        )
    }
    return KcodeAgentRuntime(
        chatService = pluginRuntime.chatService,
        webContainerController = pluginRuntime.webContainerController,
        artifactRepository = pluginRuntime.artifactRepository,
        pluginManager = pluginRuntime.pluginManager,
        owner = pluginRuntime,
        applicationContent = pluginRuntime,
    )
}
