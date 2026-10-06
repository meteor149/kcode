package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.DynamicPluginSpec
import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageInstallation
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.StoredDynamicPlugin
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilePortableImportTest {
    private val bundle = ProfileBundle(id = "base", version = "1", patches = listOf(
        ProfileOperation.Insert(listOf(ProfileEntry("original", "agent"))),
    ))
    private val definition = ProfileDefinition(id = "source", bundles = listOf(ProfileBundleReference("base", "1")))
    private val release = DynamicPluginSpec(
        "agent", "1", "fixture.Agent", "local.jar", "a".repeat(64),
        packageInstallation = PluginPackageInstallation("local.kplugin", "1".repeat(64), "desktop", "2".repeat(64)),
    )
    private val lock = profileLock(PluginCompositionSnapshot(external = listOf(StoredDynamicPlugin.from(release))))
    private val text get() = Json.encodeToString(PortableProfileDocument(definition = definition, bundles = listOf(bundle), lock = lock))

    @Test
    fun oldDraftEnvelopeIsReadWithoutRewriteAndNewPublicationUpgradesIt(): Unit = runBlocking {
        val directory = Files.createTempDirectory("profile-draft-envelope")
        try {
            val repository = FileProfileRepository(directory.toFile())
            val legacy = ProfileDraftDocument(ProfileDefinition(id = "legacy"), formatVersion = 1)
            legacy.validate()
            val legacyText = Json.encodeToString(legacy)
            assertEquals(legacy, Json.decodeFromString<ProfileDraftDocument>(legacyText))
            repository.writeDraft(legacy, repository.state().revision, createOnly = true)
            assertEquals(3, repository.loadDraftDocument("legacy")!!.formatVersion)
            assertNull(repository.loadDraftDocument("legacy")!!.imported)
            assertFailsWith<IllegalArgumentException> {
                legacy.copy(imported = ProfilePortableExporter.decode(text)).validate()
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun importEditCloneAndRestartKeepFrozenMetadataUntilVerifiedPublication(): Unit = runBlocking {
        val directory = Files.createTempDirectory("profile-portable-import")
        try {
            val repository = FileProfileRepository(directory.toFile())
            var resolutions = 0
            var verifications = 0
            val resolver = object : PluginPackageResolver {
                override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> {
                    resolutions++
                    assertEquals("1".repeat(64), imports.single().sha256)
                    return listOf(release)
                }
                override suspend fun verify(spec: DynamicPluginSpec) { verifications++ }
            }
            // Same bundle ID/version, different contents in the installed catalogue.
            val changed = bundle.copy(patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("changed", "agent")))))
            suspend fun prepare(request: ProfileActivationRequest) = prepareNativeProfileActivation(
                repository, ProfileDefinition(id = "native"), listOf(changed), resolver,
                mapOf("agent" to ProfilePackageOffer(PluginPackageImport("local.kplugin", "1".repeat(64)))),
                setOf("agent"), requestedId = request.target.profileId, stageSwitch = true, activationRequest = request,
            )
            val management = ProfileManagement(repository, { listOf(changed) }, ::prepare)
            management.importPortable(text, "imported", "Imported", repository.state().revision)
            assertEquals(0, resolutions)
            assertEquals(0, verifications)
            assertNull(repository.selected())
            assertNull(repository.loadCommitted("imported"))
            assertEquals(ProfileDataScope(workspace = "profile"), repository.loadDraft("imported")!!.dataScope)
            val target = ProfileTarget("imported", ProfileSource.Draft)
            management.write(ProfileDraftWrite(repository.loadDraft("imported")!!.copy(displayName = "Edited"), repository.state().revision))
            repository.saveDraft(repository.loadDraft("imported")!!.copy(displayName = "Saved"))
            management.clone(ProfileCloneRequest(target, "copy", repository.state().revision))
            val reopened = FileProfileRepository(directory.toFile())
            assertEquals(bundle, reopened.loadDraftDocument("copy")!!.imported!!.bundles.single())
            assertEquals(lock, reopened.loadDraftDocument("copy")!!.imported!!.lock)
            val startup = prepareNativeProfileActivation(
                reopened, ProfileDefinition(id = "native"), emptyList(), resolver,
                mapOf("agent" to ProfilePackageOffer(PluginPackageImport("local.kplugin", "1".repeat(64)))),
                setOf("agent"), requestedId = "copy",
            )
            assertEquals("original", startup.resolved.composition.entries.single().id)
            assertEquals(lock, startup.resolved.lock)
            assertNull(repository.loadCommitted("copy"))
            val preview = management.preview(ProfileTarget("copy", ProfileSource.Draft))
            assertTrue(preview.packagesVerified)
            assertEquals("original", preview.entries.single().id)
            assertNull(repository.loadCommitted("copy"))
            val activation = prepare(ProfileActivationRequest(target, repository.state().revision))
            assertEquals(lock, activation.resolved.lock)
            assertEquals("original", activation.resolved.composition.entries.single().id)
            activation.session.commitDefinition(activation.resolved.definition,
                PluginCompositionSnapshot(external = activation.resolved.packages.map(StoredDynamicPlugin::from)), activation.resolved.bundles)
            activation.session.publishPreparedSwitch()
            assertEquals("imported", repository.selected())
            assertEquals(lock, repository.loadCommitted("imported")!!.lock)
            management.write(ProfileDraftWrite(repository.loadDraft("imported")!!, repository.state().revision))
            assertNotNull(repository.loadDraftDocument("imported")!!.base)
            assertNull(repository.loadDraftDocument("imported")!!.imported)
            assertFalse(management.exportPortable(ProfileTarget("imported")).contains("local.jar"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun importIsCreateOnlyAndRevisionCheckedWithoutPreparingPackages(): Unit = runBlocking {
        val directory = Files.createTempDirectory("profile-portable-import-cas")
        try {
            val repository = FileProfileRepository(directory.toFile())
            val management = ProfileManagement(repository, { emptyList() }) { error("Import must not prepare code") }
            val revision = repository.state().revision
            management.importPortable(text, "imported", "Imported", revision)
            val saved = repository.loadDraftDocument("imported")
            assertFailsWith<IllegalArgumentException> { management.importPortable(text, "another", "Stale", revision) }
            assertFailsWith<IllegalArgumentException> {
                management.importPortable(text, "imported", "Overwrite", repository.state().revision)
            }
            assertEquals(saved, repository.loadDraftDocument("imported"))
            assertNull(repository.loadDraft("another"))
            assertFailsWith<IllegalArgumentException> { management.exportPortable(ProfileTarget("imported", ProfileSource.Draft)) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun importedLockRejectsArchiveChangesAndBuiltinShadowBeforePackageResolution(): Unit = runBlocking {
        val resolver = object : PluginPackageResolver {
            override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> = error("Mismatched archive must not resolve")
            override suspend fun verify(spec: DynamicPluginSpec) = error("No verification expected")
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileResolver(resolver).resolve(definition, listOf(bundle),
                mapOf("agent" to ProfilePackageOffer(PluginPackageImport("wrong.kplugin", "3".repeat(64)))),
                setOf("agent"), expectedLock = lock)
        }
        assertFailsWith<IllegalStateException> {
            ProfileResolver(resolver).resolve(definition, listOf(bundle), emptyMap(), setOf("agent"), expectedLock = lock)
        }
    }

    @Test
    fun importedLockRejectsVariantVersionAbiAndDependencyIdentityAfterVerification(): Unit = runBlocking {
        val variants = listOf(
            release.copy(version = "2") to lock,
            release.copy(packageInstallation = release.packageInstallation!!.copy(variantId = "android")) to lock,
            release.copy(packageInstallation = release.packageInstallation!!.copy(runtimeAbi = "3".repeat(64))) to lock,
            release to ProfileLock(packages = listOf(
                lock.packages.single().copy(dependencies = mapOf("storage" to "1")),
                lock.packages.single().copy(id = "storage"),
            )),
        )
        for ((candidate, expected) in variants) {
            var verified = false
            val resolver = object : PluginPackageResolver {
                override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>) = listOf(candidate)
                override suspend fun verify(spec: DynamicPluginSpec) { verified = true }
            }
            assertFailsWith<IllegalArgumentException> {
                ProfileResolver(resolver).resolve(definition, listOf(bundle),
                    mapOf("agent" to ProfilePackageOffer(PluginPackageImport("local.kplugin", "1".repeat(64)))),
                    emptySet(), expectedLock = expected)
            }
            assertTrue(verified)
        }
    }
}
