package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin

class GenerationCompositionTest {
    @Test
    fun actualJarRequiresGenerationCleanupBeforeDisableRestoreAndUninstall(): Unit = runBlocking {
        val directory = Files.createTempDirectory("generation-owned-jar").toFile()
        val artifact = File(directory, "generation.jar")
        File(GenerationProviderPlugin::class.java.protectionDomain.codeSource.location.toURI()).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var runner: ChatGenerationRunner
        val capture = kcodePlugin(PluginDescriptor("test.generation", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-owned-generation", inject = dependencies(KcodeGeneration.Key)) { ctx, _ ->
                runner = ctx.require(KcodeGeneration.Key).runner
            }, Unit)
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture), pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        )
        var runtime = KcodePluginRuntime.create(configuration())
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        try {
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "provider.generation", version = "jar", artifactPath = artifact.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
                entryClass = GenerationProviderPlugin::class.java.name,
            ))
            val original = runner
            assertEquals("ai.meteor.kcode.plugin.execution.OwnedChatGenerationRunner", original.javaClass.name)
            assertNotSame(ChatGenerationRunner::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(GenerationProviderPlugin::class.java.classLoader, original.javaClass.classLoader)
            val response = withContext(Dispatchers.Main.immediate) {
                original.launch {
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            assertFailsWith<IllegalStateException> { runtime.close() }
                            assertFailsWith<IllegalStateException> { runtime.pluginManager.setEnabled("provider.generation", true) }
                            cleanupEntered.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
            }
            assertEquals(1, original.activeTasks.value)
            assertFailsWith<IllegalStateException> { runtime.pluginManager.setEnabled("provider.generation", false) }
            val cancellation = async { original.cancelAll(); response.join() }
            withTimeout(5_000) { cleanupEntered.await() }
            assertFalse(cancellation.isCompleted)
            assertFailsWith<IllegalStateException> { runtime.pluginManager.setEnabled("provider.generation", false) }
            releaseCleanup.complete(Unit)
            withTimeout(5_000) { cancellation.await() }
            runtime.pluginManager.setEnabled("provider.generation", false)
            assertTrue(response.isCancelled)
            assertEquals(0, original.activeTasks.value)
            assertFailsWith<IllegalStateException> { original.launch { error("retired task admitted") } }
            runtime.pluginManager.setEnabled("provider.generation", true)
            assertNotSame(original, runner)
            runtime.close()
            runtime = KcodePluginRuntime.create(configuration())
            val current = runner
            val finished = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main.immediate) { current.launch { finished.complete(Unit) } }.join()
            assertTrue(finished.isCompleted)
            runtime.pluginManager.uninstall("provider.generation")
            assertFailsWith<IllegalStateException> { current.launch { error("uninstalled task admitted") } }
        } finally {
            releaseCleanup.complete(Unit)
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    @Test
    fun generationTaskCannotRetireItsOwnScope(): Unit = runBlocking {
        lateinit var runner: ChatGenerationRunner
        val capture = kcodePlugin(PluginDescriptor("test.generation", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-generation-guard", inject = dependencies(KcodeGeneration.Key)) { ctx, _ ->
                runner = ctx.require(KcodeGeneration.Key).runner
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }), featurePlugins = listOf(capture),
        ))
        try {
            val result = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main.immediate) {
                runner.launch {
                    assertFailsWith<IllegalStateException> { runner.requireCanClose() }
                    assertFailsWith<IllegalStateException> { runtime.pluginManager.setEnabled("provider.generation", false) }
                    assertFailsWith<IllegalStateException> { runtime.close() }
                    result.complete(Unit)
                }
            }.join()
            assertTrue(result.isCompleted)
        } finally { runtime.close() }
    }
}
