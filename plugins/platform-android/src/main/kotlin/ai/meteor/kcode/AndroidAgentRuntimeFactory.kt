package ai.meteor.kcode

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.meteor.kcode.artifact.createAndroidArtifactRepository
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.TransientConversationHistoryRepository
import ai.meteor.kcode.plugin.AndroidDynamicPluginController
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.DynamicPluginControllerFactory
import ai.meteor.kcode.plugin.feature.androidShellToolPlugin
import ai.meteor.kcode.plugin.feature.artifactToolPlugin
import ai.meteor.kcode.plugin.feature.filesystemToolPlugin
import ai.meteor.kcode.plugin.feature.skillToolPlugin
import ai.meteor.kcode.plugin.feature.ubuntuShellToolPlugin
import ai.meteor.kcode.plugin.feature.webContainerToolPlugin
import ai.meteor.kcode.plugin.feature.webSearchToolPlugin
import ai.meteor.kcode.plugin.KcodePluginProfile
import ai.meteor.kcode.plugin.KcodePluginRuntime
import ai.meteor.kcode.plugin.KcodePluginRuntimeConfig
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.settings.TransientAppSettingsStore
import ai.meteor.kcode.skill.createWorkspaceSkillRuntime
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.webcontainer.AndroidWebContainerLauncher
import android.app.Activity
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

/** Creates an Android agent whose file tools use real absolute paths allowed by the OS. */
fun createAndroidKoogChatService(
    activity: Activity,
    modeProvider: suspend () -> ShellExecutionMode,
    permissionModeProvider: suspend () -> ToolPermissionMode,
    toolCallApprover: ToolCallApprover,
    settingsStore: AppSettingsStore = TransientAppSettingsStore,
): ChatService = createAndroidKoogChatRuntime(
    activity,
    modeProvider,
    permissionModeProvider,
    toolCallApprover,
    settingsStore,
).chatService

fun createAndroidKoogChatRuntime(
    activity: Activity,
    modeProvider: suspend () -> ShellExecutionMode,
    permissionModeProvider: suspend () -> ToolPermissionMode,
    toolCallApprover: ToolCallApprover,
    settingsStore: AppSettingsStore = TransientAppSettingsStore,
    historyRepository: ConversationHistoryRepository = TransientConversationHistoryRepository,
    profile: KcodePluginProfile = KcodePluginProfile(),
): KcodeAgentRuntime {
    val workspaceRoot = Files.createDirectories(activity.filesDir.toPath().resolve("agent_workspace")).toRealPath()
    val fileSystem = AndroidAgentFileSystem(workspaceRoot)
    val shellExecutor = AndroidShellExecutors(
        activity = activity,
        modeProvider = modeProvider,
    )
    val ubuntuShellExecutor = AndroidUbuntuShellExecutor(
        context = activity.applicationContext,
        modeProvider = modeProvider,
    )
    val skillWorkspace = AndroidPrivateAgentWorkspace(workspaceRoot)
    val skillRuntime = createWorkspaceSkillRuntime(skillWorkspace, "android-app-data")
    val artifactRepository = createAndroidArtifactRepository(activity.applicationContext)
    val webContainerController = AndroidWebContainerLauncher(activity.applicationContext)
    val conversationOverlayController = AndroidConversationOverlayController(activity)
    val featurePlugins = listOf(
        filesystemToolPlugin(
            ToolRegistry {
                tool(ReadFileTool(fileSystem))
                tool(ListDirectoryTool(fileSystem))
                tool(WriteFileTool(fileSystem))
                tool(EditFileTool(fileSystem))
                tool(ReadMediaFileTool(fileSystem))
            },
        ),
        webContainerToolPlugin(),
        webSearchToolPlugin(),
        androidShellToolPlugin(
            ToolRegistry {
                tool(AgentShellTool(executor = shellExecutor, description = AndroidShellToolDescription))
            },
        ),
        ubuntuShellToolPlugin(
            ToolRegistry {
                tool(
                    AgentShellTool(
                        executor = ubuntuShellExecutor,
                        toolName = "execute_ubuntu_command",
                        description = AndroidUbuntuShellToolDescription,
                    ),
                )
            },
        ),
        skillToolPlugin(),
        artifactToolPlugin(),
    )
    val pluginDirectory = Files.createDirectories(activity.filesDir.toPath().resolve("cordis_plugins")).toFile()
    val pluginRuntime = runBlocking {
        KcodePluginRuntime.create(
            KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(permissionModeProvider, toolCallApprover),
                skillRuntime = skillRuntime,
                profile = profile,
                settingsStore = settingsStore,
                historyRepository = historyRepository,
                artifactRepository = artifactRepository,
                webContainerController = webContainerController,
                conversationOverlayController = conversationOverlayController,
                featurePlugins = featurePlugins,
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
    }
    return KcodeAgentRuntime(
        chatService = pluginRuntime.chatService,
        webContainerController = webContainerController,
        artifactRepository = artifactRepository,
        conversationOverlayController = conversationOverlayController,
        pluginManager = pluginRuntime.pluginManager,
        owner = pluginRuntime,
        applicationContent = pluginRuntime,
    )
}

private val AndroidShellToolDescription = """
    Executes a shell command in an Android OS environment and returns its complete combined output and exit code.
    Commands run through /system/bin/sh, not a desktop Linux shell. Do not assume that bash, GNU utilities, apt,
    systemd, or other desktop Linux programs are installed; prefer Android/toybox-compatible commands and Android
    absolute paths. The user-selected execution identity may be the app UID, adb shell through Shizuku, or root.
    /workspace maps to the app's private agent workspace when the app identity is selected. If workingDirectory is
    omitted, the platform chooses the default directory for the selected identity.
""".trimIndent()

private val AndroidUbuntuShellToolDescription = """
    Executes a command inside kcode's complete Ubuntu 24.04 ARM64 user space powered by PRoot. This is a regular
    GNU/Linux environment with bash, apt, Python, and standard Linux paths; it is separate from Android's system
    shell. It uses the same user-selected Android execution identity as the system shell tool: app UID, adb shell
    through Shizuku, or root. /workspace is the default working directory; app and root modes share kcode's private
    agent workspace, while adb mode uses a UID-2000 workspace under /data/local/tmp. Each identity-specific Ubuntu
    environment is installed atomically on first use, which can make the first call take longer. The guest reports
    PRoot's emulated root user, while Android filesystem and device access follow the selected real Android UID.
    systemd, kernel modules, real mounts, and other kernel operations remain unavailable under PRoot.
""".trimIndent()
