package ai.meteor.kcode.plugin

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.ApplicationViewServices
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.cordis.Context
import org.cordis.dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.plugin

class PluginCompositionTest {
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
        val runtime = KcodePluginRuntime.create(config(KcodePluginProfile(overrides = listOf(uiMount(firstRenderer)))).copy(
            settingsStore = firstSettings,
            featurePlugins = listOf(consumer),
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
            testScheduler.runCurrent()
            Snapshot.sendApplyNotifications()
            testScheduler.runCurrent()
            recomposer.awaitIdle()
        }
        try {
            composition.setContent { runtime.Render(ApplicationHostOptions()) }
            renderChanges()
            assertSame(firstSettings, firstRenderer.settings)
            val remembered = firstRenderer.remembered
            runtime.pluginManager.setEnabled("consumer.tools.goal", false)
            renderChanges()
            assertSame(remembered, firstRenderer.remembered)
            runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), SettingsProviderPlugin, secondSettings))
            renderChanges()
            assertSame(secondSettings, observedSettings.last())
            assertSame(secondSettings, firstRenderer.settings)
            runtime.replacePlugin(uiMount(secondRenderer))
            renderChanges()
            assertSame(secondSettings, secondRenderer.settings)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
            runtime.close()
        }
    }
}

private const val AgentId = "provider.agent-loop.koog"
private val model = ModelConfiguration(ModelProvider.Ollama, "fixture", "")
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

private fun uiMount(renderer: ApplicationRenderer) = kcodePlugin(descriptor("provider.ui.compose"), ApplicationUiPlugin, renderer)

private class MemorySettings(language: String) : AppSettingsStore {
    override val protection = SettingsProtection.Transient
    private var settings = StoredAppSettings(language = language)
    override suspend fun load() = settings
    override suspend fun save(settings: StoredAppSettings) { this.settings = settings }
}

private class RecordingRenderer : ApplicationRenderer {
    var settings: AppSettingsStore? = null
    var remembered: Any? = null
    @Composable
    override fun Render(services: ApplicationViewServices, options: ApplicationHostOptions) {
        remembered = remember { Any() }
        settings = services.settingsStore
    }
}

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
