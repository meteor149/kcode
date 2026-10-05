package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.chat.UnavailableGoalSessions
import ai.meteor.kcode.chat.UnavailableScheduledTasks
import ai.meteor.kcode.export.ComposeConversationImageRenderContext
import ai.meteor.kcode.export.ConversationExportRequest
import ai.meteor.kcode.export.ConversationExporter
import ai.meteor.kcode.export.ExportAction
import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LanguageOption
import ai.meteor.kcode.localization.LocalizationContext
import ai.meteor.kcode.localization.LocalizationSnapshot
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.TranslationCatalog
import ai.meteor.kcode.localization.formatLocalizedText
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeConversationExport
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.export.ConversationExportFeaturePlugin
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.ChatPageRequest
import ai.meteor.kcode.plugin.ui.api.ConversationDecoration
import ai.meteor.kcode.plugin.ui.api.ConversationPageContext
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.session.HistoryConversationState
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.dependencies
import org.cordis.plugin
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExportUiPrivateLifecycleTest {
    @Test
    fun privateExportOwnsPresentationWhileChatAndHeadlessServicesRemainIndependent(): Unit = runBlocking {
        val directory = Files.createTempDirectory("private-export-ui").toFile()
        lateinit var services: Context
        lateinit var exporter: ConversationExporter
        val capture = kcodePlugin(PluginDescriptor("test.export-ui", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-export-ui", inject = dependencies(KcodeUiSlots.Key, KcodeGeneration.Key, KcodeHistory.Key, KcodeAgents.Key),
        ) { ctx, _ -> services = ctx }, Unit)
        val exportCapture = kcodePlugin(PluginDescriptor("test.export-service", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-export-service", inject = dependencies(KcodeConversationExport.Key),
        ) { ctx, _ -> exporter = ctx.require(KcodeConversationExport.Key).exporter }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture, exportCapture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        try {
            listOf("feature.conversation-export" to ConversationExportFeaturePlugin::class.java,
                "provider.ui.chat" to DefaultChatUiPlugin::class.java).forEach { (id, entry) ->
                val artifact = File(directory, "$id.jar")
                val moduleJar = File(entry.protectionDomain.codeSource.location.toURI())
                val packaged = File(moduleJar.parentFile.parentFile, "cordis/artifacts/${id.replace('.', '-')}/desktop/plugin.jar")
                check(packaged.isFile)
                packaged.copyTo(artifact)
                check(artifact.setReadOnly())
                runtime.pluginManager.replace(DynamicPluginSpec(
                    id = id, version = "private-ui", entryClass = entry.name, artifactPath = artifact.path,
                    sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
                ))
            }
            suspend fun snapshot() = services.require(KcodeUiSlots.Key).snapshot()
            var skin by mutableStateOf(snapshot())
            val originalChat = skin.chat
            val original = skin.conversationDecorations.single { it.id == "conversation-export" }
            assertNotSame(ConversationExportFeaturePlugin::class.java.classLoader, uiImplementation(original.presenter).javaClass.classLoader)
            val loader = uiImplementation(original.presenter).javaClass.classLoader
            assertSame(loader, Class.forName("ai.meteor.kcode.plugin.uitexts.conversationexport.BuiltinUiTextsKt", false, loader).classLoader)
            val target = HistoryConversationState(81, "export")
            target.messages += ChatMessage(1, MessageRole.User, "private export message")
            val chat = services.require(KcodeAgents.Key).chatService
            val generation = services.require(KcodeGeneration.Key).runner
            val history = services.require(KcodeHistory.Key).repository
            var newRequests = 0
            val request = ChatPageRequest(
                Modifier, true, target, null, chat, generation, null, {}, {}, {},
                { newRequests++ }, { target }, history, UnavailableGoalSessions, UnavailableScheduledTasks,
            )
            var menuPage by mutableStateOf<ai.meteor.kcode.plugin.ui.api.UiRenderer<androidx.compose.ui.Modifier>?>(null)
            val moreMenu = object : ai.meteor.kcode.plugin.ui.api.ConversationMoreMenu {
                override fun showPage(renderer: ai.meteor.kcode.plugin.ui.api.UiRenderer<androidx.compose.ui.Modifier>) { menuPage = renderer }
                override fun dismiss() { menuPage = null }
            }
            val page = ConversationPageContext(target, true, null, chat, generation, UnavailableScheduledTasks,
                ChatFailureMessages("setup", "connection"), {}, moreMenu = moreMenu)
            withContext(Dispatchers.Main.immediate) {
                val scene = ImageComposeScene(width = 400, height = 600, coroutineContext = coroutineContext) {
                    requireNotNull(skin.theme).Render {
                        LocalizationContext(remember(skin) { SnapshotCatalog(skin) }, "en") {
                            CompositionLocalProvider(LocalApplicationUiSlots provides skin) { requireNotNull(skin.chat).Render(request) }
                        }
                    }
                }
                var retained by mutableStateOf(original)
                var preparedCount = -1
                val probe = ImageComposeScene(width = 300, height = 400, coroutineContext = coroutineContext) {
                    LocalizationContext(remember(skin) { SnapshotCatalog(skin) }, "en") {
                        val contents = retained.presenter.Present(page)
                        SideEffect { preparedCount = contents.size }
                        requireNotNull(skin.theme).Render {
                            menuPage?.Render(Modifier)
                            contents.forEach { content ->
                                check(content.position == ai.meteor.kcode.plugin.ui.api.ConversationDecorationPosition.MoreActions)
                                content.renderer.Render(Modifier)
                            }
                        }
                    }
                }
                var frame = 0L
                suspend fun render() {
                    repeat(4) { scene.render(frame++ * 50000000L).close(); probe.render(frame * 50000000L).close(); delay(30) }
                }
                try {
                    render()
                    assertTrue("Export" in labels(probe))
                    assertTrue("New chat" in labels(scene))
                    assertEquals(1, preparedCount)
                    click(probe, "Export")
                    render()
                    assertTrue("Save to photos" in labels(probe))
                    assertTrue("Share image" in labels(probe))
                    runtime.pluginManager.setEnabled("feature.conversation-export", false)
                    skin = snapshot()
                    render()
                    assertSame(originalChat, skin.chat)
                    assertEquals(0, preparedCount)
                    assertTrue("Save to photos" !in labels(probe))
                    assertTrue("Share image" !in labels(probe))
                    assertTrue("Export" !in labels(probe))
                    assertTrue("New chat" in labels(scene))
                    click(scene, "New chat")
                    assertEquals(1, newRequests)
                    runtime.pluginManager.setEnabled("feature.conversation-export", true)
                    skin = snapshot()
                    retained = skin.conversationDecorations.single { it.id == "conversation-export" }
                    assertNotSame(original.presenter, retained.presenter)
                    render()
                    assertTrue("Export" in labels(probe))
                    val liveExporter = exporter
                    runtime.pluginManager.setEnabled("core.ui-slots", false)
                    render()
                    assertEquals(0, preparedCount)
                    lateinit var context: ComposeConversationImageRenderContext
                    val headless = ImageComposeScene(width = 200, height = 100, coroutineContext = coroutineContext) {
                        val value = ComposeConversationImageRenderContext(rememberTextMeasurer(), rememberGraphicsLayer(), LocalLayoutDirection.current)
                        SideEffect { context = value }
                    }
                    try {
                        headless.render(0L).close()
                        val result = liveExporter.export(ConversationExportRequest(81, "headless", target.messages.toList(), truncatedLabel = "truncated"), ExportAction.Save, context)
                        assertEquals(ImageSaveResult.Unsupported, result.saved)
                    } finally { headless.close() }
                    runtime.pluginManager.setEnabled("core.ui-slots", true)
                    skin = snapshot()
                    retained = skin.conversationDecorations.single { it.id == "conversation-export" }
                    assertSame(liveExporter, exporter)
                    render()
                    assertTrue("Export" in labels(probe))
                } finally { probe.close(); scene.close() }
            }
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    private fun root(scene: ImageComposeScene): SemanticsNode {
        fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).run { isAccessible = true; get(target) }
        val owner = field(field(scene, "scene"), "mainOwner")
        return (owner.javaClass.getMethod("getSemanticsOwner").invoke(owner) as SemanticsOwner).unmergedRootSemanticsNode
    }
    private fun labels(scene: ImageComposeScene): List<String> {
        fun collect(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::collect)
        return collect(root(scene))
    }
    private fun click(scene: ImageComposeScene, label: String) {
        fun containsText(node: SemanticsNode): Boolean =
            label in node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } ||
                node.children.any(::containsText)
        fun find(node: SemanticsNode): (() -> Boolean)? {
            if (label in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) {
                node.config.getOrNull(SemanticsActions.OnClick)?.action?.let { return it }
            }
            if (containsText(node)) node.config.getOrNull(SemanticsActions.OnClick)?.action?.let { return it }
            return node.children.firstNotNullOfOrNull(::find)
        }
        assertTrue(requireNotNull(find(root(scene)))())
    }
}

/** Test root using only prepared contributions, including feature-owned English defaults. */
private class SnapshotCatalog(private val slots: ApplicationUiSlots) : TranslationCatalog {
    override val available = MutableStateFlow(true)
    override fun snapshot() = LocalizationSnapshot(listOf(LanguageOption(AppLanguage.English, emptyMap())), AppLanguage.English)
    override fun displayText(language: AppLanguage, value: LocalizedText, arguments: List<Any>): String? =
        slots.textDictionaries.firstNotNullOfOrNull { if (it.available.value) it.values[value.key] else null }
            ?.let { formatLocalizedText(it, arguments) }
    override fun translate(language: AppLanguage, value: LocalizedText, vararg arguments: Any): String =
        requireNotNull(displayText(language, value, arguments.toList())) { "Missing ${value.key}" }
}
