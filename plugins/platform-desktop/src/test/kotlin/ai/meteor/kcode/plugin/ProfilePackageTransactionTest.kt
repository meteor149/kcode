package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.desktopPackageHost
import ai.meteor.kcode.plugin.packages.kcodeConfigurationExtension
import ai.meteor.kcode.plugin.packages.kcodeVariantExtension
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.packages.PackageFile
import org.cordis.packages.PackageRuntime
import org.cordis.packages.PackageTarget
import org.cordis.packages.PackageVariant
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.PluginPackageManifest
import org.cordis.packages.packageFileSha256

class ProfilePackageTransactionTest {
    @Test
    fun unrelatedPackageKeepsItsResourcesAcrossAnotherReleaseReplacement() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-package-independent").toFile()
        val repository = FileProfileRepository(File(root, "profiles"))
        val output = File(root, "first.txt")
        val otherOutput = File(root, "other.txt")
        val old = raw(root, "1", output)
        val unrelated = old.copy(id = "example.unrelated", config = otherOutput.absolutePath)
        val runtime = start(root, repository, definition(entry(old), entry(unrelated)), listOf(old, unrelated))
        try {
            otherOutput.writeText("retained resource")
            runtime.pluginManager.replace(raw(root, "2", output, ProfileJarFixtureUpdated::class.java.name))
            assertEquals("2:updated", output.readText())
            assertEquals("retained resource", otherOutput.readText())
            assertEquals("1", runtime.pluginManager.installed().single { it.id == unrelated.id }.version)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun mixedRemovalInsertionAndEnableChangePublishOneGeneration() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-package-batch").toFile()
        val repository = FileProfileRepository(File(root, "profiles"))
        val oldOutput = File(root, "old.txt")
        val newOutput = File(root, "new.txt")
        val old = raw(root, "1", oldOutput)
        val next = old.copy(id = "example.next", config = newOutput.absolutePath)
        val runtime = start(root, repository, definition(entry(old)), listOf(old))
        try {
            runtime.pluginManager.applyChanges(PluginCompositionChange(upserts = listOf(next), removals = setOf(old.id),
                enabled = mapOf(next.id to false)))
            assertEquals(2L, repository.loadCommitted("test")!!.generation)
            assertEquals(listOf(next.id), runtime.pluginManager.installed().map { it.id })
            assertEquals("closed", oldOutput.readText())
            assertFalse(newOutput.exists())
            runtime.pluginManager.setEnabled(next.id, true)
            assertEquals("example.next:active", newOutput.readText())
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun cancellationDuringCandidateAllocationRestoresOldGenerationAndReleasesMutationOwner() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-package-cancel").toFile()
        val repository = FileProfileRepository(File(root, "profiles"))
        val output = File(root, "instance.txt")
        val old = raw(root, "1", output)
        val runtime = start(root, repository, definition(entry(old)), listOf(old))
        try {
            val before = repository.loadCommitted("test")
            val job = launch { runtime.pluginManager.replace(raw(root, "2", output, ProfileJarFixtureSuspends::class.java.name)) }
            try {
                withTimeout(10_000) { while (output.readText() != "staging") delay(10) }
            } finally { job.cancelAndJoin() }
            assertEquals("example.jar:active", output.readText())
            assertEquals(before, repository.loadCommitted("test"))
            assertEquals("1", runtime.pluginManager.installed().single().version)
            runtime.pluginManager.setEnabled(old.id, false)
            assertEquals("closed", output.readText())
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun rawJarInstallReplaceAndIndependentRemovalCommitTogether() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-package-edit").toFile()
        val repository = FileProfileRepository(File(root, "profiles"))
        val first = File(root, "first.txt")
        val second = File(root, "second.txt")
        val old = raw(root, "1", first)
        val definition = definition(ProfileEntry("example.jar", old.id, JsonPrimitive(first.absolutePath), configurationKind = "string"),
            ProfileEntry("other", old.id, JsonPrimitive(second.absolutePath), configurationKind = "string"))
        val runtime = start(root, repository, definition, listOf(old))
        try {
            runtime.pluginManager.replace(raw(root, "2", first, ProfileJarFixtureUpdated::class.java.name))
            assertEquals("2:updated", first.readText())
            assertEquals("2:updated", second.readText())
            assertEquals("2", repository.loadCommitted("test")!!.composition.external.single().version)
            runtime.pluginManager.uninstall("example.jar")
            assertEquals("closed-v2", first.readText())
            assertEquals("2:updated", second.readText())
            assertEquals(listOf(old.id), runtime.pluginManager.installed().map { it.id })
            runtime.pluginManager.replace(raw(root, "3", first, ProfileJarFixtureUpdated::class.java.name))
            assertEquals("closed-v2", first.readText())
            assertEquals("3:updated", second.readText())
            assertFalse(runtime.inventory.snapshot().any { it.id == "example.jar" })
            runtime.pluginManager.uninstall("other")
            assertEquals("closed-v2", second.readText())
            assertTrue(runtime.pluginManager.installed().isEmpty())
            assertFalse(runtime.inventory.snapshot().any { it.id == "package:${old.id}" })
            runtime.pluginManager.install(old)
            assertEquals("example.jar:active", first.readText())
            assertEquals(6L, repository.loadCommitted("test")!!.generation)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun failedPackagePublicationRestoresOldCodeMetadataAndCanRetry() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-package-publish").toFile()
        val storage = FileProfileRepository(File(root, "profiles"))
        var refuse = false
        val repository = object : ProfileRepository by storage {
            override suspend fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?) {
                check(!refuse) { "publication refused" }
                storage.commit(value, expectedGeneration)
            }
        }
        val output = File(root, "instance.txt")
        val old = raw(root, "1", output)
        val runtime = start(root, repository, definition(entry(old)), listOf(old))
        try {
            val before = storage.loadCommitted("test")
            val candidate = raw(root, "2", output, ProfileJarFixtureUpdated::class.java.name)
            refuse = true
            assertFailsWith<IllegalStateException> { runtime.pluginManager.replace(candidate) }
            assertEquals(before, storage.loadCommitted("test"))
            assertEquals("example.jar:active", output.readText())
            assertEquals("1", runtime.pluginManager.installed().single().version)
            assertEquals("1", runtime.inventory.snapshot().single { it.id == old.id }.version)
            refuse = false
            runtime.pluginManager.replace(candidate)
            assertEquals("2:updated", output.readText())
            assertEquals(2L, storage.loadCommitted("test")!!.generation)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun failedCandidateAllocationRestoresAllInstancesAndAllowsNextGeneration() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-package-apply").toFile()
        val repository = FileProfileRepository(File(root, "profiles"))
        val output = File(root, "first.txt")
        val second = File(root, "second.txt")
        val old = raw(root, "1", output)
        val runtime = start(root, repository, definition(entry(old),
            ProfileEntry("other", old.id, JsonPrimitive(second.absolutePath), configurationKind = "string")), listOf(old))
        try {
            val before = repository.loadCommitted("test")
            assertFailsWith<IllegalStateException> { runtime.pluginManager.replace(raw(root, "2", output, ProfileJarFixtureBroken::class.java.name)) }
            assertEquals(before, repository.loadCommitted("test"))
            assertEquals("example.jar:active", output.readText())
            assertEquals("example.jar:active", second.readText())
            assertEquals(PluginState.Active, runtime.inventory.snapshot().single { it.id == "other" }.state)
            runtime.pluginManager.replace(raw(root, "3", output, ProfileJarFixtureUpdated::class.java.name))
            assertEquals("3:updated", second.readText())
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun invalidInstanceConfigurationDoesNotWithdrawOldCode() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-package-config").toFile()
        val repository = FileProfileRepository(File(root, "profiles"))
        val output = File(root, "instance.txt")
        val old = raw(root, "1", output)
        val runtime = start(root, repository, definition(entry(old)), listOf(old))
        try {
            assertFailsWith<IllegalArgumentException> { runtime.pluginManager.replace(old.copy(config = "bad")) }
            assertEquals("example.jar:active", output.readText())
            assertEquals(1L, repository.loadCommitted("test")!!.generation)
            runtime.pluginManager.setEnabled(old.id, false)
            assertEquals("closed", output.readText())
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun verifiedArchiveImportsUpdateLocksAndRestartTheCommittedProfile() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-package-archive").toFile()
        val repository = FileProfileRepository(File(root, "profiles"))
        val output = File(root, "instance.txt")
        val first = archive(root, "1", output, ProfileJarFixture::class.java.name)
        val second = archive(root, "2", output, ProfileJarFixtureUpdated::class.java.name)
        val runtime = start(root, repository, definition())
        try {
            runtime.pluginManager.importPackages(listOf(first))
            assertEquals("example.jar:active", output.readText())
            val firstLock = repository.loadCommitted("test")!!.lock.packages.single()
            assertEquals(first.sha256, firstLock.archiveSha256)
            runtime.pluginManager.importPackages(listOf(second))
            assertEquals("2.0.0:updated", output.readText())
            assertEquals(second.sha256, repository.loadCommitted("test")!!.lock.packages.single().archiveSha256)
            val republished = archive(File(root, "republished").also { it.mkdirs() }, "2", File(root, "another.txt"), ProfileJarFixtureUpdated::class.java.name)
            assertFailsWith<IllegalArgumentException> { runtime.pluginManager.importPackages(listOf(republished)) }
            assertEquals(second.sha256, repository.loadCommitted("test")!!.lock.packages.single().archiveSha256)
            assertEquals("2.0.0:updated", output.readText())
        } finally { runtime.close() }
        try {
            val committed = repository.loadCommitted("test")!!
            val restarted = start(root, repository, committed.definition, committed.composition.external.map { it.toSpec() }, committed.lock)
            try {
                assertEquals("2.0.0:updated", output.readText())
                assertEquals(second.sha256, repository.loadCommitted("test")!!.lock.packages.single().archiveSha256)
            } finally { restarted.close() }
        } finally { root.deleteRecursively() }
    }

    private fun definition(vararg entries: ProfileEntry) = ProfileDefinition(id = "test", patches = listOf(ProfileOperation.Insert(entries.toList())))
    private fun entry(spec: DynamicPluginSpec) = ProfileEntry(spec.id, spec.id, JsonPrimitive(spec.config as String), configurationKind = "string")

    private suspend fun start(root: File, repository: ProfileRepository, definition: ProfileDefinition,
        specs: List<DynamicPluginSpec> = emptyList(), lock: ProfileLock = ProfileLock()): KcodePluginRuntime =
        KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            profileActivation = ProfileActivation(ResolvedProfile(definition, ProfileCompiler().compile(definition, emptyList()), specs, lock),
                ProfileCompositionSession.open(repository, definition)),
            featurePlugins = listOf(kcodePlugin(PluginDescriptor("provider.plugin-packages.platform", "test", "test", emptySet()),
                NativePluginPackagesPlugin(root, desktopPackageHost(), "a".repeat(64)), Unit)),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory ->
                DesktopDynamicPluginController(context, loader, inventory, root)
            },
        ))

    private fun jar(root: File, version: String): File {
        val jar = File(root, "fixture-$version.jar")
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
        return jar
    }

    private fun raw(root: File, version: String, output: File, entryClass: String = ProfileJarFixture::class.java.name): DynamicPluginSpec {
        val jar = jar(root, version)
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        return DynamicPluginSpec("example.jar", version, entryClass, jar.absolutePath, digest, config = output.absolutePath)
    }

    private fun archive(root: File, version: String, output: File, entry: String): PluginPackageImport {
        val source = File(root, "archive-$version").also { it.mkdirs() }
        val jar = jar(root, version).copyTo(File(source, "plugin.jar"))
        val manifest = PluginPackageManifest(id = "example.jar", version = "$version.0.0",
            variants = listOf(PackageVariant("desktop", listOf("windows", "linux", "macos").map { PackageTarget(it, listOf("arm", "x86")) },
                PackageRuntime("jvm", entry, "17"), "plugin.jar", extensions = kcodeVariantExtension("a".repeat(64)))),
            files = listOf(PackageFile("plugin.jar", jar.length(), packageFileSha256(jar))),
            extensions = kcodeConfigurationExtension(StoredPluginConfiguration("string", JsonPrimitive(output.absolutePath))))
        val target = File(root, "release-$version.kplugin")
        val digest = PluginPackageArchive().pack(manifest, source, target)
        return PluginPackageImport(target.absolutePath, digest)
    }
}

class ProfileJarFixtureUpdated : Plugin<String> {
    override val config = ConfigValidator<String> { require(it != "bad"); it }
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(javaClass.classLoader !== PluginDescriptor::class.java.classLoader)
        val origin = requireNotNull(ai.meteor.kcode.plugin.api.PluginCodeOrigin.current(ctx))
        val file = File(config)
        file.writeText("${origin.artifact.version}:updated")
        effect.collect { file.writeText("closed-v2") }
    }
}

class ProfileJarFixtureBroken : Plugin<String> {
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) { error("candidate allocation failed") }
}

class ProfileJarFixtureSuspends : Plugin<String> {
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val file = File(config)
        file.writeText("staging")
        effect.collect { file.writeText("cancelled") }
        awaitCancellation()
    }
}
