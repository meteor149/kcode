@file:OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)

package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.session.HistoryConversationState

import ai.meteor.kcode.plugin.execution.OwnedChatGenerationRunner
import ai.koog.serialization.kotlinx.toKoogJSONObject
import kotlinx.serialization.json.jsonObject
import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.chat.ConversationSessionFactory
import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.ui.component.KcodeHazeState
import ai.meteor.kcode.ui.component.rememberKcodeHazeState
import ai.meteor.kcode.plugin.ui.api.ApplicationLayoutRequest
import ai.meteor.kcode.plugin.ui.api.SidebarPageRequest
import ai.meteor.kcode.plugin.ui.api.RenderApplicationLayout
import ai.meteor.kcode.plugin.ui.api.RenderApplicationSidebar
import ai.meteor.kcode.ui.state.ConversationState
import androidx.compose.ui.unit.dp
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import androidx.compose.ui.Modifier
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellRequest
import ai.meteor.kcode.plugin.api.ShellResult
import ai.meteor.kcode.plugin.feature.androidShellToolPlugin
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPluginCompositionTest {
    @Test
    fun externalApkLayoutAndSidebarInvokeHostContentAndCallbacks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-layout-ui-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "layout-ui.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var slots: KcodeUiSlots
        val capture = kcodePlugin(PluginDescriptor("test.layout-ui", "test", "test", emptySet()),
            plugin<Unit>(name = "layout-ui-capture", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            profile = KcodePluginProfile(disabled = setOf("provider.ui.layout", "provider.ui.sidebar")),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.layout-ui", version = "external", entryClass = AndroidFixtureLayoutUi::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName, config = "layout-ui",
            ))
            val snapshot = slots.snapshot()
            assertFalse(checkNotNull(snapshot.layout).javaClass.classLoader === AndroidFixtureLayoutUi::class.java.classLoader)
            var selected: Long? = null
            var destination: String? = null
            var compactContent: Boolean? = null
            val sidebar = SidebarPageRequest(null, emptyList(), true, false,
                listOf(HistoryConversationState(42, "host")), 42, {}, { selected = it }, {}, {}, {}, { destination = it })
            val request = ApplicationLayoutRequest(400.dp, false, {}, sidebar, snapshot.sidebar) { _, compact ->
                compactContent = compact
            }
            val recomposer = androidx.compose.runtime.Recomposer(coroutineContext)
            val composition = androidx.compose.runtime.Composition(AndroidNavigationApplier(), recomposer)
            try {
                composition.setContent { RenderApplicationLayout(request, snapshot.layout) }
                assertEquals(42L, selected)
                assertEquals("external", destination)
                assertEquals(true, compactContent)
            } finally {
                composition.dispose()
                recomposer.close()
            }
            runtime.pluginManager.setEnabled("fixture.layout-ui", false)
            assertEquals(null, slots.snapshot().layout)
            assertEquals(null, slots.snapshot().sidebar)
            runtime.pluginManager.setEnabled("fixture.layout-ui", true)
            assertTrue(slots.snapshot().layout != null && slots.snapshot().sidebar != null)
            runtime.pluginManager.uninstall("fixture.layout-ui")
            assertEquals(null, slots.snapshot().layout)
            assertEquals(null, slots.snapshot().sidebar)
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test
    fun externalApkConversationDecorationAndEffectRenderThroughSharedHostContract() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-conversation-ui-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "conversation-ui.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var slots: ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
        val capture = kcodePlugin(PluginDescriptor("test.conversation-ui", "test", "test", emptySet()),
            plugin<Unit>(name = "conversation-ui", inject = dependencies(ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key)) { ctx, _ ->
                slots = ctx.require(ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key)
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            profile = KcodePluginProfile(disabled = setOf("feature.goal")),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.conversation-ui", version = "external", entryClass = AndroidFixtureConversationUi::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName, config = "conversation-ui",
            ))
            val snapshot = slots.snapshot()
            assertEquals(listOf("external", "subagents", "conversation-export"), snapshot.conversationDecorations.map { it.id })
            assertEquals(listOf("external"), snapshot.conversationEffects.map { it.id })
            assertFalse(uiImplementation(snapshot.conversationDecorations.single { it.id == "external" }.presenter).javaClass.classLoader === AndroidFixtureConversationUi::class.java.classLoader)
            val target = HistoryConversationState(1, "initial")
            val page = ai.meteor.kcode.plugin.ui.api.ConversationPageContext(target, true, null, runtime.chatService,
                ai.meteor.kcode.plugin.execution.OwnedChatGenerationRunner(scope = this), ai.meteor.kcode.chat.UnavailableScheduledTasks,
                ai.meteor.kcode.chat.ChatFailureMessages("setup", "connection"), {})
            val recomposer = androidx.compose.runtime.Recomposer(coroutineContext)
            val composition = androidx.compose.runtime.Composition(AndroidNavigationApplier(), recomposer)
            try {
                composition.setContent {
                    val contributions = ai.meteor.kcode.plugin.ui.api.PresentConversationContributions(page, snapshot)
                    check(contributions.single().second.occupiedHeight.value == 17f)
                    contributions.single().second.renderer.Render(androidx.compose.ui.Modifier)
                }
                assertEquals("external effect", target.title)
                assertEquals("external decoration", target.executionFailure)
            } finally { composition.dispose(); recomposer.close() }
            assertEquals("disposed effect", target.title)
            runtime.pluginManager.setEnabled("fixture.conversation-ui", false)
            assertEquals(listOf("subagents", "conversation-export"), slots.snapshot().conversationDecorations.map { it.id })
            assertTrue(slots.snapshot().conversationEffects.isEmpty())
            runtime.pluginManager.setEnabled("fixture.conversation-ui", true)
            assertEquals(listOf("external", "subagents", "conversation-export"), slots.snapshot().conversationDecorations.map { it.id })
            runtime.pluginManager.uninstall("fixture.conversation-ui")
            assertEquals(listOf("subagents", "conversation-export"), slots.snapshot().conversationDecorations.map { it.id })
            assertTrue(slots.snapshot().conversationEffects.isEmpty())
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test
    fun goalPauseUsesNativeLocalizedFeedbackAfterGenerationCleanup() = runBlocking {
        lateinit var commands: ai.meteor.kcode.plugin.api.KcodeConversationCommands
        lateinit var sessions: ai.meteor.kcode.chat.GoalSessionFactory
        val capture = kcodePlugin(PluginDescriptor("test.goal-command", "test", "test", emptySet()),
            plugin<Unit>(name = "goal-command-capture", inject = dependencies(
                ai.meteor.kcode.plugin.api.KcodeConversationCommands.Key,
                ai.meteor.kcode.plugin.api.KcodeGoals.Key,
            )) { ctx, _ ->
                commands = ctx.require(ai.meteor.kcode.plugin.api.KcodeConversationCommands.Key)
                sessions = ctx.require(ai.meteor.kcode.plugin.api.KcodeGoals.Key).sessions
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
        ))
        val target = HistoryConversationState(1, "Native Goal command")
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val running = launch {
            entered.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        target.runningJob = running
        try {
            withTimeout(10_000) {
                checkNotNull(sessions.create(target)).createGoal("Native plugin goal")
                entered.await()
                var feedback: String? = null
                val operations = object : ai.meteor.kcode.chat.ConversationCommandOperations {
                    override fun nextMessageId(target: ConversationState) = 1L
                    override suspend fun appendFeedback(
                        target: ConversationState,
                        user: ai.meteor.kcode.model.ChatMessage,
                        content: String,
                        isError: Boolean,
                    ) {
                        assertTrue(stopped.isCompleted)
                        assertEquals(ai.meteor.kcode.history.ThreadGoalStatus.Paused, target.goal?.status)
                        feedback = content
                    }
                    override suspend fun startResponse(
                        target: ConversationState,
                        user: ai.meteor.kcode.model.ChatMessage,
                        prompt: String,
                        goalSession: ai.meteor.kcode.chat.GoalSession?,
                    ) = error("Pause must not start a model response")
                }
                checkNotNull(commands.committedSnapshot.resolve("/goal pause")).execute(
                    ai.meteor.kcode.chat.ConversationCommandRequest(
                        "/goal pause", ai.meteor.kcode.localization.AppLanguage.English, target,
                        { error("Unexpected conversation creation") }, operations,
                    ),
                )
                val text = checkNotNull(feedback)
                assertTrue("Native plugin goal" in text, text)
                assertTrue("Paused" in text, text)
            }
        } finally {
            running.cancelAndJoin()
            runtime.close()
        }
    }

    @Test
    fun externalApkSkillImplementationUsesTheHostContractAndWithdrawsOldReferences(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-skills-api-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "skills.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var current: ai.meteor.kcode.skill.SkillRuntime
        val capture = kcodePlugin(PluginDescriptor("test.skills-contract", "test", "test", emptySet()),
            plugin<Unit>(name = "skills-contract-capture", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeSkills.Key)) { ctx, _ ->
                ctx.require(ai.meteor.kcode.plugin.api.KcodeSkills.Key).runtime?.let { current = it }
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "provider.skills.platform", version = "external", entryClass = AndroidFixtureSkills::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName, config = "external-skills",
            ))
            val old = current
            val catalog = current.catalog()
            assertEquals("external-skills", catalog.generation)
            assertEquals(ai.meteor.kcode.skill.SkillCatalog::class.java, catalog.javaClass)
            assertFalse(current.javaClass.classLoader === ai.meteor.kcode.skill.SkillRuntime::class.java.classLoader)
            assertEquals("prompt", current.prepareTurn("prompt").catalogInstructions)
            val request = ai.meteor.kcode.skill.SkillReadRequest(
                ai.meteor.kcode.skill.SkillAuthority(ai.meteor.kcode.skill.SkillAuthorityKind.Executor, "external"),
                "package", "resource",
            )
            assertEquals("external-skills", current.read(request).contents)
            runtime.pluginManager.setEnabled("provider.skills.platform", false)
            assertFailsWith<IllegalStateException> { old.catalog() }
            assertFailsWith<IllegalStateException> { old.prepareTurn("late") }
            assertFailsWith<IllegalStateException> { old.read(request) }
            runtime.pluginManager.setEnabled("provider.skills.platform", true)
            assertFalse(old === current)
            assertEquals("external-skills", current.catalog().generation)
            val second = current
            runtime.pluginManager.uninstall("provider.skills.platform")
            assertFailsWith<IllegalStateException> { second.catalog() }
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test
    fun externalApkCommandUsesSharedExecutionOperationsAndWithdrawsGrammar() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-command-api-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "commands.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var commands: ai.meteor.kcode.plugin.api.KcodeConversationCommands
        lateinit var execution: ai.meteor.kcode.chat.ConversationExecution
        val capture = kcodePlugin(PluginDescriptor("test.command-consumer", "test", "test", emptySet()),
            plugin<Unit>(name = "command-consumer", inject = dependencies(
                ai.meteor.kcode.plugin.api.KcodeConversationCommands.Key,
                ai.meteor.kcode.plugin.api.KcodeConversationExecution.Key,
            )) { ctx, _ ->
                commands = ctx.require(ai.meteor.kcode.plugin.api.KcodeConversationCommands.Key)
                execution = ctx.require(ai.meteor.kcode.plugin.api.KcodeConversationExecution.Key).executor
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.command", version = "external", entryClass = AndroidFixtureCommand::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName, config = "command-api",
            ))
            assertTrue(commands.committedSnapshot.allowedDuringGeneration("/external"))
            val target = HistoryConversationState(1, "External command")
            val published = kotlinx.coroutines.CompletableDeferred<Unit>()
            val runner = ai.meteor.kcode.plugin.execution.OwnedChatGenerationRunner(scope = this)
            execution.sendMessage("/external", null, target, { error("Unexpected conversation creation") },
                runtime.chatService, runner, ai.meteor.kcode.chat.UnavailableGoalSessions, this,
                ai.meteor.kcode.chat.ChatFailureMessages("setup", "connection"), ai.meteor.kcode.localization.AppLanguage.English,
                onUserMessageAdded = { _, _ -> published.complete(Unit) }, followBottom = {})
            published.await()
            assertEquals(listOf("/external", "external feedback"), target.messages.map { it.content })
            assertFalse(target.isGenerating)
            runtime.pluginManager.setEnabled("fixture.command", false)
            assertFalse(commands.committedSnapshot.allowedDuringGeneration("/external"))
            runtime.pluginManager.setEnabled("fixture.command", true)
            assertTrue(commands.committedSnapshot.allowedDuringGeneration("/external"))
            runtime.pluginManager.uninstall("fixture.command")
            assertFalse(commands.committedSnapshot.allowedDuringGeneration("/external"))
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test(timeout = 60_000)
    fun externalApkToolHandleIsRevokedAndUnloadWaitsForItsCleanup(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-tool-lifetime-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "tools.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var tools: KcodeTools
        lateinit var factory: ai.meteor.kcode.SubagentCoordinatorFactory
        val capture = kcodePlugin(PluginDescriptor("test.tool-lifetime", "test", "test", emptySet()),
            plugin<Unit>(name = "tool-lifetime-capture", inject = dependencies(KcodeTools.Key, ai.meteor.kcode.plugin.api.KcodeSubagents.Key)) { ctx, _ ->
                tools = ctx.require(KcodeTools.Key)
                factory = ctx.require(ai.meteor.kcode.plugin.api.KcodeSubagents.Key).factory
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val underlying = factory.create(this, "fixture", { "done" }, { })
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val coordinator = object : ai.meteor.kcode.SubagentCoordinator by underlying {
            override suspend fun sendMessage(callerPath: String, target: String, message: String): String {
                when (message) {
                    "entered" -> entered.complete(Unit)
                    "cleaned" -> cleaned.complete(Unit)
                    else -> error("Unexpected fixture signal")
                }
                return "received"
            }
        }
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "test.external-tools", version = "external", entryClass = AndroidFixtureOwnedTools::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName,
            ))
            val turn = ai.meteor.kcode.AgentToolContext("/root", coordinator, null, null, null)
            val old = tools.snapshot(turn).getTool("external_wait")
            val serializer = ai.koog.serialization.kotlinx.KotlinxSerializer()
            fun args(command: String) = old.decodeArgs(
                kotlinx.serialization.json.Json.parseToJsonElement("{\"command\":\"$command\"}").jsonObject.toKoogJSONObject(),
                serializer,
            )
            val ordinaryArgs = args("ordinary")
            assertTrue(checkNotNull(ordinaryArgs).javaClass.classLoader !== ai.meteor.kcode.AgentToolContext::class.java.classLoader)
            assertEquals("external:ordinary\nExit code: 0", old.executeUnsafe(ordinaryArgs))
            val waitingArgs = args("wait")
            val execution = launch { old.executeUnsafe(waitingArgs) }
            withTimeout(10_000) { entered.await() }
            runtime.pluginManager.setEnabled("test.external-tools", false)
            assertTrue(cleaned.isCompleted)
            withTimeout(10_000) { execution.join() }
            assertTrue(execution.isCancelled)
            assertFailsWith<IllegalStateException> { old.executeUnsafe(waitingArgs) }
            assertFailsWith<IllegalStateException> { old.decodeArgs(
                kotlinx.serialization.json.Json.parseToJsonElement("{\"command\":\"late\"}").jsonObject.toKoogJSONObject(), serializer,
            ) }
            runtime.pluginManager.setEnabled("test.external-tools", true)
            assertTrue(tools.snapshot(turn).getTool("external_wait") !== old)
            runtime.pluginManager.uninstall("test.external-tools")
        } finally {
            underlying.shutdown()
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test
    fun externalApkModelCatalogReplacesAndWithdrawsProviderMetadata() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-model-api-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "models.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var llm: ai.meteor.kcode.plugin.api.KcodeLlm
        lateinit var modelSettings: ai.meteor.kcode.settings.ModelSettingsPolicy
        val capture = kcodePlugin(PluginDescriptor("test.model-client", "test", "test", emptySet()),
            plugin<Unit>(name = "model-client-capture", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeLlm.Key, ai.meteor.kcode.plugin.api.KcodeModelSettings.Key)) { ctx, _ ->
                llm = ctx.require(ai.meteor.kcode.plugin.api.KcodeLlm.Key)
                modelSettings = ctx.require(ai.meteor.kcode.plugin.api.KcodeModelSettings.Key).policy
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            val provider = ai.meteor.kcode.model.ModelProvider.DeepSeek
            assertTrue(runtime.modelCatalog().modelsFor(provider).isNotEmpty())
            assertTrue(runtime.modelCatalog().modelsFor(ai.meteor.kcode.model.ModelProvider.Bedrock).isEmpty())
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "provider.llm.koog.DeepSeek", version = "external",
                entryClass = AndroidFixtureModelAdapter::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName, config = "model-api",
            ))
            assertEquals(listOf("external-model"), runtime.modelCatalog().modelsFor(provider).map { it.id })
            val customProvider = ai.meteor.kcode.model.ModelProvider("external.gateway")
            val customSettings = ai.meteor.kcode.test.LegacySettings(provider = customProvider.id,
                modelId = "external-model", modelApiKeys = mapOf(customProvider.id to "fixture"))
            assertEquals("External Gateway", runtime.modelCatalog().provider(customProvider)?.displayName)
            assertEquals(customProvider, checkNotNull(modelSettings.resolve(customSettings, runtime.modelCatalog())).provider)
            val oldCustom = llm.resolve(checkNotNull(modelSettings.resolve(customSettings, runtime.modelCatalog())))
            val configuration = ai.meteor.kcode.model.ModelConfiguration(provider, "external-model", "", temperature = 0.6)
            val oldAdapter = llm.resolve(configuration)
            val client = oldAdapter.create(configuration, ai.koog.http.client.ktor.KtorKoogHttpClient.Factory()).client
            val model = client.models().single()
            assertEquals("external-model", model.id)
            val prompt = ai.koog.prompt.Prompt.build("fixture") { user("fixture") }
            val entered = CompletableDeferred<Unit>()
            val collector = launch {
                client.executeStreaming(prompt, model, emptyList()).collect { frame ->
                    assertEquals("external-apk-client", (frame as ai.koog.prompt.streaming.StreamFrame.TextDelta).text)
                    entered.complete(Unit)
                }
            }
            withTimeout(10_000) { entered.await() }
            runtime.pluginManager.setEnabled("provider.llm.koog.DeepSeek", false)
            withTimeout(10_000) { collector.join() }
            assertTrue(collector.isCancelled)
            assertFailsWith<IllegalStateException> { client.models() }
            assertFailsWith<IllegalStateException> { oldAdapter.create(configuration, ai.koog.http.client.ktor.KtorKoogHttpClient.Factory()) }
            assertTrue(runtime.modelCatalog().modelsFor(provider).isEmpty())
            assertEquals(null, modelSettings.resolve(customSettings, runtime.modelCatalog()))
            assertFailsWith<IllegalStateException> { oldCustom.create(
                ai.meteor.kcode.model.ModelConfiguration(customProvider, "external-model", "fixture", temperature = 0.6),
                ai.koog.http.client.ktor.KtorKoogHttpClient.Factory(),
            ) }
            runtime.pluginManager.setEnabled("provider.llm.koog.DeepSeek", true)
            assertEquals("external-model", runtime.modelCatalog().modelsFor(provider).single().id)
            assertEquals(customProvider, checkNotNull(modelSettings.resolve(customSettings, runtime.modelCatalog())).provider)
            runtime.pluginManager.uninstall("provider.llm.koog.DeepSeek")
            assertTrue(runtime.modelCatalog().modelsFor(provider).isEmpty())
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test
    fun externalApkImageRendererRebindsExportServiceThroughSharedContract() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-export-api-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "export.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var exporter: ai.meteor.kcode.export.ConversationExporter
        val capture = kcodePlugin(
            PluginDescriptor("test.export-consumer", "test", "test", emptySet()),
            plugin<Unit>(name = "export-consumer", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeConversationExport.Key)) { ctx, _ ->
                exporter = ctx.require(ai.meteor.kcode.plugin.api.KcodeConversationExport.Key).exporter
            },
            Unit,
        )
        var shared = false
        val saver = object : ai.meteor.kcode.export.ConversationImageSaver {
            override suspend fun save(image: androidx.compose.ui.graphics.ImageBitmap, fileName: String): ai.meteor.kcode.export.ImageSaveResult = error("Share must not save")
            override suspend fun share(image: androidx.compose.ui.graphics.ImageBitmap, fileName: String): ai.meteor.kcode.export.ImageSaveResult {
                assertEquals(1, image.width)
                assertEquals("kcode-42.png", fileName)
                shared = true
                return ai.meteor.kcode.export.ImageSaveResult.Shared
            }
        }
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            profile = KcodePluginProfile(disabled = setOf("feature.conversation-export")),
            featurePlugins = listOf(
                capture,
                kcodePlugin(PluginDescriptor("test.export.rendering", "test", "test", emptySet()),
                    ai.meteor.kcode.plugin.export.ConversationImageRenderingPlugin, Unit),
                kcodePlugin(PluginDescriptor("test.export.saving", "test", "test", emptySet()),
                    ai.meteor.kcode.plugin.export.ConversationImageSavingProviderPlugin,
                    ai.meteor.kcode.plugin.api.ConversationImageSaverFactory {
                        ai.meteor.kcode.plugin.api.ConversationImageSaverResource(saver) {}
                    }),
                kcodePlugin(PluginDescriptor("test.export.orchestration", "test", "test", emptySet()),
                    ai.meteor.kcode.plugin.export.ConversationExportPlugin, Unit),
            ),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val renderContext = object : ai.meteor.kcode.export.ConversationImageRenderContext {
            override val textMeasurer: androidx.compose.ui.text.TextMeasurer get() = error("Fixture does not measure")
            override val graphicsLayer: androidx.compose.ui.graphics.layer.GraphicsLayer get() = error("Fixture does not draw")
            override val layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr
        }
        val request = ai.meteor.kcode.export.ConversationExportRequest(
            42, "secret title", listOf(ai.meteor.kcode.model.ChatMessage(1, ai.meteor.kcode.model.MessageRole.User, "secret")),
            activeSecret = "secret", truncatedLabel = "truncated",
        )
        try {
            val previous = exporter
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "test.export.rendering", version = "external",
                entryClass = AndroidFixtureImageRenderer::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName, config = "export-api",
            ))
            assertFalse(previous === exporter)
            assertFailsWith<IllegalStateException> { previous.export(request, ai.meteor.kcode.export.ExportAction.Share, renderContext) }
            val result = exporter.export(request, ai.meteor.kcode.export.ExportAction.Share, renderContext)
            assertTrue(shared)
            assertTrue(result.truncated)
            assertEquals(ai.meteor.kcode.export.ImageSaveResult.Shared, result.saved)
            runtime.pluginManager.setEnabled("test.export.rendering", false)
            assertEquals(ai.meteor.kcode.plugin.api.PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "test.export.orchestration" }.state)
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test
    fun externalApkRegistersAndWithdrawsNavigationContribution() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-navigation-api-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "navigation.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var slots: ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
        val capture = kcodePlugin(
            PluginDescriptor("test.navigation-consumer", "test", "test", emptySet()),
            plugin<Unit>(name = "navigation-consumer", inject = dependencies(ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key)) { ctx, _ ->
                slots = ctx.require(ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key)
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.navigation",
                version = "external",
                entryClass = AndroidFixtureNavigation::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName,
                config = "external",
            ))
            assertEquals(listOf("external", "chat"), slots.snapshot().navigation.map { it.id })
            val contribution = slots.snapshot().navigation.first()
            assertEquals(ai.meteor.kcode.ui.component.KcodeIconAsset.Chat, contribution.icon)
            assertFalse(contribution.renderer.javaClass.classLoader === AndroidFixtureNavigation::class.java.classLoader)
            val recomposer = androidx.compose.runtime.Recomposer(coroutineContext)
            val composition = androidx.compose.runtime.Composition(AndroidNavigationApplier(), recomposer)
            var title: String? = null
            try {
                composition.setContent { title = contribution.title() }
                assertEquals("External", title)
            } finally {
                composition.dispose()
                recomposer.close()
            }
            runtime.pluginManager.setEnabled("fixture.navigation", false)
            assertEquals(listOf("chat"), slots.snapshot().navigation.map { it.id })
            runtime.pluginManager.setEnabled("fixture.navigation", true)
            assertEquals("external", slots.snapshot().navigation.first().id)
            runtime.pluginManager.uninstall("fixture.navigation")
            assertEquals(listOf("chat"), slots.snapshot().navigation.map { it.id })
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test
    fun externalApkSessionProviderUsesSharedFactoryContract() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-session-api-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "session.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var factory: ConversationSessionFactory
        val capture = kcodePlugin(
            PluginDescriptor("test.session-consumer", "test", "test", emptySet()),
            plugin<Unit>(name = "session-consumer", inject = dependencies(KcodeSessions.Key)) { ctx, _ ->
                factory = ctx.require(KcodeSessions.Key).factory
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            val original = factory
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "provider.sessions.history",
                version = "external",
                entryClass = AndroidFixtureSessionProvider::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName,
                config = "session-api",
            ))
            assertEquals("external:session-api", assertFailsWith<IllegalStateException> { factory.create(this) }.message)
            assertFailsWith<IllegalStateException> { original.create(this) }
            runtime.pluginManager.uninstall("provider.sessions.history")
            runtime.pluginManager.setEnabled("provider.sessions.history", true)
            val restored = factory.create(this)
            restored.load()
            assertTrue(restored.isLoaded)
            restored.close()
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test
    fun apkInstallationAndDisabledContributionsSurviveRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-plugin-restart-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "restart.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val config = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        )
        var runtime = KcodePluginRuntime.create(config)
        try {
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "feature.goal",
                version = "persisted",
                entryClass = AndroidFixtureOne::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName,
            ))
            runtime.pluginManager.setEnabled("feature.goal", false)
            runtime.pluginManager.setEnabled("feature.web-search", false)
            runtime.close()
            runtime = KcodePluginRuntime.create(config)
            assertEquals("persisted", runtime.pluginManager.installed().single().version)
            assertFalse(runtime.pluginManager.installed().single().enabled)
            assertFalse("android/one" in runtime.diagnostics().toolContributions)
            assertEquals(ai.meteor.kcode.plugin.api.PluginState.Disabled,
                runtime.diagnostics().plugins.first { it.id == "feature.web-search" }.state)
            runtime.pluginManager.setEnabled("feature.goal", true)
            assertTrue("android/one" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.uninstall("feature.goal")
            runtime.pluginManager.setEnabled("feature.goal", true)
            runtime.close()
            runtime = KcodePluginRuntime.create(config)
            assertTrue(runtime.pluginManager.installed().isEmpty())
            assertTrue("core/goal" in runtime.diagnostics().toolContributions)
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            File(directory, "plugin-installations.json").delete()
            directory.delete()
        }
    }

    @Test
    fun externalApkShellProviderRebindsConsumerThroughSharedExecutionApi() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val directory = File(target.filesDir, "kcode-shell-api-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "shell.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var backend: ShellBackend
        val original = kcodePlugin(
            PluginDescriptor("provider.shell.platform", "test", "test", setOf("shell")),
            plugin<Unit>(name = "original-shell") { ctx, _ -> KcodeShell(ctx, ShellBackend { ShellResult("builtin", 0) }) },
            Unit,
        )
        val probe = kcodePlugin(
            PluginDescriptor("test.shell-probe", "test", "test", emptySet()),
            plugin<Unit>(name = "shell-probe", inject = dependencies(KcodeShell.Key)) { ctx, _ -> backend = ctx.require(KcodeShell.Key).executor },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(original, androidShellToolPlugin("test shell"), probe),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, target, loader, inventory, directory)
            },
        ))
        try {
            assertEquals("builtin", backend.run(ShellRequest("hello")).output)
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "provider.shell.platform",
                version = "external",
                entryClass = AndroidFixtureShellProvider::class.java.name,
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                packageName = instrumentation.context.packageName,
                capabilities = setOf("shell"),
            ))
            assertEquals("external:hello", backend.run(ShellRequest("hello")).output)
            assertTrue("consumer.tools.android-shell" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.uninstall("provider.shell.platform")
            assertFalse("consumer.tools.android-shell" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.setEnabled("provider.shell.platform", true)
            assertEquals("builtin", backend.run(ShellRequest("hello")).output)
            assertTrue("consumer.tools.android-shell" in runtime.diagnostics().toolContributions)
        } finally {
            runtime.close()
            artifact.setWritable(true)
            artifact.delete()
            directory.delete()
        }
    }

    @Test
    fun apkPluginReplacesBuiltInAndRollsBackFailedGeneration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-plugin-test-${System.nanoTime()}").apply { mkdirs() }
        val testApk = File(instrumentation.context.applicationInfo.sourceDir)
        fun artifact(name: String) = File(directory, name).also {
            testApk.copyTo(it)
            check(it.setReadOnly())
        }
        val first = artifact("first.apk")
        val second = artifact("second.apk")
        val failed = artifact("failed.apk")
        fun spec(file: File, entryClass: String, version: String) = DynamicPluginSpec(
            id = "feature.goal",
            version = version,
            entryClass = entryClass,
            artifactPath = file.path,
            sha256 = packageFileSha256(file),
            packageName = instrumentation.context.packageName,
            capabilities = setOf("tools"),
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            val manager = runtime.pluginManager
            manager.replace(spec(first, AndroidFixtureOne::class.java.name, "1"))
            assertFalse("core/goal" in runtime.diagnostics().toolContributions)
            assertTrue("android/one" in runtime.diagnostics().toolContributions)
            manager.setEnabled("feature.goal", false)
            assertFalse("android/one" in runtime.diagnostics().toolContributions)
            manager.setEnabled("feature.goal", true)
            assertTrue("android/one" in runtime.diagnostics().toolContributions)
            manager.replace(spec(second, AndroidFixtureTwo::class.java.name, "2"))
            assertFalse("android/one" in runtime.diagnostics().toolContributions)
            assertTrue("android/two" in runtime.diagnostics().toolContributions)
            assertFailsWith<IllegalStateException> {
                manager.replace(spec(failed, AndroidFixtureFailure::class.java.name, "3"))
            }
            assertEquals("2", manager.installed().single().version)
            assertTrue("android/two" in runtime.diagnostics().toolContributions)
            manager.uninstall("feature.goal")
            manager.setEnabled("feature.goal", true)
            assertTrue("core/goal" in runtime.diagnostics().toolContributions)
        } finally {
            runtime.close()
            listOf(first, second, failed).forEach { it.setWritable(true); it.delete() }
            directory.delete()
        }
    }
}

private class AndroidNavigationApplier : androidx.compose.runtime.AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}

class AndroidFixtureImageRenderer : Plugin<String> {
    override val name = "fixture-image-renderer"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        ai.meteor.kcode.plugin.api.KcodeConversationImageRendering(ctx, ai.meteor.kcode.export.ConversationImageRenderer { request, _ ->
            check(config == "export-api")
            check(request.title == "•••• title")
            check(request.messages.single().content == "••••")
            ai.meteor.kcode.export.RenderedConversationImage(androidx.compose.ui.graphics.ImageBitmap(1, 1), true)
        })
    }
}

class AndroidFixtureNavigation : Plugin<String> {
    override val name = "fixture-navigation"
    override val inject = dependencies(ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key)
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        effect.collect(ctx.require(ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key).registerNavigation(
            ai.meteor.kcode.plugin.ui.api.NavigationDestination(
                id = config,
                order = -1,
                icon = ai.meteor.kcode.ui.component.KcodeIconAsset.Chat,
                title = { "External" },
                renderer = ai.meteor.kcode.plugin.ui.api.UiRenderer { },
            ),
        ))
    }
}

class AndroidFixtureOne : Plugin<Unit> {
    override val name = "android-fixture-one"
    override val inject = dependencies(KcodeTools.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("android/one", ToolRegistry { }))
    }
}

class AndroidFixtureTwo : Plugin<Unit> {
    override val name = "android-fixture-two"
    override val inject = dependencies(KcodeTools.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("android/two", ToolRegistry { }))
    }
}

class AndroidFixtureFailure : Plugin<Unit> {
    override val name = "android-fixture-failure"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        error("fixture replacement failure")
    }
}

class AndroidFixtureShellProvider : Plugin<Unit> {
    override val name = "android-fixture-shell-provider"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeShell(ctx, ShellBackend { request -> ShellResult("external:${request.command}", 0) })
    }
}

class AndroidFixtureSessionProvider : Plugin<String> {
    override val name = "android-fixture-session-provider"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        KcodeSessions(ctx, ConversationSessionFactory { error("external:$config") })
    }
}

class AndroidFixtureModelAdapter : Plugin<String> {
    override val name = "android-fixture-model-adapter"
    override val inject = dependencies(ai.meteor.kcode.plugin.api.KcodeLlm.Key)
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(config == "model-api")
        val provider = ai.meteor.kcode.model.ModelProvider.DeepSeek
        effect.collect(ctx.require(ai.meteor.kcode.plugin.api.KcodeLlm.Key).register(
            ai.meteor.kcode.plugin.api.ModelAdapter(
                id = "external-model-adapter", supports = { it.provider == provider },
                create = { _, _ ->
                    ai.meteor.kcode.AgentModelRuntime(
                        AndroidFixtureModelClient(),
                        ai.koog.prompt.llm.LLModel(ai.koog.prompt.llm.LLMProvider.DeepSeek, "external-model", emptyList()),
                    )
                },
                catalog = ai.meteor.kcode.model.ModelProviderSpec(provider,
                    listOf(ai.meteor.kcode.model.ModelOption(provider, "external-model", defaultTemperature = 0.6)), 0),
            ),
        ))
        val customProvider = ai.meteor.kcode.model.ModelProvider("external.gateway")
        effect.collect(ctx.require(ai.meteor.kcode.plugin.api.KcodeLlm.Key).register(
            ai.meteor.kcode.plugin.api.ModelAdapter(
                id = "external-custom-route", supports = { it.provider == customProvider },
                create = { _, _ -> ai.meteor.kcode.AgentModelRuntime(AndroidFixtureModelClient(),
                    ai.koog.prompt.llm.LLModel(ai.koog.prompt.llm.LLMProvider.DeepSeek, "external-model", emptyList())) },
                catalog = ai.meteor.kcode.model.ModelProviderSpec(customProvider,
                    listOf(ai.meteor.kcode.model.ModelOption(customProvider, "external-model", defaultTemperature = 0.6)), 0,
                    displayName = "External Gateway", description = "APK supplied provider"),
            ),
        ))
    }
}

class AndroidFixtureCommand : Plugin<String> {
    override val name = "android-fixture-command"
    override val inject = dependencies(ai.meteor.kcode.plugin.api.KcodeConversationCommands.Key)
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(config == "command-api")
        effect.collect(ctx.require(ai.meteor.kcode.plugin.api.KcodeConversationCommands.Key).register(
            ai.meteor.kcode.chat.ConversationCommandContribution("external-command") { input ->
                if (input != "/external") null else object : ai.meteor.kcode.chat.ConversationCommand {
                    override val allowedDuringGeneration = true
                    override suspend fun execute(request: ai.meteor.kcode.chat.ConversationCommandRequest) {
                        val target = checkNotNull(request.conversation)
                        request.operations.appendFeedback(target,
                            ai.meteor.kcode.model.ChatMessage(request.operations.nextMessageId(target),
                                ai.meteor.kcode.model.MessageRole.User, request.prompt), "external feedback")
                    }
                }
            },
        ))
    }
}

class AndroidFixtureConversationUi : Plugin<String> {
    override val name = "android-fixture-conversation-ui"
    override val inject = dependencies(ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key)
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(config == "conversation-ui")
        val slots = ctx.require(ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key)
        effect.collect(slots.registerConversationEffect(ai.meteor.kcode.plugin.ui.api.ConversationPageEffect(
            "external", 0, ai.meteor.kcode.plugin.ui.api.UiRenderer { request ->
                androidx.compose.runtime.DisposableEffect(Unit) {
                    request.conversation?.title = "external effect"
                    onDispose { request.conversation?.title = "disposed effect" }
                }
            },
        )))
        effect.collect(slots.registerConversationDecoration(ai.meteor.kcode.plugin.ui.api.ConversationDecoration(
            "external", 0, ai.meteor.kcode.plugin.ui.api.ConversationDecorationPresenter { request ->
                listOf(ai.meteor.kcode.plugin.ui.api.ConversationDecorationContent(androidx.compose.ui.unit.Dp(17f),
                    ai.meteor.kcode.plugin.ui.api.UiRenderer {
                        androidx.compose.runtime.SideEffect { request.conversation?.executionFailure = "external decoration" }
                    }))
            },
        )))
    }
}


class AndroidFixtureLayoutUi : Plugin<String> {
    override val name = "android-fixture-layout-ui"
    override val inject = dependencies(KcodeUiSlots.Key)
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(config == "layout-ui")
        val slots = ctx.require(KcodeUiSlots.Key)
        effect.collect(slots.register(ApplicationSlots.Layout, UiRenderer<ApplicationLayoutRequest> { request ->
            RenderApplicationSidebar(request.sidebar.copy(compact = true, width = request.width), request.sidebarRenderer)
            request.content(Modifier, true)
        }))
        effect.collect(slots.register(ApplicationSlots.Sidebar, UiRenderer<SidebarPageRequest> { request ->
            check(request.compact && request.width.value == 400f)
            check(request.conversations.single().id == 42L && request.activeId == 42L)
            request.onSelect(42)
            request.onDestination("external")
        }))
    }
}

class AndroidFixtureSkills : Plugin<String> {
    override val name = "android-fixture-skills"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val owner = ai.meteor.kcode.plugin.api.PluginOperationOwner("APK skills provider")
        effect.collect { owner.close() }
        ai.meteor.kcode.plugin.api.KcodeSkills(ctx, object : ai.meteor.kcode.skill.SkillRuntime {
            override suspend fun catalog(forceReload: Boolean) = owner.run {
                ai.meteor.kcode.skill.SkillCatalog(emptyList(), generation = config)
            }
            override suspend fun prepareTurn(originalUserPrompt: String) = owner.run {
                ai.meteor.kcode.skill.SkillTurnContext(originalUserPrompt, emptyList(), emptyList())
            }
            override suspend fun read(request: ai.meteor.kcode.skill.SkillReadRequest) = owner.run {
                ai.meteor.kcode.skill.SkillReadResult(request.authority, request.packageId, request.resourceId, config, false)
            }
        })
    }
}

/** Runs in the isolated APK loader and asserts teardown waits for its stream finalizer. */
class AndroidFixtureModelClient : ai.koog.prompt.executor.clients.LLMClient() {
    private var executing = false
    private var closed = false

    init {
        check(javaClass.classLoader !== ai.koog.prompt.executor.clients.LLMClient::class.java.classLoader)
    }

    override fun llmProvider() = ai.koog.prompt.llm.LLMProvider.DeepSeek
    override suspend fun models() = listOf(
        ai.koog.prompt.llm.LLModel(llmProvider(), "external-model", emptyList()),
    )
    override suspend fun execute(
        prompt: ai.koog.prompt.Prompt,
        model: ai.koog.prompt.llm.LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    ): Nothing = error("Fixture only streams")
    override suspend fun moderate(prompt: ai.koog.prompt.Prompt, model: ai.koog.prompt.llm.LLModel): Nothing =
        error("Fixture does not moderate")
    override fun executeStreaming(
        prompt: ai.koog.prompt.Prompt,
        model: ai.koog.prompt.llm.LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    ): kotlinx.coroutines.flow.Flow<ai.koog.prompt.streaming.StreamFrame> = kotlinx.coroutines.flow.flow {
        check(!closed)
        executing = true
        try {
            emit(ai.koog.prompt.streaming.StreamFrame.TextDelta("external-apk-client"))
            awaitCancellation()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.delay(75)
                executing = false
            }
        }
    }
    override fun close() {
        check(!executing) { "Client closed before stream cleanup" }
        check(!closed) { "Client closed twice" }
        closed = true
    }
}

class AndroidFixtureOwnedTools : Plugin<Unit> {
    override val name = "android-fixture-owned-tools"
    override val inject = dependencies(KcodeTools.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("external/owned-tools") { turn ->
            ToolRegistry {
                tool(ai.meteor.kcode.plugin.shell.AgentShellTool(
                    executor = object : ai.meteor.kcode.AgentShellExecutor {
                        override suspend fun execute(command: String, workingDirectory: String?): ai.meteor.kcode.AgentShellExecutor.ExecutionResult {
                            check(javaClass.classLoader !== ai.meteor.kcode.AgentShellExecutor::class.java.classLoader)
                            if (command == "wait") {
                                requireNotNull(turn.coordinator).sendMessage(turn.agentPath, turn.agentPath, "entered")
                                try {
                                    awaitCancellation()
                                } finally {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                        kotlinx.coroutines.delay(75)
                                        requireNotNull(turn.coordinator).sendMessage(turn.agentPath, turn.agentPath, "cleaned")
                                    }
                                }
                            }
                            return ai.meteor.kcode.AgentShellExecutor.ExecutionResult("external:$command", 0)
                        }
                    },
                    toolName = "external_wait",
                ))
            }
        })
    }
}
