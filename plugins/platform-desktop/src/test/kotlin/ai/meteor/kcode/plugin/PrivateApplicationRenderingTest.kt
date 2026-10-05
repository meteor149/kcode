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
import kotlinx.serialization.json.Json
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertNull

import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.ui.api.ConversationTranscriptRequest
import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ToolUseInfo
import ai.meteor.kcode.model.ToolUseStatus
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import org.cordis.dependencies
import org.cordis.plugin
import ai.meteor.kcode.plugin.export.ConversationExportFeaturePlugin
import ai.meteor.kcode.export.ComposeConversationImageRenderContext
import ai.meteor.kcode.export.ConversationImageRenderRequest
import ai.meteor.kcode.export.ConversationExportMessage
import ai.meteor.kcode.export.ConversationImageRenderer
import ai.meteor.kcode.plugin.api.KcodeConversationImageRendering
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalLayoutDirection
import ai.meteor.kcode.plugin.localization.LocalizationFeaturePlugin
import ai.meteor.kcode.plugin.modelsettings.ModelSettingsProviderPlugin
import ai.meteor.kcode.plugin.markdown.MarkdownFeaturePlugin
import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivateApplicationRenderingTest {
    @Test
    fun privateJarsRenderApplicationAndPagesWithoutBundlingTheHostSdk(): Unit = runBlocking {
        val directory = Files.createTempDirectory("private-application-render").toFile()
        val artifacts = mutableMapOf<File, File>()
        fun artifactFor(entry: Class<*>): File {
            val source = File(entry.protectionDomain.codeSource.location.toURI())
            return artifacts.getOrPut(source) {
                File(directory, "module-${artifacts.size}.jar").also { source.copyTo(it); check(it.setReadOnly()) }
            }
        }
        val themeValues = AtomicReference<List<Any>?>(null)
        val resolvedTemperature = AtomicReference<Double?>(null)
        lateinit var modelPolicy: ai.meteor.kcode.settings.ModelSettingsPolicy
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
                    SideEffect { themeValues.set(values); resolvedTemperature.set(request.configuration?.temperature) }
                })))
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            featurePlugins = listOf(capture, kcodePlugin(
                PluginDescriptor("test.model-policy", "test", "test", emptySet()),
                plugin<Unit>(name = "capture-model-policy", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeModelSettings.Key)) { ctx, _ ->
                    modelPolicy = ctx.require(ai.meteor.kcode.plugin.api.KcodeModelSettings.Key).policy
                }, Unit,
            )),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            settingsStore = object : AppSettingsStore {
                override val protection = SettingsProtection.Transient
                private var settings = LegacySettings(provider = "OpenAI", modelId = "gpt-4o-mini", language = "en", temperature = 1.75, modelApiKeys = mapOf("OpenAI" to "fixture"))
                override suspend fun load(): StoredAppSettings = settings
                override suspend fun save(settings: StoredAppSettings) { this.settings = settings }
            },
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        try {
            val entries = listOf(
                "feature.localization" to LocalizationFeaturePlugin::class.java,
                "provider.model-settings.catalog" to ModelSettingsProviderPlugin::class.java,
                "feature.markdown" to MarkdownFeaturePlugin::class.java,
                "feature.conversation-export" to ConversationExportFeaturePlugin::class.java,
                "provider.ui.compose" to DefaultApplicationUiPlugin::class.java,
                "provider.ui.layout" to DefaultLayoutUiPlugin::class.java,
                "provider.ui.sidebar" to DefaultSidebarUiPlugin::class.java,
                "provider.ui.chat" to DefaultChatUiPlugin::class.java,
                "provider.ui.theme" to DefaultThemeUiPlugin::class.java,
                "provider.ui.conversation.transcript" to DefaultConversationTranscriptUiPlugin::class.java,
                "provider.ui.message.user" to DefaultUserMessagePresentationPlugin::class.java,
                "provider.ui.message.assistant" to DefaultAssistantMessagePresentationPlugin::class.java,
                "provider.ui.tool.default" to DefaultToolUsePresentationPlugin::class.java,
            )
            lateinit var themeDeployment: DynamicPluginSpec
            lateinit var modelSettingsDeployment: DynamicPluginSpec
            lateinit var localizationDeployment: DynamicPluginSpec
            for ((id, entry) in entries) {
                val artifact = artifactFor(entry)
                val deployment = DynamicPluginSpec(
                    id = id,
                    version = "private-render",
                    artifactPath = artifact.path,
                    sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
                    entryClass = entry.name,
                )
                if (id == "provider.ui.theme") themeDeployment = deployment
                if (id == "provider.model-settings.catalog") modelSettingsDeployment = deployment
                if (id == "feature.localization") localizationDeployment = deployment
                runtime.pluginManager.replace(deployment)
            }
            withContext(Dispatchers.Main.immediate) {
                val scene = ImageComposeScene(width = 800, height = 600, coroutineContext = coroutineContext) {
                    runtime.Render(ApplicationHostOptions())
                }
                try {
                    repeat(12) { frame ->
                        scene.render(frame * 50000000L).use { image ->
                            assertEquals(800, image.width)
                            assertEquals(600, image.height)
                        }
                        delay(50)
                    }
                    val labels = semanticsLabels(scene)
                    assertTrue("Message kcode…" in labels, "Private chat page missing its English composer label: $labels")
                    assertTrue("New chat" in labels, "Private sidebar missing its English action label: $labels")
                    val baseline = requireNotNull(themeValues.get())
                    val configured = themeDeployment.copy(version = "configured-theme", config = Json.parseToJsonElement("""{
                        "colors": {"onSurface":"#112233", "surface":"#223344"},
                        "extendedColors": {"panel":"#334455", "selectedSurface":"#445566"},
                        "spacing": {"md":22}, "radius": {"control":19},
                        "size": {"touchTarget":64}, "glass": {"blurRadius":6}, "overlay":{"floatingSize":68}, "fontScale":1.25
                    }"""))
                    val expected = listOf(Color(0xFF112233), Color(0xFF223344), Color(0xFF334455),
                        Color(0xFF445566), 22.dp, 19.dp, 64.dp, 6.dp, 17.5.sp, 68.dp)
                    runtime.pluginManager.replace(configured)
                    var frameNumber = 12L
                    suspend fun frames() {
                        repeat(12) { scene.render(frameNumber++ * 50000000L).close(); delay(50) }
                    }
                    frames()
                    assertEquals(expected, themeValues.get())
                    val retained = slots.snapshot().theme
                    assertFailsWith<IllegalStateException> {
                        runtime.pluginManager.replace(configured.copy(config = Json.parseToJsonElement("""{"spacing":{"md":-1}}""")))
                    }
                    assertSame(retained, slots.snapshot().theme)
                    runtime.pluginManager.setEnabled("provider.ui.theme", false)
                    themeValues.set(null)
                    frames()
                    assertNull(themeValues.get())
                    assertTrue("Message kcode…" !in semanticsLabels(scene))
                    runtime.pluginManager.setEnabled("provider.ui.theme", true)
                    frames()
                    assertEquals(expected, themeValues.get())
                    runtime.pluginManager.replace(themeDeployment)
                    frames()
                    assertEquals(baseline, themeValues.get())
                    assertEquals(1.0, resolvedTemperature.get())
                    val configuredPolicy = modelSettingsDeployment.copy(config = Json.parseToJsonElement(
                        """{"maximumTemperature":2.0}""",
                    ))
                    runtime.pluginManager.replace(configuredPolicy)
                    frames()
                    assertEquals(1.75, resolvedTemperature.get())
                    runtime.pluginManager.setEnabled("provider.model-settings.catalog", false)
                    runtime.pluginManager.setEnabled("provider.artifacts.platform", false)
                    frames()
                    assertTrue("Message kcode…" in semanticsLabels(scene))
                    assertTrue(slots.snapshot().settings != null)
                    assertNull(slots.snapshot().artifacts)
                    assertTrue(slots.snapshot().navigation.none { it.id == "artifacts" })
                    assertEquals(ai.meteor.kcode.plugin.api.PluginState.Active,
                        runtime.diagnostics().plugins.single { it.id == "provider.ui.compose" }.state)
                    runtime.pluginManager.setEnabled("provider.artifacts.platform", true)
                    frames()
                    assertTrue(slots.snapshot().artifacts != null)
                    assertTrue(slots.snapshot().navigation.any { it.id == "artifacts" })
                    resolvedTemperature.set(null)
                    frames()
                    assertNull(resolvedTemperature.get())
                    assertTrue("Message kcode…" in semanticsLabels(scene))
                    assertTrue(slots.snapshot().settings != null)
                    assertTrue(slots.snapshot().settingsSections.none { it.id == "model" })
                    runtime.pluginManager.setEnabled("provider.model-settings.catalog", true)
                    frames()
                    assertEquals(1.75, resolvedTemperature.get())
                    runtime.pluginManager.replace(modelSettingsDeployment)
                    frames()
                    assertEquals(1.0, resolvedTemperature.get())

                    clickSemanticAction(scene, "New chat")
                    frames()
                    val settingsRequest = AtomicReference<ai.meteor.kcode.plugin.ui.api.SettingsPageRequest?>(null)
                    val settingsProbe = slots.registerSettings(ai.meteor.kcode.plugin.ui.api.SettingsSection(
                        id = "test.settings-probe", order = -1000, icon = KcodeIconAsset.Settings,
                        title = { "Probe" }, description = { "" }, renderer = UiRenderer { },
                        isVisible = { request ->
                            SideEffect { settingsRequest.set(request) }
                            false
                        },
                    ))
                    for ((providerId, changedTemperature) in listOf(
                        "provider.generation" to 0.25,
                        "provider.sessions.history" to 0.35,
                        "provider.history.platform" to 0.45,
                    )) {
                        settingsRequest.set(null)
                        runtime.pluginManager.setEnabled(providerId, false)
                        frames()
                        assertEquals(ai.meteor.kcode.plugin.api.PluginState.Active,
                            runtime.diagnostics().plugins.single { it.id == "provider.ui.compose" }.state)
                        assertTrue("Message kcode…" !in semanticsLabels(scene))
                        assertTrue("Open settings" in semanticsLabels(scene))
                        frames()
                        val compactScene = ImageComposeScene(width = 400, height = 600, coroutineContext = coroutineContext) {
                            runtime.Render(ApplicationHostOptions())
                        }
                        try {
                            repeat(12) { frame -> compactScene.render(frame * 50000000L).close(); delay(50) }
                            if ("Open settings" !in semanticsLabels(compactScene)) {
                                clickSemanticAction(compactScene, "Open sidebar")
                                repeat(12) { frame -> compactScene.render((frame + 12) * 50000000L).close(); delay(50) }
                            }
                            assertTrue("Open settings" in semanticsLabels(compactScene), "Missing compact navigation without $providerId")
                            assertTrue("Message kcode…" !in semanticsLabels(compactScene))
                        } finally {
                            compactScene.close()
                        }
                        clickSemanticAction(scene, "Open settings")
                        frames()
                        val openSettings = requireNotNull(settingsRequest.get())
                        assertTrue(openSettings.sections.any { it.id == "language" })
                        openSettings.onSettingsChange(modelPolicy.update(openSettings.appSettings,
                            requireNotNull(modelPolicy.resolve(openSettings.appSettings, runtime.modelCatalog())).copy(temperature = changedTemperature)))
                        frames()
                        assertEquals(changedTemperature, modelPolicy.resolve(requireNotNull(settingsRequest.get()).appSettings, runtime.modelCatalog())?.temperature)
                        runtime.pluginManager.setEnabled(providerId, true)
                        frames()
                        assertEquals(changedTemperature, resolvedTemperature.get())
                        requireNotNull(settingsRequest.get()).onDismiss()
                        frames()
                        assertTrue("Message kcode…" in semanticsLabels(scene))
                    }
                    settingsProbe.dispose()

                    val configuredLocale = localizationDeployment.copy(config = Json.parseToJsonElement(
                        """{"translations":{"en":{"message_placeholder":"Private composer"}}}""",
                    ))
                    runtime.pluginManager.replace(configuredLocale)
                    frames()
                    assertTrue("Private composer" in semanticsLabels(scene))
                    assertTrue("Message kcode…" !in semanticsLabels(scene))
                    val retainedLocale = slots.snapshot().localization
                    assertFailsWith<IllegalStateException> {
                        runtime.pluginManager.replace(configuredLocale.copy(config = Json.parseToJsonElement("""{"defaultLanguage":"absent"}""")))
                    }
                    assertSame(retainedLocale, slots.snapshot().localization)
                    val fallbackRequest = AtomicReference<ai.meteor.kcode.plugin.ui.api.SettingsPageRequest?>(null)
                    val fallbackCatalog = AtomicReference<ai.meteor.kcode.localization.TranslationCatalog?>(null)
                    val fallbackModels = AtomicReference<ai.meteor.kcode.model.ModelCatalogSnapshot?>(null)
                    val fallbackProbe = slots.registerSettings(ai.meteor.kcode.plugin.ui.api.SettingsSection(
                        id = "test.localized-settings", order = -1000, icon = KcodeIconAsset.Settings,
                        title = { "Probe" }, description = { "" }, renderer = UiRenderer { },
                        texts = mapOf("test.feature.setting" to "Feature option"),
                        isVisible = { request ->
                            val catalog = ai.meteor.kcode.localization.LocalTranslationCatalog.current
                            val models = ai.meteor.kcode.plugin.ui.api.LocalModelCatalog.current
                            SideEffect { fallbackRequest.set(request); fallbackCatalog.set(catalog); fallbackModels.set(models) }
                            false
                        },
                    ))
                    runtime.pluginManager.setEnabled("feature.localization", false)
                    frames()
                    assertTrue("Private composer" !in semanticsLabels(scene))
                    assertTrue("Message kcode…" in semanticsLabels(scene))
                    assertEquals(ai.meteor.kcode.plugin.api.PluginState.Active,
                        runtime.diagnostics().plugins.single { it.id == "provider.ui.compose" }.state)
                    clickSemanticAction(scene, "Open settings")
                    frames()
                    val preparedSettings = requireNotNull(fallbackRequest.get())
                    assertTrue(preparedSettings.sections.none { it.id == "language" })
                    assertTrue(preparedSettings.sections.any { it.id == "model" })
                    assertTrue(preparedSettings.sections.any { it.id == "search" })
                    val heldCatalog = requireNotNull(fallbackCatalog.get())
                    val featureLabel = ai.meteor.kcode.localization.LocalizedText("test.feature.setting")
                    assertEquals("Feature option", heldCatalog.translate(ai.meteor.kcode.localization.AppLanguage.English, featureLabel))
                    val privateLayoutLoader = requireNotNull(slots.snapshot().layout).javaClass.classLoader
                    assertSame(privateLayoutLoader, Class.forName(
                        "ai.meteor.kcode.plugin.uitexts.uipages.BuiltinUiTextsKt", false, privateLayoutLoader,
                    ).classLoader)
                    val fallbackTheme = slots.snapshot().theme
                    for ((sectionId, expectedLabel) in listOf("model" to "Provider", "search" to "Search provider")) {
                        val section = preparedSettings.sections.single { it.id == sectionId }
                        val formScene = ImageComposeScene(width = 800, height = 600, coroutineContext = coroutineContext) {
                            CompositionLocalProvider(
                                ai.meteor.kcode.localization.LocalTranslationCatalog provides heldCatalog,
                                ai.meteor.kcode.localization.LocalAppLanguage provides ai.meteor.kcode.localization.AppLanguage.English,
                                ai.meteor.kcode.plugin.ui.api.LocalModelCatalog provides requireNotNull(fallbackModels.get()),
                            ) {
                                fallbackTheme?.Render {
                                    section.renderer.Render(ai.meteor.kcode.plugin.ui.api.SettingsSectionRequest(preparedSettings) { })
                                }
                            }
                        }
                        try {
                            repeat(8) { frame -> formScene.render(frame * 50000000L).close(); delay(50) }
                            assertTrue(expectedLabel in semanticsLabels(formScene), "Missing private $sectionId form label")
                        } finally { formScene.close() }
                    }
                    preparedSettings.onSettingsChange(modelPolicy.update(preparedSettings.appSettings,
                        requireNotNull(modelPolicy.resolve(preparedSettings.appSettings, runtime.modelCatalog())).copy(temperature = 0.55)))
                    frames()
                    assertEquals(0.55, resolvedTemperature.get())
                    fallbackProbe.dispose()
                    assertNull(heldCatalog.displayText(ai.meteor.kcode.localization.AppLanguage.English, featureLabel, emptyList()))
                    assertEquals("Settings", heldCatalog.translate(ai.meteor.kcode.localization.AppLanguage.English, ai.meteor.kcode.localization.UiText.Settings))
                    requireNotNull(fallbackRequest.get()).onDismiss()
                    runtime.pluginManager.setEnabled("feature.localization", true)
                    frames()
                    assertTrue("Private composer" in semanticsLabels(scene))
                    assertFailsWith<IllegalStateException> { heldCatalog.translate(ai.meteor.kcode.localization.AppLanguage.English, ai.meteor.kcode.localization.UiText.Settings) }
                    runtime.pluginManager.replace(localizationDeployment)
                    frames()
                    assertTrue("Message kcode…" in semanticsLabels(scene))


                } finally {
                    scene.close()
                }
                val snapshot = slots.snapshot()
                val message = ChatMessage(
                    id = 1L,
                    role = MessageRole.Assistant,
                    content = "Private transcript body",
                    toolUses = listOf(ToolUseInfo("tool", "shell", "{}", status = ToolUseStatus.Succeeded)),
                )
                lateinit var exportContext: ComposeConversationImageRenderContext
                val transcriptScene = ImageComposeScene(width = 800, height = 600, coroutineContext = coroutineContext) {
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
                try {
                    repeat(8) { frame ->
                        transcriptScene.render(frame * 50000000L).close()
                        delay(50)
                    }
                    val labels = semanticsLabels(transcriptScene)
                    assertTrue("Private transcript body" in labels, "Private message body was not rendered: $labels")
                    assertTrue("Shell" in labels, "Private tool renderer was not rendered: $labels")
                    val exported = imageRenderer.render(
                        ConversationImageRenderRequest(
                            "",
                            listOf(ConversationExportMessage(false, "**Markdown export body**", false)),
                            "truncated",
                        ),
                        exportContext,
                    )
                    assertEquals(1080, exported.image.width)
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
                    transcriptScene.close()
                }
            }
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
    private fun clickSemanticAction(imageScene: ImageComposeScene, label: String) {
        fun matches(node: androidx.compose.ui.semantics.SemanticsNode): Boolean =
            label in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } ||
                node.children.any(::matches)
        fun find(node: androidx.compose.ui.semantics.SemanticsNode): (() -> Boolean)? {
            if (matches(node)) {
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action?.let { return it }
            }
            return node.children.firstNotNullOfOrNull(::find)
        }
        val click = requireNotNull(find(semanticsOwner(imageScene).unmergedRootSemanticsNode)) { "Missing action: $label" }
        assertTrue(click())
    }

    private fun semanticsOwner(imageScene: ImageComposeScene): SemanticsOwner {
        fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target)
        }
        val scene = field(imageScene, "scene")
        val root = field(scene, "mainOwner")
        return root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
    }

    /** Read only the scene owned by this test; Compose 1.8 exposes no ImageComposeScene semantics API. */
    private fun semanticsLabels(imageScene: ImageComposeScene): List<String> {
        val owner = semanticsOwner(imageScene)
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
