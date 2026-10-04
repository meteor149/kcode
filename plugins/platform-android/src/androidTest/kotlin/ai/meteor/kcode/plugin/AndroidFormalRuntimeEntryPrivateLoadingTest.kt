package ai.meteor.kcode.plugin

import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.search.WebSearchBackend
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertFalse
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeFileSystem
import ai.meteor.kcode.plugin.api.FileSystemBackend
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellResult
import ai.meteor.kcode.plugin.api.KcodeConversationImageRendering
import ai.meteor.kcode.plugin.api.KcodeConversationExport
import ai.meteor.kcode.plugin.api.KcodeWebSearch
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.feature.ArtifactToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.SkillToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.FilesystemToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.WebContainerToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.WebSearchToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.DesktopShellToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.AndroidShellToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.UbuntuShellToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.artifactToolPlugin
import ai.meteor.kcode.plugin.feature.skillToolPlugin
import ai.meteor.kcode.plugin.feature.filesystemToolPlugin
import ai.meteor.kcode.plugin.feature.webContainerToolPlugin
import ai.meteor.kcode.plugin.feature.webSearchToolPlugin
import ai.meteor.kcode.plugin.feature.desktopShellToolPlugin
import ai.meteor.kcode.plugin.feature.androidShellToolPlugin
import ai.meteor.kcode.plugin.feature.ubuntuShellToolPlugin
import ai.meteor.kcode.plugin.provider.HttpWebSearchProviderPlugin
import ai.meteor.kcode.plugin.provider.SearchSettingsProviderPlugin
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.tools.search.SearchSettingsPolicy
import ai.meteor.kcode.plugin.provider.webSearchProviderPlugin
import ai.meteor.kcode.plugin.export.ConversationImageRenderingPlugin
import ai.meteor.kcode.plugin.export.ConversationExportPlugin
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AndroidFormalRuntimeEntryPrivateLoadingTest {
    @Test(timeout = 90000)
    fun privateApkFileArtifactSkillAndWebConsumers(): Unit = runBlocking { verifyEntries { it.id in setOf(
        "consumer.tools.artifact", "consumer.tools.skill", "consumer.tools.filesystem", "consumer.tools.web-container", "consumer.tools.web-search") } }

    @Test(timeout = 90000)
    fun privateApkShellSearchAndExportEntries(): Unit = runBlocking { verifyEntries { it.id !in setOf(
        "consumer.tools.artifact", "consumer.tools.skill", "consumer.tools.filesystem", "consumer.tools.web-container", "consumer.tools.web-search") } }

    private suspend fun verifyEntries(select: (Entry) -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "formal-runtime-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "runtime.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
        lateinit var tools: KcodeTools
        lateinit var capturedContext: Context
        val capture = kcodePlugin(PluginDescriptor("test.formal-runtime", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-formal-runtime", inject = dependencies(KcodeTools.Key)) { ctx, _ ->
                tools = ctx.require(KcodeTools.Key)
            }, Unit)
        val fsFixture = kcodePlugin(PluginDescriptor("test.formal-fs", "test", "test", emptySet()),
            plugin<Unit>(name = "formal-fs-fixture") { ctx, _ ->
                KcodeFileSystem(ctx, Proxy.newProxyInstance(FileSystemBackend::class.java.classLoader,
                    arrayOf(FileSystemBackend::class.java)) { _, _, _ -> error("Fixture is never executed") } as FileSystemBackend)
            }, Unit)
        val shellFixture = kcodePlugin(PluginDescriptor("test.formal-shell", "test", "test", emptySet()),
            plugin<Unit>(name = "formal-shell-fixture") { ctx, _ ->
                KcodeShell(ctx, ShellBackend { ShellResult("fixture", 0) })
                KcodeUbuntuShell(ctx, ShellBackend { ShellResult("fixture", 0) })
            }, Unit)
        val gateSettings = AtomicBoolean(false)
        val searchEntered = CompletableDeferred<Unit>()
        val searchCleaning = CompletableDeferred<Unit>()
        val releaseSearchCleanup = CompletableDeferred<Unit>()
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            settingsStore = object : AppSettingsStore {
                override val protection = SettingsProtection.Transient
                override suspend fun load(): StoredAppSettings {
                    if (gateSettings.get()) {
                        searchEntered.complete(Unit)
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) { searchCleaning.complete(Unit); releaseSearchCleanup.await() }
                        }
                    }
                    return StoredAppSettings(webSearchProvider = "exa")
                }
                override suspend fun save(settings: StoredAppSettings) = Unit
            },
            featurePlugins = listOf(capture, fsFixture, shellFixture, filesystemToolPlugin(), desktopShellToolPlugin(),
                androidShellToolPlugin("Android fixture shell"), ubuntuShellToolPlugin("Ubuntu fixture shell"),
                artifactToolPlugin(), skillToolPlugin(), webContainerToolPlugin(), webSearchProviderPlugin(), webSearchToolPlugin()),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                capturedContext = ctx
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val entries = listOf(
            Entry("consumer.tools.artifact", ArtifactToolConsumerPlugin::class.java),
            Entry("consumer.tools.skill", SkillToolConsumerPlugin::class.java),
            Entry("consumer.tools.filesystem", FilesystemToolConsumerPlugin::class.java),
            Entry("consumer.tools.web-container", WebContainerToolConsumerPlugin::class.java),
            Entry("consumer.tools.web-search", WebSearchToolConsumerPlugin::class.java),
            Entry("consumer.tools.shell", DesktopShellToolConsumerPlugin::class.java),
            Entry("consumer.tools.android-shell", AndroidShellToolConsumerPlugin::class.java, "Android fixture shell"),
            Entry("consumer.tools.ubuntu-shell", UbuntuShellToolConsumerPlugin::class.java, "Ubuntu fixture shell"),
            Entry("provider.search-settings.http", SearchSettingsProviderPlugin::class.java),
            Entry("provider.web.search-http", HttpWebSearchProviderPlugin::class.java),
            Entry("provider.export.image-rendering", ConversationImageRenderingPlugin::class.java),
            Entry("provider.export.conversation", ConversationExportPlugin::class.java),
        ).filter(select)
        suspend fun contribution(id: String): Any? = when (id) {
            "provider.export.image-rendering" -> capturedContext[KcodeConversationImageRendering.Key]?.renderer
            "provider.export.conversation" -> capturedContext[KcodeConversationExport.Key]?.exporter
            "provider.search-settings.http" -> capturedContext[KcodeSearchSettings.Key]?.policy
            "provider.web.search-http" -> capturedContext[KcodeWebSearch.Key]?.backend
            else -> if (id in tools.contributionIds()) tools else null
        }
        try {
            for ((id, entry, configuration) in entries) {
                val spec = DynamicPluginSpec(id = id, version = "private-runtime", artifactPath = artifact.path,
                    sha256 = digest, entryClass = entry.name, config = configuration,
                    packageName = instrumentation.context.packageName,
                )
                runtime.pluginManager.replace(spec)
                val registered = requireNotNull(contribution(id)) { "Missing $id" }
                if (id.startsWith("provider.export.") || id == "provider.web.search-http") {
                    val privateLoader = registered.javaClass.classLoader
                    assertNotSame(entry.classLoader, privateLoader)
                    for (contract in listOf(AgentPluginManager::class.java, DynamicPluginSpec::class.java,
                        KcodePluginMount::class.java, KcodePluginProfile::class.java)) {
                        assertSame(contract, Class.forName(contract.name, false, privateLoader))
                    }
                    for (facade in listOf("ai.meteor.kcode.plugin.KcodePluginCompositionKt", "ai.meteor.kcode.plugin.ToolsPluginsKt")) {
                        assertSame(KcodePluginMount::class.java.classLoader, Class.forName(facade, false, privateLoader).classLoader)
                    }
                }
                if (id == "provider.search-settings.http") {
                    val policy = registered as SearchSettingsPolicy
                    val loader = policy.javaClass.classLoader
                    assertNotSame(SearchSettingsProviderPlugin::class.java.classLoader, loader)
                    assertSame(loader, Class.forName("ai.meteor.kcode.plugin.searchhttp.HttpSearchSettingsPolicy", false, loader).classLoader)
                    val configured = policy.resolve(StoredAppSettings(webSearchProvider = "exa", exaSearchApiKey = "fixture"))
                    assertEquals("fixture", configured.apiKeys["exa"])
                    assertEquals(listOf("google", "exa", "bright_data"), policy.providers()?.map { it.id })
                }
                assertFailsWith<IllegalStateException> { runtime.pluginManager.replace(spec.copy(version = "invalid", config = null)) }
                assertSame(registered, contribution(id))
                if (id == "provider.web.search-http") {
                    val backend = registered as WebSearchBackend
                    val privateLoader = backend.javaClass.classLoader
                    for (type in listOf("WebSearchProvider", "WebSearchConfiguration", "WebSearchConfigurationKt")) {
                        val loaded = Class.forName("ai.meteor.kcode.plugin.searchhttp.$type", false, privateLoader)
                        assertSame(privateLoader, loaded.classLoader)
                        assertNotSame(HttpWebSearchProviderPlugin::class.java.classLoader, loaded.classLoader)
                    }
                    val missingKey = assertFailsWith<IllegalArgumentException> { backend.search("fixture", 1) }
                    assertTrue(missingKey.message.orEmpty().contains("Exa search is not configured"))
                    kotlinx.coroutines.coroutineScope {
                        gateSettings.set(true)
                        val search = async { backend.search("fixture", 1) }
                        withTimeout(5000) { searchEntered.await() }
                        val disabling = async { runtime.pluginManager.setEnabled(id, false) }
                        withTimeout(5000) { searchCleaning.await() }
                        assertFalse(disabling.isCompleted)
                        assertFailsWith<IllegalStateException> { backend.search("stale", 1) }
                        releaseSearchCleanup.complete(Unit)
                        withTimeout(5000) { disabling.await() }
                        assertTrue(search.isCancelled)
                        gateSettings.set(false)
                    }
                } else runtime.pluginManager.setEnabled(id, false)
                assertEquals(null, contribution(id))
                if (id == "provider.search-settings.http") {
                    val old = registered as SearchSettingsPolicy
                    assertEquals(null, old.providers())
                    assertFailsWith<IllegalStateException> { old.resolve(StoredAppSettings()) }
                    assertEquals(null, capturedContext[KcodeWebSearch.Key])
                    assertEquals(PluginState.Pending, runtime.diagnostics().plugins.single { it.id == "consumer.settings.commands" }.state)
                }
                runtime.pluginManager.setEnabled(id, true)
                assertTrue(contribution(id) != null)
            }
            if (entries.any { it.id == "consumer.tools.filesystem" }) {
                runtime.pluginManager.setEnabled("test.formal-fs", false)
                assertEquals(null, contribution("consumer.tools.filesystem"))
                assertEquals(PluginState.Pending, runtime.diagnostics().plugins.single { it.id == "consumer.tools.filesystem" }.state)
                runtime.pluginManager.setEnabled("test.formal-fs", true)
                assertTrue(contribution("consumer.tools.filesystem") != null)
            }
        } finally {
            releaseSearchCleanup.complete(Unit)
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    private data class Entry(val id: String, val entry: Class<*>, val configuration: Any = Unit)

}
