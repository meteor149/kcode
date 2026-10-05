package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.DynamicPluginSpec
import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageInstallation
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.StoredDynamicPlugin
import ai.meteor.kcode.plugin.api.PluginCompositionStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileActivationPreparationTest {
    @Test
    fun localRawDescriptorsRestoreFromSnapshotWithoutClaimingArchiveLocks(): Unit = runBlocking {
        val local = spec("agent").copy(packageInstallation = null)
        val resolver = object : PluginPackageResolver {
            override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> = error("Do not import local snapshots")
            override suspend fun verify(spec: DynamicPluginSpec) = error("Raw descriptors are validated by the native loader")
        }
        val definition = ProfileDefinition(id = "coding", patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("agent", "agent")))))
        val restored = ProfileResolver(resolver).resolve(definition, emptyList(), emptyMap(), emptySet(), listOf(local))
        assertEquals(listOf(local), restored.packages)
        assertTrue(restored.lock.packages.isEmpty())
        assertEquals(local.sha256, restored.packages.single().sha256)
    }

    @Test
    fun lockedReleaseSurvivesBuiltinCatalogueShadowUnlessHostExplicitlyOverrides(): Unit = runBlocking {
        val release = spec("agent")
        val resolver = object : PluginPackageResolver {
            override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> = error("Do not re-resolve locks")
            override suspend fun verify(spec: DynamicPluginSpec) = Unit
        }
        val definition = ProfileDefinition(id = "coding", patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("agent", "agent")))))
        val locked = ProfileResolver(resolver).resolve(definition, emptyList(), emptyMap(), setOf("agent"), listOf(release))
        assertEquals(listOf(release), locked.packages)
        val borrowed = ProfileResolver(resolver).resolve(definition, emptyList(), emptyMap(), setOf("agent"), listOf(release),
            builtinOverrides = setOf("agent"))
        assertTrue(borrowed.packages.isEmpty())
    }

    @Test
    fun disabledInstancesDoNotWithdrawDependencyCodeOrPersistMachinePaths(): Unit = runBlocking {
        val agent = spec("agent", mapOf("storage" to "1"))
        val storage = spec("storage").copy(enabled = false, config = "/previous/machine/path")
        val bundle = ProfileBundle(id = "base", version = "1", patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("agent", "agent"), ProfileEntry("storage", "storage"),
        ))))
        val template = ProfileDefinition(id = "native", bundles = listOf(ProfileBundleReference("base", "1")))
        val migrated = migrateLegacyProfile(template, listOf(bundle), PluginCompositionSnapshot(
            external = listOf(StoredDynamicPlugin.from(agent.copy(enabled = false)), StoredDynamicPlugin.from(storage)),
        ), machineConfiguredPackages = setOf("storage"))
        val resolver = object : PluginPackageResolver {
            override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> = error("Locked releases need no re-resolution")
            override suspend fun verify(spec: DynamicPluginSpec) = Unit
        }
        val result = ProfileResolver(resolver).resolve(migrated, listOf(bundle), emptyMap(), emptySet(), listOf(agent, storage))
        assertTrue(result.packages.all { it.enabled })
        assertTrue(result.composition.entries.all { it.disabled == true })
        assertEquals(null, result.composition.entries.single { it.id == "storage" }.config)
    }

    @Test
    fun nativeRestartRetainsCommittedBundleContentAndPackageLock(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-frozen-restart")
        val repository = FileProfileRepository(root.toFile())
        val candidate = spec("agent")
        var resolutions = 0
        val resolver = object : PluginPackageResolver {
            override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> {
                resolutions++
                return listOf(candidate)
            }
            override suspend fun verify(spec: DynamicPluginSpec) = Unit
        }
        val bundle = ProfileBundle(id = "base", version = "1", patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("original", "agent"),
        ))))
        val template = ProfileDefinition(id = "coding", bundles = listOf(ProfileBundleReference("base", "1")))
        try {
            val first = prepareNativeProfileActivation(repository, template, listOf(bundle), resolver,
                mapOf("agent" to ProfilePackageOffer(PluginPackageImport("first.kplugin", "1".repeat(64)))), emptySet())
            first.session.commitDefinition(first.resolved.definition,
                PluginCompositionSnapshot(external = first.resolved.packages.map(StoredDynamicPlugin::from)), first.resolved.bundles)
            val changed = bundle.copy(patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("changed", "agent")))))
            val restarted = prepareNativeProfileActivation(repository, template, listOf(changed), resolver,
                mapOf("agent" to ProfilePackageOffer(PluginPackageImport("new.kplugin", "3".repeat(64)))), emptySet())
            assertEquals(1, resolutions)
            assertEquals("original", restarted.resolved.composition.entries.single().id)
            assertEquals(first.resolved.lock, restarted.resolved.lock)
            assertEquals(listOf(bundle), restarted.resolved.bundles)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun spec(id: String, dependencies: Map<String, String> = emptyMap()) = DynamicPluginSpec(
        id = id, version = "1", entryClass = "fixture.$id", artifactPath = "/local/$id.jar", sha256 = "0".repeat(64),
        packageInstallation = PluginPackageInstallation("/local/$id.kplugin", "1".repeat(64), "desktop", "2".repeat(64), dependencies),
    )

    @Test
    fun bootstrapMigratesOnceAndPrefersLastCommitOverBrokenDrafts() = runBlocking {
        val root = Files.createTempDirectory("profile-bootstrap")
        val repository = FileProfileRepository(root.toFile())
        val bundle = ProfileBundle(id = "base", version = "1", patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("core", "core")))))
        val template = ProfileDefinition(id = "native", bundles = listOf(ProfileBundleReference("base", "1")))
        var loads = 0
        val legacy = object : PluginCompositionStore {
            override suspend fun load(): PluginCompositionSnapshot { loads++; return PluginCompositionSnapshot(builtinsEnabled = mapOf("core" to false)) }
            override suspend fun save(snapshot: PluginCompositionSnapshot) { error("Legacy must remain untouched") }
        }
        val initial = prepareProfileBootstrap(repository, template, listOf(bundle), legacy)
        assertTrue(initial.migrating)
        assertTrue(ProfileCompiler().compile(initial.definition, listOf(bundle)).entries.single().disabled == true)
        initial.session.save(initial.session.load())
        repository.saveDraft(template.copy(patches = listOf(ProfileOperation.Disable("missing"))))
        val restarted = prepareProfileBootstrap(repository, template, listOf(bundle), legacy)
        assertEquals(initial.definition, restarted.definition)
        assertEquals(1, loads)
    }

    @Test
    fun failedInitialMigrationRetainsLegacyInstallationsForNextActivation() = runBlocking {
        val root = Files.createTempDirectory("profile-bootstrap-retry")
        val repository = FileProfileRepository(root.toFile())
        val installed = PluginCompositionSnapshot(external = listOf(StoredDynamicPlugin.from(spec("agent"))))
        val legacy = object : PluginCompositionStore {
            override suspend fun load() = installed
            override suspend fun save(snapshot: PluginCompositionSnapshot) { error("Legacy must remain untouched") }
        }
        val template = ProfileDefinition(id = "native")
        prepareProfileBootstrap(repository, template, emptyList(), legacy)
        val retry = prepareProfileBootstrap(repository, template, emptyList(), legacy)
        assertTrue(retry.migrating)
        assertEquals(installed, retry.session.load())
        assertEquals(null, repository.loadCommitted("native"))
    }

    @Test
    fun selectedPackagesResolveWithTheirDependencyClosureAndKeepEntryIdentity() = runBlocking {
        val parent = spec("agent", mapOf("core" to "1"))
        val core = spec("core")
        val requests = mutableListOf<PluginPackageImport>()
        val verified = mutableListOf<String>()
        val resolver = object : PluginPackageResolver {
            override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> {
                requests += imports
                return listOf(parent, core)
            }
            override suspend fun verify(spec: DynamicPluginSpec) { verified += spec.id }
        }
        val definition = ProfileDefinition(id = "coding", patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry(id = "scope", packageId = "core.group", children = listOf(
                ProfileEntry(id = "primary-agent", packageId = "agent", config = JsonPrimitive("custom"), configurationKind = "string"),
            )),
        ))))
        val result = ProfileResolver(resolver).resolve(definition, emptyList(), mapOf(
            "agent" to ProfilePackageOffer(PluginPackageImport("agent.kplugin", "1".repeat(64)), setOf("core")),
            "core" to ProfilePackageOffer(PluginPackageImport("core.kplugin", "1".repeat(64))),
            "unused" to ProfilePackageOffer(PluginPackageImport("unused.kplugin", "1".repeat(64))),
        ), emptySet())
        assertEquals(listOf("agent.kplugin", "core.kplugin"), requests.map { it.archivePath })
        assertEquals(listOf("core", "agent"), result.packages.map { it.id })
        assertEquals(listOf("core", "agent"), verified)
        val group = result.composition.entries.single()
        assertEquals("scope", group.id)
        val entry = (group.config as List<*>).single() as org.cordis.loader.EntryOptions
        assertEquals("primary-agent", entry.id)
        assertEquals("custom", profileConfiguration(entry)?.decode())
    }

    @Test
    fun migrationPreservesDisabledProvidersScalarConfigurationAndExplicitUninstall() {
        val bundle = ProfileBundle(id = "native", version = "1", patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("core", "core"), ProfileEntry("agent", "agent"), ProfileEntry("removed", "removed"),
        ))))
        val template = ProfileDefinition(id = "native", bundles = listOf(ProfileBundleReference("native", "1")))
        val external = spec("agent").copy(config = "historical", enabled = false)
        val legacy = PluginCompositionSnapshot(
            builtinsEnabled = mapOf("old-core" to false),
            external = listOf(StoredDynamicPlugin.from(external)),
            bundledPackages = mapOf("removed" to "1".repeat(64)),
        )
        val migrated = migrateLegacyProfile(template, listOf(bundle), legacy, mapOf("old-core" to setOf("core")))
        val compiled = ProfileCompiler().compile(migrated, listOf(bundle)).requireValid()
        assertEquals(setOf("core", "agent"), compiled.entries.map { it.id }.toSet())
        assertTrue(compiled.entries.all { it.disabled == true })
        assertEquals("historical", profileConfiguration(compiled.entries.single { it.id == "agent" })?.decode())
        assertEquals(mapOf("old-core" to false), legacy.builtinsEnabled)
        assertEquals("historical", legacy.external.single().configuration.decode())
    }

    @Test
    fun sessionPublishesThroughCompositionStoreAndRestartsFromLastCommit() = runBlocking {
        val root = Files.createTempDirectory("profile-session")
        val repository = FileProfileRepository(root.toFile())
        val definition = ProfileDefinition(id = "coding")
        val session = ProfileCompositionSession.open(repository, definition)
        val snapshot = PluginCompositionSnapshot(external = listOf(StoredDynamicPlugin.from(spec("agent"))))
        session.save(snapshot)
        val edited = definition.copy(displayName = "Edited")
        session.commitDefinition(edited, snapshot)
        val committed = repository.loadCommitted("coding")!!
        assertEquals(2L, committed.generation)
        assertEquals(edited, committed.definition)
        assertEquals(setOf("agent"), committed.lock.packages.map { it.id }.toSet())
        assertEquals(snapshot, ProfileCompositionSession.open(repository, definition).load())
    }

    @Test
    fun staleSessionCannotReplaceItsCommittedIntentOrRuntimeSnapshot() = runBlocking {
        val root = Files.createTempDirectory("profile-session")
        val repository = FileProfileRepository(root.toFile())
        val definition = ProfileDefinition(id = "coding")
        val stale = ProfileCompositionSession.open(repository, definition)
        val winner = ProfileCompositionSession.open(repository, definition)
        winner.save(PluginCompositionSnapshot())
        val next = PluginCompositionSnapshot(builtinsEnabled = mapOf("core" to false))
        assertFailsWith<IllegalArgumentException> { stale.commitDefinition(definition.copy(displayName = "Lost"), next) }
        assertEquals(PluginCompositionSnapshot(), stale.load())
        assertEquals(definition, repository.loadCommitted("coding")?.definition)
    }
}
