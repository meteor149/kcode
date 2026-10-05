package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.ScheduledTaskCoordinator
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.history.ScheduledTaskStatus
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodeModelSettings
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.provider
import ai.meteor.kcode.test.temperature
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleDispatchPrivateLifecycleTest {
    @Test
    fun actualJarWithdrawalJoinsHeadlessLoopRejectsStaleCallbackAndRecoversOnce(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        var starts = 0
        lateinit var callback: suspend (ScheduledTask) -> Boolean
        val coordinator = object : ScheduledTaskCoordinator {
            override fun sessionFor(conversationId: Long, title: String): ScheduledTaskSession? = null
            override fun notifyChanged() = Unit
            override suspend fun run(onDue: suspend (ScheduledTask) -> Boolean) {
                callback = onDue
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
        val directory = Files.createTempDirectory("headless-dispatch").toFile()
        val runtime = runtime(directory, coordinator)
        try {
            val spec = importSpec(directory)
            runtime.pluginManager.replace(spec)
            withTimeout(5_000) { entered.await() }
            assertNotSame(ScheduleDispatchPlugin::class.java.classLoader, callback.javaClass.classLoader)
            assertFailsWith<IllegalStateException> { runtime.pluginManager.replace(spec.copy(version = "invalid", config = null)) }
            assertEquals(1, starts)
            val stale = callback
            val withdrawing = async { runtime.pluginManager.setEnabled(DispatchId, false) }
            withTimeout(5_000) { cleanupEntered.await() }
            assertFalse(withdrawing.isCompleted)
            cleanupRelease.complete(Unit)
            withTimeout(5_000) { withdrawing.await() }
            assertFailsWith<IllegalStateException> { stale(task("stale")) }
            runtime.pluginManager.setEnabled(DispatchId, true)
            withTimeout(5_000) { while (starts != 2) delay(10) }
            assertEquals(2, starts)
        } finally {
            cleanupRelease.complete(Unit)
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    @Test
    fun actualJarDispatchesWithoutUiMarkdownLocalizationNotificationsOrGoalsAndReadsNewSettings(): Unit = runBlocking {
        val queue = Channel<Pair<ScheduledTask, CompletableDeferred<Boolean>>>(Channel.UNLIMITED)
        val coordinator = object : ScheduledTaskCoordinator {
            override fun sessionFor(conversationId: Long, title: String): ScheduledTaskSession? = null
            override fun notifyChanged() = Unit
            override suspend fun run(onDue: suspend (ScheduledTask) -> Boolean) {
                for ((task, response) in queue) response.complete(onDue(task))
            }
        }
        val configurations = mutableListOf<ModelConfiguration>()
        val chat = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("streaming expected")
            override suspend fun replyStreaming(
                configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String,
                goalSession: GoalSession?, scheduledTaskSession: ScheduledTaskSession?,
                scheduledTaskCompletionSession: ScheduledTaskCompletionSession?,
                onToolUse: suspend (ToolUseEvent) -> Unit, onSubAgent: suspend (SubAgentEvent) -> Unit,
                onDelta: suspend (String) -> Unit,
            ): String {
                assertNull(goalSession)
                configurations += configuration
                requireNotNull(scheduledTaskCompletionSession).complete("result-${configurations.size}")
                onDelta("process")
                return "process"
            }
        }
        val directory = Files.createTempDirectory("headless-dispatch-results").toFile()
        lateinit var services: Context
        val runtime = runtime(directory, coordinator, chat) { services = it }
        val ui = services.require(KcodeSessions.Key).factory.create(CoroutineScope(coroutineContext))
        try {
            ui.load()
            runtime.pluginManager.replace(importSpec(directory))
            val provider = services.require(KcodeLlm.Key).catalog().providers.first()
            val first = ModelConfiguration(provider.provider, provider.models.first().id, "fixture", 0.4)
            val policy = services.require(KcodeModelSettings.Key).policy
            val store = services.require(KcodeSettings.Key).store
            suspend fun trigger(name: String): Boolean {
                val accepted = CompletableDeferred<Boolean>()
                queue.send(task(name) to accepted)
                return withTimeout(5_000) { accepted.await() }
            }
            assertFalse(trigger("unconfigured"))
            assertTrue(ui.floatingConversations.isEmpty())
            store.save(policy.update(StoredAppSettings(), first))
            assertTrue(trigger("first"))
            withTimeout(5_000) { while (ui.floatingConversations.size != 1) delay(10) }
            assertEquals("result-1", ui.floatingConversations.single().messages.last().content)
            store.save(policy.update(store.load(), first.copy(temperature = 0.8)))
            assertTrue(trigger("second"))
            withTimeout(5_000) { while (ui.floatingConversations.size != 2) delay(10) }
            assertEquals(listOf(0.4, 0.8), configurations.map { it.temperature })
            assertEquals(setOf("result-1", "result-2"), ui.floatingConversations.map { it.standaloneResult }.toSet())
        } finally {
            ui.close()
            runtime.close()
            queue.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    @Test
    fun actualJarWithdrawalJoinsActiveGenerationAndDiscardsUnpublishedResult(): Unit = runBlocking {
        val queue = Channel<Pair<ScheduledTask, CompletableDeferred<Boolean>>>(Channel.UNLIMITED)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = object : ScheduledTaskCoordinator {
            override fun sessionFor(conversationId: Long, title: String): ScheduledTaskSession? = null
            override fun notifyChanged() = Unit
            override suspend fun run(onDue: suspend (ScheduledTask) -> Boolean) {
                for ((task, response) in queue) response.complete(onDue(task))
            }
        }
        val chat = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("streaming expected")
            override suspend fun replyStreaming(
                configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String,
                goalSession: GoalSession?, scheduledTaskSession: ScheduledTaskSession?,
                scheduledTaskCompletionSession: ScheduledTaskCompletionSession?,
                onToolUse: suspend (ToolUseEvent) -> Unit, onSubAgent: suspend (SubAgentEvent) -> Unit,
                onDelta: suspend (String) -> Unit,
            ): String {
                requireNotNull(scheduledTaskCompletionSession).complete("unpublished")
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val directory = Files.createTempDirectory("headless-dispatch-generation").toFile()
        lateinit var services: Context
        val runtime = runtime(directory, coordinator, chat) { services = it }
        val ui = services.require(KcodeSessions.Key).factory.create(CoroutineScope(coroutineContext))
        try {
            ui.load()
            val provider = services.require(KcodeLlm.Key).catalog().providers.first()
            services.require(KcodeSettings.Key).store.save(services.require(KcodeModelSettings.Key).policy.update(
                StoredAppSettings(), ModelConfiguration(provider.provider, provider.models.first().id, "fixture", 0.4),
            ))
            runtime.pluginManager.replace(importSpec(directory))
            val accepted = CompletableDeferred<Boolean>()
            queue.send(task("cancelled") to accepted)
            assertTrue(withTimeout(5_000) { accepted.await() })
            withTimeout(5_000) { entered.await() }
            val withdrawing = async { runtime.pluginManager.setEnabled(DispatchId, false) }
            withTimeout(5_000) { cleaning.await() }
            assertFalse(withdrawing.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { withdrawing.await() }
            assertTrue(ui.floatingConversations.isEmpty())
            assertTrue(services.require(ai.meteor.kcode.plugin.api.KcodeHistory.Key).repository.loadAll().none { it.title == "cancelled" })
        } finally {
            release.complete(Unit)
            ui.close()
            runtime.close()
            queue.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    @Test
    fun actualAggregateOwnsProviderToolsAndHeadlessDispatchAndRestoresPersistedTasks(): Unit = runBlocking {
        val replies = java.util.concurrent.atomic.AtomicInteger()
        val chat = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("streaming expected")
            override suspend fun replyStreaming(
                configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String,
                goalSession: GoalSession?, scheduledTaskSession: ScheduledTaskSession?,
                scheduledTaskCompletionSession: ScheduledTaskCompletionSession?,
                onToolUse: suspend (ToolUseEvent) -> Unit, onSubAgent: suspend (SubAgentEvent) -> Unit,
                onDelta: suspend (String) -> Unit,
            ): String {
                val completion = requireNotNull(scheduledTaskCompletionSession)
                assertNotSame(ScheduleFeaturePlugin::class.java.classLoader, completion.javaClass.classLoader)
                completion.complete("aggregate-${replies.incrementAndGet()}")
                return "process"
            }
        }
        val directory = Files.createTempDirectory("private-schedule-aggregate").toFile()
        lateinit var services: Context
        val runtime = runtime(directory, chat = chat) { services = it }
        var ui: ai.meteor.kcode.chat.ConversationSession? = null
        try {
            runtime.pluginManager.replace(importSpec(directory, "feature.schedule", ScheduleFeaturePlugin::class.java))
            val conversations = services.require(KcodeSessions.Key).factory.create(CoroutineScope(coroutineContext))
            ui = conversations
            conversations.load()
            val provider = services.require(KcodeLlm.Key).catalog().providers.first()
            services.require(KcodeSettings.Key).store.save(services.require(KcodeModelSettings.Key).policy.update(
                StoredAppSettings(), ModelConfiguration(provider.provider, provider.models.first().id, "fixture", 0.4),
            ))
            val coordinator = services.require(KcodeSchedules.Key).coordinator
            assertNotSame(ScheduleFeaturePlugin::class.java.classLoader, coordinator.javaClass.classLoader)
            val session = requireNotNull(coordinator.sessionFor(51, "aggregate"))
            val first = session.create("first", "first", 1, null, null)
            withTimeout(10_000) { while (conversations.floatingConversations.size != 1) delay(10) }
            withTimeout(5_000) { while (session.list().single { it.taskId == first.taskId }.status != ScheduledTaskStatus.Completed) delay(10) }
            val pending = session.create("after restart", "second", 1, null, null)
            runtime.pluginManager.setEnabled("feature.schedule", false)
            assertFalse("core/schedule" in runtime.diagnostics().toolContributions)
            assertFailsWith<IllegalStateException> { session.list() }
            assertFailsWith<IllegalStateException> { coordinator.notifyChanged() }
            runtime.pluginManager.setEnabled("feature.schedule", true)
            assertTrue("core/schedule" in runtime.diagnostics().toolContributions)
            val restored = requireNotNull(services.require(KcodeSchedules.Key).coordinator.sessionFor(51, "aggregate"))
            assertTrue(restored.list().any { it.taskId == pending.taskId })
            withTimeout(10_000) { while (conversations.floatingConversations.size != 2) delay(10) }
            assertEquals(2, replies.get())
        } finally {
            ui?.close()
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    private suspend fun runtime(
        directory: File,
        coordinator: ScheduledTaskCoordinator? = null,
        chat: ChatService? = null,
        capture: (Context) -> Unit = {},
    ): KcodePluginRuntime {
        val overrides = mutableListOf<KcodePluginMount>()
        if (coordinator != null) overrides += listOf(
            kcodePlugin(descriptor("provider.schedules.history"), plugin<Unit>(name = "fixture-schedules") { ctx, _ -> KcodeSchedules(ctx, coordinator) }, Unit),
            kcodePlugin(descriptor(DispatchId), plugin<Unit>(name = "fixture-idle-consumer") { _, _ -> }, Unit),
        )
        if (chat != null) overrides += kcodePlugin(descriptor("provider.agent-loop.koog"),
            plugin<Unit>(name = "fixture-agent") { ctx, _ -> KcodeAgents(ctx, chat) }, Unit)
        val store = object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            private var committed = StoredAppSettings()
            override suspend fun load() = committed
            override suspend fun save(settings: StoredAppSettings) { committed = settings }
        }
        val observer = kcodePlugin(descriptor("test.headless-services"), plugin<Unit>(
            name = "capture-headless-services", inject = dependencies(KcodeSessions.Key, KcodeModelSettings.Key, KcodeSettings.Key, KcodeLlm.Key, ai.meteor.kcode.plugin.api.KcodeHistory.Key, KcodeSchedules.Key),
        ) { ctx, _ -> capture(ctx) }, Unit)
        return KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            settingsStore = store,
            profile = KcodePluginProfile(overrides = listOf(kcodePlugin(descriptor("feature.schedule"), plugin<Unit>(name = "fixture-idle-feature") { _, _ -> }, Unit)) + overrides.filter { it.descriptor.id == "provider.agent-loop.koog" }, disabled = setOf(
                "core.ui-slots", "core.ui-contributions", "feature.markdown", "feature.localization",
                "provider.notifications.platform", "feature.goal",
            )),
            featurePlugins = listOf(observer) + overrides.filter { it.descriptor.id != "provider.agent-loop.koog" },
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
    }

    private fun importSpec(directory: File, id: String = DispatchId, entry: Class<*> = ScheduleDispatchPlugin::class.java): DynamicPluginSpec {
        val artifact = File(directory, "dispatch.jar")
        File(ScheduleDispatchPlugin::class.java.protectionDomain.codeSource.location.toURI()).copyTo(artifact)
        check(artifact.setReadOnly())
        return DynamicPluginSpec(
            id = id, version = "private-dispatch", artifactPath = artifact.path,
            sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
            entryClass = entry.name,
        )
    }

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "fixture", emptySet())
    private fun task(name: String) = ScheduledTask(name, 1L, name, "prompt-$name", ScheduledTaskStatus.Active, 0, createdAt = 0, updatedAt = 0)
    private companion object { const val DispatchId = "consumer.schedules.application" }
}
