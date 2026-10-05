package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.chat.ConversationResponseRequest
import ai.meteor.kcode.chat.ConversationSession
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.localization.configuredLanguage
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.KcodeGoals
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.api.KcodeModelSettings
import ai.meteor.kcode.plugin.api.KcodeScheduledTaskNotifications
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.scheduledispatch.DispatchLabels
import ai.meteor.kcode.plugin.scheduledispatch.PersistedScheduledTaskCompletionSession
import ai.meteor.kcode.plugin.scheduledispatch.scheduledTaskExecutionPrompt
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.cordis.ServiceKey

/** Dispatch belongs to the feature lifetime; presentation and notification providers are optional. */
object ScheduleDispatchPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "schedule-dispatch"
    override val inject = dependencies(
        KcodeSessions.Key, KcodeSchedules.Key, KcodeConversationExecution.Key,
        KcodeSettings.Key, KcodeModelSettings.Key, KcodeLlm.Key, KcodeAgents.Key, KcodeGeneration.Key,
    )

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val targets = MutableStateFlow<Set<ConversationState>>(emptySet())
        var session: ConversationSession? = null
        effect.collect {
            val failures = mutableListOf<Throwable>()
            suspend fun release(block: suspend () -> Unit) {
                try { block() } catch (error: Throwable) { failures += error }
            }
            release { owner.close() }
            targets.value.toList().mapNotNull { it.runningJob }.distinct().forEach { running ->
                release { running.cancelAndJoin() }
            }
            targets.value.toList().forEach { target ->
                release { session?.discardPendingStandaloneConversation(target.id) }
            }
            release { session?.close() }
            release { job.cancelAndJoin() }
            targets.value = emptySet()
            if (failures.isNotEmpty()) throw PluginCleanupException(name, failures)
        }
        val localization = bindOptional(ctx, effect, KcodeLocalization.Key)
        val notifications = bindOptional(ctx, effect, KcodeScheduledTaskNotifications.Key)
        val goals = bindOptional(ctx, effect, KcodeGoals.Key)
        val conversations = ctx.require(KcodeSessions.Key).factory.create(scope)
        session = conversations
        val coordinator = ctx.require(KcodeSchedules.Key).coordinator
        val execution = ctx.require(KcodeConversationExecution.Key).executor
        val settings = ctx.require(KcodeSettings.Key).store
        val modelSettings = ctx.require(KcodeModelSettings.Key).policy
        val llm = ctx.require(KcodeLlm.Key)
        val chat = ctx.require(KcodeAgents.Key).chatService
        val generation = ctx.require(KcodeGeneration.Key).runner

        suspend fun text(committed: StoredAppSettings, key: LocalizedText, vararg arguments: Any): String {
            val catalog = localization()?.catalog
            val language = catalog?.let { runCatching { it.configuredLanguage(committed) }.getOrNull() }
                ?: AppLanguage.English
            val translated = catalog?.let { runCatching { it.translate(language, key, *arguments) }.getOrNull() }
            if (translated != null) return translated
            var fallback = DispatchLabels.getValue(key.key)
            arguments.forEachIndexed { index, value -> fallback = fallback.replace("%${index + 1}\$s", value.toString()) }
            return fallback
        }

        suspend fun dispatch(task: ScheduledTask): Boolean {
            val committed = settings.load()
            val configuration = modelSettings.resolve(committed, llm.catalog()) ?: return false
            val messages = ChatFailureMessages(
                text(committed, UiText.SetupModelFirst),
                text(committed, UiText.ModelConnectionFailed),
            )
            val target = conversations.createPendingStandaloneConversation(task.name)
            targets.update { it + target }
            val completion = PersistedScheduledTaskCompletionSession { result ->
                owner.run { conversations.setPendingStandaloneResult(target.id, result) }
            }
            suspend fun discard() {
                conversations.discardPendingStandaloneConversation(target.id)
                targets.update { it - target }
            }
            return try {
                val accepted = execution.startResponse(
                    target = target,
                    request = ConversationResponseRequest(
                        prompt = scheduledTaskExecutionPrompt(task.prompt),
                        userMessage = task.prompt,
                        goalSession = goals()?.sessions?.create(target),
                        scheduledTaskSession = coordinator.sessionFor(target.id, target.title),
                        scheduledTaskCompletionSession = completion,
                    ),
                    configuration = configuration,
                    service = chat,
                    generationRunner = generation,
                    failureMessages = messages,
                    onResponseFinished = { completed ->
                        owner.runIfOpen {
                            if (!completed) {
                                discard()
                            } else {
                                val result = completion.result()
                                if (result != null) conversations.appendPendingStandaloneResultMessage(target.id)
                                conversations.revealStandaloneConversation(target.id)
                                targets.update { it - target }
                                // A missing, withdrawn or failed notification bridge never invalidates a durable result.
                                try {
                                    val notificationHost = notifications()?.host
                                    if (notificationHost != null && !notificationHost.isAppInForeground()) {
                                        notificationHost.showTriggeredNotification(
                                            text(committed, UiText.ScheduledTaskTriggered),
                                            result ?: text(committed, UiText.ScheduledTaskProcessAvailable, task.name),
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Throwable) {
                                    Unit
                                }
                            }
                        }
                    },
                )
                if (!accepted) discard()
                accepted
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { discard() }
                throw cancelled
            } catch (_: Throwable) {
                discard()
                false
            }
        }

        scope.launch {
            owner.runIfOpen {
                while (!conversations.isLoaded) {
                    conversations.load()
                    if (!conversations.isLoaded) delay(5_000)
                }
                coordinator.run { task -> owner.run { dispatch(task) } }
            }
        }
    }
}

fun scheduleDispatchPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.schedules.application", "builtin", "built-in", setOf("schedule.dispatch")),
    ScheduleDispatchPlugin,
    Unit,
)

private class OptionalBinding<T>(val value: T)

/** Optional services use their own reactive dependency boundary, without suspending dispatch. */
private suspend fun <T : Any> bindOptional(ctx: Context, effect: EffectScope, key: ServiceKey<T>): () -> T? {
    val current = MutableStateFlow<OptionalBinding<T>?>(null)
    val fiber = ctx.plugin(plugin<Unit>(name = "schedule-optional-${key.name}", inject = dependencies(key)) { child, _ ->
        val token = OptionalBinding(child.require(key))
        current.value = token
        collect { current.compareAndSet(token, null); Unit }
    }, Unit)
    effect.collect { fiber.dispose() }
    return { current.value?.value }
}
