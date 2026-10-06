package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import kotlin.test.assertTrue

class ProfileBundleArchiveWriterTest {
    private val targets = listOf(PackageTarget("windows", listOf("x86"), bits = listOf(64)))
    private val bundle = ProfileBundle(id = "example.bundle", version = "1.0.0", patches = emptyList())

    private fun code(home: File, id: String, version: String = "1.0.0"): ProfileBundleArchiveInput {
        val payload = File(home, "$id-$version").apply { mkdirs() }
        val artifact = File(payload, "code.jar").apply { writeText("publisher fixture $id@$version") }
        val manifest = PluginPackageManifest(id = id, version = version,
            variants = listOf(PackageVariant("desktop", targets, PackageRuntime("jvm", "fixture.Plugin"), "code.jar")),
            files = listOf(PackageFile("code.jar", artifact.length(), packageFileSha256(artifact))))
        val archive = File(home, "$id-$version.kplugin")
        return ProfileBundleArchiveInput(archive, PluginPackageArchive().pack(manifest, payload, archive))
    }

    @Test
    fun codeOrderDoesNotChangeDeterministicOutputAndDefinitionIsFrozen(): Unit = runBlocking {
        val home = Files.createTempDirectory("profile-bundle-writer").toFile()
        try {
            val code = listOf(code(home, "example.b"), code(home, "example.a"))
            val first = File(home, "first.kbundle")
            val writer = ProfileBundleArchiveWriter()
            val digest = writer.pack(bundle, code, targets, first)
            assertEquals(digest, writer.pack(bundle, code.reversed(), targets, File(home, "second.kbundle")))
            val manifest = PluginPackageArchive().inspect(first, digest).manifest
            assertEquals(listOf("example.a", "example.b"), manifest.dependencies.map { it.id })
            val metadata = Json.decodeFromJsonElement(ProfileBundleArchiveMetadata.serializer(),
                manifest.extensions.getValue(ProfileBundleArchiveExtension))
            assertEquals(manifest.dependencies.map { it.id }, metadata.packages.map { it.id })
            assertTrue(metadata.packages.all { it.path.matches(Regex("code/[a-f0-9]{64}\\.bin")) })
            ZipFile(first).use { archive ->
                val text = archive.getInputStream(archive.getEntry("bundle.json")).bufferedReader().use { it.readText() }
                assertEquals(bundle, Json.decodeFromString(ProfileBundle.serializer(), text))
            }
        } finally { home.deleteRecursively() }
    }

    @Test
    fun invalidInputsPreserveExistingOutputAndCannotOverwriteCode(): Unit = runBlocking {
        val home = Files.createTempDirectory("profile-bundle-writer-rejection").toFile()
        try {
            val first = code(home, "example.a")
            val other = code(home, "example.a", "2.0.0")
            val output = File(home, "output.kbundle").apply { writeText("keep") }
            val writer = ProfileBundleArchiveWriter()
            assertFailsWith<IllegalArgumentException> { writer.pack(bundle, listOf(first.copy(sha256 = "0".repeat(64))), targets, output) }
            assertFailsWith<IllegalArgumentException> { writer.pack(bundle, listOf(first, other), targets, output) }
            assertFailsWith<IllegalArgumentException> { writer.pack(bundle, listOf(first), targets, first.archive) }
            assertFailsWith<IllegalArgumentException> { writer.pack(bundle.copy(formatVersion = 2), emptyList(), targets, output) }
            assertEquals("keep", output.readText())
            assertEquals(first.sha256, packageFileSha256(first.archive))
        } finally { home.deleteRecursively() }
    }

    @Test
    fun cliResolvesPathsFromRequestAndBuildsDataOnlyArchives(): Unit = runBlocking {
        val home = Files.createTempDirectory("profile-bundle-cli").toFile()
        try {
            File(home, "bundle.json").writeText(Json.encodeToString(ProfileBundle.serializer(), bundle))
            val request = File(home, "request.json")
            request.writeText("""{"bundle":"bundle.json","targets":[{"system":"windows","arch":["x86"],"bits":[64]}],"output":"out/published.kbundle"}""")
            main(arrayOf(request.absolutePath))
            val output = File(home, "out/published.kbundle")
            val manifest = PluginPackageArchive().inspect(output, packageFileSha256(output)).manifest
            assertEquals(bundle.id, manifest.id)
            assertEquals(emptyList(), manifest.dependencies)
            assertEquals(listOf("bundle.json"), manifest.files.map { it.path })
            request.writeText("""{"bundle":"bundle.json","targets":[{"system":"windows","arch":["x86"]}],"output":"bundle.json"}""")
            assertFailsWith<IllegalArgumentException> { main(arrayOf(request.absolutePath)) }
            assertEquals(bundle, Json.decodeFromString(ProfileBundle.serializer(), File(home, "bundle.json").readText()))
        } finally { home.deleteRecursively() }
    }
}
