package ai.meteor.kcode.plugin

import ai.meteor.kcode.session.HistoryConversationState

import ai.meteor.kcode.plugin.execution.OwnedChatGenerationRunner
import ai.meteor.kcode.plugin.settingsstorage.SettingsProviderPlugin

import ai.meteor.kcode.plugin.history.HistoryProviderPlugin

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ScheduledTaskCoordinator
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.plugin.ui.api.ApplicationEffectRequest
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.ui.api.DefaultUiRenderer
import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.ui.api.ApplicationViewServices
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.ui.api.ApplicationLayoutRequest
import ai.meteor.kcode.plugin.ui.api.SidebarPageRequest
import ai.meteor.kcode.plugin.ui.api.RenderApplicationLayout
import ai.meteor.kcode.plugin.ui.api.RenderApplicationSidebar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.ChatPageRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

class PluginCompositionTest {
    @Test
    fun defaultApplicationUiDeclaresItsRequiredSettingsHistoryAndSessionProviders() = runTest {
        val runtime = KcodePluginRuntime.create(config())
        try {
            for (id in listOf("provider.settings.platform", "provider.history.platform", "provider.sessions.history")) {
                runtime.pluginManager.setEnabled(id, false)
                assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.ui.compose" }.state)
                runtime.pluginManager.setEnabled(id, true)
                assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.ui.compose" }.state)
            }
        } finally { runtime.close() }
    }

    @Test
    fun optionalWebWithdrawalKeepsApplicationMountedAndRestoresOnlyItsContributions() = runTest {
        val controller = object : ai.meteor.kcode.webcontainer.WebContainerController {
            override suspend fun launch(request: ai.meteor.kcode.webcontainer.WebPreviewRequest): ai.meteor.kcode.webcontainer.WebPreviewResult = error("Unused")
            override suspend fun list(): List<ai.meteor.kcode.webcontainer.WebContainerInfo> = emptyList()
            override suspend fun screenshot(containerId: String): ai.meteor.kcode.webcontainer.WebContainerScreenshot = error("Unused")
            override suspend fun inspect(containerId: String): ai.meteor.kcode.webcontainer.WebPageInspection = error("Unused")
            override suspend fun interact(request: ai.meteor.kcode.webcontainer.WebInteractionRequest): ai.meteor.kcode.webcontainer.WebInteractionResult = error("Unused")
            override suspend fun console(containerId: String, cursor: Long, limit: Int): ai.meteor.kcode.webcontainer.WebConsoleSnapshot = error("Unused")
            override suspend fun setState(containerId: String, state: ai.meteor.kcode.webcontainer.WebContainerState): ai.meteor.kcode.webcontainer.WebContainerInfo = error("Unused")
            override suspend fun close(containerId: String) = Unit
        }
        val runtime = KcodePluginRuntime.create(config().copy(webContainerController = controller))
        var starts = 0
        var stops = 0
        var remembered: Any? = null
        var web: ai.meteor.kcode.webcontainer.WebContainerController? = null
        var slots = ApplicationUiSlots()
        val renderer = DefaultUiRenderer { services, _ ->
            remembered = remember { Any() }
            web = services.webContainerController
            slots = services.uiSlots
            DisposableEffect(Unit) { starts++; onDispose { stops++ } }
        }
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { yield(); return onFrame(System.nanoTime()) }
        }
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val composition = Composition(UnitApplier(), recomposer)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        suspend fun renderChanges() {
            // Apply collector writes synchronously instead of racing Compose's global
            // snapshot notification thread before awaitIdle observes pending work.
            Snapshot.withMutableSnapshot { testScheduler.runCurrent() }
            Snapshot.sendApplyNotifications()
            testScheduler.runCurrent()
            recomposer.awaitIdle()
        }
        try {
            runtime.replacePlugin(uiMount(renderer))
            composition.setContent { runtime.Render(ApplicationHostOptions()) }
            renderChanges()
            val state = remembered
            val previous = requireNotNull(web)
            val chat = slots.chat
            assertTrue(state != null)
            assertEquals(1, starts)
            assertTrue(slots.webContainers != null)
            runtime.pluginManager.setEnabled("provider.web-containers.platform", false)
            renderChanges()
            assertEquals(0, stops)
            assertSame(state, remembered)
            assertNull(web)
            assertNull(slots.webContainers)
            assertSame(chat, slots.chat)
            assertFailsWith<IllegalStateException> { previous.list() }
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.ui.web-containers" }.state)
            runtime.pluginManager.setEnabled("provider.web-containers.platform", true)
            renderChanges()
            assertEquals(1, starts)
            assertEquals(0, stops)
            assertSame(state, remembered)
            assertTrue(web != null && web !== previous)
            assertTrue(slots.webContainers != null)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
            runtime.close()
        }
        assertEquals(1, stops)
    }

    @Test
    fun actualLayoutHostCleansUpWithdrawnSidebarAndLayoutWhileKeepingApplicationState() = runTest {
        var layoutStarted = 0
        var layoutStopped = 0
        var sidebarStarted = 0
        var sidebarStopped = 0
        var compact: Boolean? = null
        val sidebar = SidebarPageRequest(null, emptyList(), false, false, emptyList(), null,
            {}, {}, {}, {}, {}, {})
        val request = ApplicationLayoutRequest(400.dp, false, {}, sidebar, null) { _, isCompact -> compact = isCompact }
        val observer = RecordingRenderer(layoutContext = request)
        val runtime = KcodePluginRuntime.create(config())
        val layoutRenderer = UiRenderer<ApplicationLayoutRequest> {
            DisposableEffect(Unit) { layoutStarted += 1; onDispose { layoutStopped += 1 } }
            RenderApplicationSidebar(it.sidebar, it.sidebarRenderer)
            it.content(Modifier, true)
        }
        val sidebarRenderer = UiRenderer<SidebarPageRequest> {
            DisposableEffect(Unit) { sidebarStarted += 1; onDispose { sidebarStopped += 1 } }
        }
        val frameClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { yield(); return onFrame(System.nanoTime()) }
        }
        val recomposer = Recomposer(backgroundScope.coroutineContext + frameClock)
        val composition = Composition(UnitApplier(), recomposer)
        val runner = backgroundScope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        suspend fun renderChanges() {
            // Apply collector writes synchronously instead of racing Compose's global
            // snapshot notification thread before awaitIdle observes pending work.
            Snapshot.withMutableSnapshot { testScheduler.runCurrent() }
            Snapshot.sendApplyNotifications()
            testScheduler.runCurrent()
            recomposer.awaitIdle()
        }
        try {
            runtime.replacePlugin(uiMount(observer))
            runtime.replacePlugin(uiSlotPlugin("provider.ui.layout", ApplicationSlots.Layout, layoutRenderer))
            runtime.replacePlugin(uiSlotPlugin("provider.ui.sidebar", ApplicationSlots.Sidebar, sidebarRenderer))
            composition.setContent { runtime.Render(ApplicationHostOptions()) }
            renderChanges()
            val remembered = observer.remembered
            assertEquals(true, compact)
            assertEquals(1, layoutStarted)
            assertEquals(1, sidebarStarted)
            runtime.pluginManager.setEnabled("provider.ui.sidebar", false)
            renderChanges()
            assertEquals(1, sidebarStopped)
            assertEquals(0, layoutStopped)
            assertSame(remembered, observer.remembered)
            runtime.pluginManager.setEnabled("provider.ui.sidebar", true)
            renderChanges()
            assertEquals(2, sidebarStarted)
            runtime.pluginManager.setEnabled("provider.ui.layout", false)
            renderChanges()
            assertEquals(1, layoutStopped)
            assertEquals(2, sidebarStopped)
            assertSame(remembered, observer.remembered)
            runtime.pluginManager.setEnabled("provider.ui.layout", true)
            renderChanges()
            assertEquals(2, layoutStarted)
            assertEquals(3, sidebarStarted)
            assertSame(remembered, observer.remembered)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
            runtime.close()
        }
    }

    @Test
    fun webContainersOverlayIsIndependentAndTracksItsProvider() = runTest {
        lateinit var slots: KcodeUiSlots
        lateinit var publishedController: ai.meteor.kcode.webcontainer.WebContainerController
        val closeStarted = CompletableDeferred<Unit>()
        val releaseClose = CompletableDeferred<Unit>()
        val capture = kcodePlugin(descriptor("test.web-ui"),
            plugin<Unit>(name = "web-ui-capture", inject = dependencies(KcodeUiSlots.Key, ai.meteor.kcode.plugin.api.KcodeWebContainers.Key)) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
                publishedController = checkNotNull(ctx.require(ai.meteor.kcode.plugin.api.KcodeWebContainers.Key).controller)
            }, Unit)
        val controller = object : ai.meteor.kcode.webcontainer.WebContainerController {
            override suspend fun launch(request: ai.meteor.kcode.webcontainer.WebPreviewRequest): ai.meteor.kcode.webcontainer.WebPreviewResult = error("Unused")
            override suspend fun list(): List<ai.meteor.kcode.webcontainer.WebContainerInfo> = emptyList()
            override suspend fun screenshot(containerId: String): ai.meteor.kcode.webcontainer.WebContainerScreenshot = error("Unused")
            override suspend fun inspect(containerId: String): ai.meteor.kcode.webcontainer.WebPageInspection = error("Unused")
            override suspend fun interact(request: ai.meteor.kcode.webcontainer.WebInteractionRequest): ai.meteor.kcode.webcontainer.WebInteractionResult = error("Unused")
            override suspend fun console(containerId: String, cursor: Long, limit: Int): ai.meteor.kcode.webcontainer.WebConsoleSnapshot = error("Unused")
            override suspend fun setState(containerId: String, state: ai.meteor.kcode.webcontainer.WebContainerState): ai.meteor.kcode.webcontainer.WebContainerInfo = error("Unused")
            override suspend fun close(containerId: String): Unit = error("Unused")
            override suspend fun closeAll() {
                closeStarted.complete(Unit)
                releaseClose.await()
            }
        }
        val runtime = KcodePluginRuntime.create(config().copy(featurePlugins = listOf(capture), webContainerController = controller))
        try {
            val initial = slots.snapshot()
            assertTrue(initial.webContainers != null)
            runtime.pluginManager.setEnabled("provider.ui.web-containers", false)
            assertNull(slots.snapshot().webContainers)
            assertSame(initial.chat, slots.snapshot().chat)
            runtime.pluginManager.setEnabled("provider.ui.web-containers", true)
            assertTrue(slots.snapshot().webContainers != null)
            val staleController = publishedController
            val disabling = async { runtime.pluginManager.setEnabled("provider.web-containers.platform", false) }
            closeStarted.await()
            assertFalse(disabling.isCompleted)
            assertFailsWith<IllegalStateException> { staleController.list() }
            releaseClose.complete(Unit)
            disabling.await()
            assertNull(slots.snapshot().webContainers)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.ui.web-containers" }.state)
            runtime.pluginManager.setEnabled("provider.web-containers.platform", true)
            assertTrue(slots.snapshot().webContainers != null)
        } finally {
            releaseClose.complete(Unit)
            runtime.close()
        }
    }

    @Test
    fun standaloneAndArtifactViewsWithdrawWhenTheirFeatureOrProviderIsDisabled() = runTest {
        lateinit var slots: KcodeUiSlots
        val capture = kcodePlugin(descriptor("test.page-dependencies"),
            plugin<Unit>(name = "page-dependencies", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
            }, Unit)
        val runtime = KcodePluginRuntime.create(config().copy(featurePlugins = listOf(capture)))
        try {
            val initial = slots.snapshot()
            assertTrue(initial.standaloneConversation != null)
            assertTrue(initial.artifacts != null)
            runtime.pluginManager.setEnabled("provider.ui.conversation.standalone", false)
            assertNull(slots.snapshot().standaloneConversation)
            assertSame(initial.chat, slots.snapshot().chat)
            runtime.pluginManager.setEnabled("provider.ui.conversation.standalone", true)
            assertTrue(slots.snapshot().standaloneConversation != null)
            runtime.pluginManager.setEnabled("provider.sessions.history", false)
            assertNull(slots.snapshot().standaloneConversation)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.ui.conversation.standalone" }.state)
            runtime.pluginManager.setEnabled("provider.sessions.history", true)
            assertTrue(slots.snapshot().standaloneConversation != null)
            runtime.pluginManager.setEnabled("provider.artifacts.platform", false)
            assertNull(slots.snapshot().artifacts)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.ui.artifacts" }.state)
            runtime.pluginManager.setEnabled("provider.artifacts.platform", true)
            assertTrue(slots.snapshot().artifacts != null)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun modelSettingsVisibilityFollowsTheCommittedProviderCatalog() = runTest {
        lateinit var slots: KcodeUiSlots
        val capture = kcodePlugin(descriptor("test.settings-catalog"),
            plugin<Unit>(name = "settings-catalog", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
            }, Unit)
        val runtime = KcodePluginRuntime.create(config().copy(featurePlugins = listOf(capture)))
        suspend fun request() = ai.meteor.kcode.plugin.ui.api.SettingsPageRequest(
            current = null, appSettings = StoredAppSettings(), persistenceFailure = null,
            shellSettingsAvailable = false, onSettingsChange = {}, onModelSettingsChange = { _, _ -> },
            onDismiss = {}, modelCatalog = runtime.modelCatalog(),
        )
        try {
            val modelSection = slots.snapshot().settingsSections.first { it.id == "model" }
            assertTrue(modelSection.isVisible(request()))
            runtime.diagnostics().plugins.filter { it.id.startsWith("provider.llm.koog.") }.forEach {
                runtime.pluginManager.setEnabled(it.id, false)
            }
            assertTrue(runtime.modelCatalog().providers.isEmpty())
            assertFalse(modelSection.isVisible(request()))
            runtime.pluginManager.setEnabled("provider.llm.koog.DeepSeek", true)
            assertTrue(modelSection.isVisible(request()))
            runtime.pluginManager.setEnabled("provider.ui.settings.model", false)
            assertEquals(listOf("language", "search", "shell"), slots.snapshot().settingsSections.map { it.id })
            runtime.pluginManager.setEnabled("provider.ui.settings.model", true)
            assertEquals(listOf("language", "model", "search", "shell"), slots.snapshot().settingsSections.map { it.id })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun actualConversationHostDisposesEffectsAndPresentersWhenTheirPluginIsWithdrawn() = runTest {
        var started = 0
        var stopped = 0
        var presented = 0
        var removed = 0
        val extension = kcodePlugin(descriptor("test.conversation-extension"),
            plugin<Unit>(name = "conversation-extension", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
                collect(ctx.require(KcodeUiSlots.Key).registerConversationEffect(
                    ai.meteor.kcode.plugin.ui.api.ConversationPageEffect("custom.effect", 0, UiRenderer {
                        DisposableEffect(Unit) { started += 1; onDispose { stopped += 1 } }
                    }),
                ))
                collect(ctx.require(KcodeUiSlots.Key).registerConversationDecoration(
                    ai.meteor.kcode.plugin.ui.api.ConversationDecoration("custom.decoration", 0,
                        ai.meteor.kcode.plugin.ui.api.ConversationDecorationPresenter {
                            DisposableEffect(Unit) { presented += 1; onDispose { removed += 1 } }
                            null
                        }),
                ))
            }, Unit)
        val pageContext = ai.meteor.kcode.plugin.ui.api.ConversationPageContext(null, true, null,
            object : ChatService {
                override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("unused")
            }, OwnedChatGenerationRunner(scope = backgroundScope), ai.meteor.kcode.chat.UnavailableScheduledTasks,
            ai.meteor.kcode.chat.ChatFailureMessages("setup", "connection"), {})
        val renderer = RecordingRenderer(pageContext)
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(overrides = listOf(uiMount(renderer))))
            .copy(featurePlugins = listOf(extension)))
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { yield(); return onFrame(System.nanoTime()) }
        }
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(UnitApplier(), recomposer)
        suspend fun renderChanges() {
            testScheduler.runCurrent(); Snapshot.sendApplyNotifications(); testScheduler.runCurrent(); recomposer.awaitIdle()
        }
        try {
            composition.setContent { runtime.Render(ApplicationHostOptions()) }
            renderChanges()
            val remembered = renderer.remembered
            assertEquals(1, started)
            assertEquals(1, presented)
            runtime.pluginManager.setEnabled("test.conversation-extension", false)
            renderChanges()
            assertEquals(1, stopped)
            assertEquals(1, removed)
            assertSame(remembered, renderer.remembered)
            runtime.pluginManager.setEnabled("test.conversation-extension", true)
            renderChanges()
            assertEquals(2, started)
            assertEquals(2, presented)
            runtime.pluginManager.uninstall("test.conversation-extension")
            renderChanges()
            assertEquals(2, stopped)
            assertEquals(2, removed)
            assertSame(remembered, renderer.remembered)
        } finally { composition.dispose(); recomposer.close(); runner.cancel(); runtime.close() }
    }

    @Test
    fun legacyAggregateModelEnableStateMigratesToIndividualProviders() = runTest {
        var saved: ai.meteor.kcode.plugin.api.PluginCompositionSnapshot? = null
        val store = object : ai.meteor.kcode.plugin.api.PluginCompositionStore {
            override suspend fun load() = ai.meteor.kcode.plugin.api.PluginCompositionSnapshot(
                builtinsEnabled = mapOf("provider.llm.koog" to false),
            )
            override suspend fun save(snapshot: ai.meteor.kcode.plugin.api.PluginCompositionSnapshot) { saved = snapshot }
        }
        val runtime = KcodePluginRuntime.create(config().copy(pluginCompositionStore = store))
        try {
            assertTrue(runtime.modelCatalog().providers.isEmpty())
            ModelProvider.entries.forEach { provider ->
                assertEquals(PluginState.Disabled, runtime.diagnostics().plugins.first {
                    it.id == "provider.llm.koog.${provider.name}"
                }.state)
            }
            runtime.pluginManager.setEnabled("provider.llm.koog.DeepSeek", true)
            assertEquals(listOf(ModelProvider.DeepSeek), runtime.modelCatalog().providers.map { it.provider })
            assertTrue("provider.llm.koog" !in checkNotNull(saved).builtinsEnabled)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun reservedHarnessDefinitionsDoNotAdvertiseUnimplementedServices() = runTest {
        val keys = listOf(
            ai.meteor.kcode.plugin.api.harness.KcodeJobs.Key,
            ai.meteor.kcode.plugin.api.harness.KcodeSubprocess.Key,
            ai.meteor.kcode.plugin.api.harness.KcodeTerminals.Key,
            ai.meteor.kcode.plugin.api.harness.KcodeSessionPersistence.Key,
        )
        var sessionLogAvailable = true
        val consumers = keys.mapIndexed { index, key ->
            kcodePlugin(
                descriptor("test.reserved-$index"),
                plugin<Unit>(name = "reserved-$index", inject = dependencies(key)) { _, _ ->
                    error("A reserved definition must not imply an available provider")
                },
                Unit,
            )
        } + kcodePlugin(
            descriptor("test.session-log-capability"),
            plugin<Unit>(name = "session-log-capability", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeSessions.Key)) { ctx, _ ->
                sessionLogAvailable = ctx.require(ai.meteor.kcode.plugin.api.KcodeSessions.Key).eventStore != null
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(config().copy(featurePlugins = consumers))
        try {
            keys.indices.forEach { index ->
                assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "test.reserved-$index" }.state)
            }
            assertFalse(sessionLogAvailable)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun executionProviderDisposalWithdrawsConsumersAndRebindsOnEnable() = runTest {
        lateinit var execution: ai.meteor.kcode.chat.ConversationExecution
        val capture = kcodePlugin(
            descriptor("test.execution-consumer"),
            plugin<Unit>(name = "execution-consumer", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeConversationExecution.Key)) { ctx, _ ->
                execution = ctx.require(ai.meteor.kcode.plugin.api.KcodeConversationExecution.Key).executor
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(config().copy(featurePlugins = listOf(capture)))
        try {
            val previous = execution
            runtime.pluginManager.setEnabled("provider.conversation-execution.history", false)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.ui.chat" }.state)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "consumer.schedules.application" }.state)
            runtime.pluginManager.setEnabled("provider.conversation-execution.history", true)
            assertFalse(previous === execution)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.ui.chat" }.state)
            runtime.pluginManager.setEnabled("provider.history.platform", false)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.conversation-execution.history" }.state)
            runtime.pluginManager.setEnabled("provider.history.platform", true)
            assertFalse(previous === execution)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun actualScheduleEffectStopsWhenUnregisteredAndRestartsWithoutDuplicatedRunner() = runTest {
        var starts = 0
        var stops = 0
        val coordinator = object : ScheduledTaskCoordinator {
            override fun sessionFor(conversationId: Long, title: String): ScheduledTaskSession? = null
            override fun notifyChanged() = Unit
            override suspend fun run(onDue: suspend (ScheduledTask) -> Boolean) {
                starts += 1
                try { awaitCancellation() } finally { stops += 1 }
            }
        }
        val generationRunner = OwnedChatGenerationRunner(scope = backgroundScope)
        val renderer = DefaultUiRenderer { services, _ ->
            val session = remember(services.conversationSessions) { services.conversationSessions!!.create(backgroundScope) }
            DisposableEffect(session) { onDispose { session.cancel() } }
            LaunchedEffect(session) { session.load() }
            services.uiSlots.effects.forEach { effect ->
                key(effect) {
                    effect.renderer.Render(ApplicationEffectRequest(
                        conversationSession = session,
                        conversationExecution = services.conversationExecution,
                        configuration = null,
                        chatService = services.chatService,
                        generationRunner = generationRunner,
                        historyRepository = services.historyRepository,
                        goalSessionFactory = services.goalSessions,
                        scheduledTaskCoordinator = services.schedules,
                        language = AppLanguage.English,
                    ))
                }
            }
        }
        val scheduleMount = kcodePlugin(descriptor("provider.schedules.history"),
            plugin<Unit>(name = "recording-scheduler") { ctx, _ -> KcodeSchedules(ctx, coordinator) }, Unit)
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(overrides = listOf(uiMount(renderer), scheduleMount))))
        val frameClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                yield()
                return onFrame(System.nanoTime())
            }
        }
        val recomposer = Recomposer(backgroundScope.coroutineContext + frameClock)
        val runner = backgroundScope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(UnitApplier(), recomposer)
        suspend fun renderChanges() {
            // Apply collector writes synchronously instead of racing Compose's global
            // snapshot notification thread before awaitIdle observes pending work.
            Snapshot.withMutableSnapshot { testScheduler.runCurrent() }
            Snapshot.sendApplyNotifications()
            testScheduler.runCurrent()
            recomposer.awaitIdle()
        }
        try {
            composition.setContent { runtime.Render(ApplicationHostOptions()) }
            renderChanges()
            assertEquals(1, starts)
            runtime.pluginManager.setEnabled("consumer.schedules.application", false)
            renderChanges()
            assertEquals(1, stops)
            assertEquals(1, starts)
            runtime.pluginManager.setEnabled("consumer.schedules.application", true)
            renderChanges()
            assertEquals(2, starts)
            assertEquals(1, stops)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
            runtime.close()
        }
        assertEquals(2, stops)
    }

    @Test
    fun sessionProviderDisposalCancelsOwnedWorkAndRejectsStaleFactory() = runTest {
        lateinit var factory: ai.meteor.kcode.chat.ConversationSessionFactory
        val capture = kcodePlugin(
            descriptor("test.session-consumer"),
            plugin<Unit>(name = "session-consumer", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeSessions.Key)) { ctx, _ ->
                factory = ctx.require(ai.meteor.kcode.plugin.api.KcodeSessions.Key).factory
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(config().copy(featurePlugins = listOf(capture)))
        try {
            val previousFactory = factory
            val session = factory.create(backgroundScope)
            session.load()
            val conversation = session.ensureConversation("Session cleanup")
            val started = CompletableDeferred<Unit>()
            var stopped = false
            conversation.runningJob = backgroundScope.launch {
                started.complete(Unit)
                try { CompletableDeferred<Unit>().await() } finally { stopped = true }
            }
            started.await()
            runtime.pluginManager.setEnabled("provider.sessions.history", false)
            assertTrue(stopped)
            assertTrue(conversation.runningJob!!.isCompleted)
            assertFailsWith<IllegalStateException> { session.ensureConversation("Stale session") }
            assertFailsWith<IllegalStateException> { previousFactory.create(backgroundScope) }
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "consumer.schedules.application" }.state)
            runtime.pluginManager.setEnabled("provider.sessions.history", true)
            assertFalse(previousFactory === factory)
            val restored = factory.create(backgroundScope)
            restored.load()
            assertTrue(restored.isLoaded)
            restored.close()
        } finally {
            runtime.close()
        }
    }

    @Test
    fun schedulingProviderFollowsDependencyAndEnableChanges() = runTest {
        lateinit var coordinator: ai.meteor.kcode.chat.ScheduledTaskCoordinator
        val capture = kcodePlugin(
            descriptor("test.schedule-consumer"),
            plugin<Unit>(name = "schedule-consumer", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeSchedules.Key)) { ctx, _ ->
                coordinator = ctx.require(ai.meteor.kcode.plugin.api.KcodeSchedules.Key).coordinator
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(config().copy(featurePlugins = listOf(capture)))
        try {
            val first = coordinator
            val firstSession = first.sessionFor(1, "Scheduled")!!
            runtime.pluginManager.setEnabled("provider.schedules.history", false)
            assertFailsWith<IllegalStateException> { first.sessionFor(1, "stale") }
            assertFailsWith<IllegalStateException> { first.notifyChanged() }
            assertFailsWith<IllegalStateException> { firstSession.list() }
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "test.schedule-consumer" }.state)
            runtime.pluginManager.setEnabled("provider.schedules.history", true)
            assertFalse(first === coordinator)
            val previous = coordinator
            runtime.pluginManager.setEnabled("provider.history.platform", false)
            assertFailsWith<IllegalStateException> { previous.notifyChanged() }
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.schedules.history" }.state)
            runtime.pluginManager.setEnabled("provider.history.platform", true)
            assertFalse(previous === coordinator)
            assertTrue(coordinator.sessionFor(1, "Scheduled") != null)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun goalSessionProviderCanBeDisabledAndRebindsToHistoryReplacement() = runTest {
        lateinit var sessions: ai.meteor.kcode.chat.GoalSessionFactory
        val capture = kcodePlugin(
            descriptor("test.goal-session-consumer"),
            plugin<Unit>(name = "goal-session-consumer", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeGoals.Key)) { ctx, _ ->
                sessions = ctx.require(ai.meteor.kcode.plugin.api.KcodeGoals.Key).sessions
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(config().copy(featurePlugins = listOf(capture)))
        val conversation = HistoryConversationState(7, "Goal provider")
        try {
            val firstFactory = sessions
            val first = sessions.create(conversation)!!
            assertSame(first, sessions.create(conversation))
            val goal = first.createGoal("Plugin goal")
            assertEquals(goal, conversation.goal)
            runtime.pluginManager.setEnabled("provider.goal-sessions.history", false)
            assertFailsWith<IllegalStateException> { firstFactory.create(conversation) }
            assertFailsWith<IllegalStateException> { first.getGoal() }
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "test.goal-session-consumer" }.state)
            runtime.pluginManager.setEnabled("provider.goal-sessions.history", true)
            val second = sessions.create(conversation)!!
            assertFalse(first === second)
            assertEquals(goal, second.getGoal())
            runtime.replacePlugin(kcodePlugin(descriptor("provider.history.platform"), HistoryProviderPlugin,
                ai.meteor.kcode.test.EmptyHistoryFixture()))
            assertFailsWith<IllegalStateException> { second.getGoal() }
            assertFalse(second === sessions.create(conversation))
            assertEquals(goal, sessions.create(conversation)?.getGoal())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun profileCanDisableBuiltInsAndRejectsUnknownIds() = runTest {
        assertFailsWith<IllegalArgumentException> {
            KcodePluginRuntime.create(config(KcodePluginProfile(disabled = setOf("typo"))))
        }
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(disabled = setOf("consumer.tools.goal"))))
        try {
            assertFalse("core/goal" in runtime.diagnostics().toolContributions)
            assertEquals(PluginState.Disabled, runtime.diagnostics().plugins.first { it.id == "consumer.tools.goal" }.state)
            runtime.pluginManager.setEnabled("consumer.tools.goal", true)
            assertTrue("core/goal" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.uninstall("consumer.tools.goal")
            assertFalse("core/goal" in runtime.diagnostics().toolContributions)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun missingProviderSuspendsConsumersAndReEnablingRestoresThem() = runTest {
        val runtime = KcodePluginRuntime.create(config())
        try {
            runtime.pluginManager.setEnabled("core.tools", false)
            assertTrue(runtime.diagnostics().toolContributions.isEmpty())
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == AgentId }.state)
            assertFailsWith<IllegalStateException> { runtime.chatService.reply(model, emptyList(), "hello") }
            runtime.pluginManager.setEnabled("core.tools", true)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == AgentId }.state)
            assertTrue("core/goal" in runtime.diagnostics().toolContributions)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun stableChatFacadeFollowsProviderReplacementAndFailedReplacementRestoresPrevious() = runTest {
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(overrides = listOf(agentMount("first")))))
        val facade = runtime.chatService
        try {
            assertEquals("first", facade.reply(model, emptyList(), "hello"))
            runtime.replacePlugin(agentMount("second"))
            assertSame(facade, runtime.chatService)
            assertEquals("second", facade.reply(model, emptyList(), "hello"))
            val failure = kcodePlugin(descriptor(AgentId), plugin<Unit>(name = "failing-agent") { _, _ -> error("apply failed") }, Unit)
            assertFailsWith<IllegalStateException> { runtime.replacePlugin(failure) }
            assertEquals("second", facade.reply(model, emptyList(), "hello"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun activeTurnsPreventChangesAndCancellationReleasesTheirLease() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(overrides = listOf(agentMount("blocked") {
            entered.complete(Unit)
            release.await()
        }))))
        try {
            val reply = async { runtime.chatService.reply(model, emptyList(), "hello") }
            entered.await()
            assertFailsWith<IllegalStateException> { runtime.pluginManager.uninstall(AgentId) }
            assertFailsWith<IllegalStateException> { runtime.replacePlugin(agentMount("second")) }
            reply.cancel()
            reply.join()
            runtime.replacePlugin(agentMount("second"))
            assertEquals("second", runtime.chatService.reply(model, emptyList(), "hello"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun closingRuntimeCancelsActiveTurnsAndRejectsLaterUse() = runTest {
        val entered = CompletableDeferred<Unit>()
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(overrides = listOf(agentMount("blocked") {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }))))
        val reply = async { runtime.chatService.reply(model, emptyList(), "hello") }
        entered.await()
        runtime.close()
        assertTrue(reply.isCancelled)
        assertFailsWith<IllegalStateException> { runtime.chatService.reply(model, emptyList(), "hello") }
        assertFailsWith<IllegalStateException> { runtime.pluginManager.setEnabled(AgentId, true) }
        runtime.close()
    }

    @Test
    fun explicitBundleSelectsProductProvidersWithoutImplicitDefaults() = runTest {
        val runtime = KcodePluginRuntime.create(config().copy(bundle = listOf(agentMount("bundle"))))
        try {
            assertEquals("bundle", runtime.chatService.reply(model, emptyList(), "hello"))
            assertEquals(setOf("core.plugin-inventory", "core.loader", AgentId), runtime.diagnostics().plugins.map { it.id }.toSet())
            assertTrue(runtime.diagnostics().toolContributions.isEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun customBundleMayStartWithoutDefaultAgentOrTools() = runTest {
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(includeDefaults = false)).copy(
            featurePlugins = listOf(agentMount("custom")),
        ))
        try {
            assertEquals("custom", runtime.chatService.reply(model, emptyList(), "hello"))
            assertTrue(runtime.diagnostics().toolContributions.isEmpty())
            assertEquals(setOf("core.plugin-inventory", "core.loader", AgentId), runtime.diagnostics().plugins.map { it.id }.toSet())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun customProviderMetadataReachesComposeAndWithdrawsWithItsPlugin(): Unit = runTest {
        verifyProviderPresentation(ModelProvider("acme.gateway:v1"))
    }

    @Test
    fun builtInRoutePresentationHasNoImplicitFallbackAfterAdapterWithdrawal(): Unit = runTest {
        verifyProviderPresentation(ModelProvider.DeepSeek)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyProviderPresentation(provider: ModelProvider) {
        val renderer = RecordingRenderer(labeledProvider = provider)
        val adapter = ai.meteor.kcode.plugin.api.ModelAdapter(
            id = "custom-gateway", supports = { it.provider == provider }, create = { _, _ -> error("No network") },
            catalog = ai.meteor.kcode.model.ModelProviderSpec(provider,
                listOf(ai.meteor.kcode.model.ModelOption(provider, "gpt-4o-mini",
                    displayNames = mapOf("zh" to "Private model name"),
                    descriptions = mapOf("zh" to "Private model description"), defaultTemperature = 0.6)), 0,
                displayName = "Acme Gateway", description = "Private deployment"),
        )
        val mount = kcodePlugin(descriptor("provider.llm.custom"),
            plugin<Unit>(name = "custom-gateway", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeLlm.Key)) { ctx, _ ->
                collect(ctx.require(ai.meteor.kcode.plugin.api.KcodeLlm.Key).register(adapter))
            }, Unit)
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(overrides = listOf(uiMount(renderer)),
            disabled = if (provider == ModelProvider.DeepSeek) setOf("provider.llm.koog.DeepSeek") else emptySet()))
            .copy(featurePlugins = listOf(mount)))
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { yield(); return onFrame(System.nanoTime()) }
        }
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(UnitApplier(), recomposer)
        suspend fun renderChanges() {
            // Apply collector writes synchronously instead of racing Compose's global
            // snapshot notification thread before awaitIdle observes pending work.
            Snapshot.withMutableSnapshot { testScheduler.runCurrent() }
            Snapshot.sendApplyNotifications()
            testScheduler.runCurrent()
            recomposer.awaitIdle()
        }
        try {
            composition.setContent { runtime.Render(ApplicationHostOptions()) }
            renderChanges()
            assertEquals("Acme Gateway", renderer.providerLabel)
            assertEquals("Private deployment", renderer.providerDescription)
            assertEquals("Private model name", renderer.modelLabel)
            assertEquals("Private model description", renderer.modelDescription)
            val remembered = renderer.remembered
            runtime.pluginManager.setEnabled("provider.llm.custom", false)
            renderChanges()
            assertNull(renderer.catalog.provider(provider))
            assertEquals(provider.id, renderer.providerLabel)
            assertEquals("", renderer.providerDescription)
            assertEquals("gpt-4o-mini", renderer.modelLabel)
            assertEquals("", renderer.modelDescription)
            runtime.pluginManager.setEnabled("provider.llm.custom", true)
            renderChanges()
            assertEquals("Acme Gateway", renderer.providerLabel)
            assertSame(remembered, renderer.remembered)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
            runtime.close()
        }
    }

    @Test
    fun settingsConsumersRebindAndComposeHostUsesCommittedUiProvider() = runTest {
        val firstSettings = MemorySettings("first")
        val secondSettings = MemorySettings("second")
        val observedSettings = mutableListOf<AppSettingsStore>()
        val firstRenderer = RecordingRenderer()
        val secondRenderer = RecordingRenderer()
        val consumer = kcodePlugin(
            descriptor("test.settings-consumer"),
            plugin<Unit>(name = "test-settings-consumer", inject = dependencies(KcodeSettings.Key)) { ctx, _ ->
                observedSettings += ctx.require(KcodeSettings.Key).store
            },
            Unit,
        )
        val customNavigation = ai.meteor.kcode.plugin.ui.api.NavigationDestination(
            id = "custom", order = -1, icon = ai.meteor.kcode.ui.component.KcodeIconAsset.Chat,
            title = { "Custom" }, renderer = UiRenderer { },
        )
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(
            overrides = listOf(uiMount(firstRenderer)), disabled = setOf("provider.ui.navigation.custom"),
        )).copy(
            settingsStore = firstSettings,
            featurePlugins = listOf(consumer, navigationPlugin(customNavigation)),
        ))
        val frameClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                yield()
                return onFrame(System.nanoTime())
            }
        }
        val recomposer = Recomposer(backgroundScope.coroutineContext + frameClock)
        val runner = backgroundScope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(UnitApplier(), recomposer)
        suspend fun renderChanges() {
            // Apply collector writes synchronously instead of racing Compose's global
            // snapshot notification thread before awaitIdle observes pending work.
            Snapshot.withMutableSnapshot { testScheduler.runCurrent() }
            Snapshot.sendApplyNotifications()
            testScheduler.runCurrent()
            recomposer.awaitIdle()
        }
        try {
            composition.setContent { runtime.Render(ApplicationHostOptions()) }
            renderChanges()
            assertEquals(firstSettings.load(), checkNotNull(firstRenderer.settings).load())
            assertSame(observedSettings.last(), firstRenderer.settings)
            val remembered = firstRenderer.remembered
            assertEquals(ModelProvider.entries.toList(), firstRenderer.catalog.providers.map { it.provider })
            runtime.pluginManager.setEnabled("provider.llm.koog.DeepSeek", false)
            renderChanges()
            assertNull(firstRenderer.catalog.provider(ModelProvider.DeepSeek))
            assertNull(runtime.modelCatalog().provider(ModelProvider.DeepSeek))
            assertSame(remembered, firstRenderer.remembered)
            runtime.pluginManager.setEnabled("provider.llm.koog.DeepSeek", true)
            renderChanges()
            val previousCatalog = firstRenderer.catalog
            val duplicateAdapter = ai.meteor.kcode.plugin.api.ModelAdapter(
                id = "duplicate-provider", supports = { false }, create = { _, _ -> error("unused") },
                catalog = previousCatalog.provider(ModelProvider.OpenAI),
            )
            val duplicateMount = kcodePlugin(descriptor("test.duplicate-model"),
                plugin<Unit>(name = "duplicate-model", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeLlm.Key)) { ctx, _ ->
                    collect(ctx.require(ai.meteor.kcode.plugin.api.KcodeLlm.Key).register(duplicateAdapter))
                }, Unit)
            assertFailsWith<IllegalArgumentException> {
                runtime.replacePlugin(object : KcodePluginMount {
                    override val descriptor = descriptor("provider.llm.koog.DeepSeek")
                    override suspend fun mount(context: Context) = duplicateMount.mount(context)
                })
            }
            renderChanges()
            assertEquals(previousCatalog, firstRenderer.catalog)
            assertEquals(previousCatalog, runtime.modelCatalog())
            assertSame(remembered, firstRenderer.remembered)
            assertEquals(listOf("goal"), firstRenderer.commands.contributions.map { it.id })
            assertTrue(firstRenderer.commands.allowedDuringGeneration("/goal pause"))
            runtime.pluginManager.setEnabled("consumer.commands.goal", false)
            renderChanges()
            assertTrue(firstRenderer.commands.contributions.isEmpty())
            assertFalse(firstRenderer.commands.allowedDuringGeneration("/goal pause"))
            assertSame(remembered, firstRenderer.remembered)
            runtime.pluginManager.setEnabled("consumer.commands.goal", true)
            renderChanges()
            assertEquals(listOf("goal"), firstRenderer.commands.contributions.map { it.id })
            runtime.pluginManager.setEnabled("provider.goal-sessions.history", false)
            renderChanges()
            assertTrue(firstRenderer.commands.contributions.isEmpty())
            assertTrue(firstRenderer.slots.conversationDecorations.isEmpty())
            assertTrue(firstRenderer.slots.conversationEffects.isEmpty())
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "consumer.commands.goal" }.state)
            runtime.pluginManager.setEnabled("provider.goal-sessions.history", true)
            renderChanges()
            assertEquals(listOf("goal"), firstRenderer.commands.contributions.map { it.id })
            assertEquals(listOf("goal"), firstRenderer.slots.conversationDecorations.map { it.id })
            assertEquals(listOf("goal.restore"), firstRenderer.slots.conversationEffects.map { it.id })
            runtime.pluginManager.setEnabled("provider.ui.chat.goal", false)
            renderChanges()
            assertTrue(firstRenderer.slots.conversationDecorations.isEmpty())
            assertEquals(listOf("goal.restore"), firstRenderer.slots.conversationEffects.map { it.id })
            assertSame(remembered, firstRenderer.remembered)
            runtime.pluginManager.setEnabled("provider.ui.chat.goal", true)
            renderChanges()
            runtime.pluginManager.setEnabled("consumer.goals.chat-restoration", false)
            renderChanges()
            assertEquals(listOf("goal"), firstRenderer.slots.conversationDecorations.map { it.id })
            assertTrue(firstRenderer.slots.conversationEffects.isEmpty())
            runtime.pluginManager.setEnabled("consumer.goals.chat-restoration", true)
            renderChanges()
            val originalChat = firstRenderer.slots.chat
            val originalExporter = firstRenderer.exporter
            assertTrue(originalExporter != null)
            runtime.pluginManager.setEnabled("provider.export.image-rendering", false)
            renderChanges()
            assertNull(firstRenderer.exporter)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.export.conversation" }.state)
            assertSame(originalChat, firstRenderer.slots.chat)
            assertSame(remembered, firstRenderer.remembered)
            runtime.pluginManager.setEnabled("provider.export.image-rendering", true)
            renderChanges()
            assertTrue(firstRenderer.exporter != null)
            assertFalse(originalExporter === firstRenderer.exporter)
            runtime.pluginManager.setEnabled("provider.export.conversation", false)
            renderChanges()
            assertNull(firstRenderer.exporter)
            runtime.pluginManager.setEnabled("provider.export.conversation", true)
            renderChanges()
            assertTrue(firstRenderer.exporter != null)
            assertEquals(listOf("chat", "artifacts"), firstRenderer.slots.navigation.map { it.id })
            val originalNavigation = firstRenderer.slots.navigation.first()
            runtime.pluginManager.setEnabled("provider.ui.navigation.custom", true)
            renderChanges()
            assertEquals(listOf("custom", "chat", "artifacts"), firstRenderer.slots.navigation.map { it.id })
            assertEquals("custom", ai.meteor.kcode.plugin.ui.api.resolveNavigationDestination("removed", firstRenderer.slots.navigation))
            assertEquals("chat", ai.meteor.kcode.plugin.ui.api.resolveNavigationDestination("chat", firstRenderer.slots.navigation))
            runtime.pluginManager.setEnabled("provider.ui.navigation.custom", false)
            renderChanges()
            assertEquals(listOf("chat", "artifacts"), firstRenderer.slots.navigation.map { it.id })
            assertSame(remembered, firstRenderer.remembered)
            runtime.pluginManager.setEnabled("provider.ui.navigation.chat", false)
            renderChanges()
            assertEquals("artifacts", ai.meteor.kcode.plugin.ui.api.resolveNavigationDestination("chat", firstRenderer.slots.navigation))
            runtime.pluginManager.setEnabled("provider.ui.navigation.chat", true)
            renderChanges()
            assertFailsWith<IllegalArgumentException> {
                runtime.replacePlugin(kcodePlugin(
                    descriptor("provider.ui.navigation.chat"),
                    plugin<Unit>(name = "duplicate-route", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
                        collect(ctx.require(KcodeUiSlots.Key).registerNavigation(originalNavigation.copy(id = "artifacts")))
                    },
                    Unit,
                ))
            }
            renderChanges()
            assertEquals(listOf("chat", "artifacts"), firstRenderer.slots.navigation.map { it.id })
            assertEquals(listOf("schedule.dispatch"), firstRenderer.slots.effects.map { it.id })
            runtime.pluginManager.setEnabled("consumer.schedules.application", false)
            renderChanges()
            assertTrue(firstRenderer.slots.effects.isEmpty())
            assertSame(remembered, firstRenderer.remembered)
            runtime.pluginManager.setEnabled("consumer.schedules.application", true)
            renderChanges()
            assertEquals(listOf("schedule.dispatch"), firstRenderer.slots.effects.map { it.id })
            assertEquals(listOf("assistant", "error", "user"), firstRenderer.slots.messagePresentations.map { it.id })
            runtime.pluginManager.setEnabled("provider.ui.message.assistant", false)
            renderChanges()
            assertEquals(listOf("error", "user"), firstRenderer.slots.messagePresentations.map { it.id })
            assertSame(remembered, firstRenderer.remembered)
            runtime.pluginManager.setEnabled("provider.ui.message.assistant", true)
            renderChanges()
            val previousAssistant = firstRenderer.slots.messagePresentations.first { it.id == "assistant" }
            val customAssistant = previousAssistant.copy(renderer = UiRenderer { })
            runtime.replacePlugin(messagePresentationPlugin(customAssistant))
            renderChanges()
            assertSame(customAssistant, firstRenderer.slots.messagePresentations.first { it.id == "assistant" })
            runtime.pluginManager.setEnabled("provider.ui.tool.default", false)
            renderChanges()
            assertTrue(firstRenderer.slots.toolUsePresentations.isEmpty())
            runtime.pluginManager.setEnabled("provider.ui.tool.default", true)
            renderChanges()
            assertEquals(listOf("default"), firstRenderer.slots.toolUsePresentations.map { it.id })
            assertEquals(listOf("language", "model", "search", "shell"), firstRenderer.slots.settingsSections.map { it.id })
            runtime.pluginManager.setEnabled("provider.ui.settings.search", false)
            renderChanges()
            assertEquals(listOf("language", "model", "shell"), firstRenderer.slots.settingsSections.map { it.id })
            assertSame(remembered, firstRenderer.remembered)
            runtime.pluginManager.setEnabled("provider.ui.settings.search", true)
            renderChanges()
            val originalModel = firstRenderer.slots.settingsSections.first { it.id == "model" }
            val replacementModel = originalModel.copy(renderer = UiRenderer { })
            runtime.replacePlugin(settingsSectionPlugin(replacementModel))
            renderChanges()
            assertSame(replacementModel, firstRenderer.slots.settingsSections.first { it.id == "model" })
            assertFailsWith<IllegalArgumentException> {
                runtime.replacePlugin(kcodePlugin(
                    descriptor("provider.ui.settings.model"),
                    plugin<Unit>(name = "duplicate-section", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
                        collect(ctx.require(KcodeUiSlots.Key).registerSettings(
                            originalModel.copy(id = "language"),
                        ))
                    },
                    Unit,
                ))
            }
            renderChanges()
            assertSame(replacementModel, firstRenderer.slots.settingsSections.first { it.id == "model" })
            runtime.pluginManager.setEnabled("provider.ui.artifacts", false)
            renderChanges()
            assertNull(firstRenderer.slots.artifacts)
            assertEquals(listOf("chat"), firstRenderer.slots.navigation.filter { it.isAvailable(firstRenderer.slots) }.map { it.id })
            assertSame(originalChat, firstRenderer.slots.chat)
            assertSame(remembered, firstRenderer.remembered)
            runtime.pluginManager.setEnabled("provider.ui.artifacts", true)
            renderChanges()
            assertTrue(firstRenderer.slots.artifacts != null)
            val replacementChat = UiRenderer<ChatPageRequest> { }
            runtime.replacePlugin(uiSlotPlugin("provider.ui.chat", ApplicationSlots.Chat, replacementChat))
            renderChanges()
            assertSame(replacementChat, firstRenderer.slots.chat)
            assertSame(remembered, firstRenderer.remembered)
            assertFailsWith<IllegalArgumentException> {
                runtime.replacePlugin(uiSlotPlugin("provider.ui.chat", ApplicationSlots.Settings, DefaultSettingsPageRenderer))
            }
            renderChanges()
            assertSame(replacementChat, firstRenderer.slots.chat)
            runtime.pluginManager.setEnabled("consumer.tools.goal", false)
            renderChanges()
            assertSame(remembered, firstRenderer.remembered)
            val staleSettings = checkNotNull(firstRenderer.settings)
            runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), SettingsProviderPlugin, secondSettings))
            renderChanges()
            assertFailsWith<IllegalStateException> { staleSettings.load() }
            assertFailsWith<IllegalStateException> { staleSettings.save(StoredAppSettings()) }
            assertEquals(secondSettings.load(), observedSettings.last().load())
            assertSame(observedSettings.last(), firstRenderer.settings)
            runtime.replacePlugin(uiMount(secondRenderer))
            renderChanges()
            assertSame(observedSettings.last(), secondRenderer.settings)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
            runtime.close()
        }
    }
}

private const val AgentId = "provider.agent-loop.koog"
private val model = ModelConfiguration(ModelProvider.Ollama, "fixture", "", temperature = 0.6)
private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
private fun config(profile: KcodePluginProfile = KcodePluginProfile()) = KcodePluginRuntimeConfig(
    interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
    profile = profile,
)

private fun agentMount(answer: String, beforeReply: suspend () -> Unit = {}): KcodePluginMount = kcodePlugin(
    descriptor(AgentId),
    object : Plugin<Unit> {
        override val name = "fixture-agent-$answer"
        override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
            KcodeAgents(ctx, object : ChatService {
                override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String {
                    beforeReply()
                    return answer
                }
            })
        }
    },
    Unit,
)

private fun uiMount(renderer: DefaultUiRenderer) = kcodePlugin(descriptor("provider.ui.compose"), ApplicationUiPlugin, renderer)

private class MemorySettings(language: String) : AppSettingsStore {
    override val protection = SettingsProtection.Transient
    private var settings = StoredAppSettings(language = language)
    override suspend fun load() = settings
    override suspend fun save(settings: StoredAppSettings) { this.settings = settings }
}

private class RecordingRenderer(
    private val pageContext: ai.meteor.kcode.plugin.ui.api.ConversationPageContext? = null,
    private val layoutContext: ApplicationLayoutRequest? = null,
    private val labeledProvider: ModelProvider? = null,
) : DefaultUiRenderer {
    var commands = ai.meteor.kcode.chat.ConversationCommandSnapshot()
    var catalog = ai.meteor.kcode.model.ModelCatalogSnapshot()
    var exporter: ai.meteor.kcode.export.ConversationExporter? = null
    var settings: AppSettingsStore? = null
    var slots = ApplicationUiSlots()
    var remembered: Any? = null
    var providerLabel: String? = null
    var providerDescription: String? = null
    var modelLabel: String? = null
    var modelDescription: String? = null
    @Composable
    override fun Render(services: ApplicationViewServices, options: ApplicationHostOptions) {
        remembered = remember { Any() }
        settings = services.settingsStore
        commands = services.commands
        catalog = services.modelCatalog
        androidx.compose.runtime.CompositionLocalProvider(
            ai.meteor.kcode.plugin.ui.api.LocalModelCatalog provides services.modelCatalog,
            ai.meteor.kcode.localization.LocalAppLanguage provides AppLanguage.Chinese,
        ) {
            labeledProvider?.let {
                providerLabel = ai.meteor.kcode.plugin.ui.api.providerName(it)
                providerDescription = ai.meteor.kcode.plugin.ui.api.providerNote(it)
                val option = ai.meteor.kcode.model.ModelOption(it, "gpt-4o-mini", defaultTemperature = 0.6)
                modelLabel = ai.meteor.kcode.plugin.ui.api.modelName(option)
                modelDescription = ai.meteor.kcode.plugin.ui.api.modelDescription(option)
            }
        }
        exporter = services.conversationExporter
        slots = services.uiSlots
        layoutContext?.let { request ->
            RenderApplicationLayout(request.copy(sidebarRenderer = slots.sidebar), slots.layout)
        }
        pageContext?.let { context ->
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f),
            ) {
                ai.meteor.kcode.plugin.ui.api.PresentConversationContributions(context, services.uiSlots)
            }
        }
    }
}

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
