package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.pages.chat.StandaloneConversationOverlay
import ai.meteor.kcode.plugin.ui.api.StandaloneConversationRequest
import ai.meteor.kcode.plugin.pages.chat.ChatPane
import ai.meteor.kcode.plugin.ui.api.ChatPageRequest
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
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
            settingsEditor = request.settingsEditor,
        )
    }
}

object DefaultStandaloneConversationRenderer : UiRenderer<StandaloneConversationRequest> {
    @Composable
    override fun Render(request: StandaloneConversationRequest) {
        StandaloneConversationOverlay(request.conversation, request.pendingCount, request.onAddToRecent, request.onClose)
    }
}
