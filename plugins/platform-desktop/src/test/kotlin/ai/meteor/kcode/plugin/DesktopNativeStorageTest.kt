package ai.meteor.kcode.plugin

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.history.DesktopNativeHistoryPlugin
import ai.meteor.kcode.plugin.settingsstorage.DesktopNativeSettingsPlugin
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlinx.coroutines.runBlocking
import org.cordis.dependencies
import org.cordis.plugin

class DesktopNativeStorageTest {
    @Test
    fun actualArtifactOwnsNativeStorageAndReopensDurableData(): Unit = runBlocking {
        val directory = Files.createTempDirectory("native-storage-jar").toFile()
        val artifact = File(directory, "storage.jar")
        val entries = mutableSetOf<String>()
        JarOutputStream(artifact.outputStream()).use { output ->
            listOf(
                DesktopNativeSettingsPlugin::class.java,
            ).map { File(it.protectionDomain.codeSource.location.toURI()) }
                .plus(File(requireNotNull(System.getProperty("kcode.history.packaged.jar"))))
                .distinct().forEach { source ->
                    ZipFile(source).use { zip ->
                        zip.entries().asSequence().filter { !it.isDirectory && entries.add(it.name) }.forEach { entry ->
                            output.putNextEntry(JarEntry(entry.name))
                            zip.getInputStream(entry).use { it.copyTo(output) }
                            output.closeEntry()
                        }
                    }
                }
        }
        val workspace = File(directory, "workspace").apply { mkdirs() }
        check(artifact.setReadOnly())
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
        lateinit var settings: AppSettingsStore
        lateinit var history: ConversationHistoryRepository
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            profile = KcodePluginProfile(includeDefaults = false),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(kcodePlugin(
                PluginDescriptor("test.capture", "test", "test", emptySet()),
                plugin<Unit>(name = "capture-storage", inject = dependencies(KcodeSettings.Key, KcodeHistory.Key)) { ctx, _ ->
                    settings = ctx.require(KcodeSettings.Key).store
                    history = ctx.require(KcodeHistory.Key).repository
                }, Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        val classes = listOf(DesktopNativeSettingsPlugin::class.java, DesktopNativeHistoryPlugin::class.java)
        val paths = listOf(File(directory, "settings.preferences_pb"), File(directory, "history.db"), workspace)
        val specs = classes.mapIndexed { index, entry -> DynamicPluginSpec(
            id = "fixture.storage.$index", version = "native", entryClass = entry.name,
            artifactPath = artifact.path, sha256 = digest, config = paths[index].absolutePath,
        ) }
        try {
            specs.forEach { runtime.pluginManager.install(it) }
            // The coordinator is shared SDK; its borrowed storage implementation stays private.
            assertEquals(AppSettingsStore::class.java.classLoader, settings.javaClass.classLoader)
            val settingsLoader = settings.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
                .get(settings).javaClass.classLoader
            assertNotSame(classes[0].classLoader, settingsLoader)
            assertNotSame(classes[1].classLoader, history.javaClass.classLoader)
            assertEquals(history.javaClass.classLoader, Class.forName(
                "androidx.room3.Room", false, history.javaClass.classLoader,
            ).classLoader)
            assertEquals(history.javaClass.classLoader, Class.forName(
                "androidx.collection.LruCache", false, history.javaClass.classLoader,
            ).classLoader)
            assertEquals(Class.forName("androidx.sqlite.driver.bundled.BundledSQLiteDriver"), Class.forName(
                "androidx.sqlite.driver.bundled.BundledSQLiteDriver", false, history.javaClass.classLoader,
            ))
            assertEquals(settingsLoader, Class.forName(
                "ai.meteor.kcode.plugin.settingsstorage.DefaultSettingsKt", false, settingsLoader,
            ).classLoader)
            assertEquals(StoredAppSettings(), settings.load())
            val saved = LegacySettings(language = "en", namespaces = mapOf(
                "private.feature/v2" to (Json.parseToJsonElement(
                    """{"credential":"private-fixture","unknown":[null,{"key.with.dots":""}]}""",
                ) as JsonObject),
            ))
            settings.save(saved)
            history.appendMessage(1, "native", 1, "User", "durable")
            val oldSettings = settings
            val oldHistory = history
            specs.forEach { runtime.pluginManager.setEnabled(it.id, false) }
            assertFailsWith<IllegalStateException> { oldSettings.load() }
            assertFailsWith<IllegalStateException> { oldHistory.loadAll() }
            specs.forEach { runtime.pluginManager.setEnabled(it.id, true) }
            assertNotSame(oldSettings, settings)
            assertNotSame(oldHistory, history)
            assertEquals(saved, settings.load())
            assertEquals("durable", history.loadAll().single().messages.single().content)
            val lastSettings = settings
            val lastHistory = history
            specs.forEach { runtime.pluginManager.uninstall(it.id) }
            assertFailsWith<IllegalStateException> { lastSettings.load() }
            assertFailsWith<IllegalStateException> { lastHistory.loadAll() }
        } finally {
            try { runtime.close() } finally { artifact.setWritable(true); directory.deleteRecursively() }
        }
    }
}
