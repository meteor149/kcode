package ai.meteor.kcode.plugin

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.dependencies
import org.cordis.EffectScope
import org.cordis.Plugin

class KcodePluginRuntimeTest {
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
        val id = "consumer.tools.goal"
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
        val model = ModelConfiguration(ModelProvider.Ollama, "fixture", "")
        fun generation(path: Path, answer: String) = spec(path, DynamicFixtureAgentPlugin::class.qualifiedName!!, answer).copy(id = id, config = answer)
        try {
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
            listOf("core/subagent", "core/goal", "core/schedule", "test/tools"),
            diagnostics.toolContributions,
        )
        assertEquals(listOf("kcode/default"), diagnostics.promptSections)
        assertEquals(listOf("koog-built-in-providers"), diagnostics.modelAdapters)
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
                name.startsWith("DynamicFixturePlugin") || name.startsWith("FailingDynamicFixturePlugin") || name.startsWith("DynamicFixtureAgentPlugin")
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
            override val availability = null
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = config
        })
    }
}
