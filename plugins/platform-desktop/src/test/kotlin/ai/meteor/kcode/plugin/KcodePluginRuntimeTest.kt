@file:OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)

package ai.meteor.kcode.plugin

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.shell.AgentShellTool
import ai.meteor.kcode.AgentToolContext
import ai.meteor.kcode.MultiAgentCoordinator
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginCompositionStore
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.DesktopPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.plugin.api.PluginCodeOrigin
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellResult
import ai.meteor.kcode.plugin.feature.desktopShellToolPlugin
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.swing.Swing
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

class KcodePluginRuntimeTest {
    @Test
    fun optionalFeatureWithdrawalKeepsCoreAgentActiveAndRetractsSubagentTools() = runTest {
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(interactionPolicy = testInteractionPolicy()))
        try {
            for (id in listOf("provider.skills.platform", "feature.subagents", "core.conversation-overlays")) {
                runtime.pluginManager.setEnabled(id, false)
                assertEquals(PluginState.Active, runtime.diagnostics().plugins.single { it.id == "provider.agent-loop.koog" }.state, id)
                if (id == "feature.subagents") assertFalse("core/subagent" in runtime.diagnostics().toolContributions)
                runtime.pluginManager.setEnabled(id, true)
                assertEquals(PluginState.Active, runtime.diagnostics().plugins.single { it.id == "provider.agent-loop.koog" }.state, id)
            }
            assertTrue("core/subagent" in runtime.diagnostics().toolContributions)
        } finally { runtime.close() }
    }

    @Test
    fun failedReplacementCannotScheduleAnUnmanagedBackgroundReload() = runTest {
        val directory = createTempDirectory("kcode-managed-hmr")
        val jar = fixtureJar(directory.resolve("plugin.jar"))
        lateinit var shell: ShellBackend
        val capture = kcodePlugin(PluginDescriptor("test.managed-hmr", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-managed-hmr", inject = dependencies(KcodeShell.Key)) { ctx, _ ->
                shell = ctx.require(KcodeShell.Key).executor
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = testInteractionPolicy(), featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory.toFile())
            },
        ))
        try {
            runtime.pluginManager.install(spec(jar, DynamicFixtureShellPlugin::class.java.name, "1").copy(config = "first"))
            val committedLoader = shell.javaClass.classLoader
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(spec(jar, FailingDynamicFixturePlugin::class.java.name, "broken"))
            }
            assertTrue(shell.javaClass.classLoader === committedLoader)
            // Cordis' debounce scope uses real Dispatchers.Default time, independent of runTest.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(350) }
            assertTrue(shell.javaClass.classLoader === committedLoader)
            assertEquals("first:stable", shell.run(ai.meteor.kcode.plugin.api.ShellRequest("stable")).output)
        } finally {
            runtime.close()
            jar.toFile().setWritable(true)
            Files.delete(jar)
            Files.delete(directory)
        }
    }

    @Test
    fun privateJarHostInputLeasesRevokeAndRestoreWithCurrentWindow() = kotlinx.coroutines.runBlocking {
        val directory = createTempDirectory("kcode-host-input-jar")
        val jar = fixtureJar(directory.resolve("plugin.jar"))
        val firstWindow = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Swing) { java.awt.Frame("first") }
        val secondWindow = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Swing) { java.awt.Frame("second") }
        var currentWindow: java.awt.Frame? = firstWindow
        var lookups = 0
        var inputs = DesktopPluginHostInputs { lookups += 1; currentWindow }
        lateinit var shell: ShellBackend
        val capture = kcodePlugin(PluginDescriptor("test.host-capture", "test", "test", emptySet()),
            plugin<Unit>(name = "host-capture", inject = dependencies(KcodeShell.Key)) { ctx, _ ->
                shell = ctx.require(KcodeShell.Key).executor
            }, Unit)
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = testInteractionPolicy(), hostInputs = inputs, featurePlugins = listOf(capture),
            pluginCompositionStore = FilePluginCompositionStore(directory.toFile()),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory.toFile())
            },
        )
        suspend fun window() = shell.run(ai.meteor.kcode.plugin.api.ShellRequest("window")).output
        var runtime = KcodePluginRuntime.create(configuration())
        try {
            runtime.pluginManager.install(spec(jar, DynamicFixtureHostInputPlugin::class.java.name, "1").copy(config = "ok"))
            assertEquals("first", window())
            currentWindow = secondWindow
            assertEquals("second", window())
            val retired = shell
            runtime.pluginManager.setEnabled("fixture", false)
            val before = lookups
            assertFailsWith<IllegalStateException> { retired.run(ai.meteor.kcode.plugin.api.ShellRequest("stale")) }
            assertEquals(before, lookups)
            runtime.pluginManager.setEnabled("fixture", true)
            assertEquals("second", window())
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(spec(jar, DynamicFixtureHostInputPlugin::class.java.name, "2").copy(config = "fail"))
            }
            assertEquals("second", window())
            runtime.close()
            assertFailsWith<IllegalStateException> { inputs.applicationWindow() }
            inputs = DesktopPluginHostInputs { currentWindow }
            runtime = KcodePluginRuntime.create(configuration())
            assertEquals("second", window())
            val beforeUninstall = shell
            runtime.pluginManager.uninstall("fixture")
            assertFailsWith<IllegalStateException> { beforeUninstall.run(ai.meteor.kcode.plugin.api.ShellRequest("stale")) }
            assertEquals(secondWindow, inputs.applicationWindow())
        } finally {
            runtime.close()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Swing) { firstWindow.dispose(); secondWindow.dispose() }
            directory.toFile().walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    @Test
    fun codeOriginTracksPrivateJarReplacementRollbackAndRestart() = runTest {
        val directory = createTempDirectory("kcode-code-origin")
        val first = fixtureJar(directory.resolve("first.jar"))
        val second = fixtureJar(directory.resolve("second.jar"))
        val broken = fixtureJar(directory.resolve("broken.jar"))
        lateinit var shell: ShellBackend
        val capture = kcodePlugin(
            PluginDescriptor("test.origin-capture", "test", "test", emptySet()),
            plugin<Unit>(name = "origin-capture", inject = dependencies(KcodeShell.Key)) { ctx, _ ->
                assertEquals(null, PluginCodeOrigin.current(ctx))
                shell = ctx.require(KcodeShell.Key).executor
            }, Unit,
        )
        val configuration = KcodePluginRuntimeConfig(
            interactionPolicy = testInteractionPolicy(), featurePlugins = listOf(capture),
            pluginCompositionStore = FilePluginCompositionStore(directory.toFile()),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory.toFile())
            },
        )
        suspend fun assertOrigin(path: Path, version: String, config: String) {
            assertEquals("${path.toFile().canonicalPath}|${sha256(path)}|$version|$config",
                shell.run(ai.meteor.kcode.plugin.api.ShellRequest("origin")).output)
        }
        var runtime = KcodePluginRuntime.create(configuration)
        try {
            runtime.pluginManager.install(spec(first, DynamicFixtureOriginPlugin::class.java.name, "1").copy(config = "first"))
            assertOrigin(first, "1", "first")
            runtime.pluginManager.replace(spec(second, DynamicFixtureOriginPlugin::class.java.name, "2").copy(config = "second"))
            assertOrigin(second, "2", "second")
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(spec(broken, FailingDynamicFixturePlugin::class.java.name, "3"))
            }
            assertOrigin(second, "2", "second")
            runtime.pluginManager.setEnabled("fixture", false)
            runtime.pluginManager.setEnabled("fixture", true)
            assertOrigin(second, "2", "second")
            runtime.close()
            runtime = KcodePluginRuntime.create(configuration)
            assertOrigin(second, "2", "second")
        } finally {
            runtime.close()
            directory.toFile().walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    @Test
    fun manifestRejectsInvalidPublicationAndCorruptionWithoutErasingCommittedState() = runTest {
        val directory = createTempDirectory("kcode-manifest")
        val store = FilePluginCompositionStore(directory.toFile())
        val committed = PluginCompositionSnapshot(builtinsEnabled = mapOf("provider.ui.settings.search" to false))
        val manifest = directory.resolve("plugin-installations.json")
        try {
            assertEquals(PluginCompositionSnapshot(), store.load())
            store.save(committed)
            assertEquals(committed, store.load())
            assertFailsWith<IllegalArgumentException> { store.save(committed.copy(formatVersion = 99)) }
            assertEquals(committed, store.load())
            manifest.writeBytes("{ invalid-json }".encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { store.load() }
            assertEquals("{ invalid-json }", Files.readString(manifest))
            manifest.writeBytes(ByteArray(1_048_577))
            assertFailsWith<IllegalArgumentException> { store.load() }
        } finally {
            Files.deleteIfExists(manifest)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun installedArtifactsAndDisabledBuiltinsSurviveRuntimeRestart() = runTest {
        val directory = createTempDirectory("kcode-plugin-manifest-test")
        val jar = fixtureJar(directory.resolve("plugin.jar"))
        val store = FilePluginCompositionStore(directory.toFile())
        val configuration = KcodePluginRuntimeConfig(
            interactionPolicy = testInteractionPolicy(),
            pluginCompositionStore = store,
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                DesktopDynamicPluginController(context, loader, inventory, directory.toFile())
            },
        )
        val first = KcodePluginRuntime.create(configuration)
        try {
            first.pluginManager.setEnabled("feature.schedule", false)
            first.pluginManager.replace(spec(jar, DynamicFixturePluginV1::class.qualifiedName!!, "1").copy(id = "feature.goal"))
            first.pluginManager.setEnabled("feature.goal", false)
        } finally {
            first.close()
        }
        val restarted = KcodePluginRuntime.create(configuration)
        try {
            assertEquals(PluginState.Disabled, restarted.diagnostics().plugins.first { it.id == "feature.schedule" }.state)
            assertEquals(PluginState.Disabled, restarted.diagnostics().plugins.first { it.id == "feature.goal" }.state)
            assertFalse("external/fixture-v1" in restarted.diagnostics().toolContributions)
            assertEquals("1", restarted.pluginManager.installed().single().version)
            assertFalse(restarted.pluginManager.installed().single().enabled)
            restarted.pluginManager.setEnabled("feature.goal", true)
            assertTrue("external/fixture-v1" in restarted.diagnostics().toolContributions)
            restarted.pluginManager.uninstall("feature.goal")
            restarted.pluginManager.setEnabled("feature.goal", true)
        } finally {
            restarted.close()
        }
        val restoredBuiltin = KcodePluginRuntime.create(configuration)
        try {
            assertTrue("core/goal" in restoredBuiltin.diagnostics().toolContributions)
            assertTrue(restoredBuiltin.pluginManager.installed().isEmpty())
        } finally {
            restoredBuiltin.close()
            jar.toFile().setWritable(true)
            Files.delete(jar)
            Files.delete(directory.resolve("plugin-installations.json"))
            Files.delete(directory)
        }
    }

    @Test
    fun failedManifestPublicationRollsBackReplacementAndEnableState() = runTest {
        val directory = createTempDirectory("kcode-plugin-manifest-rollback")
        val firstJar = fixtureJar(directory.resolve("first.jar"))
        val secondJar = fixtureJar(directory.resolve("second.jar"))
        val store = FailingCompositionStore()
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = testInteractionPolicy(),
            pluginCompositionStore = store,
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                DesktopDynamicPluginController(context, loader, inventory, directory.toFile())
            },
        ))
        try {
            runtime.pluginManager.install(spec(firstJar, DynamicFixturePluginV1::class.qualifiedName!!, "1"))
            val committed = store.snapshot
            store.failNext = true
            assertFailsWith<java.io.IOException> {
                runtime.pluginManager.replace(spec(secondJar, DynamicFixturePluginV2::class.qualifiedName!!, "2"))
            }
            assertEquals(committed, store.snapshot)
            assertEquals("1", runtime.pluginManager.installed().single().version)
            assertTrue("external/fixture-v1" in runtime.diagnostics().toolContributions)
            assertFalse("external/fixture-v2" in runtime.diagnostics().toolContributions)
            store.failNext = true
            assertFailsWith<java.io.IOException> { runtime.pluginManager.setEnabled("feature.goal", false) }
            assertTrue("core/goal" in runtime.diagnostics().toolContributions)
            assertEquals(committed, store.snapshot)
            assertFailsWith<IllegalArgumentException> {
                runtime.pluginManager.replace(spec(secondJar, DynamicFixturePluginV2::class.qualifiedName!!, "wrong-api").copy(apiVersion = CurrentPluginApiVersion + 1))
            }
            assertFailsWith<IllegalArgumentException> {
                runtime.pluginManager.replace(
                    spec(secondJar, DynamicFixturePluginV2::class.qualifiedName!!, "old-api")
                        .copy(apiVersion = MinimumCompatiblePluginApiVersion - 1),
                )
            }
            assertEquals("1", runtime.pluginManager.installed().single().version)
            assertEquals(committed, store.snapshot)
        } finally {
            runtime.close()
            listOf(firstJar, secondJar).forEach { it.toFile().setWritable(true); Files.delete(it) }
            Files.delete(directory)
        }
    }

    @Test
    fun externalShellProviderUsesHostContractAndRebindsExistingToolConsumer() = runTest {
        val directory = createTempDirectory("kcode-shell-abi-test")
        val first = fixtureJar(directory.resolve("shell.jar"))
        lateinit var tools: KcodeTools
        val capture = kcodePlugin(
            ai.meteor.kcode.plugin.api.PluginDescriptor("test.shell-capture", "test", "test", emptySet()),
            plugin<Unit>(name = "shell-capture", inject = dependencies(KcodeTools.Key)) { ctx, _ -> tools = ctx.require(KcodeTools.Key) },
            Unit,
        )
        val original = kcodePlugin(
            ai.meteor.kcode.plugin.api.PluginDescriptor("provider.shell.platform", "test", "test", emptySet()),
            plugin<Unit>(name = "shell-original") { ctx, _ -> KcodeShell(ctx, ShellBackend { ShellResult("builtin", 0) }) },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = testInteractionPolicy(),
            featurePlugins = listOf(original, desktopShellToolPlugin(), capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                DesktopDynamicPluginController(context, loader, inventory, directory.toFile())
            },
        ))
        val turn = AgentToolContext("/root", MultiAgentCoordinator(backgroundScope, "fixture", runAgent = { "done" }), null, null, null)
        suspend fun execute() = tools.snapshot(turn).getTool("execute_shell_command").executeUnsafe(AgentShellTool.Args("hello")) as String
        try {
            assertTrue(execute().contains("builtin"))
            runtime.pluginManager.replace(spec(first, DynamicFixtureShellPlugin::class.qualifiedName!!, "external").copy(
                id = "provider.shell.platform", config = "external",
            ))
            assertTrue(execute().contains("external:hello"))
            runtime.pluginManager.uninstall("provider.shell.platform")
            assertFalse("consumer.tools.shell" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.setEnabled("provider.shell.platform", true)
            assertTrue(execute().contains("builtin"))
        } finally {
            runtime.close()
            first.toFile().setWritable(true)
            Files.delete(first)
            Files.delete(directory)
        }
    }

    @Test
    fun externalArtifactCanReplaceBuiltInAndOriginalCanBeRestored() = runTest {
        val directory = createTempDirectory("kcode-builtin-hmr-test")
        val first = fixtureJar(directory.resolve("first.jar"))
        val failed = fixtureJar(directory.resolve("failed.jar"))
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = testInteractionPolicy(),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                DesktopDynamicPluginController(context, loader, inventory, directory.toFile())
            },
        ))
        val id = "feature.goal"
        try {
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(spec(failed, FailingDynamicFixturePlugin::class.qualifiedName!!, "failed").copy(id = id))
            }
            assertTrue("core/goal" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.replace(spec(first, DynamicFixturePluginV1::class.qualifiedName!!, "1").copy(id = id))
            assertFalse("core/goal" in runtime.diagnostics().toolContributions)
            assertTrue("external/fixture-v1" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.setEnabled(id, false)
            assertFalse("external/fixture-v1" in runtime.diagnostics().toolContributions)
            assertEquals(PluginState.Disabled, runtime.diagnostics().plugins.first { it.id == id }.state)
            runtime.pluginManager.setEnabled(id, true)
            assertTrue("external/fixture-v1" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.setEnabled("core.tools", false)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == id }.state)
            runtime.pluginManager.setEnabled("core.tools", true)
            assertTrue("external/fixture-v1" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.uninstall(id)
            runtime.pluginManager.setEnabled(id, true)
            assertTrue("core/goal" in runtime.diagnostics().toolContributions)
        } finally {
            runtime.close()
            listOf(first, failed).forEach { it.toFile().setWritable(true); Files.deleteIfExists(it) }
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun externalAgentProviderUsesHostAbiAndReplacementConfiguration() = runTest {
        val directory = createTempDirectory("kcode-agent-hmr-test")
        val first = fixtureJar(directory.resolve("first.jar"))
        val second = fixtureJar(directory.resolve("second.jar"))
        val failed = fixtureJar(directory.resolve("failed.jar"))
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = testInteractionPolicy(),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                DesktopDynamicPluginController(context, loader, inventory, directory.toFile())
            },
        ))
        val id = "provider.agent-loop.koog"
        val model = ModelConfiguration(ModelProvider.Ollama, "fixture", "", temperature = 0.6)
        fun generation(path: Path, answer: String) = spec(path, DynamicFixtureAgentPlugin::class.qualifiedName!!, answer).copy(id = id, config = answer)
        try {
            assertFailsWith<IllegalArgumentException> {
                runtime.pluginManager.install(generation(first, "reject").copy(id = "test.disabled-validator", enabled = false))
            }
            assertTrue(runtime.pluginManager.installed().isEmpty())
            assertFalse(runtime.diagnostics().plugins.any { it.id == "test.disabled-validator" })
            runtime.pluginManager.replace(generation(first, "first"))
            assertEquals("first", runtime.chatService.reply(model, emptyList(), "hello"))
            runtime.pluginManager.replace(generation(second, "second"))
            assertEquals("second", runtime.chatService.reply(model, emptyList(), "hello"))
            assertFailsWith<IllegalStateException> { runtime.pluginManager.replace(generation(failed, "reject")) }
            assertEquals("second", runtime.chatService.reply(model, emptyList(), "hello"))
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(spec(failed, FailingDynamicFixturePlugin::class.qualifiedName!!, "failed").copy(id = id))
            }
            assertEquals("second", runtime.chatService.reply(model, emptyList(), "hello"))
        } finally {
            runtime.close()
            listOf(first, second, failed).forEach { it.toFile().setWritable(true); Files.deleteIfExists(it) }
            Files.deleteIfExists(directory)
        }
    }
    @Test
    fun assembledRuntimePublishesServiceProvidersAndFeatureConsumers() = runTest {
        val feature = kcodePlugin(
            descriptor = PluginDescriptor(
                id = "test.tools",
                version = "test",
                source = "test",
                capabilities = setOf("tools"),
            ),
            plugin = toolContributionPlugin("test/tools", ToolRegistry { }),
            config = Unit,
        )
        val runtime = KcodePluginRuntime.create(
            KcodePluginRuntimeConfig(
                interactionPolicy = testInteractionPolicy(),
                featurePlugins = listOf(feature),
            ),
        )

        val diagnostics = runtime.diagnostics()

        assertTrue(diagnostics.plugins.any { it.id == "core.tools" })
        assertTrue(diagnostics.plugins.any { it.id == "provider.agent-loop.koog" })
        assertTrue(diagnostics.plugins.any { it.id == "test.tools" })
        assertEquals(
            setOf("core/subagent", "core/goal", "core/schedule", "consumer.tools.web-search", "test/tools"),
            diagnostics.toolContributions.toSet(),
        )
        assertEquals(listOf("kcode/default"), diagnostics.promptSections)
        assertEquals(ai.meteor.kcode.model.ModelProvider.entries.map { "koog.${it.name}" }, diagnostics.modelAdapters)
        runtime.close()
    }

    @Test
    fun disposingFeatureFiberRemovesItsToolContribution() = runTest {
        val context = Context()
        val service = context.plugin(ToolsServicePlugin, Unit).await()
        val feature = context.plugin(toolContributionPlugin("temporary", ToolRegistry { }), Unit).await()

        assertEquals(listOf("temporary"), context.require(KcodeTools.Key).contributionIds())

        feature.dispose()

        assertTrue(context.require(KcodeTools.Key).contributionIds().isEmpty())
        service.dispose()
    }

    @Test
    fun desktopLoaderRejectsUntrustedArtifactWithoutPublishingInventory() = runTest {
        val directory = createTempDirectory("kcode-plugin-test")
        val artifact = directory.resolve("broken.jar")
        artifact.writeBytes(byteArrayOf(1, 2, 3))
        val runtime = KcodePluginRuntime.create(
            KcodePluginRuntimeConfig(
                interactionPolicy = testInteractionPolicy(),
                dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                    DesktopDynamicPluginController(context, loader, inventory, directory.toFile())
                },
            ),
        )
        val controller = checkNotNull(runtime.dynamicPlugins)

        assertFailsWith<IllegalArgumentException> {
            controller.install(
                DynamicPluginSpec(
                    id = "broken",
                    version = "1.0.0",
                    entryClass = "example.BrokenPlugin",
                    artifactPath = artifact.toString(),
                    sha256 = "0".repeat(64),
                ),
            )
        }

        assertTrue(controller.installed().isEmpty())
        assertFalse(runtime.inventory.snapshot().any { it.id == "broken" })

        assertFailsWith<IllegalArgumentException> {
            controller.install(
                DynamicPluginSpec(
                    id = "core.tools",
                    version = "1.0.0",
                    entryClass = "example.ShadowCorePlugin",
                    artifactPath = artifact.toString(),
                    sha256 = sha256(artifact),
                ),
            )
        }
        assertEquals(1, runtime.inventory.snapshot().count { it.id == "core.tools" })
        runtime.close()
        Files.deleteIfExists(artifact)
        Files.deleteIfExists(directory)
    }

    @Test
    fun desktopLoaderCommitsValidGenerationAndRollsBackFailedReplacement() = runTest {
        val directory = createTempDirectory("kcode-plugin-hmr-test")
        val first = fixtureJar(directory.resolve("first.jar"))
        val second = fixtureJar(directory.resolve("second.jar"))
        val broken = fixtureJar(directory.resolve("broken.jar"))
        val runtime = KcodePluginRuntime.create(
            KcodePluginRuntimeConfig(
                interactionPolicy = testInteractionPolicy(),
                dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                    DesktopDynamicPluginController(context, loader, inventory, directory.toFile())
                },
            ),
        )
        val controller = checkNotNull(runtime.dynamicPlugins)

        controller.install(spec(first, DynamicFixturePluginV1::class.qualifiedName!!, "1.0.0"))
        assertTrue("external/fixture-v1" in runtime.diagnostics().toolContributions)

        controller.replace(spec(second, DynamicFixturePluginV2::class.qualifiedName!!, "2.0.0"))
        assertFalse("external/fixture-v1" in runtime.diagnostics().toolContributions)
        assertTrue("external/fixture-v2" in runtime.diagnostics().toolContributions)

        assertFailsWith<IllegalStateException> {
            controller.replace(spec(broken, FailingDynamicFixturePlugin::class.qualifiedName!!, "3.0.0"))
        }
        assertEquals("2.0.0", controller.installed().single().version)
        assertTrue("external/fixture-v2" in runtime.diagnostics().toolContributions)

        controller.uninstall("fixture")
        assertTrue(controller.installed().isEmpty())
        assertFalse("external/fixture-v2" in runtime.diagnostics().toolContributions)
        assertFalse(runtime.inventory.snapshot().any { it.id == "fixture" })

        runtime.close()
        listOf(first, second, broken).forEach { path ->
            path.toFile().setWritable(true)
            Files.deleteIfExists(path)
        }
        Files.deleteIfExists(directory)
    }
}

private fun testInteractionPolicy() = InteractionPolicy(
    permissionModeProvider = { ToolPermissionMode.Bypass },
    approver = ToolCallApprover { true },
)

private fun spec(path: Path, entryClass: String, version: String) = DynamicPluginSpec(
    id = "fixture",
    version = version,
    entryClass = entryClass,
    artifactPath = path.toString(),
    sha256 = sha256(path),
    capabilities = setOf("tools"),
)

private fun fixtureJar(path: Path): Path {
    val classesRoot = Path.of(DynamicFixturePluginV1::class.java.protectionDomain.codeSource.location.toURI())
    val packagePath = Path.of("ai", "meteor", "kcode", "plugin")
    JarOutputStream(Files.newOutputStream(path)).use { jar ->
        Files.list(classesRoot.resolve(packagePath)).use { files ->
            files.filter { file ->
                val name = file.fileName.toString()
                name.startsWith("DynamicFixturePlugin") || name.startsWith("FailingDynamicFixturePlugin") || name.startsWith("DynamicFixtureAgentPlugin") || name.startsWith("DynamicFixtureShellPlugin") || name.startsWith("DynamicFixtureOriginPlugin") || name.startsWith("DynamicFixtureHostInputPlugin")
            }.forEach { file ->
                val entryName = classesRoot.relativize(file).toString().replace('\\', '/')
                jar.putNextEntry(JarEntry(entryName))
                Files.copy(file, jar)
                jar.closeEntry()
            }
        }
    }
    check(path.toFile().setReadOnly()) { "unable to make fixture JAR immutable" }
    return path
}

private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(path))
    .joinToString("") { "%02x".format(it) }

class DynamicFixturePluginV1 : Plugin<Unit> {
    override val name = "dynamic-fixture-v1"
    override val inject: Dependencies = dependencies(KcodeTools.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("external/fixture-v1", ToolRegistry { }))
    }
}

class DynamicFixturePluginV2 : Plugin<Unit> {
    override val name = "dynamic-fixture-v2"
    override val inject: Dependencies = dependencies(KcodeTools.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("external/fixture-v2", ToolRegistry { }))
    }
}

class FailingDynamicFixturePlugin : Plugin<Unit> {
    override val name = "dynamic-fixture-failing"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        error("fixture replacement failure")
    }
}

class DynamicFixtureAgentPlugin : Plugin<String> {
    override val name = "dynamic-fixture-agent"
    override val config = ConfigValidator<String> {
        require(it != "reject") { "invalid fixture configuration" }
        it
    }
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        KcodeAgents(ctx, object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = config
        })
    }
}

class DynamicFixtureShellPlugin : Plugin<String> {
    override val name = "dynamic-fixture-shell"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        KcodeShell(ctx, ShellBackend { request -> ShellResult("$config:${request.command}", 0) })
    }
}

private class FailingCompositionStore : PluginCompositionStore {
    var snapshot = PluginCompositionSnapshot()
    var failNext = false
    override suspend fun load() = snapshot
    override suspend fun save(snapshot: PluginCompositionSnapshot) {
        if (failNext) {
            failNext = false
            throw java.io.IOException("disk unavailable")
        }
        this.snapshot = snapshot
    }
}

class DynamicFixtureOriginPlugin : Plugin<String> {
    override val name = "dynamic-origin"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(javaClass.classLoader !== PluginCodeOrigin::class.java.classLoader)
        val origin = requireNotNull(PluginCodeOrigin.current(ctx))
        check(origin.dependencies.isEmpty())
        KcodeShell(ctx, ShellBackend {
            val artifact = origin.artifact
            ShellResult("${artifact.artifactPath}|${artifact.sha256}|${artifact.version}|$config", 0)
        })
    }
}

class DynamicFixtureHostInputPlugin : Plugin<String> {
    override val name = "dynamic-host-inputs"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(javaClass.classLoader !== PluginHostInputs::class.java.classLoader)
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? DesktopPluginHostInputs)
        if (config == "fail") error("host input fixture failure")
        KcodeShell(ctx, ShellBackend {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Swing) {
                ShellResult(inputs.applicationWindow()?.title ?: "no-window", 0)
            }
        })
    }
}
