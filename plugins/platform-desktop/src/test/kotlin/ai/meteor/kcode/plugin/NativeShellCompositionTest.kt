package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellRequest
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import ai.meteor.kcode.plugin.nativeexecution.DesktopNativeShellPlugin
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.desktopPackageHost
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cordis.dependencies
import org.cordis.packages.packageFileSha256
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class NativeShellCompositionTest {
    @Test
    fun actualArchiveOwnsProcessesAcrossIndependentActivationsAndFailedReplacement(): Unit = runBlocking {
        val directory = Files.createTempDirectory("native-shell-jar").toFile()
        val artifact = File(directory, "shell.jar")
        File(DesktopNativeShellPlugin::class.java.protectionDomain.codeSource.location.toURI()).copyTo(artifact)
        check(artifact.setReadOnly())
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
        val archive = File(directory, "shell.kplugin")
        javaClass.classLoader.bundledArchive("provider.shell.platform")
            .use { input -> archive.outputStream().use { input.copyTo(it) } }
        check(archive.setReadOnly())
        val workspace = File(directory, "workspace").apply { mkdirs() }
        val captured = mutableMapOf<String, ShellBackend>()
        suspend fun create(id: String) = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            profile = KcodePluginProfile(includeDefaults = false),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = (if (id == "a") listOf(kcodePlugin(
                PluginDescriptor("provider.shell.platform", "builtin", "native", setOf("shell")),
                DesktopNativeShellPlugin(), workspace.absolutePath,
            )) else emptyList()) + listOf(kcodePlugin(
                PluginDescriptor("provider.plugin-packages.platform", "test", "test", emptySet()),
                NativePluginPackagesPlugin(directory, desktopPackageHost()), Unit,
            ), kcodePlugin(
                PluginDescriptor("test.capture", "test", "test", emptySet()),
                plugin<Unit>(name = "capture-shell", inject = dependencies(KcodeShell.Key)) { ctx, _ ->
                    captured[id] = ctx.require(KcodeShell.Key).executor
                }, Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        val a = create("a")
        val b = create("b")
        val spec = DynamicPluginSpec(
            id = "provider.shell.platform", version = "jar", entryClass = DesktopNativeShellPlugin::class.java.name,
            artifactPath = artifact.path, sha256 = digest, config = workspace.absolutePath,
        )
        try {
            val builtin = captured.getValue("a")
            assertFailsWith<IllegalArgumentException> {
                a.pluginManager.replace(spec.copy(version = "invalid-builtin", config = "relative"))
            }
            assertSame(builtin, captured.getValue("a"))
            assertEquals(0, builtin.run(ShellRequest("echo builtin-preserved")).exitCode)
            assertFailsWith<IllegalArgumentException> {
                a.replacePlugin(kcodePlugin(
                    PluginDescriptor(spec.id, "invalid-typed", "native", setOf("shell")),
                    DesktopNativeShellPlugin(), "relative",
                ))
            }
            assertSame(builtin, captured.getValue("a"))
            val packageImport = PluginPackageImport(
                archive.absolutePath, packageFileSha256(archive),
                configuration = StoredPluginConfiguration.encode(workspace.absolutePath),
            )
            a.pluginManager.importPackages(listOf(packageImport))
            b.pluginManager.importPackages(listOf(packageImport))
            val first = captured.getValue("a")
            val other = captured.getValue("b")
            assertNotSame(DesktopNativeShellPlugin::class.java.classLoader, first.javaClass.classLoader)
            assertNotSame(first.javaClass.classLoader, other.javaClass.classLoader)
            assertEquals(0, first.run(ShellRequest("echo native-a")).exitCode)
            assertFailsWith<Throwable> { a.pluginManager.replace(spec.copy(version = "invalid", config = "relative")) }
            val retained = captured.getValue("a")
            assertSame(first, retained, "Invalid configuration must not retire the current generation")
            assertFailsWith<IllegalArgumentException> {
                a.pluginManager.install(spec.copy(id = "fixture.disabled-invalid", config = "relative", enabled = false))
            }
            assertFalse(a.pluginManager.installed().any { it.id == "fixture.disabled-invalid" })
            assertTrue(retained.run(ShellRequest("echo retained-generation")).output.contains("retained-generation"))

            val source = File(workspace, "OwnedShellProcess.java")
            source.writeText("""
                class OwnedShellProcess {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
                        Thread.sleep(60000);
                    }
                }
            """.trimIndent())
            val ready = File(workspace, "running.pid")
            val java = File(System.getProperty("java.home"), "bin/${if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"}")
            val command = "\"${java.absolutePath}\" \"${source.absolutePath}\" \"${ready.absolutePath}\""
            val running = async(Dispatchers.IO) { retained.run(ShellRequest(command)) }
            withTimeout(15_000) { while (!ready.exists() || ready.readText().isBlank()) delay(10) }
            val process = ProcessHandle.of(ready.readText().trim().toLong()).orElseThrow()
            assertTrue(process.isAlive)
            assertFailsWith<IllegalStateException> {
                a.pluginManager.replace(spec.copy(version = "invalid-running", config = "relative"))
            }
            assertSame(retained, captured.getValue("a"))
            assertTrue(process.isAlive, "Pure config rejection must preserve active native work")
            assertFalse(running.isCompleted)
            withTimeout(10_000) { a.pluginManager.setEnabled(spec.id, false) }
            withTimeout(10_000) { running.join() }
            assertTrue(running.isCancelled)
            assertFalse(process.isAlive)
            assertFailsWith<IllegalStateException> { retained.run(ShellRequest("echo stale")) }
            assertEquals(0, other.run(ShellRequest("echo independent-b")).exitCode)
            a.pluginManager.setEnabled(spec.id, true)
            val restored = captured.getValue("a")
            assertNotSame(first, restored)
            assertEquals(0, restored.run(ShellRequest("echo restored")).exitCode)
            a.pluginManager.uninstall(spec.id)
            assertFailsWith<IllegalStateException> { restored.run(ShellRequest("echo stale")) }
            assertEquals(0, other.run(ShellRequest("echo still-b")).exitCode)
        } finally {
            try { a.close() } finally {
                try { b.close() } finally {
                    artifact.setWritable(true)
                    archive.setWritable(true)
                    directory.deleteRecursively()
                }
            }
        }
    }
}
