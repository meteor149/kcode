package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.ConversationSessionFactory
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.ScheduledTaskCoordinator
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.KcodeGoals
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.ui.api.ApplicationEffectRequest
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull

@OptIn(ExperimentalComposeUiApi::class)
class ScheduleDispatchPrivateLifecycleTest {
    @Test
    fun actualJarWithdrawalJoinsTheRenderedDispatchLoopAndRejectsLateRendering(): Unit = runBlocking {
        val directory = Files.createTempDirectory("dispatch-lifecycle").toFile()
        val artifact = File(directory, "dispatch.jar")
        File(ScheduleDispatchPlugin::class.java.protectionDomain.codeSource.location.toURI()).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var slots: KcodeUiSlots
        lateinit var sessions: ConversationSessionFactory
        lateinit var execution: ConversationExecution
        lateinit var generation: ChatGenerationRunner
        lateinit var goals: GoalSessionFactory
        lateinit var history: ConversationHistoryRepository
        val capture = kcodePlugin(PluginDescriptor("test.dispatch", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-dispatch", inject = dependencies(KcodeUiSlots.Key, KcodeSessions.Key,
                KcodeConversationExecution.Key, KcodeGeneration.Key, KcodeGoals.Key, KcodeHistory.Key),
        ) { ctx, _ ->
            slots = ctx.require(KcodeUiSlots.Key)
            sessions = ctx.require(KcodeSessions.Key).factory
            execution = ctx.require(KcodeConversationExecution.Key).executor
            generation = ctx.require(KcodeGeneration.Key).runner
            goals = ctx.require(KcodeGoals.Key).sessions
            history = ctx.require(KcodeHistory.Key).repository
        }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        var starts = 0
        val coordinator = object : ScheduledTaskCoordinator {
            override fun sessionFor(conversationId: Long, title: String): ScheduledTaskSession? = null
            override fun notifyChanged() = Unit
            override suspend fun run(onDue: suspend (ScheduledTask) -> Boolean) {
                starts++
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            }
        }
        val session = sessions.create(CoroutineScope(coroutineContext))
        try {
            session.load()
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "consumer.schedules.application", version = "private-dispatch", artifactPath = artifact.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
                entryClass = ScheduleDispatchPlugin::class.java.name,
            ))
            val renderer = slots.snapshot().effects.single { it.id == "schedule.dispatch" }.renderer
            assertNotSame(ScheduleDispatchPlugin::class.java.classLoader, renderer.javaClass.classLoader)
            val request = ApplicationEffectRequest(session, execution, null, runtime.chatService,
                generation, history, goals, coordinator, AppLanguage.English)
            withContext(Dispatchers.Main.immediate) {
                val scene = ImageComposeScene(width = 10, height = 10, coroutineContext = coroutineContext) {
                    renderer.Render(request)
                }
                try {
                    scene.render(0L).close()
                    withTimeout(5_000) { entered.await() }
                    val withdrawing = async { runtime.pluginManager.setEnabled("consumer.schedules.application", false) }
                    withTimeout(5_000) { cleanupEntered.await() }
                    assertFalse(withdrawing.isCompleted)
                    cleanupRelease.complete(Unit)
                    withTimeout(5_000) { withdrawing.await() }
                    assertNull(slots.snapshot().effects.singleOrNull { it.id == "schedule.dispatch" })
                    scene.setContent { renderer.Render(request.copy(language = AppLanguage.Chinese)) }
                    scene.render(1L).close()
                    delay(50)
                    assertEquals(1, starts)
                } finally { scene.close() }
            }
        } finally {
            cleanupRelease.complete(Unit)
            session.close()
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
