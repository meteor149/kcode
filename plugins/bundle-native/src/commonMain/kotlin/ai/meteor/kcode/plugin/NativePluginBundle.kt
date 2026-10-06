package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.SettingsStoreFactory
import ai.meteor.kcode.plugin.settingsstorage.SettingsProviderPlugin

import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory
import ai.meteor.kcode.plugin.history.memoryHistoryRepositoryFactory
import ai.meteor.kcode.plugin.api.HistoryRepositoryResource
import ai.meteor.kcode.plugin.history.FactoryHistoryProviderPlugin

import ai.meteor.kcode.plugin.settingscommands.SettingsCommandsPlugin
import ai.meteor.kcode.plugin.websearch.WebSearchFeaturePlugin
import ai.meteor.kcode.plugin.export.ConversationExportFeaturePlugin
import ai.meteor.kcode.plugin.notifications.ScheduledTaskNotificationsProviderPlugin
import ai.meteor.kcode.plugin.notifications.foregroundOnlyNotificationsFactory

import ai.meteor.kcode.plugin.api.ConversationOverlayFactory
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsFactory
import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.plugin.api.ConversationOverlayHostState
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginCompositionStore
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.plugin.messagecodec.MessageCodecProviderPlugin
import ai.meteor.kcode.plugin.markdown.MarkdownFeaturePlugin
import ai.meteor.kcode.plugin.modelsettings.ModelSettingsProviderPlugin
import ai.meteor.kcode.plugin.profileui.DefaultProfileUiPlugin
import ai.meteor.kcode.plugin.localization.LocalizationFeaturePlugin
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
    val pluginCompositionStore: PluginCompositionStore? = null,
    val conversationImageSaverFactory: ConversationImageSaverFactory? = null,
    val scheduledTaskNotificationsFactory: ScheduledTaskNotificationsFactory? = null,
    val historyRepositoryFactory: HistoryRepositoryFactory? = null,
    val conversationOverlayHostState: ConversationOverlayHostState = ConversationOverlayHostState(),
    val packagedProviderIds: Set<String> = emptySet(),
)

private fun builtinDescriptor(id: String, vararg capabilities: String) =
    PluginDescriptor(id, "builtin", "built-in", capabilities.toSet())

private fun <C> builtin(id: String, plugin: Plugin<C>, config: C, vararg capabilities: String) =
    kcodePlugin(builtinDescriptor(id, *capabilities), plugin, config)

fun nativePluginBundle(config: NativePluginServices): List<KcodePluginMount> = listOfNotNull(
    builtin("provider.plugin-installations.platform", PluginInstallationsProviderPlugin, config.pluginCompositionStore, "pluginInstallations"),
    if ("core.conversation-overlays" !in config.packagedProviderIds) builtin("core.conversation-overlays", HostOverlayRegistryInputPlugin, config.conversationOverlayHostState, "conversationOverlays") else null,
    if ("provider.conversation-overlay.platform" !in config.packagedProviderIds) builtin("provider.conversation-overlay.platform", HostOverlayProviderInputPlugin, config.conversationOverlayFactory, "conversationOverlay") else null,
    if ("core.tools" !in config.packagedProviderIds) builtin("core.tools", ToolsServicePlugin, Unit, "tools") else null,
    if ("core.system-prompt" !in config.packagedProviderIds) builtin("core.system-prompt", SystemPromptServicePlugin, Unit, "systemPrompt") else null,
    if ("core.conversation-commands" !in config.packagedProviderIds) builtin("core.conversation-commands", ConversationCommandsServicePlugin, Unit, "conversationCommands") else null,
    if ("core.llm" !in config.packagedProviderIds) builtin("core.llm", LlmServicePlugin, Unit, "llm") else null,
    if ("feature.localization" !in config.packagedProviderIds) builtin("feature.localization", LocalizationFeaturePlugin, Unit, "localization", "uiSlots") else null,
    if ("provider.model-settings.catalog" !in config.packagedProviderIds) builtin("provider.model-settings.catalog", ModelSettingsProviderPlugin, Unit, "modelSettings") else null,
    if ("core.continuations" !in config.packagedProviderIds) builtin("core.continuations", ContinuationServicePlugin, Unit, "continuations") else null,
    if ("feature.subagents" !in config.packagedProviderIds) builtin("feature.subagents", SubagentFeaturePlugin, Unit, "subagents", "tools", "continuations", "uiSlots") else null,
    if ("provider.prompt.default" !in config.packagedProviderIds) builtin("provider.prompt.default", DefaultSystemPromptPlugin, Unit, "systemPrompt") else null,
    if ("provider.skills.platform" !in config.packagedProviderIds) builtin("provider.skills.platform", SkillServicePlugin, SkillServicePluginConfig(config.skillRuntime), "skills") else null,
    if ("feature.schedule" !in config.packagedProviderIds) builtin("feature.schedule", ScheduleFeaturePlugin, Unit, "schedules", "tools", "schedule.dispatch") else null,
    if ("feature.goal" !in config.packagedProviderIds) builtin("feature.goal", GoalFeaturePlugin, Unit, "goals", "tools", "conversationCommands", "continuations", "uiSlots") else null,
    if ("provider.interaction.platform" in config.packagedProviderIds) null else if (config.settingsBackedInteraction) {
        builtin("provider.interaction.platform", SettingsApproverInteractionPlugin, config.interactionPolicy.approver, "interaction")
    } else {
        builtin("provider.interaction.platform", HostInteractionInputPlugin, config.interactionPolicy, "interaction")
    },
    if ("provider.settings.platform" in config.packagedProviderIds) null else config.settingsStoreFactory?.let {
        builtin("provider.settings.platform", HostSettingsInputPlugin, it, "settings")
    } ?: config.settingsStore?.let { store ->
        builtin("provider.settings.platform", HostSettingsInputPlugin, SettingsStoreFactory {
            ai.meteor.kcode.plugin.api.SettingsStoreResource(store) {}
        }, "settings")
    } ?: builtin("provider.settings.platform", SettingsProviderPlugin, null, "settings"),
    if ("feature.web-search" !in config.packagedProviderIds) builtin("feature.web-search", WebSearchFeaturePlugin, Unit, "web", "tools", "searchSettings") else null,
    if ("consumer.settings.commands" !in config.packagedProviderIds) builtin("consumer.settings.commands", SettingsCommandsPlugin, Unit, "settingsCommands") else null,
    if ("provider.history.platform" in config.packagedProviderIds) null else config.historyRepositoryFactory?.let {
        builtin("provider.history.platform", HostHistoryInputPlugin, it, "history")
    } ?: config.historyRepository?.let {
        builtin("provider.history.platform", HostHistoryInputPlugin, HistoryRepositoryFactory {
            HistoryRepositoryResource(it) {}
        }, "history")
    } ?: builtin("provider.history.platform", FactoryHistoryProviderPlugin, memoryHistoryRepositoryFactory(), "history"),
    if ("feature.markdown" !in config.packagedProviderIds) builtin("feature.markdown", MarkdownFeaturePlugin, Unit, "markdown", "uiSlots") else null,
    if ("provider.message-codec.envelope" !in config.packagedProviderIds) builtin("provider.message-codec.envelope", MessageCodecProviderPlugin, Unit, "messageCodec") else null,
    if ("provider.sessions.history" !in config.packagedProviderIds) builtin("provider.sessions.history", SessionHistoryProviderPlugin, Unit, "sessions") else null,
    if ("provider.notifications.platform" !in config.packagedProviderIds) builtin("provider.notifications.platform", ScheduledTaskNotificationsProviderPlugin, config.scheduledTaskNotificationsFactory ?: foregroundOnlyNotificationsFactory(), "scheduledTaskNotifications") else null,
    if ("feature.conversation-export" !in config.packagedProviderIds) builtin("feature.conversation-export", ConversationExportFeaturePlugin, config.conversationImageSaverFactory ?: Unit, "conversationImageRendering", "conversationImageSaving", "conversationExport") else null,
    if ("provider.generation" !in config.packagedProviderIds) builtin("provider.generation", GenerationProviderPlugin(), Unit, "generation") else null,
    if ("provider.conversation-execution.history" !in config.packagedProviderIds) builtin("provider.conversation-execution.history", ConversationExecutionProviderPlugin, Unit, "conversationExecution") else null,
    if ("provider.agent-loop.koog" !in config.packagedProviderIds) builtin("provider.agent-loop.koog", KoogAgentLoopPlugin, Unit, "agents", "agentLoop") else null,
    if ("core.ui-contributions" !in config.packagedProviderIds) builtin("core.ui-contributions", UiContributionsServicePlugin, Unit, "uiContributions") else null,
    if ("core.ui-slots" !in config.packagedProviderIds) builtin("core.ui-slots", UiSlotsServicePlugin, Unit, "uiSlots") else null,
    if ("provider.ui.compose" !in config.packagedProviderIds) builtin("provider.ui.compose", DefaultApplicationUiPlugin, Unit, "applicationUi") else null,
    if ("provider.ui.conversation.transcript" !in config.packagedProviderIds) builtin("provider.ui.conversation.transcript", DefaultConversationTranscriptUiPlugin, Unit, "uiSlots", "conversation.transcript") else null,
    if ("provider.ui.layout" !in config.packagedProviderIds) builtin("provider.ui.layout", DefaultLayoutUiPlugin, Unit, "uiSlots", "application.layout") else null,
    if ("provider.ui.sidebar" !in config.packagedProviderIds) builtin("provider.ui.sidebar", DefaultSidebarUiPlugin, Unit, "uiSlots", "page.sidebar") else null,
    if ("provider.ui.chat" !in config.packagedProviderIds) builtin("provider.ui.chat", DefaultChatUiPlugin, Unit, "uiSlots", "page.chat") else null,
    if ("provider.ui.conversation.standalone" !in config.packagedProviderIds) builtin("provider.ui.conversation.standalone", DefaultStandaloneConversationUiPlugin, Unit, "uiSlots", "conversation.standalone") else null,
    if ("provider.ui.settings" !in config.packagedProviderIds) builtin("provider.ui.settings", DefaultSettingsUiPlugin, Unit, "uiSlots", "page.settings") else null,
    if ("provider.ui.settings.profiles" !in config.packagedProviderIds) builtin("provider.ui.settings.profiles", DefaultProfileUiPlugin, Unit, "uiSlots", "profiles") else null,
    if ("provider.ui.theme" !in config.packagedProviderIds) builtin("provider.ui.theme", DefaultThemeUiPlugin, Unit, "uiSlots", "theme") else null,
    if ("provider.ui.navigation.chat" !in config.packagedProviderIds) builtin("provider.ui.navigation.chat", DefaultChatNavigationPlugin, Unit, "uiSlots", "navigation") else null,
    if ("provider.ui.message.user" !in config.packagedProviderIds) builtin("provider.ui.message.user", DefaultUserMessagePresentationPlugin, Unit, "uiSlots", "message.renderer") else null,
    if ("provider.ui.message.assistant" !in config.packagedProviderIds) builtin("provider.ui.message.assistant", DefaultAssistantMessagePresentationPlugin, Unit, "uiSlots", "message.renderer") else null,
    if ("provider.ui.message.error" !in config.packagedProviderIds) builtin("provider.ui.message.error", DefaultErrorMessagePresentationPlugin, Unit, "uiSlots", "message.renderer") else null,
    if ("provider.ui.tool.default" !in config.packagedProviderIds) builtin("provider.ui.tool.default", DefaultToolUsePresentationPlugin, Unit, "uiSlots", "tool.renderer") else null,
) + defaultModelAdapterPlugins(config.packagedProviderIds)


/** Map retired product identities to their current install/enable boundaries. */
val NativeBuiltinAliases: Map<String, Set<String>> = buildMap {
    listOf("provider.schedules.history", "consumer.tools.schedule", "consumer.schedules.application").forEach {
        put(it, setOf("feature.schedule"))
    }
    listOf("provider.export.image-rendering", "provider.export.image-saving", "provider.export.conversation").forEach {
        put(it, setOf("feature.conversation-export"))
    }
    listOf("provider.markdown.default", "consumer.markdown.ui").forEach {
        put(it, setOf("feature.markdown"))
    }
    listOf("provider.localization.default", "consumer.localization.ui").forEach {
        put(it, setOf("feature.localization"))
    }
    listOf("provider.subagents.in-process", "consumer.tools.subagent", "provider.continuation.subagent").forEach {
        put(it, setOf("feature.subagents"))
    }
    listOf("consumer.commands.goal", "consumer.tools.goal", "provider.continuation.goal",
        "provider.goal-sessions.history", "provider.ui.chat.goal", "consumer.goals.chat-restoration").forEach {
        put(it, setOf("feature.goal"))
    }
    listOf("provider.search-settings.http", "provider.web.search-http", "consumer.tools.web-search").forEach {
        put(it, setOf("feature.web-search"))
    }
    // Legacy UI-only packages are replaced by feature-owned child contributions.
    listOf("language", "model", "search", "shell").forEach { put("provider.ui.settings.$it", emptySet()) }
    put("provider.llm.koog", ai.meteor.kcode.model.ModelProvider.entries
        .filter { nativeBedrockAvailable() || it != ai.meteor.kcode.model.ModelProvider.Bedrock }
        .mapTo(linkedSetOf()) { "provider.llm.koog.${it.name}" })
    // Android's former no-op entry has no executable client and no package variant.
    if (!nativeBedrockAvailable()) put("provider.llm.koog.Bedrock", emptySet())
}
