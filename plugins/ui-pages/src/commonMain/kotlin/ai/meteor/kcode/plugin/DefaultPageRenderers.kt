package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.pages.artifact.ArtifactsPage
import ai.meteor.kcode.plugin.pages.chat.StandaloneConversationOverlay
import ai.meteor.kcode.plugin.ui.api.StandaloneConversationRequest
import ai.meteor.kcode.plugin.pages.chat.ChatPane
import ai.meteor.kcode.plugin.ui.api.ChatPageRequest
import ai.meteor.kcode.plugin.ui.api.ArtifactsPageRequest
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.plugin.pages.settings.SettingsPageOverlay
import ai.meteor.kcode.plugin.pages.sidebar.SidebarScaffold
import ai.meteor.kcode.plugin.pages.sidebar.Sidebar
import ai.meteor.kcode.plugin.ui.api.ApplicationLayoutRequest
import ai.meteor.kcode.plugin.ui.api.SidebarPageRequest
import androidx.compose.runtime.Composable

object DefaultChatPageRenderer : UiRenderer<ChatPageRequest> {
    @Composable
    override fun Render(request: ChatPageRequest) {
        ChatPane(
            modifier = request.modifier,
            compact = request.compact,
            conversation = request.conversation,
            conversationExecution = request.conversationExecution,
            service = request.service,
            generationRunner = request.generationRunner,
            configuration = request.configuration,
            onConfigurationChange = request.onConfigurationChange,
            onMenu = request.onMenu,
            onSettings = request.onSettings,
            onNewConversation = request.onNewConversation,
            onSendToNew = request.onSendToNew,
            historyRepository = request.historyRepository,
            goalSessionFactory = request.goalSessionFactory,
            scheduledTaskCoordinator = request.scheduledTaskCoordinator,
            conversationExporter = request.conversationExporter,
            toolPermissionControlsAvailable = request.toolPermissionControlsAvailable,
            toolPermissionMode = request.toolPermissionMode,
            onToolPermissionModeChange = request.onToolPermissionModeChange,
        )
    }
}


object DefaultArtifactsPageRenderer : UiRenderer<ArtifactsPageRequest> {
    @Composable
    override fun Render(request: ArtifactsPageRequest) {
        ArtifactsPage(
            repository = request.repository,
            webContainerController = request.webContainerController,
            compact = request.compact,
            onMenu = request.onMenu,
            modifier = request.modifier,
        )
    }
}


object DefaultSettingsPageRenderer : UiRenderer<SettingsPageRequest> {
    @Composable
    override fun Render(request: SettingsPageRequest) {
        SettingsPageOverlay(request)
    }
}





object DefaultStandaloneConversationRenderer : UiRenderer<StandaloneConversationRequest> {
    @Composable
    override fun Render(request: StandaloneConversationRequest) {
        StandaloneConversationOverlay(request.conversation, request.pendingCount, request.onAddToRecent, request.onClose)
    }
}

object DefaultApplicationLayoutRenderer : UiRenderer<ApplicationLayoutRequest> {
    @Composable
    override fun Render(request: ApplicationLayoutRequest) = SidebarScaffold(request)
}

object DefaultSidebarRenderer : UiRenderer<SidebarPageRequest> {
    @Composable
    override fun Render(request: SidebarPageRequest) {
        Sidebar(
            conversations = request.conversations,
            activeId = request.activeId,
            destination = request.destination,
            navigation = request.navigation,
            conversationActionsAvailable = request.conversationActionsAvailable,
            settingsAvailable = request.settingsAvailable,
            compact = request.compact,
            width = request.width,
            onNew = request.onNew,
            onSelect = request.onSelect,
            onPin = request.onPin,
            onDelete = request.onDelete,
            onSettings = request.onSettings,
            onDestination = request.onDestination,
        )
    }
}
