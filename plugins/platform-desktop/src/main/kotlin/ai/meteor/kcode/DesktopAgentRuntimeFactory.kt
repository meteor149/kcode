package ai.meteor.kcode

import ai.meteor.kcode.plugin.nativeexecution.DesktopNativeShellPlugin
import ai.meteor.kcode.plugin.notifications.DesktopNativeNotificationsPlugin
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.LocalizedNativeToolApprovalPlugin
import ai.meteor.kcode.plugin.SettingsToolInteractionPlugin
import java.awt.Frame
import ai.meteor.kcode.plugin.export.DesktopNativeImageSavingPlugin
import ai.meteor.kcode.plugin.artifacts.DesktopNativeArtifactsPlugin
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.plugin.history.DesktopNativeHistoryPlugin
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.plugin.DesktopDynamicPluginController
import ai.meteor.kcode.plugin.DynamicPluginControllerFactory
import ai.meteor.kcode.plugin.FilePluginCompositionStore
import ai.meteor.kcode.plugin.KcodePluginProfile
import ai.meteor.kcode.plugin.KcodePluginRuntime
import ai.meteor.kcode.plugin.KcodePluginRuntimeConfig
import ai.meteor.kcode.plugin.api.DesktopPluginHostInputs
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.feature.artifactToolPlugin
import ai.meteor.kcode.plugin.feature.desktopShellToolPlugin
import ai.meteor.kcode.plugin.feature.filesystemToolPlugin
import ai.meteor.kcode.plugin.feature.skillToolPlugin
import ai.meteor.kcode.plugin.feature.webContainerToolPlugin
import ai.meteor.kcode.plugin.feature.webSearchToolPlugin
import ai.meteor.kcode.plugin.provider.webSearchProviderPlugin
import ai.meteor.kcode.plugin.settingsstorage.DesktopNativeSettingsPlugin
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.webcontainer.native.DesktopNativeWebContainerPlugin
import ai.meteor.kcode.plugin.nativefilesystem.DesktopNativeFileSystemPlugin
import ai.meteor.kcode.plugin.skills.WorkspaceSkillsPlugin
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
    val nativeOverrides = if (profile.includeDefaults) buildList {
        if (historyRepository == null && profile.overrides.none { it.descriptor.id == "provider.history.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.history.platform", "builtin", "native", setOf("history")),
            DesktopNativeHistoryPlugin(), Path.of(System.getProperty("user.home"), ".kcode", "history.db").toAbsolutePath().toString(),
        ))
        if (settingsStore == null && profile.overrides.none { it.descriptor.id == "provider.settings.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.settings.platform", "builtin", "native", setOf("settings")),
            DesktopNativeSettingsPlugin(), Path.of(System.getProperty("user.home"), ".kcode", "settings.preferences_pb").toAbsolutePath().toString(),
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.artifacts.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.artifacts.platform", "builtin", "native", setOf("artifacts")),
            DesktopNativeArtifactsPlugin(), Path.of(System.getProperty("user.home"), ".kcode", "workspace").toAbsolutePath().toString(),
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.skills.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.skills.platform", "builtin", "native", setOf("skills")),
            WorkspaceSkillsPlugin(), Unit,
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.web-containers.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.web-containers.platform", "builtin", "native", setOf("webContainers")),
            DesktopNativeWebContainerPlugin(), workspace.toString(),
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.interaction.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.interaction.platform", "builtin", "native", setOf("interaction")),
            SettingsToolInteractionPlugin(), Unit,
        ))

        if (profile.overrides.none { it.descriptor.id == "provider.notifications.platform" }) add(kcodePlugin(
            PluginDescriptor("provider.notifications.platform", "builtin", "native", setOf("notifications")),
            DesktopNativeNotificationsPlugin(), Unit,
        ))
        if (profile.overrides.none { it.descriptor.id == "provider.export.image-saving" }) add(kcodePlugin(
            PluginDescriptor("provider.export.image-saving", "builtin", "native", setOf("conversationImageSaving")),
            DesktopNativeImageSavingPlugin(), Unit,
        ))
    } else emptyList()
    val nativeProfile = profile.copy(overrides = profile.overrides + nativeOverrides)
    val featurePlugins = listOf(
        kcodePlugin(
            PluginDescriptor("provider.tool-approvals.native", "builtin", "native", setOf("toolApprovals")),
            LocalizedNativeToolApprovalPlugin(), Unit,
        ),
        kcodePlugin(
            PluginDescriptor("provider.fs.platform", "builtin", "native", setOf("fs", "skillWorkspace")),
            DesktopNativeFileSystemPlugin(), workspace.toString(),
        ),
        filesystemToolPlugin(),
        kcodePlugin(
            PluginDescriptor("provider.shell.platform", "builtin", "native", setOf("shell")),
            DesktopNativeShellPlugin(), workspace.toString(),
        ),
        desktopShellToolPlugin(),
        webContainerToolPlugin(),
        webSearchProviderPlugin(),
        webSearchToolPlugin(),
        skillToolPlugin(),
        artifactToolPlugin(),
    )
    val pluginRuntime = runBlocking {
        KcodePluginRuntime.create(
            KcodePluginRuntimeConfig(
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
