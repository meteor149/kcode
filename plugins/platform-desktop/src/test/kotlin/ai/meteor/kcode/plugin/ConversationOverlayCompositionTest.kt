package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeConversationOverlays
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.ConversationOverlayFactory
import ai.meteor.kcode.plugin.overlay.ConversationOverlayProviderPlugin
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.cordis.dependencies
import org.cordis.plugin

class ConversationOverlayCompositionTest {
    @Test
    fun hostProjectionRebindsAndForegroundSurvivesProviderAndRegistryWithdrawal() = runTest {
        val delegates = mutableListOf<Fixture>()
        lateinit var registry: KcodeConversationOverlays
        val capture = kcodePlugin(PluginDescriptor("test.overlay", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-overlay", inject = dependencies(KcodeConversationOverlays.Key),
        ) { ctx, _ -> registry = ctx.require(KcodeConversationOverlays.Key) }, Unit)
        val runtime = KcodePluginRuntime.create(config().copy(
            conversationOverlayFactory = { Fixture().also { delegates += it } },
            featurePlugins = listOf(capture),
        ))
        val host = runtime.conversationOverlayController
        val old = requireNotNull(registry.current())
        try {
            host.setHostForeground(false)
            val turn = host.startTurn(emptyList())
            runtime.pluginManager.setEnabled("provider.conversation-overlay.platform", false)
            assertNull(registry.startTurn(emptyList()))
            assertFails { host.startTurn(emptyList()) }
            assertFailsWith<IllegalStateException> { old.startTurn(emptyList()) }
            assertFailsWith<IllegalStateException> { turn.update(emptyList()) }
            assertEquals(1, delegates[0].finished)
            assertEquals(1, delegates[0].closed)
            runtime.pluginManager.setEnabled("provider.conversation-overlay.platform", true)
            assertNotSame(old, registry.current())
            assertSame(host, runtime.conversationOverlayController)
            assertEquals(false, delegates[1].foregrounds.last())
            runtime.pluginManager.setEnabled("core.conversation-overlays", false)
            assertEquals(1, delegates[1].closed)
            assertFails { host.startTurn(emptyList()) }
            host.setHostForeground(true)
            runtime.pluginManager.setEnabled("core.conversation-overlays", true)
            assertEquals(true, delegates[2].foregrounds.last())
            runtime.replacePlugin(kcodePlugin(
                PluginDescriptor("provider.conversation-overlay.platform", "replacement", "test", setOf("conversationOverlay")),
                ConversationOverlayProviderPlugin, ConversationOverlayFactory { Fixture().also { delegates += it } },
            ))
            host.startTurn(emptyList()).finish()
            assertEquals(1, delegates.last().finished)
        } finally { runtime.close() }
        assertTrue(delegates.all { it.closed == 1 })
        assertFailsWith<IllegalStateException> { host.setHostForeground(false) }
        assertFailsWith<IllegalStateException> { host.startTurn(emptyList()) }
    }

    @Test
    fun missingPlatformControllerKeepsTheRegistryEmptyAndDoesNotCreateAnOverlay() = runTest {
        lateinit var registry: KcodeConversationOverlays
        val runtime = KcodePluginRuntime.create(config().copy(featurePlugins = listOf(kcodePlugin(
            PluginDescriptor("test.overlay", "test", "test", emptySet()), plugin<Unit>(
                name = "capture-overlay", inject = dependencies(KcodeConversationOverlays.Key),
            ) { ctx, _ -> registry = ctx.require(KcodeConversationOverlays.Key) }, Unit,
        ))))
        try {
            assertNull(registry.current())
            assertNull(registry.startTurn(emptyList()))
            runtime.conversationOverlayController.setHostForeground(false)
            assertFails { runtime.conversationOverlayController.startTurn(emptyList()) }
        } finally { runtime.close() }
    }

    @Test
    fun failedRegistrationClosesTheReplacementAndRestoresAFreshPreviousProvider() = runTest {
        val originals = mutableListOf<Fixture>()
        val failed = Fixture { throw IllegalArgumentException("foreground initialization failed") }
        val runtime = KcodePluginRuntime.create(config().copy(
            conversationOverlayFactory = { Fixture().also { originals += it } },
        ))
        val host = runtime.conversationOverlayController
        val oldTurn = host.startTurn(emptyList())
        try {
            assertFails {
                runtime.replacePlugin(kcodePlugin(
                    PluginDescriptor("provider.conversation-overlay.platform", "failed", "test", setOf("conversationOverlay")),
                    ConversationOverlayProviderPlugin, ConversationOverlayFactory { failed },
                ))
            }
            assertEquals(1, failed.closed)
            assertEquals(2, originals.size)
            assertEquals(1, originals.first().closed)
            assertFailsWith<IllegalStateException> { oldTurn.update(emptyList()) }
            host.startTurn(emptyList()).finish()
            assertEquals(1, originals.last().finished)
            assertEquals("builtin", runtime.inventory.snapshot().single { it.id == "provider.conversation-overlay.platform" }.version)
        } finally { runtime.close() }
        assertTrue(originals.all { it.closed == 1 })
        assertEquals(1, failed.closed)
    }

    @Test
    fun duplicateProviderAbortsAssemblyAndClosesBothAllocatedControllers() = runTest {
        val original = Fixture()
        val duplicate = Fixture()
        assertFails {
            KcodePluginRuntime.create(config().copy(
                conversationOverlayFactory = { original },
                featurePlugins = listOf(kcodePlugin(
                    PluginDescriptor("test.duplicate-overlay", "test", "test", emptySet()),
                    ConversationOverlayProviderPlugin, ConversationOverlayFactory { duplicate },
                )),
            ))
        }
        assertEquals(1, original.closed)
        assertEquals(1, duplicate.closed)
    }

    private fun config() = KcodePluginRuntimeConfig(
        interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
    )

    private class Fixture(
        private val onForeground: suspend (Boolean) -> Unit = {},
    ) : AgentConversationOverlayController {
        var closed = 0
        var finished = 0
        val foregrounds = mutableListOf<Boolean>()
        override suspend fun setHostForeground(isForeground: Boolean) {
            foregrounds += isForeground
            onForeground(isForeground)
        }
        override suspend fun close() { closed++ }
        override suspend fun startTurn(initialMessages: List<ChatMessage>) = object : AgentConversationOverlayTurn {
            override suspend fun update(messages: List<ChatMessage>) = Unit
            override suspend fun finish() { finished++ }
        }
    }
}
