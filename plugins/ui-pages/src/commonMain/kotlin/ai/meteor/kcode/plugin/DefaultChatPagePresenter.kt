package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.UnavailableGoalSessions
import ai.meteor.kcode.chat.UnavailableScheduledTasks
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.plugin.api.ApplicationServices
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.KcodeGoals
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodeModelSettings
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.plugin.ui.api.ChatPageRequest
import ai.meteor.kcode.plugin.ui.api.NavigationPagePresenter
import ai.meteor.kcode.plugin.ui.api.NavigationPageRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import androidx.compose.runtime.key

/** Page-owned dependencies are resolved before the application frame is committed. */
internal object DefaultChatPagePresenter : NavigationPagePresenter {
    override suspend fun prepare(services: ApplicationServices): UiRenderer<NavigationPageRequest>? {
        val chat = services[KcodeAgents.Key]?.chatService ?: return null
        val history = services[KcodeHistory.Key]?.repository ?: return null
        val generation = services[KcodeGeneration.Key]?.runner
        val execution = services[KcodeConversationExecution.Key]?.executor
        val models = services[KcodeModelSettings.Key]?.policy ?: return null
        val catalog = services.modelCatalog ?: services[KcodeLlm.Key]?.catalog() ?: ModelCatalogSnapshot()
        val goals = services[KcodeGoals.Key]?.sessions ?: UnavailableGoalSessions
        val schedules = services[KcodeSchedules.Key]?.coordinator ?: UnavailableScheduledTasks
        return UiRenderer { request ->
            val session = request.conversationSession ?: return@UiRenderer
            val runner = request.hostOptions.generationRunner ?: generation ?: return@UiRenderer
            val configuration = models.resolve(request.committedSettings, catalog)
            request.slots.chat?.let { renderer ->
                key(renderer) {
                    renderer.Render(ChatPageRequest(
                        modifier = request.modifier,
                        compact = request.compact,
                        conversation = session.conversations.firstOrNull { it.id == session.activeId },
                        conversationExecution = execution,
                        service = chat,
                        generationRunner = runner,
                        configuration = configuration,
                        onConfigurationChange = { value ->
                            request.settingsEditor.submit(models.update(request.settingsEditor.draft, value))
                        },
                        onMenu = request.onMenu,
                        onSettings = request.onSettings,
                        onNewConversation = request.onNewConversation,
                        onSendToNew = session::ensureConversation,
                        historyRepository = history,
                        goalSessionFactory = goals,
                        scheduledTaskCoordinator = schedules,
                        settingsEditor = request.settingsEditor.takeIf { request.hostOptions.conversationSettingsControlsAvailable },
                    ))
                }
            }
        }
    }
}
