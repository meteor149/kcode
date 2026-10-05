package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactFileStore
import ai.meteor.kcode.artifact.ArtifactManifestPath
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.ArtifactResourcesRoot
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifact.SaveWebArtifactRequest
import ai.meteor.kcode.plugin.artifacts.androidArtifactFileStoreFactory
import ai.meteor.kcode.plugin.api.ArtifactFileStoreFactory
import ai.meteor.kcode.plugin.artifacts.FactoryFileArtifactsProviderPlugin
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.artifacts.FileArtifactsProviderPlugin
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidArtifactProviderTest {
    @Test(timeout = 60_000)
    fun externalApkOwnsTheFileRepositoryAndItsPrivateManifestCodec(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "artifact-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "artifact-provider.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var current: ArtifactRepository
        val capture = kcodePlugin(PluginDescriptor("test.capture", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-artifacts", inject = dependencies(KcodeArtifacts.Key),
        ) { ctx, _ -> current = ctx.require(KcodeArtifacts.Key).repository }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            profile = KcodePluginProfile(disabled = setOf("provider.artifacts.platform")),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            assertFails { runtime.artifactRepository.list() }
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.artifact-files", version = "test", entryClass = AndroidFixtureFileArtifacts::class.java.name,
                artifactPath = apk.path,
                sha256 = packageFileSha256(apk),
                packageName = instrumentation.context.packageName, config = "APK repository",
            ))
            val old = current
            assertTrue(old.javaClass.classLoader !== FileArtifactsProviderPlugin::class.java.classLoader)
            assertFalse(old is MutableArtifactRepository)
            val artifact = old.list().single()
            assertTrue(artifact.javaClass === Artifact::class.java)
            assertEquals("APK repository", artifact.name)
            assertEquals(listOf(artifact), runtime.artifactRepository.list())
            runtime.pluginManager.setEnabled("fixture.artifact-files", false)
            assertFailsWith<IllegalStateException> { old.list() }
            assertFails { runtime.artifactRepository.list() }
            runtime.pluginManager.setEnabled("fixture.artifact-files", true)
            assertTrue(old !== current)
            assertEquals("APK repository", current.list().single().name)
            runtime.pluginManager.uninstall("fixture.artifact-files")
            assertFails { runtime.artifactRepository.list() }
        } finally {
            runtime.close()
            apk.setWritable(true)
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 60_000)
    fun privateApkFactoryOwnsNativeFilesAndPersistsAcrossReload(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "artifact-native-apk-${System.nanoTime()}").apply { mkdirs() }
        val source = File(directory, "agent_workspace/draft/index.html").apply {
            parentFile!!.mkdirs(); writeText("<html>private native</html>")
        }
        val apk = File(directory, "native-artifact.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var current: ArtifactRepository
        val capture = kcodePlugin(PluginDescriptor("test.capture", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-private-native-artifact", inject = dependencies(KcodeArtifacts.Key),
        ) { ctx, _ -> current = ctx.require(KcodeArtifacts.Key).repository }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            profile = KcodePluginProfile(disabled = setOf("provider.artifacts.platform")), featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, instrumentation.targetContext, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.native-artifact", version = "test", entryClass = AndroidFixtureNativeArtifacts::class.java.name,
                artifactPath = apk.path, config = directory.path,
                sha256 = packageFileSha256(apk),
                packageName = instrumentation.context.packageName,
            ))
            assertTrue(current.javaClass.classLoader !== ArtifactRepository::class.java.classLoader)
            val previous = current as MutableArtifactRepository
            val saved = previous.saveWebApp(SaveWebArtifactRequest("private", "Private native", "/workspace/draft"))
            assertEquals(source.readText(), File(directory, "agent_workspace/artifacts/resources/private/index.html").readText())
            runtime.pluginManager.setEnabled("fixture.native-artifact", false)
            assertFailsWith<IllegalStateException> { previous.list() }
            runtime.pluginManager.setEnabled("fixture.native-artifact", true)
            assertEquals(listOf(saved), current.list())
            val beforeUninstall = current
            runtime.pluginManager.uninstall("fixture.native-artifact")
            assertFailsWith<IllegalStateException> { beforeUninstall.list() }
        } finally { runtime.close(); apk.setWritable(true); directory.deleteRecursively() }
    }

    @Test(timeout = 60_000)
    fun nativeFileBridgePersistsAnArtifactAcrossProviderRecreation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "artifact-native-${System.nanoTime()}").apply { mkdirs() }
        val scopedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = directory
        }
        val source = File(directory, "agent_workspace/draft/index.html").apply {
            parentFile!!.mkdirs()
            writeText("<html>native artifact</html>")
        }
        lateinit var current: ArtifactRepository
        val capture = kcodePlugin(PluginDescriptor("test.capture", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-native-artifacts", inject = dependencies(KcodeArtifacts.Key),
        ) { ctx, _ -> current = ctx.require(KcodeArtifacts.Key).repository }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            artifactFileStoreFactory = androidArtifactFileStoreFactory(scopedContext),
            featurePlugins = listOf(capture),
        ))
        try {
            val old = current as MutableArtifactRepository
            val saved = old.saveWebApp(SaveWebArtifactRequest("native", "Native", "/workspace/draft"))
            assertEquals(source.readText(), File(directory, "agent_workspace/artifacts/resources/native/index.html").readText())
            runtime.pluginManager.setEnabled("provider.artifacts.platform", false)
            assertFailsWith<IllegalStateException> { old.list() }
            runtime.pluginManager.setEnabled("provider.artifacts.platform", true)
            assertEquals(listOf(saved), current.list())
            assertEquals(listOf(saved), runtime.artifactRepository.list())
        } finally {
            runtime.close()
            directory.deleteRecursively()
        }
    }
}

class AndroidFixtureFileArtifacts : Plugin<String> {
    override val name = "fixture-file-artifacts"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        FileArtifactsProviderPlugin.apply(ctx, object : ArtifactFileStore {
            override suspend fun exists(path: String) = path == "$ArtifactResourcesRoot/apk/index.html"
            override suspend fun readText(path: String): String? = if (path == ArtifactManifestPath) {
                """{"version":1,"artifacts":[{"id":"apk","name":"$config","type":"web_app","directory":"apk"}]}"""
            } else null
        }, effect)
    }
}

class AndroidFixtureNativeArtifacts : Plugin<String> {
    override val name = "fixture-native-artifacts"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry", true,
            ArtifactFileStoreFactory::class.java.classLoader)
        val instrumentation = registry.getMethod("getInstrumentation").invoke(null) as android.app.Instrumentation
        val isolated = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = File(config)
        }
        val native = androidArtifactFileStoreFactory(isolated)
        FactoryFileArtifactsProviderPlugin.apply(ctx, ArtifactFileStoreFactory {
            native.create().also { resource ->
                check(resource.store.javaClass.classLoader !== ArtifactFileStoreFactory::class.java.classLoader)
            }
        }, effect)
    }
}
