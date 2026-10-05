package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.androidPackageHost
import ai.meteor.kcode.plugin.packages.androidPackageVerifier

import ai.meteor.kcode.createAndroidKoogChatRuntime
import ai.meteor.kcode.settings.ShellExecutionMode
import android.app.Activity
import android.content.ContextWrapper
import android.content.Context as AndroidContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.overlay.AndroidNativeConversationOverlayPlugin
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.ui.api.defaultUi
import ai.meteor.kcode.plugin.api.KcodeConversationOverlays
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.ConversationOverlayFactory
import ai.meteor.kcode.plugin.overlay.ConversationOverlayProviderPlugin
import ai.meteor.kcode.plugin.overlay.createAndroidConversationOverlayController
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidConversationOverlayProviderTest {
    @Test(timeout = 60_000)
    fun isolatedApkOwnsOverlayTurnsAndRebindsHostProjection(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "overlay-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "overlay-provider.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var registry: KcodeConversationOverlays
        val capture = kcodePlugin(PluginDescriptor("test.overlay", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-overlay", inject = dependencies(KcodeConversationOverlays.Key),
        ) { ctx, _ -> registry = ctx.require(KcodeConversationOverlays.Key) }, Unit)
        val runtime = KcodePluginRuntime.create(config().copy(
            profile = KcodePluginProfile(disabled = setOf("provider.conversation-overlay.platform")),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val host = runtime.conversationOverlayController
        try {
            assertFails { host.startTurn(emptyList()) }
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.overlay", version = "test", entryClass = AndroidFixtureConversationOverlay::class.java.name,
                artifactPath = apk.path,
                sha256 = packageFileSha256(apk),
                packageName = instrumentation.context.packageName, config = "APK overlay",
            ))
            val old = requireNotNull(registry.current())
            assertTrue(old.javaClass.classLoader !== ConversationOverlayProviderPlugin::class.java.classLoader)
            host.setHostForeground(false)
            val turn = host.startTurn(listOf(ChatMessage(1L, MessageRole.Assistant, "APK overlay")))
            assertTrue(turn.javaClass.classLoader !== AgentConversationOverlayTurn::class.java.classLoader)
            turn.update(listOf(ChatMessage(1L, MessageRole.Assistant, "updated")))
            runtime.pluginManager.setEnabled("fixture.overlay", false)
            assertNull(registry.current())
            assertFailsWith<IllegalStateException> { turn.update(emptyList()) }
            assertFailsWith<IllegalStateException> { old.setHostForeground(true) }
            assertFails { host.startTurn(emptyList()) }
            runtime.pluginManager.setEnabled("fixture.overlay", true)
            assertNotSame(old, registry.current())
            assertSame(host, runtime.conversationOverlayController)
            host.startTurn(listOf(ChatMessage(2L, MessageRole.Assistant, "APK overlay"))).finish()
            runtime.pluginManager.uninstall("fixture.overlay")
            assertNull(registry.startTurn(emptyList()))
            assertFails { host.startTurn(emptyList()) }
        } finally {
            runtime.close()
            apk.setWritable(true)
            directory.deleteRecursively()
        }
        assertFailsWith<IllegalStateException> { host.setHostForeground(true) }
    }

    @Test(timeout = 60_000)
    fun actualNativeEntryLoadsFromApkAndReleasesEachController(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "native-overlay-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "overlay.kplugin")
        instrumentation.context.assets.open("provider.conversation-overlay.platform-1.0.0.kplugin").use { input -> apk.outputStream().use { input.copyTo(it) } }
        check(apk.setReadOnly())
        val registryArchive = File(directory, "registry.kplugin")
        instrumentation.context.assets.open("core.conversation-overlays-1.0.0.kplugin").use { input -> registryArchive.outputStream().use { input.copyTo(it) } }
        check(registryArchive.setReadOnly())
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() {
                override fun getApplicationContext(): AndroidContext = context
            }
        }
        val inputs = AndroidPluginHostInputs(activity)
        lateinit var registry: KcodeConversationOverlays
        val capture = kcodePlugin(PluginDescriptor("test.overlay", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-native-overlay", inject = dependencies(KcodeConversationOverlays.Key),
        ) { ctx, _ -> registry = ctx.require(KcodeConversationOverlays.Key) }, Unit)
        val runtime = KcodePluginRuntime.create(config().copy(
            hostInputs = inputs,
            profile = KcodePluginProfile(disabled = setOf("provider.conversation-overlay.platform")),
            featurePlugins = listOf(capture, kcodePlugin(
                PluginDescriptor("provider.plugin-packages.platform", "test", "test", emptySet()),
                NativePluginPackagesPlugin(directory, androidPackageHost(), artifactVerifier = androidPackageVerifier(context)), Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val delegates = mutableListOf<AgentConversationOverlayController>()
        fun delegate(owned: AgentConversationOverlayController): AgentConversationOverlayController =
            owned.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
                .get(owned) as AgentConversationOverlayController
        try {
            val originalProjection = registry.uiSlots
            runtime.conversationOverlayController.setHostForeground(false)
            runtime.pluginManager.importPackages(listOf(
                PluginPackageImport(registryArchive.absolutePath, packageFileSha256(registryArchive)),
                PluginPackageImport(apk.absolutePath, packageFileSha256(apk), enabled = true),
            ))
            assertSame(originalProjection, registry.uiSlots)
            val projection = registry.uiSlots
            assertFalse(projection is kotlinx.coroutines.flow.MutableStateFlow<*>)
            assertNotNull(projection.value.defaultUi().theme)
            runtime.pluginManager.setEnabled("provider.ui.theme", false)
            assertNull(projection.value.defaultUi().theme)
            runtime.pluginManager.setEnabled("provider.ui.theme", true)
            assertNotNull(projection.value.defaultUi().theme)
            val first = delegate(requireNotNull(registry.current())).also { delegates += it }
            assertNotSame(AndroidNativeConversationOverlayPlugin::class.java.classLoader, first.javaClass.classLoader)
            assertSame(AgentConversationOverlayController::class.java, first.javaClass.interfaces.single())
            assertFalse(first.javaClass.getDeclaredField("hostForeground").apply { isAccessible = true }.getBoolean(first))
            runtime.conversationOverlayController.setHostForeground(true)
            val turn = runtime.conversationOverlayController.startTurn(emptyList())
            runtime.pluginManager.setEnabled("provider.conversation-overlay.platform", false)
            assertNativeClosed(first)
            assertNull(registry.current())
            assertFailsWith<IllegalStateException> { turn.update(emptyList()) }
            runtime.pluginManager.setEnabled("provider.conversation-overlay.platform", true)
            val second = delegate(requireNotNull(registry.current())).also { delegates += it }
            assertNotSame(first, second)
            assertSame(projection, registry.uiSlots)
            runtime.conversationOverlayController.startTurn(emptyList()).finish()
            runtime.pluginManager.uninstall("provider.conversation-overlay.platform")
            assertNativeClosed(second)
            assertFailsWith<IllegalStateException> { second.startTurn(emptyList()) }
        } finally {
            runtime.close()
            apk.setWritable(true)
            directory.deleteRecursively()
        }
        delegates.forEach(::assertNativeClosed)
        assertFailsWith<IllegalStateException> { inputs.activity() }
    }

    @Test(timeout = 60_000)
    fun nativeControllerIsAllocatedPerProviderGenerationAndItsScopeIsJoined(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val delegates = mutableListOf<AgentConversationOverlayController>()
        val runtime = KcodePluginRuntime.create(config().copy(conversationOverlayFactory = { uiSlots ->
            createAndroidConversationOverlayController(context, uiSlots).also { delegates += it }
        }))
        try {
            val first = delegates.single()
            assertTrue(first.javaClass.name.startsWith("ai.meteor.kcode.plugin.overlay."))
            val turn = runtime.conversationOverlayController.startTurn(listOf(ChatMessage(1L, MessageRole.Assistant, "fixture")))
            runtime.pluginManager.setEnabled("provider.conversation-overlay.platform", false)
            assertNativeClosed(first)
            assertFailsWith<IllegalStateException> { turn.update(emptyList()) }
            assertFailsWith<IllegalStateException> { first.startTurn(emptyList()) }
            runtime.pluginManager.setEnabled("provider.conversation-overlay.platform", true)
            assertEquals(2, delegates.size)
            assertNotSame(first, delegates[1])
            runtime.conversationOverlayController.startTurn(emptyList()).finish()
        } finally { runtime.close() }
        delegates.forEach(::assertNativeClosed)
    }

    @Test(timeout = 60_000)
    fun nativeHostFactoryCanInitializeAndCloseOnMainWithoutBlockingIt(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "overlay-host-${System.nanoTime()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getDatabasePath(name: String) = File(directory, name)
            override fun getApplicationContext(): AndroidContext = this
        }
        try {
            withTimeout(30_000) {
                withContext(Dispatchers.Main.immediate) {
                    val activity = object : Activity() {
                        override fun getApplicationContext(): AndroidContext = isolatedContext
                        override fun getFilesDir() = directory
                        override fun getAssets() = InstrumentationRegistry.getInstrumentation().context.assets
                        override fun getResources() = context.resources
                    }
                    val runtime = createAndroidKoogChatRuntime(
                        activity = activity,
                        modeProvider = { ShellExecutionMode.App },
                        permissionModeProvider = { ToolPermissionMode.Bypass },
                        toolCallApprover = ToolCallApprover { true },
                    )
                    try {
                        val turn = requireNotNull(runtime.conversationOverlayController).startTurn(emptyList())
                        turn.finish()
                        assertTrue((runtime.owner as KcodeProfileHost).diagnostics().plugins.any {
                            it.id == "provider.conversation-overlay.platform"
                        })
                    } finally { runtime.close() }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun assertNativeClosed(controller: AgentConversationOverlayController) {
        val scopeField = controller.javaClass.getDeclaredField("scope").apply { isAccessible = true }
        val job = checkNotNull((scopeField.get(controller) as CoroutineScope).coroutineContext[Job])
        assertTrue(job.isCompleted)
        val turnsField = controller.javaClass.getDeclaredField("turns").apply { isAccessible = true }
        assertTrue((turnsField.get(controller) as List<*>).isEmpty())
    }

    private fun config() = KcodePluginRuntimeConfig(
        interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
    )
}

class AndroidFixtureConversationOverlay : Plugin<String> {
    override val name = "fixture-conversation-overlay"
    override val inject = dependencies(KcodeConversationOverlays.Key)
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        ConversationOverlayProviderPlugin.apply(ctx, ConversationOverlayFactory { FixtureOverlay(config) }, effect)
    }
}

private class FixtureOverlay(private val expected: String) : AgentConversationOverlayController {
    private var closed = false
    override suspend fun setHostForeground(isForeground: Boolean) { check(!closed) }
    override suspend fun startTurn(initialMessages: List<ChatMessage>): AgentConversationOverlayTurn {
        check(!closed)
        check(initialMessages.single().content == expected)
        return object : AgentConversationOverlayTurn {
            override suspend fun update(messages: List<ChatMessage>) {
                check(!closed)
                check(messages.single().content == "updated")
            }
            override suspend fun finish() = Unit
        }
    }
    override suspend fun close() { closed = true }
}
