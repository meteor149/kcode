package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.packages.NativePluginPackageResolver
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.desktopPackageHost
import ai.meteor.kcode.plugin.packages.stageBundledPackageCatalog
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchive
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveCode
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveInput
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveExtension
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveMetadata
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveRuntime
import ai.meteor.kcode.plugin.profiles.ProfileManagement
import ai.meteor.kcode.plugin.profiles.ProfilePortableExporter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.cordis.packages.PackageDependency
import org.cordis.packages.PackageFile
import org.cordis.packages.PackageRuntime
import org.cordis.packages.PackageTarget
import org.cordis.packages.PackageVariant
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.PluginPackageManifest
import org.cordis.packages.packageFileSha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeProfileBundleArchiveTest {
    @Test
    fun independentBundleDataImportsVerifiedJarAndRestartsWithoutItsSourceArchive(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-external-bundle").toFile()
        val repository = FileProfileRepository(File(home, "profiles"))
        val packages = File(home, "plugins")
        val host = desktopPackageHost()
        val offers = stageBundledPackageCatalog(packages, host = host) { name ->
            requireNotNull(NativePluginPackagesPlugin::class.java.classLoader.getResourceAsStream(name))
        }
        val release = offers.single { it.id == "feature.localization" }.release
        val bundle = ProfileBundle(id = "example.dictionary.bundle", version = "1.0.0", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("dictionary", "feature.localization",
                Json.parseToJsonElement("""{"defaultLanguage":"en"}""")))),
        ))
        val source = File(home, "source").apply { mkdirs() }
        val definition = File(source, "bundle.json").apply { writeText(Json.encodeToString(bundle)) }
        val path = "code/${release.sha256}.bin"
        val code = File(source, path).apply { parentFile.mkdirs() }
        File(release.archivePath).copyTo(code)
        val codeManifest = PluginPackageArchive().inspect(code, release.sha256).manifest
        val manifest = PluginPackageManifest(id = bundle.id, version = bundle.version,
            dependencies = listOf(PackageDependency(codeManifest.id, codeManifest.version)),
            variants = listOf(PackageVariant("data", listOf("windows", "linux", "macos").map {
                PackageTarget(it, listOf("arm", "x86"))
            }, PackageRuntime(ProfileBundleArchiveRuntime, "bundle.json", "1"), "bundle.json")),
            files = listOf(PackageFile("bundle.json", definition.length(), packageFileSha256(definition)),
                PackageFile(path, code.length(), release.sha256)),
            extensions = JsonObject(mapOf(ProfileBundleArchiveExtension to Json.encodeToJsonElement(
                ProfileBundleArchiveMetadata.serializer(), ProfileBundleArchiveMetadata(packages = listOf(
                    ProfileBundleArchiveCode(codeManifest.id, path),
                )),
            ))),
        )
        val archive = File(home, "dictionary.kbundle")
        val digest = PluginPackageArchive().pack(manifest, source, archive)
        try {
            val importer = ProfileBundleArchive(packages, host, NativePluginPackageResolver(packages, host))
            val before = repository.state()
            assertFailsWith<IllegalArgumentException> { importer.prepare(archive, "a".repeat(64), "bad") }
            assertEquals(before, repository.state())
            val wrongDependency = File(home, "wrong-dependency.kbundle")
            val wrongDigest = PluginPackageArchive().pack(manifest.copy(dependencies = listOf(
                PackageDependency(codeManifest.id, "0.0.0"),
            )), source, wrongDependency)
            assertFailsWith<IllegalArgumentException> { importer.prepare(wrongDependency, wrongDigest, "bad") }
            assertEquals(before, repository.state())
            val unknownMetadata = File(home, "unknown-metadata.kbundle")
            val unknownDigest = PluginPackageArchive().pack(manifest.copy(extensions = JsonObject(mapOf(
                ProfileBundleArchiveExtension to Json.parseToJsonElement("""{"formatVersion":2,"packages":[]}"""),
            ))), source, unknownMetadata)
            assertFailsWith<IllegalArgumentException> { importer.prepare(unknownMetadata, unknownDigest, "bad") }
            assertEquals(before, repository.state())
            val overlay = ProfileBundle(id = "example.dictionary.overlay", version = "1.0.0", patches = listOf(
                ProfileOperation.Configure("dictionary", Json.parseToJsonElement("""{"defaultLanguage":"zh"}""")),
            ))
            definition.writeText(Json.encodeToString(overlay))
            val overlayArchive = File(home, "overlay.kbundle")
            val overlayDigest = PluginPackageArchive().pack(manifest.copy(id = overlay.id, files = manifest.files.map {
                if (it.path == "bundle.json") PackageFile(it.path, definition.length(), packageFileSha256(definition)) else it
            }), source, overlayArchive)
            val inputs = listOf(ProfileBundleArchiveInput(archive, digest), ProfileBundleArchiveInput(overlayArchive, overlayDigest))
            assertFailsWith<IllegalArgumentException> { importer.prepare(inputs + inputs.first(), "duplicate") }
            val imported = importer.prepare(inputs, "imported")
            assertEquals(listOf(bundle, overlay), imported.bundles)
            assertEquals(listOf("feature.localization"), imported.lock.packages.map { it.id })
            assertNull(repository.loadDraft("imported"))
            val management = ProfileManagement(repository, { emptyList() }) { error("Bundle import cannot activate") }
            management.importPortable(Json.encodeToString(imported), "imported", "Imported", before.revision)
            assertNull(repository.loadCommitted("imported"))
            assertTrue(archive.delete())
            assertTrue(overlayArchive.delete())
            assertTrue(source.deleteRecursively())
            assertTrue(File(packages, "bundles").deleteRecursively())
            repeat(2) {
                val runtime = createDesktopProfileHost(homeDirectory = home.toPath(), profileId = "imported")
                try {
                    runtime.state.value.failure?.let { throw it }
                    assertEquals(ProfileHostPhase.Ready, runtime.state.value.phase)
                    val client = assertNotNull(runtime.profileCommands)
                    val catalogue = client.catalogue()
                    val preview = client.preview(ProfileTarget("imported"))
                    assertTrue(preview.packagesVerified)
                    assertEquals(listOf("dictionary"), preview.entries.map { it.id })
                    assertEquals(Json.parseToJsonElement("""{"defaultLanguage":"zh"}"""), preview.entries.single().config)
                    val exported = ProfilePortableExporter.decode(client.exportPortable(
                        ProfilePortableExport(ProfileTarget("imported"), catalogue.revision)))
                    assertEquals(listOf(bundle, overlay), exported.bundles)
                    assertEquals(listOf("feature.localization"), exported.lock.packages.map { it.id })
                } finally { runtime.close() }
            }
        } finally { home.deleteRecursively() }
    }
}
