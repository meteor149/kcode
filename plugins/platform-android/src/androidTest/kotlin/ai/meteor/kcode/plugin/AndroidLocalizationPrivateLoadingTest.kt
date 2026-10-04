package ai.meteor.kcode.plugin

import ai.meteor.kcode.session.HistoryConversationState

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.TranslationCatalog
import ai.meteor.kcode.localization.UiText
import kotlin.test.assertNull
import ai.meteor.kcode.plugin.localization.LocalizationProviderPlugin
import ai.meteor.kcode.plugin.api.KcodeConversationCommands
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.cordis.dependencies
import org.cordis.plugin
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AndroidLocalizationPrivateLoadingTest {
    @Test
    fun privateDictionaryRebindsUiAndGoalCommandsAcrossRestart(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "localization-private-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "localization.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var catalog: TranslationCatalog
        lateinit var commands: KcodeConversationCommands
        lateinit var slots: KcodeUiSlots
        val capture = kcodePlugin(
            PluginDescriptor("test.localization", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-localization", inject = dependencies(KcodeLocalization.Key, KcodeConversationCommands.Key, KcodeUiSlots.Key)) { ctx, _ ->
                catalog = ctx.require(KcodeLocalization.Key).catalog
                commands = ctx.require(KcodeConversationCommands.Key)
                slots = ctx.require(KcodeUiSlots.Key)
            },
            Unit,
        )
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        )
        var runtime = KcodePluginRuntime.create(configuration())
        val target = HistoryConversationState(1L, "fixture")
        var feedback: String? = null
        val request = ai.meteor.kcode.chat.ConversationCommandRequest("/goal", AppLanguage.English, target,
            { error("Unexpected new conversation") }, object : ai.meteor.kcode.chat.ConversationCommandOperations {
                override fun nextMessageId(target: ai.meteor.kcode.ui.state.ConversationState) = target.reserveMessageIds()
                override suspend fun appendFeedback(target: ai.meteor.kcode.ui.state.ConversationState, user: ai.meteor.kcode.model.ChatMessage, content: String, isError: Boolean) { feedback = content }
                override suspend fun startResponse(target: ai.meteor.kcode.ui.state.ConversationState, user: ai.meteor.kcode.model.ChatMessage, prompt: String, goalSession: ai.meteor.kcode.chat.GoalSession?) = error("Unexpected generation")
            })
        suspend fun goalFeedback(): String? {
            feedback = null
            commands.committedSnapshot.resolve("/goal")?.execute(request)
            return feedback
        }
        suspend fun state(id: String) = runtime.diagnostics().plugins.first { it.id == id }.state
        try {
            assertEquals("New chat", catalog.translate(AppLanguage.English, UiText.NewChat))
            val deployment = DynamicPluginSpec(
                id = "provider.localization.default", version = "private-dictionary",
                artifactPath = artifact.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
                entryClass = LocalizationProviderPlugin::class.java.name,
                packageName = instrumentation.context.packageName,
                config = Json.parseToJsonElement("""{"defaultLanguage":"en","translations":{"en":{"new_chat":"Private action","goal_no_goal":"Private goal message"}}}"""),
            )
            runtime.pluginManager.replace(deployment)
            val original = catalog
            assertNotSame(TranslationCatalog::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(LocalizationProviderPlugin::class.java.classLoader, original.javaClass.classLoader)
            for (name in listOf("BuiltinTranslationsKt", "DictionaryConfiguration", "TranslationFormattingKt")) {
                assertEquals(original.javaClass.classLoader, Class.forName(
                    "ai.meteor.kcode.plugin.localization.$name", false, original.javaClass.classLoader,
                ).classLoader)
            }
            assertEquals("Private action", original.translate(AppLanguage.English, UiText.NewChat))
            assertEquals("Private goal message", goalFeedback())
            assertSame(original, slots.snapshot().localization)
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(deployment.copy(config = Json.parseToJsonElement("""{"defaultLanguage":"absent"}""")))
            }
            assertSame(original, catalog)
            assertEquals("Private goal message", goalFeedback())
            val oldCommand = checkNotNull(commands.committedSnapshot.resolve("/goal"))
            runtime.pluginManager.setEnabled("provider.localization.default", false)
            for (id in listOf("provider.ui.compose", "provider.ui.chat", "provider.ui.settings.language", "consumer.commands.goal", "consumer.schedules.application", "consumer.localization.ui")) {
                assertEquals(PluginState.Pending, state(id), id)
            }
            assertNull(slots.snapshot().localization)
            assertNull(original.snapshot())
            assertNull(original.displayText(AppLanguage.English, UiText.NewChat, emptyList()))
            assertFailsWith<IllegalStateException> { original.translate(AppLanguage.English, UiText.NewChat) }
            assertFailsWith<IllegalStateException> { oldCommand.execute(request) }
            assertNull(commands.committedSnapshot.resolve("/goal"))
            runtime.close()
            runtime = KcodePluginRuntime.create(configuration())
            assertEquals(PluginState.Pending, state("provider.ui.compose"))
            runtime.pluginManager.setEnabled("provider.localization.default", true)
            assertNotSame(original, catalog)
            assertEquals("Private action", catalog.translate(AppLanguage.English, UiText.NewChat))
            assertEquals("Private goal message", goalFeedback())
            assertEquals(PluginState.Active, state("provider.ui.compose"))
            val restored = catalog
            runtime.pluginManager.uninstall("provider.localization.default")
            assertFailsWith<IllegalStateException> { restored.translate(AppLanguage.English, UiText.NewChat) }
            assertEquals(PluginState.Pending, state("provider.ui.compose"))
            runtime.pluginManager.setEnabled("provider.localization.default", true)
            assertNotSame(restored, catalog)
            assertEquals("New chat", catalog.translate(AppLanguage.English, UiText.NewChat))
            assertEquals(AppLanguage.Chinese, catalog.snapshot()?.defaultLanguage)
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
