package ai.meteor.kcode.plugin

import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeOverlay
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import ai.meteor.kcode.ui.design.KcodeGlass
import ai.meteor.kcode.ui.design.kcodeColors
import ai.meteor.kcode.plugin.ui.api.ApplicationEffect
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.runtime.DisposableEffect
import kotlinx.serialization.json.Json
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertNull

import ai.meteor.kcode.plugin.export.ConversationImageRenderingPlugin
import ai.meteor.kcode.export.ComposeConversationImageRenderContext
import ai.meteor.kcode.export.ConversationImageRenderRequest
import ai.meteor.kcode.export.ConversationExportMessage
import ai.meteor.kcode.export.ConversationImageRenderer
import ai.meteor.kcode.plugin.api.KcodeConversationImageRendering
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalLayoutDirection
import ai.meteor.kcode.plugin.localization.LocalizationProviderPlugin
import ai.meteor.kcode.plugin.localization.LocalizationUiContributionPlugin
import ai.meteor.kcode.plugin.modelsettings.ModelSettingsProviderPlugin
import ai.meteor.kcode.plugin.markdown.MarkdownProviderPlugin
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.ui.api.ConversationTranscriptRequest
import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ToolUseInfo
import ai.meteor.kcode.model.ToolUseStatus
import androidx.compose.runtime.CompositionLocalProvider
import org.cordis.dependencies
import org.cordis.plugin
import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.api.AndroidPluginWindow
import ai.meteor.kcode.plugin.api.AndroidPluginWindowContent
import ai.meteor.kcode.plugin.api.AndroidPluginWindowFactory
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.NativeAndroidPluginWindowHost
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import android.app.Activity
import android.os.Bundle
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertTrue

class AndroidPrivateApplicationRenderingTest {
    @Test(timeout = 90000)
    fun privateApkApplicationAndPagesRenderInAnActualComposeWindow(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "private-render-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "application.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
        val themeValues = AtomicReference<List<Any>?>(null)
        val resolvedTemperature = AtomicReference<Double?>(null)
        lateinit var slots: KcodeUiSlots
        lateinit var imageRenderer: ConversationImageRenderer
        val capture = kcodePlugin(
            PluginDescriptor("test.render-slots", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-render-slots", inject = dependencies(KcodeUiSlots.Key, KcodeConversationImageRendering.Key)) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
                imageRenderer = ctx.require(KcodeConversationImageRendering.Key).renderer
                collect(slots.registerEffect(ApplicationEffect("test.theme-probe", -1000, UiRenderer { request ->
                    val values = listOf(
                        Ink, Paper, Panel, MaterialTheme.kcodeColors.selectedSurface,
                        KcodeSpacing.md, KcodeRadius.control, KcodeSize.touchTarget,
                        KcodeGlass.blurRadius, MaterialTheme.typography.bodyMedium.fontSize, KcodeOverlay.floatingSize,
                    )
                    KcodeIcon(KcodeIconAsset.SelectionHandle, Ink, Modifier.size(24.dp))
                    DisposableEffect(Unit) {
                        onDispose { themeValues.set(null); resolvedTemperature.set(null) }
                    }
                    SideEffect { themeValues.set(values); resolvedTemperature.set(request.configuration?.temperature) }
                })))
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            featurePlugins = listOf(capture),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            settingsStore = object : AppSettingsStore {
                override val protection = SettingsProtection.Transient
                private var settings = StoredAppSettings(provider = "OpenAI", modelId = "gpt-4o-mini", language = "en", temperature = 1.75, modelApiKeys = mapOf("OpenAI" to "fixture"))
                override suspend fun load(): StoredAppSettings = settings
                override suspend fun save(settings: StoredAppSettings) { this.settings = settings }
            },
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        var window: AndroidPluginWindow? = null
        val commits = AtomicInteger()
        lateinit var view: ComposeView
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            val entries = listOf(
                "provider.localization.default" to LocalizationProviderPlugin::class.java,
                "consumer.localization.ui" to LocalizationUiContributionPlugin::class.java,
                "provider.model-settings.catalog" to ModelSettingsProviderPlugin::class.java,
                "provider.markdown.default" to MarkdownProviderPlugin::class.java,
                "provider.export.image-rendering" to ConversationImageRenderingPlugin::class.java,
                "provider.ui.compose" to DefaultApplicationUiPlugin::class.java,
                "provider.ui.layout" to DefaultLayoutUiPlugin::class.java,
                "provider.ui.sidebar" to DefaultSidebarUiPlugin::class.java,
                "provider.ui.chat" to DefaultChatUiPlugin::class.java,
                "provider.ui.theme" to DefaultThemeUiPlugin::class.java,
                "provider.ui.conversation.transcript" to DefaultConversationTranscriptUiPlugin::class.java,
                "provider.ui.message.assistant" to DefaultAssistantMessagePresentationPlugin::class.java,
                "provider.ui.tool.default" to DefaultToolUsePresentationPlugin::class.java,
            )
            lateinit var themeDeployment: DynamicPluginSpec
            lateinit var modelSettingsDeployment: DynamicPluginSpec
            lateinit var localizationDeployment: DynamicPluginSpec
            for ((id, entry) in entries) {
                val deployment = DynamicPluginSpec(
                    id = id,
                    version = "private-render",
                    artifactPath = artifact.path,
                    sha256 = digest,
                    entryClass = entry.name,
                    packageName = instrumentation.context.packageName,
                )
                if (id == "provider.ui.theme") themeDeployment = deployment
                if (id == "provider.model-settings.catalog") modelSettingsDeployment = deployment
                if (id == "provider.localization.default") localizationDeployment = deployment
                runtime.pluginManager.replace(deployment)
            }
            window = NativeAndroidPluginWindowHost(context).open(AndroidPluginWindowFactory { activity: Activity ->
                object : AndroidPluginWindowContent {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        // Hardware image readback requires a drawing window, including on a locked device.
                        activity.setShowWhenLocked(true)
                        activity.setTurnScreenOn(true)
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        view = ComposeView(activity)
                        activity.setContentView(view)
                        view.setContent {
                            runtime.Render(ApplicationHostOptions())
                            SideEffect { commits.incrementAndGet() }
                        }
                    }

                    override suspend fun close() {
                        withContext(Dispatchers.Main.immediate) { view.disposeComposition() }
                    }
                }
            })
            withTimeout(10000) { window.show(); window.awaitReady() }
            withTimeout(15000) {
                while (commits.get() < 1 || !withContext(Dispatchers.Main.immediate) { view.width > 0 && view.height > 0 }) {
                    delay(50)
                }
            }
            delay(500)
            instrumentation.waitForIdleSync()
            // Inspect only this window: accessibility focus can belong to another task.
            // These labels must cross the application/page class-loader boundary in English.
            var labels = emptyList<String>()
            repeat(100) {
                labels = withContext(Dispatchers.Main.immediate) { semanticsLabels(view) }
                if ("Message kcode…" !in labels) delay(50)
            }
            assertTrue("Message kcode…" in labels, "Private chat page missing its English composer label: $labels")
            assertTrue("Open sidebar" in labels, "Private chat header missing its English control label: $labels")
            assertTrue(commits.get() >= 1)
            assertTrue(withContext(Dispatchers.Main.immediate) { view.isAttachedToWindow })
            val baseline = requireNotNull(themeValues.get())
            val configured = themeDeployment.copy(version = "configured-theme", config = Json.parseToJsonElement("""{
                        "colors": {"onSurface":"#112233", "surface":"#223344"},
                        "extendedColors": {"panel":"#334455", "selectedSurface":"#445566"},
                        "spacing": {"md":22}, "radius": {"control":19},
                        "size": {"touchTarget":64}, "glass": {"blurRadius":6}, "overlay":{"floatingSize":68}, "fontScale":1.25
                    }"""))
            val expected = listOf(Color(0xFF112233), Color(0xFF223344), Color(0xFF334455),
                        Color(0xFF445566), 22.dp, 19.dp, 64.dp, 6.dp, 17.5.sp, 68.dp)
            suspend fun awaitTheme(values: List<Any>?) {
                withTimeout(10000) { while (themeValues.get() != values) delay(50) }
            }
            runtime.pluginManager.replace(configured)
            awaitTheme(expected)
            val retained = slots.snapshot().theme
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(configured.copy(config = Json.parseToJsonElement("""{"spacing":{"md":-1}}""")))
            }
            assertSame(retained, slots.snapshot().theme)
            runtime.pluginManager.setEnabled("provider.ui.theme", false)
            assertNull(slots.snapshot().theme)
            awaitTheme(null)
            instrumentation.waitForIdleSync()
            assertNull(themeValues.get())
            assertTrue("Message kcode…" !in withContext(Dispatchers.Main.immediate) { semanticsLabels(view) })
            runtime.pluginManager.setEnabled("provider.ui.theme", true)
            awaitTheme(expected)
            runtime.pluginManager.replace(themeDeployment)
            awaitTheme(baseline)
            suspend fun awaitTemperature(expectedTemperature: Double?) {
                withTimeout(10000) { while (resolvedTemperature.get() != expectedTemperature) delay(50) }
            }
            awaitTemperature(1.0)
            val configuredPolicy = modelSettingsDeployment.copy(config = Json.parseToJsonElement(
                """{"maximumTemperature":2.0}""",
            ))
            runtime.pluginManager.replace(configuredPolicy)
            awaitTemperature(1.75)
            runtime.pluginManager.setEnabled("provider.model-settings.catalog", false)
            awaitTemperature(null)
            instrumentation.waitForIdleSync()
            assertNull(resolvedTemperature.get())
            assertTrue("Message kcode…" !in withContext(Dispatchers.Main.immediate) { semanticsLabels(view) })
            runtime.pluginManager.setEnabled("provider.model-settings.catalog", true)
            awaitTemperature(1.75)
            runtime.pluginManager.replace(modelSettingsDeployment)
            awaitTemperature(1.0)

            suspend fun awaitLabel(label: String) {
                withTimeout(10000) {
                    while (label !in withContext(Dispatchers.Main.immediate) { semanticsLabels(view) }) delay(50)
                }
            }
            val configuredLocale = localizationDeployment.copy(config = Json.parseToJsonElement(
                """{"translations":{"en":{"message_placeholder":"Private composer"}}}""",
            ))
            runtime.pluginManager.replace(configuredLocale)
            awaitLabel("Private composer")
            val retainedLocale = slots.snapshot().localization
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(configuredLocale.copy(config = Json.parseToJsonElement("""{"defaultLanguage":"absent"}""")))
            }
            assertSame(retainedLocale, slots.snapshot().localization)
            runtime.pluginManager.setEnabled("provider.localization.default", false)
            delay(500)
            instrumentation.waitForIdleSync()
            assertTrue("Private composer" !in withContext(Dispatchers.Main.immediate) { semanticsLabels(view) })
            runtime.pluginManager.setEnabled("provider.localization.default", true)
            awaitLabel("Private composer")
            runtime.pluginManager.replace(localizationDeployment)
            awaitLabel("Message kcode…")


            val snapshot = slots.snapshot()
            val message = ChatMessage(
                id = 1L,
                role = MessageRole.Assistant,
                content = "Private transcript body",
                toolUses = listOf(ToolUseInfo("tool", "shell", "{}", status = ToolUseStatus.Succeeded)),
            )
            lateinit var exportContext: ComposeConversationImageRenderContext
            withContext(Dispatchers.Main.immediate) {
                view.setContent {
                    val textMeasurer = rememberTextMeasurer()
                    val graphicsLayer = rememberGraphicsLayer()
                    val layoutDirection = LocalLayoutDirection.current
                    SideEffect { exportContext = ComposeConversationImageRenderContext(textMeasurer, graphicsLayer, layoutDirection) }
                    CompositionLocalProvider(LocalApplicationUiSlots provides snapshot, ai.meteor.kcode.localization.LocalTranslationCatalog provides snapshot.localization, ai.meteor.kcode.localization.LocalAppLanguage provides ai.meteor.kcode.localization.AppLanguage.English) {
                        snapshot.theme?.Render {
                            snapshot.conversationTranscript?.Render(ConversationTranscriptRequest(listOf(message.id)) { message })
                        }
                    }
                }
            }
            delay(500)
            instrumentation.waitForIdleSync()
            withTimeout(10000) {
                do {
                    labels = withContext(Dispatchers.Main.immediate) { semanticsLabels(view) }
                    if ("Private transcript body" !in labels || "Shell" !in labels) delay(50)
                } while ("Private transcript body" !in labels || "Shell" !in labels)
            }
            assertTrue("Private transcript body" in labels, "Private Markdown/message body missing: $labels")
            assertTrue("Shell" in labels, "Private tool body missing: $labels")
            val exported = withTimeout(15000) {
                withContext(Dispatchers.Main.immediate) {
                    imageRenderer.render(
                        ConversationImageRenderRequest(
                            "",
                            listOf(ConversationExportMessage(false, "**Markdown export body**", false)),
                            "truncated",
                        ),
                        exportContext,
                    )
                }
            }
            assertTrue(exported.image.width == 1080)
            assertTrue(exported.image.height > 200)
            assertTrue(!exported.truncated)
            // The title is empty: opaque dark pixels must come from the exported message body.
            val pixels = exported.image.toPixelMap()
            assertTrue((0 until pixels.width step 4).any { x ->
                (0 until pixels.height step 4).any { y ->
                    val color = pixels[x, y]
                    color.alpha > 0.9f && color.red < 0.8f && color.green < 0.8f && color.blue < 0.8f
                }
            }, "Exported Markdown body has no visible pixels")

        } finally {
            try {
                window?.close()
                runtime.close()
                directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
    }
    private fun semanticsLabels(view: View): List<String> {
        val labels = mutableListOf<String>()
        fun visit(current: View) {
            val ownerMethod = current.javaClass.methods.firstOrNull {
                it.name == "getSemanticsOwner" && it.parameterCount == 0
            }
            val owner = ownerMethod?.invoke(current) as? SemanticsOwner
            if (owner != null) {
                fun collect(node: androidx.compose.ui.semantics.SemanticsNode) {
                    labels += node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                    labels += node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    node.children.forEach(::collect)
                }
                collect(owner.unmergedRootSemanticsNode)
            } else if (current is ViewGroup) {
                repeat(current.childCount) { visit(current.getChildAt(it)) }
            }
        }
        visit(view)
        return labels
    }
}
