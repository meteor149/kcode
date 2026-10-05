package ai.meteor.kcode.plugin

import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
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
import ai.meteor.kcode.plugin.feature.SkillToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.FilesystemToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.WebSearchToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.DesktopShellToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.AndroidShellToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.UbuntuShellToolConsumerPlugin
import ai.meteor.kcode.plugin.feature.skillToolPlugin
import ai.meteor.kcode.plugin.feature.filesystemToolPlugin
import ai.meteor.kcode.plugin.feature.webSearchToolPlugin
import ai.meteor.kcode.plugin.feature.desktopShellToolPlugin
import ai.meteor.kcode.plugin.feature.androidShellToolPlugin
import ai.meteor.kcode.plugin.feature.ubuntuShellToolPlugin
import ai.meteor.kcode.plugin.provider.HttpWebSearchProviderPlugin
import ai.meteor.kcode.plugin.provider.SearchSettingsProviderPlugin
import ai.meteor.kcode.plugin.websearch.WebSearchFeaturePlugin
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.tools.search.SearchSettingsPolicy
import ai.meteor.kcode.plugin.provider.webSearchProviderPlugin
import ai.meteor.kcode.plugin.export.ConversationImageRenderingPlugin
import ai.meteor.kcode.plugin.export.ConversationExportFeaturePlugin
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
import java.nio.file.Files
import java.util.jar.JarFile
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test

class FormalRuntimeEntryPrivateLoadingTest {
    @Test
    fun privateJarsOwnFormalToolsSearchAndExportEntries(): Unit = runBlocking { verifyEntries { true } }

    private suspend fun verifyEntries(select: (Entry) -> Boolean) {
        val directory = Files.createTempDirectory("formal-runtime").toFile()
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
                    return LegacySettings(webSearchProvider = "exa")
                }
                override suspend fun save(settings: StoredAppSettings) = Unit
            },
            featurePlugins = listOf(capture, fsFixture, shellFixture, filesystemToolPlugin(), desktopShellToolPlugin(),
                androidShellToolPlugin("Android fixture shell"), ubuntuShellToolPlugin("Ubuntu fixture shell"),
                skillToolPlugin()),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                capturedContext = ctx
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        val entries = listOf(
            Entry("consumer.tools.skill", SkillToolConsumerPlugin::class.java),
            Entry("consumer.tools.filesystem", FilesystemToolConsumerPlugin::class.java),
            Entry("consumer.tools.shell", DesktopShellToolConsumerPlugin::class.java),
            Entry("consumer.tools.android-shell", AndroidShellToolConsumerPlugin::class.java, "Android fixture shell"),
            Entry("consumer.tools.ubuntu-shell", UbuntuShellToolConsumerPlugin::class.java, "Ubuntu fixture shell"),
            Entry("feature.web-search", WebSearchFeaturePlugin::class.java),
            Entry("feature.conversation-export", ConversationExportFeaturePlugin::class.java),
        ).filter(select)
        suspend fun contribution(id: String): Any? = when (id) {
            "feature.conversation-export" -> capturedContext[KcodeConversationExport.Key]?.exporter
            "feature.web-search" -> capturedContext[KcodeWebSearch.Key]?.backend
            else -> if (id in tools.contributionIds()) tools else null
        }
        try {
            for ((id, entry, configuration) in entries) {
                val source = File(entry.protectionDomain.codeSource.location.toURI())
                val artifact = File(directory, "${entry.simpleName}.jar")
                if (id == "feature.web-search") {
                    val dependencies = System.getProperty("kcode.private.search.classpath").split(File.pathSeparator).filter(String::isNotBlank).map(::File)
                    bundle(listOf(source) + dependencies, artifact)
                } else source.copyTo(artifact)
                check(artifact.setReadOnly())
                val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
                val spec = DynamicPluginSpec(id = id, version = "private-runtime", artifactPath = artifact.path,
                    sha256 = digest, entryClass = entry.name, config = configuration,
                )
                runtime.pluginManager.replace(spec)
                val oldSearchPolicy = capturedContext[KcodeSearchSettings.Key]?.policy
                val registered = requireNotNull(contribution(id)) { "Missing $id" }
                if (id == "feature.conversation-export" || id == "feature.web-search") {
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
                if (id == "feature.web-search") {
                    val policy = capturedContext[KcodeSearchSettings.Key]!!.policy
                    val loader = policy.javaClass.classLoader
                    assertNotSame(SearchSettingsProviderPlugin::class.java.classLoader, loader)
                    assertSame(loader, Class.forName("ai.meteor.kcode.plugin.searchhttp.HttpSearchSettingsPolicy", false, loader).classLoader)
                    val configured = policy.resolve(LegacySettings(webSearchProvider = "exa", exaSearchApiKey = "fixture"))
                    assertEquals("fixture", configured.apiKeys["exa"])
                    assertEquals(listOf("google", "exa", "bright_data"), policy.providers()?.map { it.id })
                    assertSame(loader, Class.forName("ai.meteor.kcode.plugin.searchsettings.ui.SearchSettingsRenderer", false, loader).classLoader)
                    assertTrue(capturedContext[ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key]!!.snapshot().settingsSections.any { it.id == "search" })
                }
                assertFailsWith<IllegalStateException> { runtime.pluginManager.replace(spec.copy(version = "invalid", config = null)) }
                assertSame(registered, contribution(id))
                if (id == "feature.web-search") {
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
                if (id == "feature.web-search") {
                    val old = oldSearchPolicy!!
                    assertEquals(null, old.providers())
                    assertFailsWith<IllegalStateException> { old.resolve(StoredAppSettings()) }
                    assertEquals(null, capturedContext[KcodeWebSearch.Key])
                    val remainingUi = capturedContext[ai.meteor.kcode.plugin.ui.api.KcodeUiSlots.Key]!!.snapshot()
                    assertTrue(remainingUi.settingsSections.none { it.id == "search" })
                    assertTrue(remainingUi.settings != null)
                    assertEquals(PluginState.Active, runtime.diagnostics().plugins.single { it.id == "consumer.settings.commands" }.state)
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
    private fun bundle(sources: List<File>, artifact: File) {
        val entries = linkedMapOf<String, ByteArray>()
        val services = linkedMapOf<String, MutableSet<String>>()
        sources.forEach { source -> JarFile(source).use { jar ->
            jar.entries().asSequence().filter { !it.isDirectory && it.name != "META-INF/MANIFEST.MF" }.forEach { entry ->
                val data = jar.getInputStream(entry).use { it.readBytes() }
                if (entry.name.startsWith("META-INF/services/")) {
                    services.getOrPut(entry.name) { linkedSetOf() }.addAll(data.decodeToString().lines().filter { it.isNotBlank() })
                } else entries.putIfAbsent(entry.name, data)
            }
        } }
        services.forEach { (name, lines) -> entries[name] = lines.joinToString("\n", postfix = "\n").encodeToByteArray() }
        JarOutputStream(artifact.outputStream()).use { output -> entries.forEach { (name, data) ->
            output.putNextEntry(JarEntry(name)); output.write(data); output.closeEntry()
        } }
    }

}
