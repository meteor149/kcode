package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.profiles.CommittedProfileGeneration
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileActivation
import ai.meteor.kcode.plugin.profiles.ProfileCompiler
import ai.meteor.kcode.plugin.profiles.ProfileCompositionSession
import ai.meteor.kcode.plugin.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.profiles.ProfileEntry
import ai.meteor.kcode.plugin.profiles.ProfileLock
import ai.meteor.kcode.plugin.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.ProfileRepository
import ai.meteor.kcode.plugin.profiles.ResolvedProfile
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.ServiceKey
import org.cordis.dependencies
import org.cordis.plugin

class ProfileRuntimeTest {
    @Test
    fun enableTransactionsWithdrawAndRecoverOnlyTheSelectedInstance() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-enabled").toFile()
        val repository = FileProfileRepository(root)
        val observed = mutableListOf<String>()
        val released = mutableListOf<String>()
        val answer = ServiceKey<String>("profileEnableAnswer")
        val provider = kcodePlugin(descriptor("example.provider"),
            plugin<String>(validator = ConfigValidator { it }) { context, value ->
                context.provide(answer, value)
                collect { released += value }
            }, "default")
        val consumer = kcodePlugin(descriptor("example.consumer"),
            plugin<Unit>(inject = dependencies(answer)) { context, _ -> observed += context.require(answer) }, Unit)
        fun group(id: String, value: String) = ProfileEntry(id, "core.group", children = listOf(
            ProfileEntry("$id-provider", "example.provider", JsonPrimitive(value), configurationKind = "string"),
            ProfileEntry("$id-consumer", "example.consumer"),
        ), isolate = mapOf(answer.name to null))
        val runtime = start(repository, definition(group("first", "one"), group("second", "two")), listOf(provider, consumer))
        try {
            runtime.pluginManager.setEnabled("first-provider", false)
            assertEquals(listOf("one"), released)
            assertEquals(PluginState.Pending, runtime.inventory.snapshot().single { it.id == "first-consumer" }.state)
            assertEquals(PluginState.Active, runtime.inventory.snapshot().single { it.id == "second-provider" }.state)
            assertEquals(ProfileOperation.Disable("first-provider"), repository.loadCommitted("test")!!.definition.patches.last())
            runtime.pluginManager.applyChanges(PluginCompositionChange(enabled = mapOf("first-provider" to true)))
            assertEquals(listOf("one", "two", "one"), observed)
            assertEquals(PluginState.Active, runtime.inventory.snapshot().single { it.id == "first-consumer" }.state)
            assertEquals(3L, repository.loadCommitted("test")!!.generation)
        } finally {
            runtime.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failedEnablePublicationRestoresRuntimeAndCommittedIntent() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-enabled-failure").toFile()
        val storage = FileProfileRepository(root)
        var refuse = false
        val repository = object : ProfileRepository by storage {
            override suspend fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?) {
                check(!refuse) { "publication refused" }
                storage.commit(value, expectedGeneration)
            }
        }
        var allocations = 0
        var releases = 0
        val provider = kcodePlugin(descriptor("example.provider"), plugin<Unit> { _, _ ->
            allocations++
            collect { releases++ }
        }, Unit)
        val runtime = start(repository, definition(ProfileEntry("provider", "example.provider")), listOf(provider))
        try {
            val before = storage.loadCommitted("test")
            refuse = true
            assertFailsWith<IllegalStateException> { runtime.pluginManager.setEnabled("provider", false) }
            assertEquals(before, storage.loadCommitted("test"))
            assertEquals(PluginState.Active, runtime.inventory.snapshot().single { it.id == "provider" }.state)
            assertEquals(1, allocations - releases)
            refuse = false
            runtime.pluginManager.setEnabled("provider", false)
            assertEquals(2L, storage.loadCommitted("test")!!.generation)
            assertEquals(allocations, releases)
        } finally {
            runtime.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidEnableBatchCannotPartiallyWithdrawProviders() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-enabled-invalid").toFile()
        val repository = FileProfileRepository(root)
        var releases = 0
        val provider = kcodePlugin(descriptor("example.provider"), plugin<Unit> { _, _ -> collect { releases++ } }, Unit)
        val runtime = start(repository, definition(ProfileEntry("provider", "example.provider")), listOf(provider))
        try {
            assertFailsWith<IllegalArgumentException> {
                runtime.pluginManager.applyChanges(PluginCompositionChange(enabled = mapOf("provider" to false, "missing" to false)))
            }
            assertEquals(0, releases)
            assertEquals(1L, repository.loadCommitted("test")!!.generation)
        } finally {
            runtime.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun launchDisableStillOutranksCommittedEnableIntent() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-enabled-overlay").toFile()
        val repository = FileProfileRepository(root)
        var allocations = 0
        val provider = kcodePlugin(descriptor("example.provider"), plugin<Unit> { _, _ -> allocations++ }, Unit)
        val runtime = start(repository, definition(ProfileEntry("provider", "example.provider")), listOf(provider),
            launchOverrides = listOf(ProfileOperation.Disable("provider")))
        try {
            runtime.pluginManager.setEnabled("provider", true)
            assertEquals(0, allocations)
            assertEquals(PluginState.Disabled, runtime.inventory.snapshot().single { it.id == "provider" }.state)
            assertEquals(ProfileOperation.Enable("provider"), repository.loadCommitted("test")!!.definition.patches.last())
        } finally {
            runtime.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyRuntimeCannotEnterDeclarativeStartupAfterCreation() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-startup-boundary").toFile()
        val repository = FileProfileRepository(root)
        val definition = definition()
        val activation = ProfileActivation(
            ResolvedProfile(definition, ProfileCompiler().compile(definition, emptyList()), emptyList(), ProfileLock()),
            ProfileCompositionSession.open(repository, definition),
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            profile = KcodePluginProfile(includeDefaults = false),
        ))
        try {
            assertFailsWith<IllegalStateException> { runtime.pluginManager.activateProfile(activation, emptyList()) }
            assertNull(repository.loadCommitted("test"))
        } finally {
            runtime.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun allConfigurationsAreValidatedBeforeAnyProviderAllocation() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-preflight").toFile()
        val repository = FileProfileRepository(root)
        var allocations = 0
        val module = kcodePlugin(descriptor("example.provider"),
            plugin<String>(validator = ConfigValidator { require(it != "invalid"); it }) { _, _ -> allocations++ },
            "default")
        try {
            assertFailsWith<IllegalArgumentException> {
                start(repository, definition(
                    ProfileEntry("valid", "example.provider"),
                    ProfileEntry("invalid", "example.provider", JsonPrimitive("invalid"), configurationKind = "string"),
                ), listOf(module))
            }
            assertEquals(0, allocations)
            assertNull(repository.loadCommitted("test"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun missingRequiredServicesCommitPendingConsumers(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-pending").toFile()
        val repository = FileProfileRepository(root)
        val module = kcodePlugin(descriptor("example.consumer"),
            plugin<Unit>(inject = dependencies(ServiceKey<String>("missing"))) { _, _ -> error("Must remain pending") }, Unit)
        try {
            val runtime = start(repository, definition(ProfileEntry("consumer", "example.consumer")), listOf(module))
            try {
                assertEquals(PluginState.Pending, runtime.inventory.snapshot().single { it.id == "consumer" }.state)
                assertNotNull(repository.loadCommitted("test"))
            } finally { runtime.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun groupsKeepProviderConfigurationAndServiceRealmsIndependent() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-realms").toFile()
        val repository = FileProfileRepository(root)
        val observed = mutableListOf<String>()
        val released = mutableListOf<String>()
        val answer = ServiceKey<String>("profileAnswer")
        val provider = kcodePlugin(descriptor("example.provider"),
            plugin<String>(validator = ConfigValidator { it }) { context, value ->
                context.provide(answer, value)
                collect { released += value }
            }, "default")
        val consumer = kcodePlugin(descriptor("example.consumer"),
            plugin<Unit>(inject = dependencies(answer)) { context, _ -> observed += context.require(answer) }, Unit)
        fun group(id: String, value: String) = ProfileEntry(id, "core.group", children = listOf(
            ProfileEntry("$id-provider", "example.provider", JsonPrimitive(value), configurationKind = "string"),
            ProfileEntry("$id-consumer", "example.consumer"),
        ), isolate = mapOf(answer.name to null))
        val definition = definition(group("first", "one"), group("second", "two"))
        try {
            val runtime = start(repository, definition, listOf(provider, consumer))
            try {
                assertEquals(listOf("one", "two"), observed)
                assertEquals(1L, repository.loadCommitted(definition.id)?.generation)
                assertEquals(PluginState.Active, runtime.inventory.snapshot().single { it.id == "first-provider" }.state)
            } finally { runtime.close() }
            assertEquals(setOf("one", "two"), released.toSet())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun failedPublisherClosesAllocationsAndLeavesNoCommittedGeneration() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-publication").toFile()
        val storage = FileProfileRepository(root)
        val repository = object : ProfileRepository by storage {
            override suspend fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?) {
                error("publication refused")
            }
        }
        var allocations = 0
        var releases = 0
        val provider = kcodePlugin(descriptor("example.provider"), plugin<Unit> { _, _ ->
            allocations++
            collect { releases++ }
        }, Unit)
        try {
            assertFailsWith<IllegalStateException> {
                start(repository, definition(ProfileEntry("provider", "example.provider")), listOf(provider))
            }
            assertEquals(allocations, releases)
            assertNull(storage.loadCommitted("test"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun realJarCreatesTwoInstancesWithDifferentConfigurationsAndCodeOrigins() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-private-jar").toFile()
        val repository = FileProfileRepository(File(root, "profiles"))
        val jar = File(root, "profile.jar")
        val classes = File(ProfileJarFixture::class.java.protectionDomain.codeSource.location.toURI()).toPath()
        JarOutputStream(jar.outputStream()).use { output ->
            Files.list(classes.resolve("ai/meteor/kcode/plugin")).use { paths ->
                paths.filter { it.fileName.toString().startsWith("ProfileJarFixture") }.forEach { path ->
                    output.putNextEntry(JarEntry(classes.relativize(path).toString().replace('\\', '/')))
                    Files.copy(path, output)
                    output.closeEntry()
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
            .joinToString("") { "%02x".format(it) }
        val spec = DynamicPluginSpec("example.jar", "1", ProfileJarFixture::class.java.name,
            jar.absolutePath, digest, config = "default")
        val first = File(root, "first.txt")
        val second = File(root, "second.txt")
        val definition = definition(
            ProfileEntry("example.jar", spec.id, JsonPrimitive(first.absolutePath), configurationKind = "string"),
            ProfileEntry("another-instance", spec.id, JsonPrimitive(second.absolutePath), configurationKind = "string"),
        )
        try {
            val runtime = start(repository, definition, packages = listOf(spec), trusted = root)
            try {
                assertEquals("example.jar:active", first.readText())
                assertEquals("example.jar:active", second.readText())
                assertEquals(listOf(spec.id), runtime.pluginManager.installed().map { it.id })
                assertNotNull(repository.loadCommitted("test"))
            } finally { runtime.close() }
            assertEquals("closed", first.readText())
            assertEquals("closed", second.readText())
        } finally { root.deleteRecursively() }
    }

    private suspend fun start(repository: ProfileRepository, definition: ProfileDefinition,
        modules: List<KcodePluginMount> = emptyList(), packages: List<DynamicPluginSpec> = emptyList(),
        trusted: File? = null, launchOverrides: List<ProfileOperation> = emptyList()): KcodePluginRuntime {
        val activation = ProfileActivation(
            ResolvedProfile(definition, ProfileCompiler().compile(definition, emptyList(), launchOverrides = launchOverrides),
                packages, ProfileLock(), launchOverrides = launchOverrides),
            ProfileCompositionSession.open(repository, definition),
        )
        return KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            profileActivation = activation,
            profileBuiltinModules = modules,
            dynamicPluginControllerFactory = trusted?.let { directory ->
                DynamicPluginControllerFactory { context, loader, inventory ->
                    DesktopDynamicPluginController(context, loader, inventory, directory)
                }
            },
        ))
    }

    private fun definition(vararg entries: ProfileEntry) = ProfileDefinition(id = "test",
        patches = listOf(ProfileOperation.Insert(entries.toList())))

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
}

class ProfileJarFixture : Plugin<String> {
    override val config = ConfigValidator<String> { require(it != "bad"); it }

    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(javaClass.classLoader !== PluginDescriptor::class.java.classLoader)
        val origin = requireNotNull(ai.meteor.kcode.plugin.api.PluginCodeOrigin.current(ctx))
        val file = File(config)
        file.writeText("${origin.artifact.id}:active")
        effect.collect { file.writeText("closed") }
    }
}
