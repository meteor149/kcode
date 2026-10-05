package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.cordis.dependencies
import org.cordis.plugin
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AndroidFormalUiContributionPrivateLoadingTest {
    @Test(timeout = 60000)
    fun privateApkPageShellContributions(): Unit = runBlocking {
        verifyEntries({ it.id in setOf("provider.ui.layout", "provider.ui.sidebar", "provider.ui.settings", "provider.ui.theme") })
    }

    @Test(timeout = 60000)
    fun privateApkFeaturePageContributions(): Unit = runBlocking {
        verifyEntries({ it.id in setOf("provider.ui.chat", "feature.artifacts", "provider.ui.conversation.standalone", "provider.ui.conversation.transcript") })
    }

    @Test(timeout = 60000)
    fun privateApkNavigationAndMessageContributions(): Unit = runBlocking {
        verifyEntries({ it.id.startsWith("provider.ui.navigation.") || it.id.startsWith("provider.ui.message.") || it.id.startsWith("provider.ui.tool.") })
    }

    @Test(timeout = 60000)
    fun privateRegistryWithdrawalRebindsEveryContributionKind(): Unit = runBlocking {
        verifyEntries({ it.id in setOf("provider.ui.chat", "provider.ui.navigation.chat", "provider.ui.message.user") }, verifyCore = true)
    }

    private suspend fun verifyEntries(selectEntries: (UiCase) -> Boolean, verifyCore: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "formal-ui-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "ui.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val digest = packageFileSha256(artifact)
        lateinit var slots: KcodeUiSlots
        val capture = kcodePlugin(PluginDescriptor("test.formal-ui", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-formal-ui", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val entries = listOf(
            UiCase("provider.ui.conversation.transcript", DefaultConversationTranscriptUiPlugin::class.java) { it.conversationTranscript },
            UiCase("provider.ui.layout", DefaultLayoutUiPlugin::class.java) { it.layout },
            UiCase("provider.ui.sidebar", DefaultSidebarUiPlugin::class.java) { it.sidebar },
            UiCase("provider.ui.chat", DefaultChatUiPlugin::class.java) { it.chat },
            UiCase("feature.artifacts", ArtifactFeaturePlugin::class.java) { it.artifacts },
            UiCase("provider.ui.conversation.standalone", DefaultStandaloneConversationUiPlugin::class.java) { it.standaloneConversation },
            UiCase("provider.ui.settings", DefaultSettingsUiPlugin::class.java) { it.settings },
            UiCase("provider.ui.theme", DefaultThemeUiPlugin::class.java) { it.theme },
            UiCase("provider.ui.navigation.chat", DefaultChatNavigationPlugin::class.java) { it.navigation.singleOrNull { item -> item.id == "chat" }?.renderer },
            UiCase("feature.artifacts", ArtifactFeaturePlugin::class.java) { it.navigation.singleOrNull { item -> item.id == "artifacts" }?.renderer },
            UiCase("provider.ui.message.user", DefaultUserMessagePresentationPlugin::class.java) { it.messagePresentations.singleOrNull { item -> item.id == "user" }?.renderer },
            UiCase("provider.ui.message.assistant", DefaultAssistantMessagePresentationPlugin::class.java) { it.messagePresentations.singleOrNull { item -> item.id == "assistant" }?.renderer },
            UiCase("provider.ui.message.error", DefaultErrorMessagePresentationPlugin::class.java) { it.messagePresentations.singleOrNull { item -> item.id == "error" }?.renderer },
            UiCase("provider.ui.tool.default", DefaultToolUsePresentationPlugin::class.java) { it.toolUsePresentations.singleOrNull { item -> item.id == "default" }?.renderer },
        ).filter(selectEntries)
        fun spec(id: String, entry: Class<*>) = DynamicPluginSpec(
            id = id, version = "private-ui", artifactPath = artifact.path,
            sha256 = digest,
            entryClass = entry.name, packageName = instrumentation.context.packageName,
        )
        try {
            for ((id, entry, select) in entries) {
                val deployment = spec(id, entry)
                runtime.pluginManager.replace(deployment)
                val renderer = requireNotNull(select(slots.snapshot())) { "Missing contribution $id" }
                assertNotSame(ApplicationUiSlots::class.java.classLoader, renderer.javaClass.classLoader, id)
                assertNotSame(entry.classLoader, renderer.javaClass.classLoader, id)
                if (id == "provider.ui.settings") {
                    for (part in listOf("BottomSheetOverlayKt", "BottomSheetDialogPropertiesKt")) {
                        val implementation = Class.forName(
                            "ai.meteor.kcode.ui.component.$part",
                            false,
                            renderer.javaClass.classLoader,
                        )
                        assertSame(ai.meteor.kcode.ui.component.KcodeIconAsset::class.java.classLoader, implementation.classLoader, part)
                        assertNotSame(renderer.javaClass.classLoader, implementation.classLoader, part)
                    }
                    assertFailsWith<ClassNotFoundException> {
                        Class.forName("ai.meteor.kcode.plugin.pages.component.BottomSheetOverlayKt", false, renderer.javaClass.classLoader)
                    }
                }
                if (id == "provider.ui.theme") {
                    for (part in listOf("DefaultPaletteKt", "DefaultTypographyKt", "DefaultDesignTokensKt")) {
                        val implementation = Class.forName("ai.meteor.kcode.plugin.pages.ui.design.$part", false, renderer.javaClass.classLoader)
                        assertSame(renderer.javaClass.classLoader, implementation.classLoader, part)
                    }
                }

                if (entry == DefaultConversationTranscriptUiPlugin::class.java || id.startsWith("provider.ui.message.") || id.startsWith("provider.ui.tool.")) {
                    val privateBody = Class.forName(
                        "ai.meteor.kcode.plugin.pages.chat.component.ChatAssistantMessageKt",
                        false,
                        renderer.javaClass.classLoader,
                    )
                    assertSame(renderer.javaClass.classLoader, privateBody.classLoader, id)
                    assertFailsWith<ClassNotFoundException> {
                        Class.forName("ai.meteor.kcode.ui.component.chat.ChatAssistantMessageKt", false, renderer.javaClass.classLoader)
                    }
                }
                assertFailsWith<IllegalStateException> {
                    runtime.pluginManager.replace(deployment.copy(version = "invalid-config", config = null))
                }
                assertSame(renderer, select(slots.snapshot()), id)
                runtime.pluginManager.setEnabled(id, false)
                assertNull(select(slots.snapshot()), id)
                runtime.pluginManager.setEnabled(id, true)
                assertNotSame(entry.classLoader, requireNotNull(select(slots.snapshot())).javaClass.classLoader, id)
            }
            if (entries.any { it.id == "provider.ui.chat" }) {
                runtime.pluginManager.setEnabled("provider.conversation-execution.history", false)
                assertNull(slots.snapshot().chat)
                runtime.pluginManager.setEnabled("provider.conversation-execution.history", true)
                assertNotSame(DefaultChatUiPlugin::class.java.classLoader, requireNotNull(slots.snapshot().chat).javaClass.classLoader)
            }
            if (verifyCore) {
                runtime.pluginManager.replace(spec("core.ui-slots", UiSlotsServicePlugin::class.java))
                val previous = slots
                runtime.pluginManager.setEnabled("core.ui-slots", false)
                assertNull(previous.snapshot().layout)
                runtime.pluginManager.setEnabled("core.ui-slots", true)
                assertNotSame(previous, slots)
                for ((id, entry, select) in entries) {
                    assertNotSame(entry.classLoader, requireNotNull(select(slots.snapshot())) { id }.javaClass.classLoader, id)
                }
            }
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    private data class UiCase(val id: String, val entry: Class<*>, val select: (ApplicationUiSlots) -> Any?)
}
