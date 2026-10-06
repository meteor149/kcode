package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.cordis.dependencies
import org.cordis.plugin
import java.nio.file.Files
import kotlin.test.Test

class FormalUiContributionPrivateLoadingTest {
    @Test
    fun realJarsOwnEveryDefaultPageNavigationSettingsAndMessageContribution(): Unit = runBlocking {
        val directory = Files.createTempDirectory("formal-ui-private").toFile()
        val artifacts = mutableMapOf<String, File>()
        fun artifactFor(id: String, entry: Class<*>): File {
            val moduleJar = File(entry.protectionDomain.codeSource.location.toURI())
            val source = File(moduleJar.parentFile.parentFile, "cordis/artifacts/${id.replace('.', '-')}/desktop/plugin.jar")
            check(source.isFile) { "Missing packaged private dependency closure for $id" }
            return artifacts.getOrPut(id) {
                File(directory, "module-${artifacts.size}.jar").also { source.copyTo(it); check(it.setReadOnly()) }
            }
        }
        lateinit var slots: KcodeUiSlots
        val capture = kcodePlugin(PluginDescriptor("test.formal-ui", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-formal-ui", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        val entries = listOf(
            UiCase("provider.ui.conversation.transcript", DefaultConversationTranscriptUiPlugin::class.java) { it.conversationTranscript },
            UiCase("provider.ui.layout", DefaultLayoutUiPlugin::class.java) { it.layout },
            UiCase("provider.ui.sidebar", DefaultSidebarUiPlugin::class.java) { it.sidebar },
            UiCase("provider.ui.chat", DefaultChatUiPlugin::class.java) { it.chat },
            UiCase("provider.ui.conversation.standalone", DefaultStandaloneConversationUiPlugin::class.java) { it.standaloneConversation },
            UiCase("provider.ui.settings", DefaultSettingsUiPlugin::class.java) { it.settings },
            UiCase("provider.ui.theme", DefaultThemeUiPlugin::class.java) { it.theme },
            UiCase("provider.ui.navigation.chat", DefaultChatNavigationPlugin::class.java) { it.navigation.singleOrNull { item -> item.id == "chat" }?.renderer },
            UiCase("provider.ui.message.user", DefaultUserMessagePresentationPlugin::class.java) { it.messagePresentations.singleOrNull { item -> item.id == "user" }?.renderer },
            UiCase("provider.ui.message.assistant", DefaultAssistantMessagePresentationPlugin::class.java) { it.messagePresentations.singleOrNull { item -> item.id == "assistant" }?.renderer },
            UiCase("provider.ui.message.error", DefaultErrorMessagePresentationPlugin::class.java) { it.messagePresentations.singleOrNull { item -> item.id == "error" }?.renderer },
            UiCase("provider.ui.tool.default", DefaultToolUsePresentationPlugin::class.java) { it.toolUsePresentations.singleOrNull { item -> item.id == "default" }?.renderer },
        )
        fun spec(id: String, entry: Class<*>) = DynamicPluginSpec(
            id = id, version = "private-ui", artifactPath = artifactFor(id, entry).path,
            sha256 = MessageDigest.getInstance("SHA-256").digest(artifactFor(id, entry).readBytes()).joinToString("") { "%02x".format(it) },
            entryClass = entry.name,
        )
        try {
            for ((id, entry, select) in entries) {
                val deployment = spec(id, entry)
                runtime.pluginManager.replace(deployment)
                val renderer = requireNotNull(select(slots.snapshot())) { "Missing contribution $id" }
                assertNotSame(ApplicationUiSlots::class.java.classLoader, uiImplementation(renderer).javaClass.classLoader, id)
                assertNotSame(entry.classLoader, uiImplementation(renderer).javaClass.classLoader, id)
                if (id == "provider.ui.settings") {
                    for (part in listOf("BottomSheetOverlayKt", "BottomSheetDialogPropertiesKt")) {
                        val implementation = Class.forName(
                            "ai.meteor.kcode.ui.component.$part",
                            false,
                            uiImplementation(renderer).javaClass.classLoader,
                        )
                        assertSame(ai.meteor.kcode.ui.component.KcodeIconAsset::class.java.classLoader, implementation.classLoader, part)
                        assertNotSame(uiImplementation(renderer).javaClass.classLoader, implementation.classLoader, part)
                    }
                    assertFailsWith<ClassNotFoundException> {
                        Class.forName("ai.meteor.kcode.plugin.pages.component.BottomSheetOverlayKt", false, uiImplementation(renderer).javaClass.classLoader)
                    }
                }
                if (id == "provider.ui.theme") {
                    for (part in listOf("KcodeDefaultThemeKt", "KcodeDefaultTypographyKt")) {
                        val sharedDefaults = Class.forName(
                            "ai.meteor.kcode.ui.defaulttheme.$part",
                            true,
                            uiImplementation(renderer).javaClass.classLoader,
                        )
                        assertSame(ai.meteor.kcode.ui.component.KcodeIconAsset::class.java.classLoader, sharedDefaults.classLoader, part)
                        assertNotSame(uiImplementation(renderer).javaClass.classLoader, sharedDefaults.classLoader, part)
                    }
                    for (part in listOf("DefaultPaletteKt", "DefaultThemeRendererKt", "DefaultTypographyKt", "DefaultDesignTokensKt")) {
                        val implementation = Class.forName("ai.meteor.kcode.plugin.pages.ui.design.$part", false, uiImplementation(renderer).javaClass.classLoader)
                        assertSame(uiImplementation(renderer).javaClass.classLoader, implementation.classLoader, part)
                    }
                }

                if (entry == DefaultConversationTranscriptUiPlugin::class.java || id.startsWith("provider.ui.message.") || id.startsWith("provider.ui.tool.")) {
                    val privateBody = Class.forName(
                        "ai.meteor.kcode.plugin.pages.chat.component.ChatAssistantMessageKt",
                        false,
                        uiImplementation(renderer).javaClass.classLoader,
                    )
                    assertSame(uiImplementation(renderer).javaClass.classLoader, privateBody.classLoader, id)
                    assertFailsWith<ClassNotFoundException> {
                        Class.forName("ai.meteor.kcode.ui.component.chat.ChatAssistantMessageKt", false, uiImplementation(renderer).javaClass.classLoader)
                    }
                }
                assertFailsWith<IllegalStateException> {
                    runtime.pluginManager.replace(deployment.copy(version = "invalid-config", config = null))
                }
                assertSame(renderer, select(slots.snapshot()), id)
                runtime.pluginManager.setEnabled(id, false)
                assertNull(select(slots.snapshot()), id)
                runtime.pluginManager.setEnabled(id, true)
                assertNotSame(entry.classLoader, uiImplementation(requireNotNull(select(slots.snapshot()))).javaClass.classLoader, id)
            }
            runtime.pluginManager.setEnabled("provider.conversation-execution.history", false)
            assertNull(slots.snapshot().chat)
            runtime.pluginManager.setEnabled("provider.conversation-execution.history", true)
            assertNotSame(DefaultChatUiPlugin::class.java.classLoader, uiImplementation(requireNotNull(slots.snapshot().chat)).javaClass.classLoader)
            runtime.pluginManager.replace(spec("core.ui-slots", UiSlotsServicePlugin::class.java))
            val previous = slots
            runtime.pluginManager.setEnabled("core.ui-slots", false)
            assertFailsWith<IllegalStateException> { previous.snapshot() }
            runtime.pluginManager.setEnabled("core.ui-slots", true)
            assertNotSame(previous, slots)
            for ((id, entry, select) in entries) {
                assertNotSame(entry.classLoader, uiImplementation(requireNotNull(select(slots.snapshot())) { id }).javaClass.classLoader, id)
            }
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    private data class UiCase(val id: String, val entry: Class<*>, val select: (ApplicationUiSlots) -> Any?)
}
