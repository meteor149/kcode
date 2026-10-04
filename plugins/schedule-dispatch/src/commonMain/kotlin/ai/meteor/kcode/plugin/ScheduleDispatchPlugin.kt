package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.scheduledispatch.PersistedScheduledTaskCompletionSession
import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.plugin.api.KcodeScheduledTaskNotifications
import ai.meteor.kcode.chat.ConversationResponseRequest
import ai.meteor.kcode.plugin.scheduledispatch.scheduledTaskExecutionPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.TranslationCatalog
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.ui.api.MarkdownContent
import ai.meteor.kcode.plugin.ui.api.KcodeMarkdown
import ai.meteor.kcode.plugin.ui.api.ApplicationEffect
import ai.meteor.kcode.plugin.ui.api.ApplicationEffectRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.plugin.api.PluginDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.plugin

private class ScheduleDispatchRenderer(
    private val scheduledTaskPlatformHost: ScheduledTaskPlatformHost,
    private val markdown: MarkdownContent,
    private val localization: TranslationCatalog,
    private val owner: PluginOperationOwner,
) : UiRenderer<ApplicationEffectRequest> {
    @Composable
    override fun Render(request: ApplicationEffectRequest) {
        val execution = request.conversationExecution ?: return
        val conversationSession = request.conversationSession
        val configuration = request.configuration
        val chatService = request.chatService
        val generationRunner = request.generationRunner
        val goalSessionFactory = request.goalSessionFactory
        val scheduledTaskCoordinator = request.scheduledTaskCoordinator
        val appLanguage = request.language
        val loaded = conversationSession.isLoaded
        LaunchedEffect(scheduledTaskCoordinator, configuration, appLanguage, conversationSession, goalSessionFactory, execution, loaded) {
            if (!loaded) return@LaunchedEffect
            owner.runIfOpen {
                scheduledTaskCoordinator.run { task ->
                    if (configuration == null) return@run false
                    val scheduledTaskFailureMessages = ChatFailureMessages(
                        setupModel = localization.translate(appLanguage, UiText.SetupModelFirst),
                        connectionFailed = localization.translate(appLanguage, UiText.ModelConnectionFailed),
                    )
                    val scheduledTaskNotificationTitle = localization.translate(appLanguage, UiText.ScheduledTaskTriggered)
                    val target = conversationSession.createPendingStandaloneConversation(task.name)
                    val completionSession = PersistedScheduledTaskCompletionSession { result ->
                        owner.run { conversationSession.setPendingStandaloneResult(target.id, result) }
                    }
                    val accepted = try {
                        execution.startResponse(
                            target = target,
                            request = ConversationResponseRequest(
                                prompt = scheduledTaskExecutionPrompt(task.prompt),
                                userMessage = task.prompt,
                                goalSession = goalSessionFactory.create(target),
                                scheduledTaskSession = scheduledTaskCoordinator.sessionFor(target.id, target.title),
                                scheduledTaskCompletionSession = completionSession,
                            ),
                            configuration = configuration,
                            service = chatService,
                            generationRunner = generationRunner,
                            failureMessages = scheduledTaskFailureMessages,
                            onResponseFinished = { completed ->
                                owner.runIfOpen {
                                    val result = completionSession.result()
                                    if (completed) {
                                        if (result != null) {
                                            conversationSession.appendPendingStandaloneResultMessage(target.id)
                                        }
                                        conversationSession.revealStandaloneConversation(target.id)
                                        if (!scheduledTaskPlatformHost.isAppInForeground()) {
                                            scheduledTaskPlatformHost.showTriggeredNotification(
                                                scheduledTaskNotificationTitle,
                                                result?.let { markdown.plainText(it).ifBlank { it } }
                                                    ?: localization.translate(
                                                        appLanguage,
                                                        UiText.ScheduledTaskProcessAvailable,
                                                        task.name,
                                                    ),
                                            )
                                        }
                                    } else {
                                        conversationSession.discardPendingStandaloneConversation(target.id)
                                    }
                                }
                            },
                        )
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            conversationSession.discardPendingStandaloneConversation(target.id)
                        }
                        throw cancelled
                    } catch (error: Throwable) {
                        conversationSession.discardPendingStandaloneConversation(target.id)
                        false
                    }
                    accepted
                }
            }
        }
    }
}

object ScheduleDispatchPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "schedule-dispatch"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeSessions.Key, KcodeSchedules.Key, KcodeConversationExecution.Key, KcodeScheduledTaskNotifications.Key, KcodeMarkdown.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        effect.collect { owner.close() }
        effect.collect(ctx.require(KcodeUiSlots.Key).registerEffect(ApplicationEffect("schedule.dispatch", 100, ScheduleDispatchRenderer(ctx.require(KcodeScheduledTaskNotifications.Key).host, ctx.require(KcodeMarkdown.Key).content, ctx.require(KcodeLocalization.Key).catalog, owner))))
    }
}

fun scheduleDispatchPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.schedules.application", "builtin", "built-in", setOf("uiSlots", "schedule.dispatch")),
    ScheduleDispatchPlugin,
    Unit,
)
