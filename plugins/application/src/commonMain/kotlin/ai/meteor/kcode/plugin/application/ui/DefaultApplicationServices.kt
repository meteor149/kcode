package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ConversationCommandSnapshot
import ai.meteor.kcode.chat.UnavailableGoalSessions
import ai.meteor.kcode.chat.UnavailableScheduledTasks
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.plugin.ui.api.ApplicationViewServices
import ai.meteor.kcode.plugin.api.ApplicationServices
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeConversationCommands
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.KcodeGoals
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodeModelSettings
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.KcodeShellMode
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.DefaultUiSnapshotKey

/** The default product chooses its feature requirements; other roots do not pass this gate. */
internal suspend fun defaultApplicationServices(ctx: ApplicationServices): ApplicationViewServices? {
    val settings = ctx[KcodeSettings.Key]?.mutationStore ?: return null
    val history = ctx[KcodeHistory.Key]?.repository
    val sessions = ctx[KcodeSessions.Key]?.factory
    val slots = ctx.uiContributions?.let { it[DefaultUiSnapshotKey] ?: ApplicationUiSlots() }
        ?: ctx[KcodeUiSlots.Key]?.snapshot() ?: ApplicationUiSlots()
    val preparedSlots = if (slots.navigation.none { it.presenter != null }) slots else slots.copy(navigation = slots.navigation.mapNotNull { destination ->
        val presenter = destination.presenter ?: return@mapNotNull destination
        presenter.prepare(ctx)?.let { destination.copy(renderer = it, presenter = null) }
    })
    return ApplicationViewServices(
        chatService = ctx[KcodeAgents.Key]?.chatService ?: UnavailableDefaultUiChatService,
        settingsStore = settings,
        historyRepository = history,
        uiSlots = preparedSlots,
        goalSessions = ctx[KcodeGoals.Key]?.sessions ?: UnavailableGoalSessions,
        schedules = ctx[KcodeSchedules.Key]?.coordinator ?: UnavailableScheduledTasks,
        conversationSessions = sessions,
        conversationExecution = ctx[KcodeConversationExecution.Key]?.executor,
        modelCatalog = ctx.modelCatalog ?: ctx[KcodeLlm.Key]?.catalog() ?: ModelCatalogSnapshot(),
        commands = ctx.conversationCommands ?: ctx[KcodeConversationCommands.Key]?.commitSnapshot() ?: ConversationCommandSnapshot(),
        generationRunner = ctx[KcodeGeneration.Key]?.runner,
        modelSettingsPolicy = ctx[KcodeModelSettings.Key]?.policy,
        shellModeSettingsPolicy = ctx[KcodeShellMode.Key]?.policy?.settings,
        toolPermissionSettingsPolicy = ctx[KcodeInteraction.Key]?.policy?.settings,
    )
}

private object UnavailableDefaultUiChatService : ChatService {
    override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String =
        error("The default UI has no mounted agent provider")
}
