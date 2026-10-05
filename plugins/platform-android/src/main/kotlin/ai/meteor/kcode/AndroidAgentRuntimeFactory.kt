package ai.meteor.kcode

import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.stageBundledPackageCatalog
import ai.meteor.kcode.plugin.BundledPluginPackage
import ai.meteor.kcode.plugin.packages.androidPackageHost
import ai.meteor.kcode.plugin.packages.androidPackageVerifier

import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.plugin.AndroidDynamicPluginController
import ai.meteor.kcode.plugin.DynamicPluginControllerFactory
import ai.meteor.kcode.plugin.FilePluginCompositionStore
import ai.meteor.kcode.plugin.KcodePluginProfile
import ai.meteor.kcode.plugin.KcodePluginRuntime
import ai.meteor.kcode.plugin.KcodePluginRuntimeConfig
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.AndroidPermissionHost
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.HostToolPermissionModeInputPlugin
import ai.meteor.kcode.plugin.HostToolApprovalsInputPlugin
import ai.meteor.kcode.plugin.HostShellModeInputPlugin
import ai.meteor.kcode.plugin.api.ConfirmationDialogHost
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
import android.app.Activity
import android.content.Context
import java.io.File
import java.nio.file.Files

/** Native loader bridge for custom compositions; the factory borrows the application context. */
fun androidPluginControllerFactory(context: Context, directory: File): DynamicPluginControllerFactory {
    val application = context.applicationContext
    return DynamicPluginControllerFactory { cordis, loader, inventory ->
        AndroidDynamicPluginController(cordis, application, loader, inventory, directory)
    }
}

/** Creates an Android agent whose file tools use real absolute paths allowed by the OS. */
suspend fun createAndroidKoogChatService(
    activity: Activity,
    modeProvider: suspend () -> ShellExecutionMode = { ShellExecutionMode.App },
    permissionModeProvider: suspend () -> ToolPermissionMode,
    toolCallApprover: ToolCallApprover? = null,
    settingsStore: AppSettingsStore? = null,
): ChatService = createAndroidKoogChatRuntime(
    activity,
    modeProvider,
    permissionModeProvider,
    toolCallApprover,
    settingsStore,
).chatService

suspend fun createAndroidKoogChatRuntime(
    activity: Activity,
    modeProvider: suspend () -> ShellExecutionMode = { ShellExecutionMode.App },
    permissionModeProvider: suspend () -> ToolPermissionMode = { ToolPermissionMode.Ask },
    toolCallApprover: ToolCallApprover? = null,
    settingsStore: AppSettingsStore? = null,
    historyRepository: ConversationHistoryRepository? = null,
    profile: KcodePluginProfile = KcodePluginProfile(),
    settingsBackedInteraction: Boolean = false,
    settingsBackedShell: Boolean = false,
    permissionHost: AndroidPermissionHost? = null,
    confirmationDialogs: ConfirmationDialogHost? = null,
    profileId: String? = null,
): KcodeAgentRuntime {
    val nativeOverrides = if (profile.includeDefaults) buildList {
        if (toolCallApprover == null && !settingsBackedInteraction && profile.overrides.none { it.descriptor.id == "provider.interaction.platform" }) {
            val descriptor = PluginDescriptor("provider.interaction.platform", "builtin", "native", setOf("interaction"))
            add(kcodePlugin(descriptor, HostToolPermissionModeInputPlugin(), permissionModeProvider))
        }
    } else emptyList()
    val nativeProfile = profile.copy(overrides = profile.overrides + nativeOverrides)
    val pluginDirectory = Files.createDirectories(activity.filesDir.toPath().resolve("cordis_plugins")).toFile()
    val featurePlugins = listOf(kcodePlugin(
        PluginDescriptor("provider.plugin-packages.platform", "builtin", "native", setOf("pluginPackages")),
        NativePluginPackagesPlugin(pluginDirectory, androidPackageHost(), artifactVerifier = androidPackageVerifier(activity)), Unit,
    )) + if (profile.includeDefaults && settingsBackedInteraction && toolCallApprover != null) listOf(kcodePlugin(
        PluginDescriptor("provider.tool-approvals.native", "builtin", "native", setOf("toolApprovals")),
        HostToolApprovalsInputPlugin,
        toolCallApprover,
    )) else emptyList()
    val nativeFeaturePlugins = featurePlugins + if (!profile.includeDefaults || settingsBackedShell) emptyList() else listOf(kcodePlugin(
        PluginDescriptor("policy.shell-mode.platform", "builtin", "native", setOf("shellMode")),
        HostShellModeInputPlugin(), modeProvider,
    ))
    val bundled = if (!profile.includeDefaults) emptyList() else stageBundledPackageCatalog(pluginDirectory, host = androidPackageHost()) { name ->
        activity.assets.open(name)
    }.filter { it.id != "policy.shell-mode.platform" || settingsBackedShell }
        .filter { it.id != "policy.notifications.permission.android" || permissionHost != null }
        .filter { it.id != "provider.tool-approvals.native" || toolCallApprover == null }
        .filter { it.id != "provider.interaction.platform" || settingsBackedInteraction }
        .filter { it.id != "provider.settings.platform" || settingsStore == null }
        .filter { it.id != "provider.history.platform" || historyRepository == null }
    val legacyStore = FilePluginCompositionStore(pluginDirectory)
    val repository = FileProfileRepository(File(activity.filesDir, "cordis_profiles"))
    val pluginRuntime = KcodePluginRuntime.create(
        KcodePluginRuntimeConfig(
            bundledPackages = bundled.map { BundledPluginPackage(it.id, it.release) },
            profileStartup = ProfileStartupFactory { modules ->
                val bundles = nativeProfileBundles((modules.map { it.descriptor.id } + bundled.map { it.id }).distinct())
                prepareNativeProfileActivation(repository, nativeProfileTemplate(bundles, profile.includeDefaults), bundles,
                    NativePluginPackageResolver(pluginDirectory, androidPackageHost(), artifactVerifier = androidPackageVerifier(activity)),
                    bundled.associate { it.id to ProfilePackageOffer(it.release) }, modules.map { it.descriptor.id }.toSet(),
                    legacyStore, NativeBuiltinAliases + NativeProfileInfrastructureAliases, profileId,
                    machineOverrides = { definition, frozen ->
                        val settingsKey = profileDataScopeKey(definition.id, definition.dataScope.settings)
                        val historyKey = profileDataScopeKey(definition.id, definition.dataScope.history)
                        val configs = buildMap {
                            if (settingsKey != "legacy") put("provider.settings.platform", "kcode.settings.$settingsKey")
                            if (historyKey != "legacy") put("provider.history.platform",
                                File(activity.filesDir, "profile-data/$historyKey/history.db").absolutePath)
                        }.filterKeys { id -> bundled.any { it.id == id } }.mapValues { StoredPluginConfiguration.encode(it.value) }
                        profileMachineConfiguration(definition, frozen, configs)
                    },
                    launchOverrides = profile.disabled.map { ProfileOperation.Disable(it) },
                    machineConfiguredPackages = setOf("provider.settings.platform", "provider.history.platform"),
                    builtinOverrides = nativeProfile.overrides.map { it.descriptor.id }.toSet() +
                        nativeFeaturePlugins.filterNot { it.descriptor.id == "provider.plugin-packages.platform" }.map { it.descriptor.id } + buildSet {
                            if (settingsStore != null) add("provider.settings.platform")
                            if (historyRepository != null) add("provider.history.platform")
                        },
                )
            },
            profileBuiltinModules = nativeFeaturePlugins.filterNot { it.descriptor.id == "provider.plugin-packages.platform" },
            interactionPolicy = InteractionPolicy(permissionModeProvider, toolCallApprover ?: ToolCallApprover { false }),
            hostInputs = when {
                confirmationDialogs != null -> AndroidPluginHostInputs(activity, permissionHost, confirmationDialogs)
                permissionHost != null -> AndroidPluginHostInputs(activity, permissionHost)
                else -> AndroidPluginHostInputs(activity)
            },
            settingsBackedInteraction = settingsBackedInteraction,
            profile = nativeProfile,
            settingsStore = settingsStore,
            historyRepository = historyRepository,
            featurePlugins = nativeFeaturePlugins.filter { it.descriptor.id == "provider.plugin-packages.platform" },
            pluginCompositionStore = legacyStore,
            dynamicPluginControllerFactory = androidPluginControllerFactory(activity, pluginDirectory),
        ),
    )
    return KcodeAgentRuntime(
        chatService = pluginRuntime.chatService,
        conversationOverlayController = pluginRuntime.conversationOverlayController,
        pluginManager = pluginRuntime.pluginManager,
        owner = pluginRuntime,
        applicationContent = pluginRuntime,
    )
}
