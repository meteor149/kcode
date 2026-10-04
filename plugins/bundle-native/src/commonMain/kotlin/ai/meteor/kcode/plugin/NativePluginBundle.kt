package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.SettingsStoreFactory
import ai.meteor.kcode.plugin.settingsstorage.SettingsProviderPlugin
import ai.meteor.kcode.plugin.settingsstorage.FactorySettingsProviderPlugin

import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory
import ai.meteor.kcode.plugin.history.memoryHistoryRepositoryFactory
import ai.meteor.kcode.plugin.history.HistoryProviderPlugin
import ai.meteor.kcode.plugin.history.FactoryHistoryProviderPlugin

import ai.meteor.kcode.plugin.settingscommands.SettingsCommandsPlugin
import ai.meteor.kcode.plugin.provider.SearchSettingsProviderPlugin
import ai.meteor.kcode.plugin.goalui.GoalRestorationEffectPlugin
import ai.meteor.kcode.plugin.goalui.GoalDecorationPlugin
import ai.meteor.kcode.plugin.export.ConversationImageSavingProviderPlugin
import ai.meteor.kcode.plugin.export.unsupportedConversationImageSaverFactory
import ai.meteor.kcode.plugin.notifications.ScheduledTaskNotificationsProviderPlugin
import ai.meteor.kcode.plugin.notifications.foregroundOnlyNotificationsFactory
import ai.meteor.kcode.plugin.export.ConversationExportPlugin
import ai.meteor.kcode.plugin.export.ConversationImageRenderingPlugin

import ai.meteor.kcode.plugin.api.ConversationOverlayFactory
import ai.meteor.kcode.plugin.overlay.ConversationOverlayProviderPlugin
import ai.meteor.kcode.plugin.overlay.ConversationOverlaysServicePlugin
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.ArtifactFileStore
import ai.meteor.kcode.plugin.artifacts.ArtifactsProviderPlugin
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsFactory
import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
import ai.meteor.kcode.plugin.api.ArtifactFileStoreFactory
import ai.meteor.kcode.plugin.artifacts.FactoryFileArtifactsProviderPlugin
import ai.meteor.kcode.plugin.artifacts.EmptyArtifactsProviderPlugin
import ai.meteor.kcode.plugin.artifacts.FileArtifactsProviderPlugin
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.plugin.api.ConversationOverlayHostState
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginCompositionStore
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.plugin.messagecodec.MessageCodecProviderPlugin
import ai.meteor.kcode.plugin.markdown.MarkdownProviderPlugin
import ai.meteor.kcode.plugin.markdown.MarkdownUiContributionPlugin
import ai.meteor.kcode.plugin.modelsettings.ModelSettingsProviderPlugin
import ai.meteor.kcode.plugin.localization.LocalizationProviderPlugin
import ai.meteor.kcode.plugin.localization.LocalizationUiContributionPlugin
import org.cordis.Plugin

/** Deployment inputs belong to the composition bundle, not individual consumers. */
data class NativePluginServices(
    val interactionPolicy: InteractionPolicy,
    val settingsBackedInteraction: Boolean = false,
    val skillRuntime: SkillRuntime?,
    val conversationOverlayFactory: ConversationOverlayFactory,
    val settingsStore: AppSettingsStore?,
    val settingsStoreFactory: SettingsStoreFactory? = null,
    val historyRepository: ConversationHistoryRepository?,
    val artifactRepository: ArtifactRepository?,
    val webContainerController: WebContainerController?,
    val pluginCompositionStore: PluginCompositionStore? = null,
    val artifactFileStore: ArtifactFileStore? = null,
    val artifactFileStoreFactory: ArtifactFileStoreFactory? = null,
    val conversationImageSaverFactory: ConversationImageSaverFactory? = null,
    val scheduledTaskNotificationsFactory: ScheduledTaskNotificationsFactory? = null,
    val historyRepositoryFactory: HistoryRepositoryFactory? = null,
    val conversationOverlayHostState: ConversationOverlayHostState = ConversationOverlayHostState(),
)

private fun builtinDescriptor(id: String, vararg capabilities: String) =
    PluginDescriptor(id, "builtin", "built-in", capabilities.toSet())

private fun <C> builtin(id: String, plugin: Plugin<C>, config: C, vararg capabilities: String) =
    kcodePlugin(builtinDescriptor(id, *capabilities), plugin, config)

fun nativePluginBundle(config: NativePluginServices): List<KcodePluginMount> = listOf(
    builtin("provider.plugin-installations.platform", PluginInstallationsProviderPlugin, config.pluginCompositionStore, "pluginInstallations"),
    builtin("core.conversation-overlays", ConversationOverlaysServicePlugin, config.conversationOverlayHostState, "conversationOverlays"),
    builtin("provider.conversation-overlay.platform", ConversationOverlayProviderPlugin, config.conversationOverlayFactory, "conversationOverlay"),
    builtin("core.tools", ToolsServicePlugin, Unit, "tools"),
    builtin("core.system-prompt", SystemPromptServicePlugin, Unit, "systemPrompt"),
    builtin("core.conversation-commands", ConversationCommandsServicePlugin, Unit, "conversationCommands"),
    builtin("core.llm", LlmServicePlugin, Unit, "llm"),
    builtin("provider.localization.default", LocalizationProviderPlugin, Unit, "localization"),
    builtin("consumer.localization.ui", LocalizationUiContributionPlugin, Unit, "uiSlots", "localization"),
    builtin("provider.model-settings.catalog", ModelSettingsProviderPlugin, Unit, "modelSettings"),
    builtin("core.continuations", ContinuationServicePlugin, Unit, "continuations"),
    builtin("provider.subagents.in-process", InProcessSubagentProviderPlugin, Unit, "subagents"),
    builtin("provider.prompt.default", DefaultSystemPromptPlugin, Unit, "systemPrompt"),
    builtin("provider.skills.platform", SkillServicePlugin, SkillServicePluginConfig(config.skillRuntime), "skills"),
    builtin("consumer.tools.subagent", SubagentToolConsumerPlugin, Unit, "subagent", "tools"),
    builtin("consumer.commands.goal", GoalCommandConsumerPlugin, Unit, "conversationCommands"),
    builtin("consumer.tools.goal", GoalToolConsumerPlugin, Unit, "goal", "tools"),
    builtin("consumer.tools.schedule", ScheduledTaskToolConsumerPlugin, Unit, "schedule", "tools"),
    builtin("provider.continuation.subagent", SubagentContinuationPlugin, Unit, "subagent", "continuations"),
    builtin("provider.continuation.goal", GoalContinuationPlugin, Unit, "goal", "continuations"),
    if (config.settingsBackedInteraction) {
        builtin("provider.interaction.platform", SettingsInteractionProviderPlugin, config.interactionPolicy.approver, "interaction")
    } else {
        builtin("provider.interaction.platform", InteractionServicePlugin, InteractionPluginConfig(config.interactionPolicy), "interaction")
    },
    config.settingsStoreFactory?.let {
        builtin("provider.settings.platform", FactorySettingsProviderPlugin, it, "settings")
    } ?: builtin("provider.settings.platform", SettingsProviderPlugin, config.settingsStore, "settings"),
    builtin("provider.search-settings.http", SearchSettingsProviderPlugin, Unit, "searchSettings"),
    builtin("consumer.settings.commands", SettingsCommandsPlugin, Unit, "settingsCommands"),
    config.historyRepositoryFactory?.let {
        builtin("provider.history.platform", FactoryHistoryProviderPlugin, it, "history")
    } ?: config.historyRepository?.let {
        builtin("provider.history.platform", HistoryProviderPlugin, it, "history")
    } ?: builtin("provider.history.platform", FactoryHistoryProviderPlugin, memoryHistoryRepositoryFactory(), "history"),
    builtin("provider.goal-sessions.history", GoalSessionProviderPlugin, Unit, "goals"),
    builtin("provider.schedules.history", ScheduledTaskProviderPlugin, Unit, "schedules"),
    builtin("provider.markdown.default", MarkdownProviderPlugin, Unit, "markdown"),
    builtin("consumer.markdown.ui", MarkdownUiContributionPlugin, Unit, "uiSlots", "markdown"),
    builtin("provider.message-codec.envelope", MessageCodecProviderPlugin, Unit, "messageCodec"),
    builtin("provider.sessions.history", SessionHistoryProviderPlugin, Unit, "sessions"),
    builtin("provider.notifications.platform", ScheduledTaskNotificationsProviderPlugin, config.scheduledTaskNotificationsFactory ?: foregroundOnlyNotificationsFactory(), "scheduledTaskNotifications"),
    builtin("provider.export.image-rendering", ConversationImageRenderingPlugin, Unit, "conversationImageRendering"),
    builtin("provider.export.image-saving", ConversationImageSavingProviderPlugin, config.conversationImageSaverFactory ?: unsupportedConversationImageSaverFactory(), "conversationImageSaving"),
    builtin("provider.export.conversation", ConversationExportPlugin, Unit, "conversationExport"),
    builtin("provider.generation", GenerationProviderPlugin(), Unit, "generation"),
    builtin("provider.conversation-execution.history", ConversationExecutionProviderPlugin, Unit, "conversationExecution"),
    config.artifactFileStoreFactory?.let {
        builtin("provider.artifacts.platform", FactoryFileArtifactsProviderPlugin, it, "artifacts")
    } ?: config.artifactFileStore?.let {
        builtin("provider.artifacts.platform", FileArtifactsProviderPlugin, it, "artifacts")
    } ?: config.artifactRepository?.let {
        builtin("provider.artifacts.platform", ArtifactsProviderPlugin, it, "artifacts")
    } ?: builtin("provider.artifacts.platform", EmptyArtifactsProviderPlugin, Unit, "artifacts"),
    builtin("provider.web-containers.platform", WebContainersProviderPlugin, config.webContainerController, "webContainers"),
    builtin("provider.agent-loop.koog", KoogAgentLoopPlugin, Unit, "agents", "agentLoop"),
    builtin("core.ui-slots", UiSlotsServicePlugin, Unit, "uiSlots"),
    builtin("provider.ui.chat.goal", GoalDecorationPlugin, Unit, "uiSlots"),
    builtin("consumer.goals.chat-restoration", GoalRestorationEffectPlugin, Unit, "uiSlots"),
    builtin("provider.ui.compose", DefaultApplicationUiPlugin, Unit, "applicationUi"),
) + defaultModelAdapterPlugins() + defaultUiPagePlugins() + defaultNavigationPlugins() + defaultSettingsPlugins() + defaultMessagePresentationPlugins() + scheduleDispatchPlugin()


/** Restore the previous aggregate enable state without reintroducing an aggregate provider. */
val NativeBuiltinAliases: Map<String, Set<String>> = mapOf(
    "provider.llm.koog" to ai.meteor.kcode.model.ModelProvider.entries.mapTo(linkedSetOf()) {
        "provider.llm.koog.${it.name}"
    },
)
