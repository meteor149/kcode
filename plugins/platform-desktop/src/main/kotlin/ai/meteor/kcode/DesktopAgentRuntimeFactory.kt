package ai.meteor.kcode

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.meteor.kcode.artifact.createDesktopArtifactRepository
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.TransientConversationHistoryRepository
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.DesktopDynamicPluginController
import ai.meteor.kcode.plugin.DynamicPluginControllerFactory
import ai.meteor.kcode.plugin.feature.artifactToolPlugin
import ai.meteor.kcode.plugin.feature.desktopShellToolPlugin
import ai.meteor.kcode.plugin.feature.filesystemToolPlugin
import ai.meteor.kcode.plugin.feature.skillToolPlugin
import ai.meteor.kcode.plugin.feature.webContainerToolPlugin
import ai.meteor.kcode.plugin.feature.webSearchToolPlugin
import ai.meteor.kcode.plugin.KcodePluginProfile
import ai.meteor.kcode.plugin.KcodePluginRuntime
import ai.meteor.kcode.plugin.KcodePluginRuntimeConfig
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.skill.createWorkspaceSkillRuntime
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.webcontainer.DesktopWebContainerLauncher
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

fun createDesktopKoogChatService(settingsStore: AppSettingsStore): ChatService =
    createDesktopKoogChatRuntime(settingsStore).chatService

fun createDesktopKoogChatRuntime(
    settingsStore: AppSettingsStore,
    historyRepository: ConversationHistoryRepository = TransientConversationHistoryRepository,
    profile: KcodePluginProfile = KcodePluginProfile(),
): KcodeAgentRuntime {
    val workspace = Files.createDirectories(
        Path.of(System.getProperty("user.home"), ".kcode", "workspace"),
    ).toRealPath()
    val fileSystem = DesktopAgentWorkspaceFileSystem(workspace)
    val skillWorkspace = DesktopAgentWorkspace(workspace)
    val skillRuntime = createWorkspaceSkillRuntime(skillWorkspace, "desktop-app-data")
    val artifactRepository = createDesktopArtifactRepository()
    val webContainerController = DesktopWebContainerLauncher(workspace)
    val pluginDirectory = Files.createDirectories(
        Path.of(System.getProperty("user.home"), ".kcode", "plugins"),
    ).toFile()
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
        desktopShellToolPlugin(
            ToolRegistry { tool(AgentShellTool(DesktopShellCommandExecutor(workspace))) },
        ),
        webContainerToolPlugin(),
        webSearchToolPlugin(),
        skillToolPlugin(),
        artifactToolPlugin(),
    )
    val pluginRuntime = runBlocking {
        KcodePluginRuntime.create(
            KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(
                    permissionModeProvider = {
                        ToolPermissionMode.fromCode(settingsStore.load().toolPermissionMode)
                    },
                    approver = ToolCallApprover { request -> confirmDesktopToolCall(request) },
                ),
                skillRuntime = skillRuntime,
                profile = profile,
                settingsStore = settingsStore,
                historyRepository = historyRepository,
                artifactRepository = artifactRepository,
                webContainerController = webContainerController,
                featurePlugins = featurePlugins,
                dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                    DesktopDynamicPluginController(context, loader, inventory, pluginDirectory)
                },
            ),
        )
    }
    return KcodeAgentRuntime(
        chatService = pluginRuntime.chatService,
        webContainerController = webContainerController,
        artifactRepository = artifactRepository,
        pluginManager = pluginRuntime.pluginManager,
        owner = pluginRuntime,
        applicationContent = pluginRuntime,
    )
}

private suspend fun confirmDesktopToolCall(request: ToolApprovalRequest): Boolean =
    withContext(Dispatchers.Swing) {
        JOptionPane.showConfirmDialog(
            null,
            "kcode wants to use ${request.name}.\n\nPurpose\n${request.description.ifBlank { request.name }.take(2_048)}\n\nInput\n${request.input.take(8_192)}",
            "Allow tool call?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        ) == JOptionPane.YES_OPTION
    }
