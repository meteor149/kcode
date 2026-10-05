package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.AgentWorkspace
import ai.meteor.kcode.AgentWorkspaceEntry
import ai.meteor.kcode.plugin.api.FileContentKind
import ai.meteor.kcode.plugin.api.FileKind
import ai.meteor.kcode.plugin.api.FileSystemBackend
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeFileSystem
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.nativefilesystem.AndroidAgentFileSystem
import ai.meteor.kcode.plugin.nativefilesystem.AndroidPrivateAgentWorkspace
import ai.meteor.kcode.plugin.provider.PlatformFileSystemProviderPlugin
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidNativeFileSystemTest {
    @Test(timeout = 60_000)
    fun privateApkOwnsNativeFilesAndWorkspaceAndRevokesOldBackend(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-fs-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "native-fs.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var backend: FileSystemBackend
        val capture = kcodePlugin(PluginDescriptor("test.capture", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-native-fs", inject = dependencies(KcodeFileSystem.Key),
        ) { ctx, _ -> backend = ctx.require(KcodeFileSystem.Key).backend }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }), featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.native-fs", version = "test", entryClass = AndroidFixtureNativeFileSystem::class.java.name,
                artifactPath = apk.path, config = directory.path,
                sha256 = packageFileSha256(apk),
                packageName = instrumentation.context.packageName,
            ))
            assertTrue(backend.javaClass.classLoader !== FileSystemBackend::class.java.classLoader)
            val path = "/workspace/docs/native.txt"
            backend.writeBytes(path, "native-text".encodeToByteArray())
            assertEquals("native-text", backend.readBytes(path).decodeToString())
            assertEquals(FileKind.File, requireNotNull(backend.metadata(path)).kind)
            assertEquals(FileContentKind.Text, backend.contentKind(path))
            assertEquals(listOf(path), backend.list("/workspace/docs"))
            assertEquals("native-text", File(directory, "workspace/docs/native.txt").readText())
            assertFailsWith<IllegalArgumentException> { backend.normalize("/workspace/../escape") }
            val previous = backend
            runtime.pluginManager.setEnabled("fixture.native-fs", false)
            assertFailsWith<IllegalStateException> { previous.readBytes(path) }
            assertFailsWith<IllegalStateException> { previous.normalize(path) }
            runtime.pluginManager.setEnabled("fixture.native-fs", true)
            assertEquals("native-text", backend.readBytes(path).decodeToString())
            val beforeUninstall = backend
            runtime.pluginManager.uninstall("fixture.native-fs")
            assertFailsWith<IllegalStateException> { beforeUninstall.writeBytes(path, byteArrayOf()) }
        } finally { runtime.close(); apk.setWritable(true); directory.deleteRecursively() }
    }
}

class AndroidFixtureNativeFileSystem : Plugin<String> {
    override val name = "fixture-native-fs"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val root = Files.createDirectories(Path.of(config).resolve("workspace")).toRealPath()
        check(AndroidAgentFileSystem::class.java.classLoader !== FileSystemBackend::class.java.classLoader)
        check(AndroidPrivateAgentWorkspace::class.java.classLoader !== AgentWorkspace::class.java.classLoader)
        val workspace: AgentWorkspace = AndroidPrivateAgentWorkspace(root)
        workspace.writeText("/workspace/fixture.txt", "workspace")
        check(workspace.readText("/workspace/fixture.txt") == "workspace")
        check(workspace.canonicalize("/workspace/fixture.txt") == "/workspace/fixture.txt")
        check(workspace.list("/workspace").first().javaClass === AgentWorkspaceEntry::class.java)
        val outside = Files.createDirectories(Path.of(config).resolve("outside")).toRealPath()
        val escape = Files.createSymbolicLink(root.resolve("escape"), outside)
        try {
            check(runCatching { workspace.writeText("/workspace/escape/forbidden", "blocked") }
                .exceptionOrNull() is IllegalArgumentException)
            check(!Files.exists(outside.resolve("forbidden")))
        } finally { Files.delete(escape) }
        PlatformFileSystemProviderPlugin<Path>().apply(ctx, AndroidAgentFileSystem(root), effect)
    }
}
