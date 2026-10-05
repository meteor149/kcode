package ai.meteor.kcode

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ChatMessageCodec
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.BundledPluginPackage
import ai.meteor.kcode.plugin.FilePluginCompositionStore
import ai.meteor.kcode.plugin.KcodePluginRuntime
import ai.meteor.kcode.plugin.KcodePluginRuntimeConfig
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.AndroidPermissionHost
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
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.provider
import ai.meteor.kcode.test.modelApiKeys
import ai.meteor.kcode.test.searchApiKeys
import ai.meteor.kcode.test.toolPermissionMode
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.KcodeMessageCodec
import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.plugin.api.KcodeToolApprovals
import ai.meteor.kcode.plugin.api.ConfirmationDialogHost
import ai.meteor.kcode.plugin.api.ConfirmationDialogRequest
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.plugin.api.KcodeConversationImageSaving
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ImageSaveResult
import android.content.ContentUris
import android.Manifest
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.androidPackageHost
import ai.meteor.kcode.plugin.packages.androidPackageVerifier
import ai.meteor.kcode.plugin.packages.stageBundledPackageCatalog
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import dalvik.system.DexFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class BundledPluginDistributionTest {
    @Test(timeout = 120000)
    fun nativeActivityStartsWithIndependentUiPackagesAndRendersControls(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
            Manifest.permission.POST_NOTIFICATIONS,
        )
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            val runtime = withTimeout(60000) {
                var current: KcodeAgentRuntime? = null
                while (current == null) {
                    launched.onActivity { activity ->
                        current = MainActivity::class.java.getDeclaredField("agentRuntime").run {
                            isAccessible = true
                            get(activity) as? KcodeAgentRuntime
                        }
                    }
                    if (current == null) delay(100)
                }
                requireNotNull(current)
            }
            val manager = requireNotNull(runtime.pluginManager)
            assertEquals(if (androidPackageHost().arch == "arm64") 66 else 65, manager.installed().size)
            assertTrue(manager.installed().any { it.id == "provider.ui.compose" })
            val pluginRuntime = runtime.applicationContent as KcodePluginRuntime
            val inactive = pluginRuntime.diagnostics().plugins.filter {
                it.state != ai.meteor.kcode.plugin.api.PluginState.Active
            }
            assertTrue(inactive.isEmpty(), "Native startup has inactive plugins: $inactive")
            val device = UiDevice.getInstance(instrumentation)
            withTimeout(15000) {
                while (device.findObjects(By.pkg(instrumentation.targetContext.packageName).clickable(true)).size < 2) {
                    delay(100)
                }
            }

        } finally {
            try {
                scenario?.close()
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
    }

    @Test(timeout = 120000)
    fun shippedArchiveLoadsWithoutCodecImplementationInHostAndRemembersUninstall(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entries = listOf("ai.meteor.kcode.plugin.messagecodec.MessageCodecProviderPlugin",
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
            "ai.meteor.kcode.plugin.nativefilesystem.AndroidNativeFileSystemPlugin",
            "ai.meteor.kcode.plugin.skills.WorkspaceSkillsPlugin",
            "ai.meteor.kcode.plugin.ScheduleDispatchPlugin",
            "ai.meteor.kcode.plugin.export.ConversationImageRenderingPlugin",
            "ai.meteor.kcode.plugin.export.ConversationExportPlugin",
            "ai.meteor.kcode.plugin.export.ConversationExportFeaturePlugin",
            "ai.meteor.kcode.plugin.export.AndroidNativeImageSavingPlugin",
            "ai.meteor.kcode.plugin.feature.FilesystemToolConsumerPlugin",
            "ai.meteor.kcode.plugin.feature.SkillToolConsumerPlugin",
            "ai.meteor.kcode.plugin.feature.ArtifactToolConsumerPlugin",
            "ai.meteor.kcode.plugin.feature.WebSearchToolConsumerPlugin",
            "ai.meteor.kcode.plugin.feature.WebContainerToolConsumerPlugin",
            "ai.meteor.kcode.plugin.overlay.ConversationOverlaysServicePlugin",
            "ai.meteor.kcode.plugin.overlay.NativeConversationOverlaysServicePlugin",
            "ai.meteor.kcode.plugin.executionsettings.SettingsShellModePlugin",
            "ai.meteor.kcode.plugin.nativeexecution.AndroidPackagedShellPlugin",
            "ai.meteor.kcode.plugin.nativeexecution.AndroidPackagedUbuntuShellPlugin",
            "ai.meteor.kcode.plugin.nativeexecution.AndroidNativeSettingsShellPlugin",
            "ai.meteor.kcode.plugin.nativeexecution.AndroidNativeSettingsUbuntuShellPlugin",
            "ai.meteor.kcode.plugin.nativeexecution.AndroidShellExecutors",
            "ai.meteor.kcode.plugin.nativeexecution.AndroidUbuntuShellExecutor",
            "ai.meteor.kcode.plugin.overlay.ConversationOverlayProviderPlugin",
            "ai.meteor.kcode.plugin.overlay.AndroidNativeConversationOverlayPlugin",
            "ai.meteor.kcode.plugin.overlay.AndroidConversationOverlayController",
            "ai.meteor.kcode.plugin.WebContainersProviderPlugin",
            "ai.meteor.kcode.plugin.webcontainer.native.AndroidNativeWebContainerPlugin",
            "ai.meteor.kcode.plugin.webcontainer.native.AndroidWebContainerFeaturePlugin",
            "ai.meteor.kcode.plugin.WebContainerFeaturePlugin",
            "ai.meteor.kcode.plugin.provider.HttpWebSearchProviderPlugin",
            "ai.meteor.kcode.plugin.searchhttp.HttpWebSearchBackend",
            "ai.meteor.kcode.plugin.feature.DefaultAndroidShellToolConsumerPlugin",
            "ai.meteor.kcode.plugin.feature.DefaultUbuntuShellToolConsumerPlugin",
            "ai.meteor.kcode.plugin.LocalizedNativeToolApprovalPlugin",
            "ai.meteor.kcode.plugin.SettingsToolInteractionPlugin",
            "ai.meteor.kcode.plugin.InteractionServicePlugin",
            "ai.meteor.kcode.plugin.HostModeToolInteractionPlugin",
            "ai.meteor.kcode.plugin.provider.PlatformFileSystemProviderPlugin",
            "ai.meteor.kcode.plugin.provider.SettingsShellProviderPlugin",
            "ai.meteor.kcode.plugin.provider.SettingsUbuntuShellProviderPlugin",
            "ai.meteor.kcode.plugin.provider.CapabilityProvidersKt",
            "ai.meteor.kcode.plugin.artifacts.AndroidNativeArtifactsPlugin",
            "ai.meteor.kcode.plugin.artifacts.FileArtifactRepository",
            "ai.meteor.kcode.plugin.history.AndroidNativeHistoryPlugin",
            "ai.meteor.kcode.plugin.history.HistoryDatabase_Impl",
            "androidx.room3.Room",
            "ai.meteor.kcode.plugin.settingsstorage.AndroidNativeSettingsPlugin",
            "ai.meteor.kcode.plugin.settingsstorage.MmkvAppSettingsStore",
            "ai.meteor.kcode.plugin.notifications.LocalizedAndroidNativeNotificationsPlugin",
            "ai.meteor.kcode.plugin.notifications.AndroidNotificationPermissionPlugin",
            "ai.meteor.kcode.plugin.notifications.LocalizedAndroidGenerationForegroundPlugin")
        // Instrumentation includes native execution fixtures in its own class loader.
        // Verify their absence in the installed application rather than the test loader.
        val hostDex = DexFile(context.applicationInfo.sourceDir)
        val hostDefinitions = try {
            hostDex.entries().asSequence().toSet()
        } finally {
            hostDex.close()
        }
        val fixtureEntries = setOf(
            "ai.meteor.kcode.plugin.InteractionServicePlugin",
            "ai.meteor.kcode.plugin.HostModeToolInteractionPlugin",
            "ai.meteor.kcode.plugin.provider.PlatformFileSystemProviderPlugin",
            "ai.meteor.kcode.plugin.provider.SettingsShellProviderPlugin",
            "ai.meteor.kcode.plugin.provider.SettingsUbuntuShellProviderPlugin",
            "ai.meteor.kcode.plugin.provider.CapabilityProvidersKt",
        )
        entries.forEach { entry ->
            check(entry !in hostDefinitions) { "Implementation is embedded in the host: $entry" }
            if (!entry.startsWith("ai.meteor.kcode.plugin.nativeexecution.") && entry !in fixtureEntries) {
                assertFailsWith<ClassNotFoundException> {
                    Class.forName(entry, false, context.classLoader)
                }
            }
        }
        val directory = File(context.cacheDir, "bundled-distribution-${System.nanoTime()}").also { it.mkdirs() }
        lateinit var interaction: InteractionPolicy
        lateinit var artifacts: ArtifactRepository
        lateinit var history: ConversationHistoryRepository
        lateinit var settings: AppSettingsStore
        lateinit var mutationSettings: AppSettingsStore
        lateinit var searchBackend: WebSearchBackend
        lateinit var searchPolicy: SearchSettingsPolicy
        lateinit var llm: KcodeLlm
        lateinit var codec: ChatMessageCodec
        lateinit var skills: SkillRuntime
        lateinit var uiSlots: KcodeUiSlots
        lateinit var imageSaver: ConversationImageSaver
        lateinit var approver: ToolCallApprover
        var confirmation: ConfirmationDialogRequest? = null
        val capture = kcodePlugin(PluginDescriptor("test.bundled-codec", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-bundled-codec", inject = dependencies(KcodeArtifacts.Key, KcodeHistory.Key, KcodeInteraction.Key, KcodeSettings.Key, KcodeSearchSettings.Key, KcodeWebSearch.Key, KcodeLlm.Key, KcodeMessageCodec.Key, KcodeSkills.Key, KcodeUiSlots.Key, KcodeConversationImageSaving.Key, KcodeToolApprovals.Key)) { ctx, _ ->
                interaction = ctx.require(KcodeInteraction.Key).policy
                artifacts = ctx.require(KcodeArtifacts.Key).repository
                history = ctx.require(KcodeHistory.Key).repository
                settings = ctx.require(KcodeSettings.Key).store
                mutationSettings = ctx.require(KcodeSettings.Key).mutationStore
                searchBackend = ctx.require(KcodeWebSearch.Key).backend
                searchPolicy = ctx.require(KcodeSearchSettings.Key).policy
                llm = ctx.require(KcodeLlm.Key)
                codec = ctx.require(KcodeMessageCodec.Key).codec
                skills = requireNotNull(ctx.require(KcodeSkills.Key).runtime)
                uiSlots = ctx.require(KcodeUiSlots.Key)
                imageSaver = ctx.require(KcodeConversationImageSaving.Key).saver
                approver = ctx.require(KcodeToolApprovals.Key).approver
            }, Unit)
        var hostActivity: MainActivity? = null
        var scenario: ActivityScenario<MainActivity>? = null
        suspend fun configuration() = KcodePluginRuntimeConfig(
            hostInputs = AndroidPluginHostInputs(requireNotNull(hostActivity), object : AndroidPermissionHost {
                override fun isGranted(permission: String): Boolean = true
                override suspend fun request(permission: String): Boolean = true
            }, ConfirmationDialogHost { request -> confirmation = request; false }),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { false }),
            bundledPackages = stageBundledPackageCatalog(directory, host = androidPackageHost()) {
                check(it != "kcode/plugins/consumer.tools.shell-1.0.0.kplugin")
                check(it != "kcode/plugins/provider.llm.koog.Bedrock-1.0.0.kplugin")
                context.assets.open(it)
            }
                .map { BundledPluginPackage(it.id, it.release) },
            featurePlugins = listOf(capture, kcodePlugin(
                PluginDescriptor("provider.plugin-packages.platform", "test", "test", emptySet()),
                NativePluginPackagesPlugin(directory, androidPackageHost(), artifactVerifier = androidPackageVerifier(context)), Unit,
            )),
            pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = androidPluginControllerFactory(context, directory),
        )
        FilePluginCompositionStore(directory).save(ai.meteor.kcode.plugin.api.PluginCompositionSnapshot(
            builtinsEnabled = mapOf("provider.llm.koog" to true, "provider.llm.koog.DeepSeek" to false, "provider.llm.koog.Bedrock" to false),
        ))
        var runtime: KcodePluginRuntime? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
            Manifest.permission.POST_NOTIFICATIONS,
        )
        try {
            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            launched.onActivity { hostActivity = it }
            runtime = KcodePluginRuntime.create(configuration())
            assertEquals(if (androidPackageHost().arch == "arm64") 66 else 65, runtime.pluginManager.installed().size)
            assertTrue(runtime.pluginManager.installed().none { it.id == "consumer.tools.shell" })
            check(!runtime.pluginManager.installed().single { it.id == "provider.llm.koog.DeepSeek" }.enabled)
            runtime.pluginManager.setEnabled("provider.llm.koog.DeepSeek", true)
            val migrated = FilePluginCompositionStore(directory).load()
            check("provider.llm.koog" !in migrated.builtinsEnabled)
            check(migrated.builtinsEnabled.keys.none { it.startsWith("provider.llm.koog.") })
            verifyBundledSettingsMutations(runtime, { settings }) { mutationSettings }
            verifyBundledSearchPolicy(runtime) { searchPolicy }
            verifyBundledHttpSearch(runtime) { searchBackend }
            verifyBundledInteraction(runtime, { interaction }, { settings })
            verifyBundledSettingsStorage(runtime) { settings }
            verifyBundledHistoryStorage(runtime) { history }
            verifyBundledArtifactStorage(runtime, File(context.filesDir, "agent_workspace")) { artifacts }
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
            assertTrue(runtime.pluginManager.installed().none { it.id == "provider.llm.koog.Bedrock" })
            val modelCatalog = llm.catalog()
            for (provider in modelCatalog.providers) {
                val configuration = ModelConfiguration(provider.provider, provider.models.first().id, "fixture", 0.6,
                    endpoint = "https://fixture.invalid", region = "us-west-2", deployment = "fixture")
                val adapter = llm.resolve(configuration)
                if (provider.provider == ModelProvider.Bedrock) {
                    adapter.create(configuration, allocationProbe).client.close()
                } else {
                    val failure = runCatching { adapter.create(configuration, allocationProbe) }.exceptionOrNull()
                    check(failure is IllegalStateException && failure.message == "offline-model-allocation") { "$failure" }
                }
                val id = "provider.llm.koog.${provider.provider.name}"
                runtime.pluginManager.setEnabled(id, false)
                check(!adapter.supports(configuration))
                check(llm.catalog().providers.none { it.provider == provider.provider })
                runtime.pluginManager.setEnabled(id, true)
                check(llm.catalog().providers.single { it.provider == provider.provider } == provider)
                check(llm.resolve(configuration).supports(configuration))
            }
            val modelConfiguration = ModelConfiguration(ModelProvider.GLM, "glm-4", "fixture", temperature = 0.6)
            val originalLlm = llm
            val originalAdapter = llm.resolve(modelConfiguration)
            check(llm.adapterIds().size == 10)
            check(originalAdapter.supports(modelConfiguration))
            runtime.pluginManager.setEnabled("core.llm", false)
            check(!originalAdapter.supports(modelConfiguration))
            check(originalLlm.adapterIds().isEmpty())
            check(runCatching { originalLlm.resolve(modelConfiguration) }.isFailure)
            check(runtime.diagnostics().plugins.filter { it.id.startsWith("provider.llm.koog.") }.all {
                it.state == ai.meteor.kcode.plugin.api.PluginState.Pending
            })
            runtime.pluginManager.setEnabled("core.llm", true)
            check(llm !== originalLlm)
            check(llm.adapterIds().size == 10)
            check(llm.resolve(modelConfiguration).supports(modelConfiguration))
            check(runtime.diagnostics().plugins.filter { it.id.startsWith("provider.llm.koog.") }.all {
                it.state == ai.meteor.kcode.plugin.api.PluginState.Active
            })
            val approvalRequest = ToolApprovalRequest("package-check", "{}", "request-body")
            assertTrue(!approver.approve(approvalRequest))
            assertTrue(requireNotNull(confirmation).title.contains("package-check"))
            assertTrue(requireNotNull(confirmation).message.contains("request-body"))
            val oldApprover = approver
            runtime.pluginManager.setEnabled("provider.tool-approvals.native", false)
            assertTrue(runCatching { oldApprover.approve(approvalRequest) }.isFailure)
            runtime.pluginManager.setEnabled("provider.tool-approvals.native", true)
            assertNotSame(oldApprover, approver)
            assertTrue(!approver.approve(approvalRequest))
            assertTrue(runtime.pluginManager.installed().all { it.packageInstallation?.variantId == "android" })
            val packageIds = runtime.pluginManager.installed().map { it.id }.toSet()
            assertTrue(runtime.diagnostics().plugins.filter { it.id in packageIds }.all { it.state == ai.meteor.kcode.plugin.api.PluginState.Active })
            listOf("consumer.tools.filesystem", "consumer.tools.skill", "consumer.tools.artifact", "consumer.tools.web-search", "consumer.tools.android-shell", "consumer.tools.ubuntu-shell").forEach { id ->
                val pluginId = when (id) {
                    "consumer.tools.web-search" -> "feature.web-search"
                    "consumer.tools.artifact" -> "feature.artifacts"
                    else -> id
                }
                assertTrue(id in runtime.diagnostics().toolContributions)
                runtime.pluginManager.setEnabled(pluginId, false)
                assertTrue(id !in runtime.diagnostics().toolContributions)
                runtime.pluginManager.setEnabled(pluginId, true)
                assertEquals(1, runtime.diagnostics().toolContributions.count { it == id })
            }
            val installed = runtime.pluginManager.installed().single { it.id == "provider.message-codec.envelope" }
            assertEquals("provider.message-codec.envelope", installed.id)
            assertEquals("android", installed.packageInstallation?.variantId)
            assertNotSame(context.classLoader, codec.javaClass.classLoader)
            val message = ChatMessage(1, MessageRole.Assistant, "bundled independent APK")
            assertEquals(message.content, codec.decode(codec.encode(message)).text)
            assertTrue(skills.catalog().entries.any { it.name == "kcode-web-app-builder" })
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "feature.schedule" }.state)
            runtime.pluginManager.setEnabled("feature.schedule", false)
            assertEquals(PluginState.Disabled, runtime.diagnostics().plugins.first { it.id == "feature.schedule" }.state)
            assertTrue(uiSlots.snapshot().effects.none { it.id == "schedule.dispatch" })
            runtime.pluginManager.setEnabled("feature.schedule", true)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "feature.schedule" }.state)
            val fileName = "kcode-package-export-${System.nanoTime()}.png"
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            fun savedUris(): List<android.net.Uri> = context.contentResolver.query(
                collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(fileName), null,
            )!!.use { cursor -> buildList {
                while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
            } }
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xff245678.toInt()) }
            try {
                assertTrue(imageSaver.save(bitmap.asImageBitmap(), fileName) is ImageSaveResult.Saved)
                val uri = savedUris().single()
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val decoded = requireNotNull(BitmapFactory.decodeStream(input))
                    try {
                        assertEquals(2, decoded.width)
                        assertEquals(0xff245678.toInt(), decoded.getPixel(0, 0))
                    } finally { decoded.recycle() }
                }
                val oldSaver = imageSaver
                runtime.pluginManager.setEnabled("feature.conversation-export", false)
                assertTrue(runtime.diagnostics().plugins.single { it.id == "feature.conversation-export" }.state == ai.meteor.kcode.plugin.api.PluginState.Disabled)
                assertTrue(runCatching { oldSaver.save(bitmap.asImageBitmap(), fileName) }.isFailure)
                runtime.pluginManager.setEnabled("feature.conversation-export", true)
                assertNotSame(oldSaver, imageSaver)
            } finally {
                bitmap.recycle()
                savedUris().forEach { context.contentResolver.delete(it, null, null) }
            }
            runtime.pluginManager.uninstall(installed.id)
            runtime.close()
            runtime = KcodePluginRuntime.create(configuration())
            assertEquals(packageIds - installed.id, runtime.pluginManager.installed().map { it.id }.toSet())
            assertTrue(runtime.pluginManager.installed().none { it.id == installed.id })
            assertTrue(FilePluginCompositionStore(directory).load().bundledPackages.containsKey(installed.id))
        } finally {
            try {
                runtime?.close()
            } finally {
                try {
                    directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
                    scenario?.close()
                    hostActivity = null
                } finally {
                    instrumentation.uiAutomation.dropShellPermissionIdentity()
                }
            }
        }
    }
}

private suspend fun verifyBundledSettingsStorage(runtime: KcodePluginRuntime, currentStore: () -> AppSettingsStore) {
    val previous = currentStore().load()
    val saved = previous.copy(
        modelApiKeys = previous.modelApiKeys + ("fixture.custom" to "fixture-key"),
        searchApiKeys = previous.searchApiKeys + ("fixture.search" to "fixture-search-key"),
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
    try {
        for ((code, expected) in listOf("deny" to ToolPermissionMode.Deny, "bypass" to ToolPermissionMode.Bypass, "unknown-mode" to ToolPermissionMode.Ask)) {
            currentSettings().save(stored.copy(toolPermissionMode = code))
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
    check(currentPolicy().permissionModeProvider() == (ToolPermissionMode.fromCode(stored.toolPermissionMode) ?: ToolPermissionMode.Ask))
    check(!currentPolicy().approver.approve(request))
    check(runtime.diagnostics().plugins.single { it.id == "provider.agent-loop.koog" }.state == ai.meteor.kcode.plugin.api.PluginState.Active)
}

private suspend fun verifyBundledSearchPolicy(runtime: KcodePluginRuntime, currentPolicy: () -> SearchSettingsPolicy) {
    val storedSearch = LegacySettings(webSearchProvider = "exa", exaSearchApiKey = "legacy",
        webSearchApiKey = "bright", searchApiKeys = mapOf("custom.route" to "custom"))
    val searchConfiguration = currentPolicy().resolve(storedSearch)
    check(searchConfiguration.apiKeys["exa"] == "legacy")
    check(searchConfiguration.apiKeys["custom.route"] == "custom")
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
    listOf("io.ktor.client.engine.okhttp.OkHttp", "okhttp3.OkHttpClient", "org.slf4j.LoggerFactory").forEach { type ->
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

private suspend fun verifyBundledSettingsMutations(
    runtime: KcodePluginRuntime,
    storage: () -> AppSettingsStore,
    mutations: () -> AppSettingsStore,
) {
    val original = storage().load()
    val cases = listOf(
        Triple("policy.shell-mode.platform", "feature.execution-settings", """{"mode":"unavailable.mode"}"""),
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
