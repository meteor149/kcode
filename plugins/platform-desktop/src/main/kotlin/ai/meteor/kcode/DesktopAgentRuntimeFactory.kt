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
import ai.meteor.kcode.plugin.NativeBuiltinAliases
import ai.meteor.kcode.plugin.NativeProfileInfrastructureAliases
import ai.meteor.kcode.plugin.nativeProfileBundles
import ai.meteor.kcode.plugin.nativeProfileTemplate
import ai.meteor.kcode.plugin.packages.NativePluginPackageResolver
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileStartupFactory
import ai.meteor.kcode.plugin.profiles.ProfilePackageOffer
import ai.meteor.kcode.plugin.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.prepareNativeProfileActivation
import ai.meteor.kcode.plugin.profiles.profileMachineConfiguration
import ai.meteor.kcode.plugin.profiles.profileDataScopeKey
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
    profileId: String? = null,
    homeDirectory: Path = Path.of(System.getProperty("user.home"), ".kcode"),
): KcodeAgentRuntime {
    val workspace = Files.createDirectories(
        homeDirectory.resolve("workspace"),
    ).toRealPath()
    val pluginDirectory = Files.createDirectories(
        homeDirectory.resolve("plugins"),
    ).toFile()
    val nativeProfile = profile
    val featurePlugins = listOf(
        kcodePlugin(
            PluginDescriptor("provider.plugin-packages.platform", "builtin", "native", setOf("pluginPackages")),
            NativePluginPackagesPlugin(pluginDirectory, desktopPackageHost()), Unit,
        ),
    )
    val pluginRuntime = runBlocking {
        val legacyStore = FilePluginCompositionStore(pluginDirectory)
        val repository = FileProfileRepository(homeDirectory.resolve("profiles").toFile())
        val bundled = if (!profile.includeDefaults) emptyList() else stageBundledPackageCatalog(pluginDirectory, host = desktopPackageHost()) { name ->
            checkNotNull(NativePluginPackagesPlugin::class.java.classLoader.getResourceAsStream(name)) { "Missing bundled plugin resource '$name'" }
        }.filter { it.id != "provider.settings.platform" || settingsStore == null }
            .filter { it.id != "provider.history.platform" || historyRepository == null }
        KcodePluginRuntime.create(
            KcodePluginRuntimeConfig(
                bundledPackages = bundled.map { BundledPluginPackage(it.id, it.release) },
                profileStartup = ProfileStartupFactory { modules ->
                    val bundles = nativeProfileBundles((modules.map { it.descriptor.id } + bundled.map { it.id }).distinct())
                    prepareNativeProfileActivation(repository, nativeProfileTemplate(bundles, profile.includeDefaults), bundles,
                        NativePluginPackageResolver(pluginDirectory, desktopPackageHost()),
                        bundled.associate { it.id to ProfilePackageOffer(it.release) }, modules.map { it.descriptor.id }.toSet(),
                        legacyStore, NativeBuiltinAliases + NativeProfileInfrastructureAliases, profileId,
                        machineOverrides = { definition, frozen ->
                            fun dataFile(scope: String, filename: String): String {
                                val key = profileDataScopeKey(definition.id, scope)
                                return (if (key == "legacy") homeDirectory.resolve(filename)
                                    else homeDirectory.resolve("profile-data").resolve(key).resolve(filename)).toAbsolutePath().toString()
                            }
                            val workspaceKey = profileDataScopeKey(definition.id, definition.dataScope.workspace)
                            val cwd = if (definition.dataScope.workspace == "default") workspace else
                                Files.createDirectories(homeDirectory.resolve("workspaces").resolve(workspaceKey)).toRealPath()
                            val configs = mapOf(
                                "provider.settings.platform" to dataFile(definition.dataScope.settings, "settings.preferences_pb"),
                                "provider.history.platform" to dataFile(definition.dataScope.history, "history.db"),
                                "provider.shell.platform" to cwd.toString(),
                                "provider.fs.platform" to cwd.toString(),
                            ).filterKeys { id -> bundled.any { it.id == id } }.mapValues { StoredPluginConfiguration.encode(it.value) }
                            profileMachineConfiguration(definition, frozen, configs)
                        },
                        launchOverrides = profile.disabled.map { ProfileOperation.Disable(it) },
                        machineConfiguredPackages = setOf("provider.settings.platform", "provider.history.platform", "provider.shell.platform", "provider.fs.platform"),
                    )
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
                pluginCompositionStore = legacyStore,
                dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                    DesktopDynamicPluginController(context, loader, inventory, pluginDirectory)
                },
            ),
        )
    }
    return KcodeAgentRuntime(
        chatService = pluginRuntime.chatService,
        pluginManager = pluginRuntime.pluginManager,
        owner = pluginRuntime,
        applicationContent = pluginRuntime,
    )
}
