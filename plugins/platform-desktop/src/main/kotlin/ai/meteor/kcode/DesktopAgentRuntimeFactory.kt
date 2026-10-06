package ai.meteor.kcode

import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.profiles.ProfileManagement

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
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.KcodePluginRuntime
import ai.meteor.kcode.plugin.KcodePluginRuntimeConfig
import ai.meteor.kcode.plugin.KcodeProfileHost
import ai.meteor.kcode.plugin.PreparedProfileRuntime
import ai.meteor.kcode.plugin.ProfileRuntimeFactory
import ai.meteor.kcode.plugin.ProfileCommandGateway
import ai.meteor.kcode.plugin.profiles.ProfileActivation
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
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
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
    moduleFactories: Map<String, () -> KcodePluginMount> = emptyMap(),
): KcodeAgentRuntime {
    return runBlocking { createDesktopProfileHost(settingsStore, historyRepository, profile, applicationWindow, profileId, homeDirectory, moduleFactories).requireInitialRuntime() }
}

/** Suspends while constructing the initial product; later switches reuse only host inputs/catalogue. */
suspend fun createDesktopProfileHost(
    settingsStore: AppSettingsStore? = null,
    historyRepository: ConversationHistoryRepository? = null,
    profile: KcodePluginProfile = KcodePluginProfile(),
    applicationWindow: () -> Frame? = { null },
    profileId: String? = null,
    homeDirectory: Path = Path.of(System.getProperty("user.home"), ".kcode"),
    moduleFactories: Map<String, () -> KcodePluginMount> = emptyMap(),
): KcodeProfileHost {
    val availableModuleFactories = moduleFactories.toMap()
    val commands = ProfileCommandGateway()
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
    ) + commands.pluginMount()
    lateinit var catalogue: Set<String>
    var startupReached = false
    var startupProfileId = profileId ?: "native"
    val legacyStore = FilePluginCompositionStore(pluginDirectory)
    val repository = FileProfileRepository(homeDirectory.resolve("profiles").toFile())
    val bundled = if (!profile.includeDefaults) emptyList() else stageBundledPackageCatalog(pluginDirectory, host = desktopPackageHost()) { name ->
        checkNotNull(NativePluginPackagesPlugin::class.java.classLoader.getResourceAsStream(name)) { "Missing bundled plugin resource '$name'" }
    }.filter { it.id != "provider.settings.platform" || settingsStore == null }
        .filter { it.id != "provider.history.platform" || historyRepository == null }
    suspend fun prepare(requestedId: String? = profileId, staging: Boolean = false, request: ProfileActivationRequest? = null): ProfileActivation {
        val bundles = nativeProfileBundles((catalogue - availableModuleFactories.keys + bundled.map { it.id }).toList())
        return prepareNativeProfileActivation(repository, nativeProfileTemplate(bundles, profile.includeDefaults), bundles,
            NativePluginPackageResolver(pluginDirectory, desktopPackageHost()),
            bundled.associate { it.id to ProfilePackageOffer(it.release) }, catalogue,
            legacyStore, NativeBuiltinAliases + NativeProfileInfrastructureAliases, requestedId,
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
            builtinOverrides = profile.overrides.map { it.descriptor.id }.toSet() + buildSet {
                if (settingsStore != null) add("provider.settings.platform")
                if (historyRepository != null) add("provider.history.platform")
            },
            stageSwitch = staging,
            activationRequest = request,
        )
    }
    val startup = ProfileStartupFactory { modules ->
        catalogue = modules.map { it.descriptor.id }.toSet()
        startupReached = true
        startupProfileId = profileId ?: repository.selected() ?: "native"
        prepare()
    }
    val configuration = KcodePluginRuntimeConfig(
        bundledPackages = bundled.map { BundledPluginPackage(it.id, it.release) },
        profileStartup = startup,
        profileModuleFactories = availableModuleFactories,
        interactionPolicy = InteractionPolicy(
            approver = ToolCallApprover { false },
        ),
        settingsBackedInteraction = true,
        profile = nativeProfile,
        settingsStore = settingsStore,
        historyRepository = historyRepository,
        featurePlugins = featurePlugins,
        pluginCompositionStore = legacyStore,
        dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
            DesktopDynamicPluginController(context, loader, inventory, pluginDirectory)
        },
    )
    fun facade(runtime: KcodePluginRuntime) = KcodeAgentRuntime(
        chatService = runtime.chatService,
        pluginManager = runtime.pluginManager,
        owner = runtime,
        applicationContent = runtime,
    )
    suspend fun prepared(id: String, request: ProfileActivationRequest? = null): PreparedProfileRuntime {
        val activation = prepare(id, staging = true, request = request)
        return PreparedProfileRuntime(activation) {
            facade(KcodePluginRuntime.create(configuration.copy(
                hostInputs = DesktopPluginHostInputs(applicationWindow),
                profileStartup = ProfileStartupFactory { modules ->
                    check(modules.map { it.descriptor.id }.toSet() == catalogue) { "Native module catalogue changed" }
                    activation
                },
            )))
        }
    }
    val factory = object : ProfileRuntimeFactory {
        override suspend fun prepare(id: String) = prepared(id)
        override suspend fun prepare(request: ProfileActivationRequest) = prepared(request.target.profileId, request)
    }
    val management = ProfileManagement(repository,
        { nativeProfileBundles((catalogue - availableModuleFactories.keys + bundled.map { it.id }).toList()) },
        { request -> prepare(request.target.profileId, staging = true, request = request) })
    return KcodeProfileHost.start({ startupProfileId }, factory, management, commands,
        canRecover = { startupReached }) {
        facade(KcodePluginRuntime.create(configuration.copy(hostInputs = DesktopPluginHostInputs(applicationWindow))))
    }
}
