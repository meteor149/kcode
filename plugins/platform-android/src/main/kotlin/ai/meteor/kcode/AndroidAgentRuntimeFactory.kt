package ai.meteor.kcode

import ai.meteor.kcode.plugin.nativeexecution.AndroidNativeShellPlugin
import ai.meteor.kcode.plugin.nativeexecution.AndroidNativeSettingsShellPlugin
import ai.meteor.kcode.plugin.nativeexecution.AndroidNativeUbuntuShellPlugin
import ai.meteor.kcode.plugin.nativeexecution.AndroidNativeSettingsUbuntuShellPlugin
import ai.meteor.kcode.plugin.notifications.LocalizedAndroidNativeNotificationsPlugin
import ai.meteor.kcode.plugin.notifications.AndroidNotificationPermissionPlugin
import ai.meteor.kcode.plugin.notifications.LocalizedAndroidGenerationForegroundPlugin
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.export.AndroidNativeImageSavingPlugin
import ai.meteor.kcode.plugin.artifacts.AndroidNativeArtifactsPlugin
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.plugin.history.AndroidNativeHistoryPlugin
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
import ai.meteor.kcode.plugin.feature.androidShellToolPlugin
import ai.meteor.kcode.plugin.feature.artifactToolPlugin
import ai.meteor.kcode.plugin.feature.filesystemToolPlugin
import ai.meteor.kcode.plugin.feature.skillToolPlugin
import ai.meteor.kcode.plugin.feature.ubuntuShellToolPlugin
import ai.meteor.kcode.plugin.feature.webContainerToolPlugin
import ai.meteor.kcode.plugin.feature.webSearchToolPlugin
import ai.meteor.kcode.plugin.provider.webSearchProviderPlugin
import ai.meteor.kcode.plugin.settingsstorage.AndroidNativeSettingsPlugin
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.webcontainer.native.AndroidNativeWebContainerPlugin
import ai.meteor.kcode.plugin.overlay.AndroidNativeConversationOverlayPlugin
import ai.meteor.kcode.plugin.LocalizedNativeToolApprovalPlugin
import ai.meteor.kcode.plugin.SettingsToolInteractionPlugin
import ai.meteor.kcode.plugin.HostModeToolInteractionPlugin
import ai.meteor.kcode.plugin.api.ConfirmationDialogHost
import android.app.Activity
import ai.meteor.kcode.plugin.nativefilesystem.AndroidNativeFileSystemPlugin
import ai.meteor.kcode.plugin.skills.WorkspaceSkillsPlugin
import java.nio.file.Files

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
        if (historyRepository == null && profile.overrides.none { it.descriptor.id == "provider.history.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.history.platform", "builtin", "native", setOf("history")),
            AndroidNativeHistoryPlugin(), Unit,
        ))
        if (settingsStore == null && profile.overrides.none { it.descriptor.id == "provider.settings.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.settings.platform", "builtin", "native", setOf("settings")),
            AndroidNativeSettingsPlugin(), Unit,
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.artifacts.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.artifacts.platform", "builtin", "native", setOf("artifacts")),
            AndroidNativeArtifactsPlugin(), Unit,
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.skills.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.skills.platform", "builtin", "native", setOf("skills")),
            WorkspaceSkillsPlugin(), Unit,
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.web-containers.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.web-containers.platform", "builtin", "native", setOf("webContainers")),
            AndroidNativeWebContainerPlugin(), Unit,
        ))
        if (toolCallApprover == null && profile.overrides.none { it.descriptor.id == "provider.interaction.platform" }) {
            val descriptor = PluginDescriptor("provider.interaction.platform", "builtin", "native", setOf("interaction"))
            add(if (settingsBackedInteraction) kcodePlugin(descriptor, SettingsToolInteractionPlugin(), Unit)
                else kcodePlugin(descriptor, HostModeToolInteractionPlugin(), permissionModeProvider))
        }

        if (profile.overrides.none { it.descriptor.id == "provider.notifications.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.notifications.platform", "builtin", "native", setOf("notifications")),
            LocalizedAndroidNativeNotificationsPlugin(),
            Unit,
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.export.image-saving" }) add(kcodePlugin(
            PluginDescriptor("provider.export.image-saving", "builtin", "native", setOf("conversationImageSaving")),
            AndroidNativeImageSavingPlugin(), Unit,
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.conversation-overlay.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.conversation-overlay.platform", "builtin", "native", setOf("conversationOverlays")),
            AndroidNativeConversationOverlayPlugin(), Unit,
        ))
    } else emptyList()
    val nativeProfile = profile.copy(overrides = profile.overrides + nativeOverrides)
    val featurePlugins = listOfNotNull(if (toolCallApprover == null) kcodePlugin(
        PluginDescriptor("provider.tool-approvals.native", "builtin", "native", setOf("toolApprovals")),
        LocalizedNativeToolApprovalPlugin(), Unit,
    ) else null) + listOfNotNull(permissionHost?.let {
        kcodePlugin(
            PluginDescriptor("policy.notifications.permission.android", "builtin", "native", setOf("uiSlots")),
            AndroidNotificationPermissionPlugin(), Unit,
        )
    }) + listOf(
        kcodePlugin(
            PluginDescriptor("provider.generation.foreground.android", "builtin", "native", setOf("generation")),
            LocalizedAndroidGenerationForegroundPlugin(), Unit,
        ),
        kcodePlugin(
            PluginDescriptor("provider.fs.platform", "builtin", "native", setOf("fs", "skillWorkspace")),
            AndroidNativeFileSystemPlugin(), Unit,
        ),
        filesystemToolPlugin(),
        webContainerToolPlugin(),
        webSearchProviderPlugin(),
        webSearchToolPlugin(),
        if (settingsBackedShell) kcodePlugin(
            PluginDescriptor("provider.shell.platform", "builtin", "native", setOf("shell")),
            AndroidNativeSettingsShellPlugin(), Unit,
        ) else kcodePlugin(
            PluginDescriptor("provider.shell.platform", "builtin", "native", setOf("shell")),
            AndroidNativeShellPlugin(), modeProvider,
        ),
        androidShellToolPlugin(),
        if (settingsBackedShell) kcodePlugin(
            PluginDescriptor("provider.shell.ubuntu", "builtin", "native", setOf("ubuntuShell")),
            AndroidNativeSettingsUbuntuShellPlugin(), Unit,
        ) else kcodePlugin(
            PluginDescriptor("provider.shell.ubuntu", "builtin", "native", setOf("ubuntuShell")),
            AndroidNativeUbuntuShellPlugin(), modeProvider,
        ),
        ubuntuShellToolPlugin(),
        skillToolPlugin(),
        artifactToolPlugin(),
    )
    val pluginDirectory = Files.createDirectories(activity.filesDir.toPath().resolve("cordis_plugins")).toFile()
    val pluginRuntime = KcodePluginRuntime.create(
        KcodePluginRuntimeConfig(
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
            featurePlugins = featurePlugins,
            pluginCompositionStore = FilePluginCompositionStore(pluginDirectory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                AndroidDynamicPluginController(
                    context,
                    activity.applicationContext,
                    loader,
                    inventory,
                    pluginDirectory,
                )
            },
        ),
    )
    return KcodeAgentRuntime(
        chatService = pluginRuntime.chatService,
        webContainerController = pluginRuntime.webContainerController,
        artifactRepository = pluginRuntime.artifactRepository,
        conversationOverlayController = pluginRuntime.conversationOverlayController,
        pluginManager = pluginRuntime.pluginManager,
        owner = pluginRuntime,
        applicationContent = pluginRuntime,
    )
}
