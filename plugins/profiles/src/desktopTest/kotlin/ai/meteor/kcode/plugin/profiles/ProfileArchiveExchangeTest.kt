package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.DynamicPluginSpec
import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.packages.PackageHost
import org.cordis.packages.PluginPackageArchive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProfileArchiveExchangeTest {
    private val bundle = ProfileBundle(id = "base", version = "1", patches = listOf(
        ProfileOperation.Insert(listOf(ProfileEntry("provider", "example.provider", JsonPrimitive("frozen")))),
    ))
    private val definition = ProfileDefinition(id = "source", bundles = listOf(ProfileBundleReference("base", "1")))
    private val host = PackageHost("windows", "x86_64", runtimes = mapOf("jvm" to "17"))
    private val resolver = object : PluginPackageResolver {
        override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> {
            assertEquals(emptyList(), imports)
            assertEquals(emptyList(), installed)
            return emptyList()
        }
        override suspend fun verify(spec: DynamicPluginSpec): Unit = error("Data-only fixture has no code")
    }

    private suspend fun seed(repository: FileProfileRepository) {
        repository.commit(CommittedProfileGeneration(generation = 1, definition = definition,
            lock = ProfileLock(), composition = PluginCompositionSnapshot(), bundles = listOf(bundle)), null)
        repository.commit(CommittedProfileGeneration(generation = 2, definition = definition.copy(patches = listOf(
            ProfileOperation.Configure("provider", JsonPrimitive("new")),
        )), lock = ProfileLock(), composition = PluginCompositionSnapshot(), bundles = listOf(bundle)), 1)
    }

    @Test
    fun historicalArchivePreservesFrozenLayersWithoutPublishingReceiverMetadata(): Unit = runBlocking {
        val home = Files.createTempDirectory("profile-archive-history").toFile()
        try {
            val repository = FileProfileRepository(File(home, "profiles"))
            seed(repository)
            val before = repository.state()
            val management = ProfileManagement(repository, { error("History must use frozen bundles") },
                ProfileExportReview { value -> if (value.packageId == "example.provider") value.value else null }) {
                error("Export must not activate")
            }
            val output = File(home, "historical.kprofile")
            val exchange = ProfileArchiveExchange(File(home, "receiver"), host, resolver)
            val digest = exchange.export(management, ProfileTarget("source", ProfileSource.History, 1), before.revision, output)
            assertEquals(before, repository.state())
            val archived = ZipFile(output).use { zip ->
                ProfilePortableExporter.decode(zip.getInputStream(zip.getEntry("profile.json")).bufferedReader().use { it.readText() })
            }
            assertEquals(listOf(bundle), archived.bundles)
            assertEquals(emptyList(), archived.definition.patches)
            val prepared = exchange.prepare(ProfileBundleArchiveInput(output, digest), "copy", "Copy", setOf("example.provider"))
            assertEquals(listOf(bundle), prepared.bundles)
            assertEquals(ProfileDataScope(workspace = "profile"), prepared.definition.dataScope)
            assertEquals("copy", prepared.definition.id)
            assertEquals(archived.lock, prepared.lock)
            assertEquals(before, repository.state())
            assertNull(repository.loadDraft("copy"))
            assertNull(repository.loadCommitted("copy"))
        } finally { home.deleteRecursively() }
    }

    @Test
    fun deniedReviewStaleRevisionAndDraftExportPreserveExistingOutput(): Unit = runBlocking {
        val home = Files.createTempDirectory("profile-archive-export-rejection").toFile()
        try {
            val repository = FileProfileRepository(File(home, "profiles"))
            seed(repository)
            val before = repository.state()
            val denied = ProfileManagement(repository, { emptyList() }) { error("Cannot activate") }
            val approved = ProfileManagement(repository, { emptyList() }, ProfileExportReview { it.value }) { error("Cannot activate") }
            val output = File(home, "keep.kprofile").apply { writeText("keep") }
            val exchange = ProfileArchiveExchange(File(home, "packages"), host, resolver)
            assertFailsWith<IllegalArgumentException> { exchange.export(denied, ProfileTarget("source"), before.revision, output) }
            assertFailsWith<IllegalArgumentException> { exchange.export(approved, ProfileTarget("source"), before.revision - 1, output) }
            assertFailsWith<IllegalArgumentException> { exchange.export(approved, ProfileTarget("source", ProfileSource.Draft), before.revision, output) }
            assertEquals("keep", output.readText())
            assertEquals(before, repository.state())
        } finally { home.deleteRecursively() }
    }

    @Test
    fun unsupportedArchiveMetadataAndWrongDigestAreRejected(): Unit = runBlocking {
        val home = Files.createTempDirectory("profile-archive-import-rejection").toFile()
        try {
            val repository = FileProfileRepository(File(home, "profiles"))
            seed(repository)
            val before = repository.state()
            val management = ProfileManagement(repository, { emptyList() }, ProfileExportReview { it.value }) { error("Cannot activate") }
            val exchange = ProfileArchiveExchange(File(home, "packages"), host, resolver)
            val output = File(home, "valid.kprofile")
            val digest = exchange.export(management, ProfileTarget("source"), before.revision, output)
            assertFailsWith<IllegalArgumentException> { exchange.prepare(ProfileBundleArchiveInput(output, "0".repeat(64)), "copy") }
            val deployed = PluginPackageArchive().deploy(output, digest, File(home, "source"))
            val altered = File(home, "unsupported.kprofile")
            val alteredDigest = PluginPackageArchive().pack(deployed.manifest.copy(extensions = JsonObject(mapOf(
                ProfileArchiveExtension to Json.parseToJsonElement("""{"formatVersion":2,"packages":[]}"""),
            ))), File(deployed.directory, "payload"), altered)
            assertFailsWith<IllegalArgumentException> { exchange.prepare(ProfileBundleArchiveInput(altered, alteredDigest), "copy") }
            assertEquals(before, repository.state())
        } finally { home.deleteRecursively() }
    }
}
