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
    val pluginRuntime = KcodePluginRuntime.create(
        KcodePluginRuntimeConfig(
            bundledPackages = if (!profile.includeDefaults) emptyList() else stageBundledPackageCatalog(pluginDirectory, host = androidPackageHost()) { name ->
                activity.assets.open(name)
            }
                .filter { it.id != "policy.shell-mode.platform" || settingsBackedShell }
                .filter { it.id != "policy.notifications.permission.android" || permissionHost != null }
                .filter { it.id != "provider.tool-approvals.native" || toolCallApprover == null }
                .filter { it.id != "provider.interaction.platform" || settingsBackedInteraction }
                .filter { it.id != "provider.settings.platform" || settingsStore == null }
                .filter { it.id != "provider.history.platform" || historyRepository == null }
                .map { BundledPluginPackage(it.id, it.release) },
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
            featurePlugins = nativeFeaturePlugins,
            pluginCompositionStore = FilePluginCompositionStore(pluginDirectory),
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
