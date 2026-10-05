package ai.meteor.kcode.plugin

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ChatMessageCodec
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.koog.http.client.KoogHttpClient
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifact.SaveWebArtifactRequest
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.plugin.api.KcodeWebSearch
import ai.meteor.kcode.tools.search.WebSearchBackend
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.tools.search.SearchSettingsPolicy
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.KcodeMessageCodec
import ai.meteor.kcode.plugin.api.KcodeSkillWorkspace
import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeSubagents
import ai.meteor.kcode.plugin.api.KcodeToolApprovals
import ai.meteor.kcode.plugin.api.ConfirmationDialogHost
import ai.meteor.kcode.plugin.api.ConfirmationDialogRequest
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.SubagentCoordinatorFactory
import ai.meteor.kcode.AgentToolContext
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.plugin.api.DesktopPluginHostInputs
import ai.meteor.kcode.plugin.api.KcodeScheduledTaskNotifications
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.KcodeConversationImageRendering
import ai.meteor.kcode.export.ConversationImageRenderer
import ai.meteor.kcode.export.ComposeConversationImageRenderContext
import ai.meteor.kcode.export.ConversationImageRenderRequest
import ai.meteor.kcode.export.ConversationExportMessage
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalLayoutDirection
import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import ai.meteor.kcode.AgentWorkspace
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.desktopPackageHost
import ai.meteor.kcode.plugin.packages.stageBundledPackageCatalog
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import ai.meteor.kcode.ApplicationHostOptions
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledPluginDistributionTest {
    @Test
    fun shippedArchiveLoadsOnProductionClasspathWithoutCodecImplementation() {
        val directory = Files.createTempDirectory("bundled-desktop-process").toFile()
        try {
            val testClasses = File(BundledDesktopProcess::class.java.protectionDomain.codeSource.location.toURI())
            val classpath = testClasses.absolutePath + File.pathSeparator + File(System.getProperty("kcode.production.classpath.file")).readText()
            val java = File(System.getProperty("java.home"), "bin/java.exe").takeIf { it.exists() }
                ?: File(System.getProperty("java.home"), "bin/java")
            val log = File(directory, "process.log")
            fun argument(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val arguments = File(directory, "java.args").also {
                it.writeText(listOf("-cp", argument(classpath), BundledDesktopProcess::class.java.name, argument(directory.absolutePath)).joinToString("\n"))
            }
            val process = ProcessBuilder(java.absolutePath, "@${arguments.absolutePath}")
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Bundled desktop startup timed out")
                assertEquals(0, process.exitValue(), log.readText())
            } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
        } finally { directory.deleteRecursively() }
    }
}

/** Executed with production dependencies; the codec project is deliberately absent. */
object BundledDesktopProcess {
    @OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val directory = File(args.single())
        val classLoader = NativePluginPackagesPlugin::class.java.classLoader
        listOf("ai.meteor.kcode.plugin.messagecodec.MessageCodecProviderPlugin",
            "ai.meteor.kcode.plugin.llm.OpenAIModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.AzureOpenAIModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.AnthropicModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.GoogleModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.DeepSeekModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.OpenRouterModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.BedrockModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.MistralModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.AlibabaModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.OllamaModelAdapterPlugin",
            "ai.meteor.kcode.plugin.llm.GLMModelAdapterPlugin",
            "ai.meteor.kcode.plugin.provider.SearchSettingsProviderPlugin",
            "ai.meteor.kcode.plugin.SettingsToolInteractionPlugin",
            "ai.meteor.kcode.plugin.InteractionServicePlugin",
            "ai.meteor.kcode.plugin.HostModeToolInteractionPlugin",
            "ai.meteor.kcode.plugin.provider.PlatformFileSystemProviderPlugin",
            "ai.meteor.kcode.plugin.provider.SettingsShellProviderPlugin",
            "ai.meteor.kcode.plugin.provider.SettingsUbuntuShellProviderPlugin",
            "ai.meteor.kcode.plugin.provider.CapabilityProvidersKt",
            "ai.meteor.kcode.plugin.artifacts.DesktopNativeArtifactsPlugin",
            "ai.meteor.kcode.plugin.artifacts.FileArtifactRepository",
            "ai.meteor.kcode.plugin.history.DesktopNativeHistoryPlugin",
            "ai.meteor.kcode.plugin.history.HistoryDatabase_Impl",
            "androidx.room3.Room",
            "ai.meteor.kcode.plugin.settingsstorage.DesktopNativeSettingsPlugin",
            "ai.meteor.kcode.plugin.settingsstorage.DataStoreAppSettingsStore",
            "ai.meteor.kcode.plugin.LlmServicePlugin",
            "ai.meteor.kcode.plugin.ToolsServicePlugin", "ai.meteor.kcode.plugin.SystemPromptServicePlugin",
            "ai.meteor.kcode.plugin.DefaultSystemPromptPlugin", "ai.meteor.kcode.plugin.ContinuationServicePlugin",
            "ai.meteor.kcode.plugin.modelsettings.ModelSettingsProviderPlugin",
            "ai.meteor.kcode.plugin.GoalCommandConsumerPlugin", "ai.meteor.kcode.plugin.GoalToolConsumerPlugin",
            "ai.meteor.kcode.plugin.GoalContinuationPlugin", "ai.meteor.kcode.plugin.GoalSessionProviderPlugin",
            "ai.meteor.kcode.plugin.ScheduledTaskToolConsumerPlugin", "ai.meteor.kcode.plugin.ScheduledTaskProviderPlugin",
            "ai.meteor.kcode.plugin.SubagentToolConsumerPlugin", "ai.meteor.kcode.plugin.SubagentContinuationPlugin",
            "ai.meteor.kcode.plugin.InProcessSubagentProviderPlugin", "ai.meteor.kcode.plugin.settingscommands.SettingsCommandsPlugin",
            "ai.meteor.kcode.plugin.UiSlotsServicePlugin",
            "ai.meteor.kcode.plugin.UiContributionsServicePlugin",
            "ai.meteor.kcode.plugin.WebContainersUiPlugin",
            "ai.meteor.kcode.plugin.DefaultConversationTranscriptUiPlugin",
            "ai.meteor.kcode.plugin.DefaultLayoutUiPlugin",
            "ai.meteor.kcode.plugin.DefaultSidebarUiPlugin",
            "ai.meteor.kcode.plugin.DefaultChatUiPlugin",
            "ai.meteor.kcode.plugin.DefaultArtifactsUiPlugin",
            "ai.meteor.kcode.plugin.DefaultStandaloneConversationUiPlugin",
            "ai.meteor.kcode.plugin.DefaultSettingsUiPlugin",
            "ai.meteor.kcode.plugin.DefaultThemeUiPlugin",
            "ai.meteor.kcode.plugin.DefaultChatNavigationPlugin",
            "ai.meteor.kcode.plugin.DefaultArtifactsNavigationPlugin",
            "ai.meteor.kcode.plugin.DefaultUserMessagePresentationPlugin",
            "ai.meteor.kcode.plugin.DefaultAssistantMessagePresentationPlugin",
            "ai.meteor.kcode.plugin.DefaultErrorMessagePresentationPlugin",
            "ai.meteor.kcode.plugin.DefaultToolUsePresentationPlugin",
            "ai.meteor.kcode.plugin.DefaultLanguageSettingsSectionPlugin",
            "ai.meteor.kcode.plugin.DefaultModelSettingsSectionPlugin",
            "ai.meteor.kcode.plugin.DefaultSearchSettingsSectionPlugin",
            "ai.meteor.kcode.plugin.DefaultShellSettingsSectionPlugin",
            "ai.meteor.kcode.plugin.DefaultApplicationUiPlugin",
            "ai.meteor.kcode.plugin.markdown.MarkdownProviderPlugin",
            "ai.meteor.kcode.plugin.markdown.MarkdownFeaturePlugin",
            "ai.meteor.kcode.plugin.markdown.MarkdownUiContributionPlugin",
            "ai.meteor.kcode.plugin.goalui.GoalDecorationPlugin",
            "ai.meteor.kcode.plugin.goalui.GoalRestorationEffectPlugin",
            "ai.meteor.kcode.plugin.localization.LocalizationProviderPlugin",
            "ai.meteor.kcode.plugin.localization.LocalizationFeaturePlugin",
            "ai.meteor.kcode.plugin.localization.LocalizationUiContributionPlugin",
            "ai.meteor.kcode.plugin.SessionHistoryProviderPlugin",
            "ai.meteor.kcode.plugin.ConversationCommandsServicePlugin",
            "ai.meteor.kcode.plugin.GenerationProviderPlugin",
            "ai.meteor.kcode.plugin.ConversationExecutionProviderPlugin",
            "ai.meteor.kcode.plugin.KoogAgentLoopPlugin",
            "ai.meteor.kcode.plugin.nativefilesystem.DesktopNativeFileSystemPlugin",
            "ai.meteor.kcode.plugin.skills.WorkspaceSkillsPlugin",
            "ai.meteor.kcode.plugin.notifications.DesktopNativeNotificationsPlugin",
            "ai.meteor.kcode.plugin.ScheduleDispatchPlugin",
            "ai.meteor.kcode.plugin.ScheduleFeaturePlugin",
            "ai.meteor.kcode.plugin.subagentui.SubagentDecorationPlugin",
            "ai.meteor.kcode.plugin.ArtifactFeaturePlugin",
            "ai.meteor.kcode.plugin.export.ConversationImageRenderingPlugin",
            "ai.meteor.kcode.plugin.export.ConversationExportPlugin",
            "ai.meteor.kcode.plugin.export.ConversationExportFeaturePlugin",
            "ai.meteor.kcode.plugin.export.DesktopNativeImageSavingPlugin",
            "ai.meteor.kcode.plugin.feature.FilesystemToolConsumerPlugin",
            "ai.meteor.kcode.plugin.feature.SkillToolConsumerPlugin",
            "ai.meteor.kcode.plugin.feature.ArtifactToolConsumerPlugin",
            "ai.meteor.kcode.plugin.feature.WebSearchToolConsumerPlugin",
            "ai.meteor.kcode.plugin.feature.WebContainerToolConsumerPlugin",
            "ai.meteor.kcode.plugin.overlay.ConversationOverlaysServicePlugin",
            "ai.meteor.kcode.plugin.overlay.NativeConversationOverlaysServicePlugin",
            "ai.meteor.kcode.plugin.overlay.ConversationOverlayProviderPlugin",
            "ai.meteor.kcode.plugin.nativeexecution.DesktopNativeShellPlugin",
            "ai.meteor.kcode.plugin.nativeexecution.DesktopShellCommandExecutor",
            "ai.meteor.kcode.plugin.WebContainersProviderPlugin",
            "ai.meteor.kcode.plugin.webcontainer.native.DesktopNativeWebContainerPlugin",
            "ai.meteor.kcode.plugin.webcontainer.native.DesktopWebContainerFeaturePlugin",
            "ai.meteor.kcode.plugin.WebContainerFeaturePlugin",
            "ai.meteor.kcode.plugin.provider.HttpWebSearchProviderPlugin",
            "ai.meteor.kcode.plugin.searchhttp.HttpWebSearchBackend",
            "ai.meteor.kcode.plugin.feature.DesktopShellToolConsumerPlugin",
            "ai.koog.prompt.executor.clients.openai.OpenAILLMClient",
            "ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient",
            "ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient",
            "aws.smithy.kotlin.runtime.auth.awscredentials.Credentials",
            "ai.meteor.kcode.plugin.LocalizedNativeToolApprovalPlugin").forEach { entry ->
            check(runCatching { Class.forName(entry, false, classLoader) }.exceptionOrNull() is ClassNotFoundException)
        }
        lateinit var interaction: InteractionPolicy
        lateinit var artifacts: ArtifactRepository
        lateinit var history: ConversationHistoryRepository
        lateinit var settings: AppSettingsStore
        lateinit var mutationSettings: AppSettingsStore
        lateinit var searchBackend: WebSearchBackend
        lateinit var searchPolicy: SearchSettingsPolicy
        lateinit var llm: KcodeLlm
        lateinit var codec: ChatMessageCodec
        lateinit var workspace: AgentWorkspace
        lateinit var skills: SkillRuntime
        lateinit var notifications: ScheduledTaskPlatformHost
        lateinit var uiSlots: KcodeUiSlots
        lateinit var imageRenderer: ConversationImageRenderer
        lateinit var tools: KcodeTools
        lateinit var coordinatorFactory: SubagentCoordinatorFactory
        lateinit var approver: ToolCallApprover
        var confirmation: ConfirmationDialogRequest? = null
        val capture = kcodePlugin(PluginDescriptor("test.bundled-codec", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-bundled-codec", inject = dependencies(KcodeArtifacts.Key, KcodeHistory.Key, KcodeInteraction.Key, KcodeSettings.Key, KcodeSearchSettings.Key, KcodeWebSearch.Key, KcodeLlm.Key, KcodeMessageCodec.Key, KcodeSkillWorkspace.Key, KcodeSkills.Key, KcodeScheduledTaskNotifications.Key, KcodeUiSlots.Key, KcodeConversationImageRendering.Key, KcodeTools.Key, KcodeSubagents.Key, KcodeToolApprovals.Key)) { ctx, _ ->
                interaction = ctx.require(KcodeInteraction.Key).policy
                artifacts = ctx.require(KcodeArtifacts.Key).repository
                history = ctx.require(KcodeHistory.Key).repository
                settings = ctx.require(KcodeSettings.Key).store
                mutationSettings = ctx.require(KcodeSettings.Key).mutationStore
                searchBackend = ctx.require(KcodeWebSearch.Key).backend
                searchPolicy = ctx.require(KcodeSearchSettings.Key).policy
                llm = ctx.require(KcodeLlm.Key)
                codec = ctx.require(KcodeMessageCodec.Key).codec
                workspace = ctx.require(KcodeSkillWorkspace.Key).workspace
                skills = checkNotNull(ctx.require(KcodeSkills.Key).runtime)
                notifications = ctx.require(KcodeScheduledTaskNotifications.Key).host
                uiSlots = ctx.require(KcodeUiSlots.Key)
                imageRenderer = ctx.require(KcodeConversationImageRendering.Key).renderer
                tools = ctx.require(KcodeTools.Key)
                coordinatorFactory = ctx.require(KcodeSubagents.Key).factory
                approver = ctx.require(KcodeToolApprovals.Key).approver
            }, Unit)
        val configuration = KcodePluginRuntimeConfig(
            hostInputs = DesktopPluginHostInputs({ null }, ConfirmationDialogHost { request -> confirmation = request; false }),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { false }),
            bundledPackages = stageBundledPackageCatalog(directory, host = desktopPackageHost()) { name ->
                check(!name.startsWith("kcode/plugins/policy.notifications.permission.android-"))
                check(!name.startsWith("kcode/plugins/provider.generation.foreground.android-"))
                check(!name.startsWith("kcode/plugins/consumer.tools.android-shell-"))
                check(!name.startsWith("kcode/plugins/consumer.tools.ubuntu-shell-"))
                checkNotNull(classLoader.getResourceAsStream(name))
            }
                .map {
                    val release = if (it.id == "provider.settings.platform") it.release.copy(configuration = StoredPluginConfiguration.encode(File(directory, "settings.preferences_pb").absolutePath)) else if (it.id in setOf("provider.shell.platform", "provider.fs.platform", "provider.artifacts.platform", "feature.web-container")) it.release.copy(
                        configuration = StoredPluginConfiguration.encode(File(directory, "workspace").absolutePath),
                    ) else if (it.id == "provider.history.platform") it.release.copy(configuration = StoredPluginConfiguration.encode(File(directory, "history.db").absolutePath)) else it.release
                    BundledPluginPackage(it.id, release)
                },
            featurePlugins = listOf(capture, kcodePlugin(
                PluginDescriptor("provider.plugin-packages.platform", "test", "test", emptySet()),
                NativePluginPackagesPlugin(directory, desktopPackageHost()), Unit,
            )),
            pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        )
        var desktopEntries = 0
        configuration.bundledPackages.forEach { bundled ->
            java.util.zip.ZipFile(bundled.release.archivePath).use { archive ->
                val manifest = archive.getInputStream(archive.getEntry("plugin.json")).use {
                    org.cordis.packages.PluginPackageCodec.decode(it.readBytes())
                }
                manifest.variants.filter { it.runtime.id == "jvm" }.forEach { variant ->
                    desktopEntries += 1
                    check(runCatching { Class.forName(variant.runtime.entryPoint, false, classLoader) }
                        .exceptionOrNull() is ClassNotFoundException) {
                        "Packaged implementation is present in the host: ${variant.runtime.entryPoint}"
                    }
                }
            }
        }
        check(desktopEntries == 61) { "Unexpected desktop variant count: $desktopEntries" }
        FilePluginCompositionStore(directory).save(ai.meteor.kcode.plugin.api.PluginCompositionSnapshot(
            builtinsEnabled = mapOf("provider.llm.koog" to true, "provider.llm.koog.DeepSeek" to false),
        ))
        val runtime = KcodePluginRuntime.create(configuration)
        suspend fun publishedTool(name: String): ai.koog.agents.core.tools.ToolBase<*, *> {
            val coordinator = coordinatorFactory.create(this, "package tools", runAgent = { "unused" }, onEvent = {})
            try {
                return tools.snapshot(AgentToolContext("/root", coordinator, null, null, null)).getTool(name)
            } finally { coordinator.shutdown() }
        }
        suspend fun readThroughTool(): String {
            val reader = publishedTool("__read_file__")
            val result = reader.executeUnsafe(ReadFileTool.Args("/workspace/package-proof.txt"))
            return reader.encodeResultToStringUnsafe(result, ai.koog.serialization.kotlinx.KotlinxSerializer())
        }
        try {
            val packages = runtime.pluginManager.installed()
            check(packages.size == 61)
            check(packages.none { it.id == "policy.notifications.permission.android" })
            check(packages.none { it.id == "provider.generation.foreground.android" })
            check(packages.none { it.id in setOf("consumer.tools.android-shell", "consumer.tools.ubuntu-shell") })
            check(packages.all { it.packageInstallation?.variantId == "desktop" })
            check(!runtime.pluginManager.installed().single { it.id == "provider.llm.koog.DeepSeek" }.enabled)
            runtime.pluginManager.setEnabled("provider.llm.koog.DeepSeek", true)
            val migrated = FilePluginCompositionStore(directory).load()
            check("provider.llm.koog" !in migrated.builtinsEnabled)
            check(migrated.builtinsEnabled.keys.none { it.startsWith("provider.llm.koog.") })
            verifyBundledSettingsMutations(runtime, { settings }) { mutationSettings }
            verifyBundledSearchPolicy(runtime, { settings }) { searchPolicy }
            verifyBundledHttpSearch(runtime) { searchBackend }
            verifyBundledInteraction(runtime, { interaction }, { settings })
            verifyBundledSettingsStorage(runtime) { settings }
            verifyBundledHistoryStorage(runtime) { history }
            verifyBundledArtifactStorage(runtime, File(directory, "workspace")) { artifacts }
            verifyBundledModels(runtime) { llm }
            val approvalRequest = ToolApprovalRequest("package-check", "{}", "request-body")
            check(!approver.approve(approvalRequest))
            check(checkNotNull(confirmation).title.contains("package-check"))
            check(checkNotNull(confirmation).message.contains("request-body"))
            val oldApprover = approver
            runtime.pluginManager.setEnabled("provider.tool-approvals.native", false)
            check(runCatching { oldApprover.approve(approvalRequest) }.isFailure)
            runtime.pluginManager.setEnabled("provider.tool-approvals.native", true)
            check(approver !== oldApprover)
            check(!approver.approve(approvalRequest))
            val installed = packages.single { it.id == "provider.message-codec.envelope" }
            check(installed.packageInstallation?.variantId == "desktop")
            check(classLoader !== codec.javaClass.classLoader)
            val message = ChatMessage(1, MessageRole.Assistant, "bundled independent JAR")
            check(message.content == codec.decode(codec.encode(message)).text)
            workspace.writeText("/workspace/package-proof.txt", "private filesystem")
            check(workspace.readText("/workspace/package-proof.txt") == "private filesystem")
            check(readThroughTool().contains("private filesystem"))
            val oldReader = publishedTool("__read_file__")
            val shell = publishedTool("execute_shell_command")
            val shellJson = Json.parseToJsonElement("""{"command":"echo package-shell"}""").jsonObject.toKoogJSONObject()
            val shellArgs = checkNotNull(shell.decodeArgs(
                shellJson,
                KotlinxSerializer(),
            ))
            check(shellArgs.javaClass.classLoader !== classLoader)
            check((shell.executeUnsafe(shellArgs) as String).contains("package-shell"))
            runtime.pluginManager.setEnabled("consumer.tools.shell", false)
            check(runCatching { shell.executeUnsafe(shellArgs) }.isFailure)
            runtime.pluginManager.setEnabled("consumer.tools.shell", true)
            val restoredShell = publishedTool("execute_shell_command")
            val restoredArgs = restoredShell.decodeArgs(shellJson, KotlinxSerializer())
            check((restoredShell.executeUnsafe(restoredArgs) as String).contains("package-shell"))
            check(!notifications.isAppInForeground())
            val oldNotifications = notifications
            suspend fun dispatchState() = runtime.diagnostics().plugins.single { it.id == "feature.schedule" }.state
            check(dispatchState() == ai.meteor.kcode.plugin.api.PluginState.Active)
            check(uiSlots.snapshot().effects.none { it.id == "schedule.dispatch" })
            runtime.pluginManager.setEnabled("provider.notifications.platform", false)
            check(runCatching { oldNotifications.isAppInForeground() }.isFailure)
            check(dispatchState() == ai.meteor.kcode.plugin.api.PluginState.Active)
            runtime.pluginManager.setEnabled("provider.notifications.platform", true)
            check(notifications !== oldNotifications)
            check(!notifications.isAppInForeground())
            runtime.pluginManager.setEnabled("feature.schedule", false)
            check(dispatchState() == ai.meteor.kcode.plugin.api.PluginState.Disabled)
            check("core/schedule" !in runtime.diagnostics().toolContributions)
            runtime.pluginManager.setEnabled("feature.schedule", true)
            check(dispatchState() == ai.meteor.kcode.plugin.api.PluginState.Active)
            check("core/schedule" in runtime.diagnostics().toolContributions)
            val oldWorkspace = workspace
            check(skills.catalog().entries.any { it.name == "kcode-web-app-builder" })
            check("consumer.tools.filesystem" in runtime.diagnostics().toolContributions)
            check("consumer.tools.skill" in runtime.diagnostics().toolContributions)
            val oldSkills = skills
            runtime.pluginManager.setEnabled("provider.fs.platform", false)
            check(runCatching { oldReader.executeUnsafe(ReadFileTool.Args("/workspace/package-proof.txt")) }.isFailure)
            check("consumer.tools.filesystem" !in runtime.diagnostics().toolContributions)
            check("consumer.tools.skill" !in runtime.diagnostics().toolContributions)
            check(runCatching { oldWorkspace.readText("/workspace/package-proof.txt") }.isFailure)
            check(runCatching { oldSkills.catalog() }.isFailure)
            check(runtime.diagnostics().plugins.single { it.id == "provider.skills.platform" }.state == ai.meteor.kcode.plugin.api.PluginState.Pending)
            runtime.pluginManager.setEnabled("provider.fs.platform", true)
            check("consumer.tools.filesystem" in runtime.diagnostics().toolContributions)
            check("consumer.tools.skill" in runtime.diagnostics().toolContributions)
            check(workspace !== oldWorkspace)
            check(skills !== oldSkills)
            check(skills.catalog().entries.any { it.name == "kcode-web-app-builder" })
            check(workspace.readText("/workspace/package-proof.txt") == "private filesystem")
            check(readThroughTool().contains("private filesystem"))
            check("kcode/default" in runtime.diagnostics().promptSections)
            suspend fun inactivePackages() = runtime.diagnostics().plugins.filter {
                it.id in packages.map { spec -> spec.id } && it.state != ai.meteor.kcode.plugin.api.PluginState.Active
            }
            withTimeoutOrNull(10_000) {
                while (inactivePackages().isNotEmpty()) delay(10)
            }
            check(inactivePackages().isEmpty()) { "Bundled packages did not recover: ${inactivePackages()}" }
            runtime.pluginManager.setEnabled("core.system-prompt", false)
            check(runtime.diagnostics().promptSections.isEmpty())
            check(runtime.diagnostics().plugins.single { it.id == "provider.prompt.default" }.state == ai.meteor.kcode.plugin.api.PluginState.Pending)
            runtime.pluginManager.setEnabled("core.system-prompt", true)
            check("kcode/default" in runtime.diagnostics().promptSections)
            runtime.pluginManager.setEnabled("core.tools", false)
            check(runtime.diagnostics().toolContributions.isEmpty())
            check(runtime.diagnostics().plugins.single { it.id == "feature.goal" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
            runtime.pluginManager.setEnabled("core.tools", true)
            check("core/goal" in runtime.diagnostics().toolContributions)
            check("core/subagent" in runtime.diagnostics().toolContributions)
            check("core/schedule" in runtime.diagnostics().toolContributions)
            listOf("consumer.tools.filesystem", "consumer.tools.skill", "consumer.tools.artifact", "consumer.tools.web-search", "consumer.tools.shell").forEach { id ->
                check(id in runtime.diagnostics().toolContributions)
                runtime.pluginManager.setEnabled(when (id) {
                    "consumer.tools.web-search" -> "feature.web-search"
                    "consumer.tools.artifact" -> "feature.artifacts"
                    else -> id
                }, false)
                check(id !in runtime.diagnostics().toolContributions)
                runtime.pluginManager.setEnabled(when (id) {
                    "consumer.tools.web-search" -> "feature.web-search"
                    "consumer.tools.artifact" -> "feature.artifacts"
                    else -> id
                }, true)
                check(runtime.diagnostics().toolContributions.count { it == id } == 1)
            }
            withContext(Dispatchers.Main.immediate) {
                lateinit var renderContext: ComposeConversationImageRenderContext
                val scene = ImageComposeScene(width = 800, height = 600, coroutineContext = coroutineContext) {
                    val textMeasurer = rememberTextMeasurer()
                    val graphicsLayer = rememberGraphicsLayer()
                    val layoutDirection = LocalLayoutDirection.current
                    SideEffect { renderContext = ComposeConversationImageRenderContext(textMeasurer, graphicsLayer, layoutDirection) }
                    runtime.Render(ApplicationHostOptions())
                }
                try {
                    repeat(12) { frame -> scene.render(frame * 50000000L).close(); delay(50) }
                    check(semanticsLabels(scene).size >= 3) { "Independent UI did not render its controls" }
                    val request = ConversationImageRenderRequest("", listOf(
                        ConversationExportMessage(false, "**Independent** rendering", false),
                    ), "Truncated")
                    val rendered = imageRenderer.render(request, renderContext)
                    check(rendered.image.width == 1080 && rendered.image.height > 200)
                    val pixels = rendered.image.toPixelMap()
                    check((0 until pixels.width step 4).any { x ->
                        (0 until pixels.height step 4).any { y ->
                            val color = pixels[x, y]
                            color.alpha > 0.9f && color.red < 0.8f && color.green < 0.8f && color.blue < 0.8f
                        }
                    }) { "Private export renderer produced no visible message pixels" }
                    val oldRenderer = imageRenderer
                    runtime.pluginManager.setEnabled("feature.conversation-export", false)
                    check(runtime.diagnostics().plugins.single { it.id == "feature.conversation-export" }.state == ai.meteor.kcode.plugin.api.PluginState.Disabled)
                    check(runCatching { oldRenderer.render(request, renderContext) }.isFailure)
                    runtime.pluginManager.setEnabled("feature.conversation-export", true)
                    check(imageRenderer !== oldRenderer)
                    check(imageRenderer.render(request, renderContext).image.width == rendered.image.width)
                } finally { scene.close() }
            }
        } finally { runtime.close() }
    }

    private suspend fun verifyBundledSettingsMutations(
        runtime: KcodePluginRuntime,
        storage: () -> AppSettingsStore,
        mutations: () -> AppSettingsStore,
    ) {
        val original = storage().load()
        val cases = listOf(
            Triple("provider.model-settings.catalog", "feature.model-settings", """{"temperature":3}"""),
            Triple("feature.web-search", "feature.web-search", """{"provider":"unavailable.route"}"""),
            Triple("feature.localization", "feature.localization", """{"language":"unavailable.language"}"""),
            Triple("provider.interaction.platform", "feature.interaction-settings", """{"mode":"unavailable.mode"}"""),
        )
        for ((_, namespace, json) in cases) {
            val candidate = original.copy(namespaces = original.namespaces + (namespace to Json.parseToJsonElement(json).jsonObject))
            check(runCatching { mutations().save(candidate) }.exceptionOrNull() is IllegalArgumentException)
            check(storage().load() == original) { "Invalid namespace mutation changed durable settings: $namespace" }
        }
        // An invalid second feature must not publish the valid first feature either.
        val combined = original.copy(namespaces = original.namespaces + mapOf(
            "feature.web-search" to Json.parseToJsonElement("""{"provider":"exa"}""").jsonObject,
            "feature.interaction-settings" to Json.parseToJsonElement("""{"mode":"invalid"}""").jsonObject,
        ))
        check(runCatching { mutations().save(combined) }.isFailure)
        check(storage().load() == original)
        val persisted = original.copy(namespaces = original.namespaces + mapOf(
            "disabled.fixture" to Json.parseToJsonElement("""{"apiKeys":{"unknown":"saved"},"future":null}""").jsonObject,
            "feature.web-search" to Json.parseToJsonElement("""{"provider":"exa","future":null}""").jsonObject,
        ))
        storage().save(persisted)
        val edited = persisted.copy(namespaces = persisted.namespaces +
            ("feature.web-search" to Json.parseToJsonElement("""{"provider":"google","future":null}""").jsonObject))
        runtime.pluginManager.setEnabled("feature.web-search", false)
        check(runCatching { mutations().save(edited) }.exceptionOrNull() is IllegalArgumentException)
        check(storage().load() == persisted)
        // The root's settings store stays usable while this feature is absent.
        mutations().save(persisted.copy(namespaces = persisted.namespaces +
            ("feature.interaction-settings" to Json.parseToJsonElement("""{"mode":"ask"}""").jsonObject)))
        check(storage().load().namespaces["disabled.fixture"] == persisted.namespaces["disabled.fixture"])
        runtime.pluginManager.setEnabled("feature.web-search", true)
        mutations().save(edited)
        check(storage().load() == edited)
        storage().save(original)
    }

    private fun semanticsLabels(imageScene: ImageComposeScene): List<String> {
        fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target)
        }
        val root = field(field(imageScene, "scene"), "mainOwner")
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        val labels = mutableListOf<String>()
        fun collect(node: androidx.compose.ui.semantics.SemanticsNode) {
            labels += node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            labels += node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            node.children.forEach(::collect)
        }
        collect(owner.unmergedRootSemanticsNode)
        return labels
    }
}

private suspend fun verifyBundledModels(runtime: KcodePluginRuntime, currentLlm: () -> KcodeLlm) {
    val allocationProbe = object : KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json,
        ): KoogHttpClient = error("offline-model-allocation")
    }
    val modelCatalog = currentLlm().catalog()
    for (provider in modelCatalog.providers) {
        val configuration = ModelConfiguration(provider.provider, provider.models.first().id, "fixture", 0.6,
            endpoint = "https://fixture.invalid", region = "us-west-2", deployment = "fixture")
        val adapter = currentLlm().resolve(configuration)
        if (provider.provider == ModelProvider.Bedrock) {
            adapter.create(configuration, allocationProbe).client.close()
        } else {
            val failure = runCatching { adapter.create(configuration, allocationProbe) }.exceptionOrNull()
            check(failure is IllegalStateException && failure.message == "offline-model-allocation") { "$failure" }
        }
        val id = "provider.llm.koog.${provider.provider.name}"
        runtime.pluginManager.setEnabled(id, false)
        check(!adapter.supports(configuration))
        check(currentLlm().catalog().providers.none { it.provider == provider.provider })
        runtime.pluginManager.setEnabled(id, true)
        check(currentLlm().catalog().providers.single { it.provider == provider.provider } == provider)
        check(currentLlm().resolve(configuration).supports(configuration))
    }
    val modelConfiguration = ModelConfiguration(ModelProvider.GLM, "glm-4", "fixture", temperature = 0.6)
    val originalLlm = currentLlm()
    val originalAdapter = currentLlm().resolve(modelConfiguration)
    check(currentLlm().adapterIds().size == 11)
    check(originalAdapter.supports(modelConfiguration))
    runtime.pluginManager.setEnabled("core.llm", false)
    check(!originalAdapter.supports(modelConfiguration))
    check(originalLlm.adapterIds().isEmpty())
    check(runCatching { originalLlm.resolve(modelConfiguration) }.isFailure)
    check(runtime.diagnostics().plugins.filter { it.id.startsWith("provider.llm.koog.") }.all {
        it.state == ai.meteor.kcode.plugin.api.PluginState.Pending
    })
    runtime.pluginManager.setEnabled("core.llm", true)
    check(currentLlm() !== originalLlm)
    check(currentLlm().adapterIds().size == 11)
    check(currentLlm().resolve(modelConfiguration).supports(modelConfiguration))
    check(runtime.diagnostics().plugins.filter { it.id.startsWith("provider.llm.koog.") }.all {
        it.state == ai.meteor.kcode.plugin.api.PluginState.Active
    })
}

private suspend fun verifyBundledSettingsStorage(runtime: KcodePluginRuntime, currentStore: () -> AppSettingsStore) {
    val previous = currentStore().load()
    val saved = previous.copy(
        namespaces = previous.namespaces + ("disabled.fixture" to (Json.parseToJsonElement(
            """{"credentials":{"fixture.custom":"fixture-key","fixture.search":"fixture-search-key"},"future":[null,{"key.with.dots":""}]}""",
        ) as kotlinx.serialization.json.JsonObject)),
        legacyValues = kotlinx.serialization.json.JsonObject(previous.legacyValues +
            ("fixture.unknown" to Json.parseToJsonElement("""[null,{"retained":false}]"""))),
    )
    try {
        currentStore().save(saved)
        check(currentStore().load() == saved)
        val oldStore = currentStore()
        runtime.pluginManager.setEnabled("provider.settings.platform", false)
        check(runCatching { oldStore.load() }.isFailure)
        check(runCatching { oldStore.protection }.isFailure)
        check(runCatching { oldStore.save(previous) }.isFailure)
        check(runtime.diagnostics().plugins.single { it.id == "provider.interaction.platform" }.state == ai.meteor.kcode.plugin.api.PluginState.Pending)
        runtime.pluginManager.setEnabled("provider.settings.platform", true)
        check(currentStore() !== oldStore)
        check(currentStore().load() == saved)
        check(runtime.diagnostics().plugins.single { it.id == "provider.interaction.platform" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
    } finally {
        currentStore().save(previous)
    }
}

private suspend fun verifyBundledInteraction(
    runtime: KcodePluginRuntime,
    currentPolicy: () -> InteractionPolicy,
    currentSettings: () -> AppSettingsStore,
) {
    val stored = currentSettings().load()
    val restoredMode = requireNotNull(currentPolicy().settings).resolve(stored)
    try {
        for ((code, expected) in listOf("deny" to ToolPermissionMode.Deny, "bypass" to ToolPermissionMode.Bypass, "unknown-mode" to ToolPermissionMode.Ask)) {
            val document = kotlinx.serialization.json.JsonObject(stored.namespaces["feature.interaction-settings"].orEmpty() +
                ("mode" to kotlinx.serialization.json.JsonPrimitive(code)))
            currentSettings().save(stored.copy(namespaces = stored.namespaces + ("feature.interaction-settings" to document)))
            check(currentPolicy().permissionModeProvider() == expected)
        }
    } finally {
        currentSettings().save(stored)
    }
    val oldPolicy = currentPolicy()
    val request = ToolApprovalRequest("interaction-package-check", "{}", "request-body")
    check(!oldPolicy.approver.approve(request))
    runtime.pluginManager.setEnabled("provider.interaction.platform", false)
    check(runCatching { oldPolicy.permissionModeProvider() }.isFailure)
    check(runCatching { oldPolicy.approver.approve(request) }.isFailure)
    check(runtime.diagnostics().plugins.single { it.id == "provider.agent-loop.koog" }.state == ai.meteor.kcode.plugin.api.PluginState.Pending)
    runtime.pluginManager.setEnabled("provider.interaction.platform", true)
    check(currentPolicy() !== oldPolicy)
    check(currentPolicy().permissionModeProvider() == restoredMode)
    check(!currentPolicy().approver.approve(request))
    check(runtime.diagnostics().plugins.single { it.id == "provider.agent-loop.koog" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
}

private suspend fun verifyBundledSearchPolicy(
    runtime: KcodePluginRuntime,
    currentStore: () -> AppSettingsStore,
    currentPolicy: () -> SearchSettingsPolicy,
) {
    val storedSearch = StoredAppSettings(legacyValues = Json.parseToJsonElement(
        """{"webSearchProvider":"exa","exaSearchApiKey":"legacy","webSearchApiKey":"bright","searchApiKeys":{"custom.route":"custom"}}""",
    ) as kotlinx.serialization.json.JsonObject)
    val searchConfiguration = currentPolicy().resolve(storedSearch)
    check(searchConfiguration.apiKeys["exa"] == "legacy")
    check(searchConfiguration.apiKeys["custom.route"] == "custom")
    val previous = currentStore().load()
    val migrated = currentPolicy().update(previous.copy(
        legacyValues = kotlinx.serialization.json.JsonObject(previous.legacyValues + storedSearch.legacyValues),
        namespaces = previous.namespaces - "feature.web-search",
    ), searchConfiguration.copy(apiKeys = searchConfiguration.apiKeys + ("exa" to "")))
    check(migrated.namespaces.containsKey("feature.web-search"))
    check((migrated.legacyValues["exaSearchApiKey"] as kotlinx.serialization.json.JsonPrimitive).content == "legacy")
    currentStore().save(migrated)
    try {
        val oldSearchPolicy = currentPolicy()
        runtime.pluginManager.setEnabled("feature.web-search", false)
        check(oldSearchPolicy.providers() == null)
        check(runCatching { oldSearchPolicy.resolve(storedSearch) }.isFailure)
        check(runCatching { oldSearchPolicy.update(storedSearch, searchConfiguration) }.isFailure)
        check(runtime.diagnostics().plugins.single { it.id == "consumer.settings.commands" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
        runtime.pluginManager.setEnabled("feature.web-search", true)
        check(currentPolicy() !== oldSearchPolicy)
        check(currentPolicy().resolve(storedSearch) == searchConfiguration)
        check(runtime.diagnostics().plugins.single { it.id == "consumer.settings.commands" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
        val recovered = currentPolicy().resolve(currentStore().load())
        check(recovered.apiKeys["exa"] == "")
        check(recovered.apiKeys["custom.route"] == "custom")
        check(currentStore().load().namespaces == migrated.namespaces)
    } finally {
        currentStore().save(previous)
    }
}

private suspend fun verifyBundledHistoryStorage(
    runtime: KcodePluginRuntime,
    currentRepository: () -> ConversationHistoryRepository,
) {
    val id = currentRepository().nextConversationId()
    try {
        currentRepository().appendMessages(id, "package history", listOf(
            ai.meteor.kcode.history.HistoryMessageWrite(1, "User", "durable user"),
            ai.meteor.kcode.history.HistoryMessageWrite(2, "Assistant", "durable response"),
        ))
        currentRepository().setPinned(id, true)
        val saved = currentRepository().loadAll().single { it.id == id }
        val oldRepository = currentRepository()
        runtime.pluginManager.setEnabled("provider.history.platform", false)
        check(runCatching { oldRepository.loadAll() }.isFailure)
        check(runCatching { oldRepository.deleteConversation(id) }.isFailure)
        check(runtime.diagnostics().plugins.single { it.id == "provider.sessions.history" }.state == ai.meteor.kcode.plugin.api.PluginState.Pending)
        runtime.pluginManager.setEnabled("provider.history.platform", true)
        check(currentRepository() !== oldRepository)
        check(currentRepository().loadAll().single { it.id == id } == saved)
        check(runtime.diagnostics().plugins.single { it.id == "provider.sessions.history" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
        check(Class.forName("androidx.room3.Room", false, currentRepository().javaClass.classLoader).classLoader == currentRepository().javaClass.classLoader)
        check(Class.forName("androidx.sqlite.driver.bundled.BundledSQLiteDriver", false, currentRepository().javaClass.classLoader) == Class.forName("androidx.sqlite.driver.bundled.BundledSQLiteDriver"))
    } finally {
        currentRepository().deleteConversation(id)
    }
}
private suspend fun verifyBundledArtifactStorage(
    runtime: KcodePluginRuntime,
    workspaceDirectory: File,
    currentRepository: () -> ArtifactRepository,
) {
    val id = "package-artifact-${System.nanoTime()}"
    val source = File(workspaceDirectory, id).also { check(it.mkdirs()) }
    val manifest = File(workspaceDirectory, "artifacts/manifest.json")
    val previousManifest = manifest.takeIf { it.exists() }?.readBytes()
    File(source, "index.html").writeText("<h1>durable packaged artifact</h1>")
    val request = SaveWebArtifactRequest(id, "Package artifact", "/workspace/$id")
    try {
        val saved = (currentRepository() as MutableArtifactRepository).saveWebApp(request)
        check(currentRepository().list().single { it.id == id } == saved)
        val resource = File(workspaceDirectory, "artifacts/resources/$id/index.html")
        check(resource.readText() == "<h1>durable packaged artifact</h1>")
        val oldRepository = currentRepository()
        runtime.pluginManager.setEnabled("provider.artifacts.platform", false)
        check(runCatching { oldRepository.list() }.isFailure)
        check(runCatching { (oldRepository as MutableArtifactRepository).saveWebApp(request) }.isFailure)
        check(runtime.diagnostics().plugins.single { it.id == "feature.artifacts" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
        runtime.pluginManager.setEnabled("provider.artifacts.platform", true)
        check(currentRepository() !== oldRepository)
        check(currentRepository().list().single { it.id == id } == saved)
        check(resource.readText() == "<h1>durable packaged artifact</h1>")
        check(runtime.diagnostics().plugins.single { it.id == "feature.artifacts" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
        check(Class.forName("ai.meteor.kcode.plugin.artifacts.FileArtifactRepository", false, currentRepository().javaClass.classLoader).classLoader == currentRepository().javaClass.classLoader)
    } finally {
        if (previousManifest == null) check(!manifest.exists() || manifest.delete())
        else manifest.writeBytes(previousManifest)
        check(File(workspaceDirectory, "artifacts/resources/$id").deleteRecursively())
        check(source.deleteRecursively())
    }
}
private suspend fun verifyBundledHttpSearch(runtime: KcodePluginRuntime, currentBackend: () -> WebSearchBackend) {
    val oldBackend = currentBackend()
    val loader = oldBackend.javaClass.classLoader
    check(Class.forName("ai.meteor.kcode.plugin.searchhttp.HttpWebSearchBackend", false, loader).classLoader == loader)
    check(Class.forName("io.ktor.client.HttpClient", false, loader).classLoader == loader)
    listOf("io.ktor.client.engine.cio.CIO", "org.slf4j.LoggerFactory").forEach { type ->
        check(Class.forName(type, false, loader).classLoader == loader)
    }
    // Empty queries are rejected before external network access; construction allocates the real engine.
    check(runCatching { oldBackend.search("", 1) }.exceptionOrNull() is IllegalArgumentException)
    runtime.pluginManager.setEnabled("feature.web-search", false)
    check(runCatching { oldBackend.search("", 1) }.exceptionOrNull() is IllegalStateException)
    check(runtime.diagnostics().plugins.single { it.id == "feature.web-search" }.state == ai.meteor.kcode.plugin.api.PluginState.Disabled)
    runtime.pluginManager.setEnabled("feature.web-search", true)
    check(currentBackend() !== oldBackend)
    check(Class.forName("io.ktor.client.HttpClient", false, currentBackend().javaClass.classLoader).classLoader == currentBackend().javaClass.classLoader)
    check(runCatching { currentBackend().search("", 1) }.exceptionOrNull() is IllegalArgumentException)
    check(runtime.diagnostics().plugins.single { it.id == "feature.web-search" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
}
