package ai.meteor.kcode.plugin

import ai.meteor.kcode.createAndroidProfileHost
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.packages.NativePluginPackageResolver
import ai.meteor.kcode.plugin.packages.androidPackageHost
import ai.meteor.kcode.plugin.packages.androidPackageVerifier
import ai.meteor.kcode.plugin.packages.stageBundledPackageCatalog
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchive
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveCode
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveExtension
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveMetadata
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveRuntime
import ai.meteor.kcode.plugin.profiles.ProfileManagement
import ai.meteor.kcode.plugin.profiles.ProfilePortableExporter
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidProfileBundleArchiveTest {
    @Test(timeout = 180_000)
    fun pureBundleDataStagesAnApkAndRestartsFromFrozenMetadataAndCodeCache(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "profile-bundle-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
        }
        val repository = FileProfileRepository(File(directory, "cordis_profiles"))
        val packages = File(directory, "cordis_plugins")
        val platform = androidPackageHost()
        val release = stageBundledPackageCatalog(packages, host = platform) { name -> context.assets.open(name) }
            .single { it.id == "feature.localization" }.release
        val bundle = ProfileBundle(id = "example.dictionary.bundle", version = "1.0.0", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("dictionary", "feature.localization",
                Json.parseToJsonElement("""{"defaultLanguage":"en"}""")))),
        ))
        val source = File(directory, "source").apply { mkdirs() }
        val definition = File(source, "bundle.json").apply { writeText(Json.encodeToString(bundle)) }
        val path = "code/${release.sha256}.bin"
        val code = File(source, path).apply { parentFile.mkdirs() }
        File(release.archivePath).copyTo(code)
        val manifest = PluginPackageArchive().inspect(code, release.sha256).manifest
        val outer = PluginPackageManifest(id = bundle.id, version = bundle.version,
            dependencies = listOf(PackageDependency(manifest.id, manifest.version)),
            variants = listOf(PackageVariant("data", listOf(PackageTarget("android", listOf("arm", "x86"))),
                PackageRuntime(ProfileBundleArchiveRuntime, "bundle.json", "1"), "bundle.json")),
            files = listOf(PackageFile("bundle.json", definition.length(), packageFileSha256(definition)),
                PackageFile(path, code.length(), release.sha256)),
            extensions = JsonObject(mapOf(ProfileBundleArchiveExtension to Json.encodeToJsonElement(
                ProfileBundleArchiveMetadata.serializer(), ProfileBundleArchiveMetadata(packages = listOf(
                    ProfileBundleArchiveCode(manifest.id, path),
                )),
            ))),
        )
        val archive = File(directory, "dictionary.kbundle")
        val digest = PluginPackageArchive().pack(outer, source, archive)
        try {
            val imported = ProfileBundleArchive(packages, platform, NativePluginPackageResolver(packages, platform,
                artifactVerifier = androidPackageVerifier(isolated))).prepare(archive, digest, "imported")
            assertNull(repository.loadDraft("imported"))
            assertEquals(listOf("feature.localization"), imported.lock.packages.map { it.id })
            val management = ProfileManagement(repository, { emptyList() }) { error("Bundle import cannot activate") }
            management.importPortable(Json.encodeToString(imported), "imported", "Imported", repository.state().revision)
            assertNull(repository.loadCommitted("imported"))
            assertTrue(archive.delete())
            assertTrue(source.deleteRecursively())
            assertTrue(File(packages, "bundles").deleteRecursively())
            repeat(2) {
                val host = withContext(Dispatchers.Main.immediate) {
                    val activity = object : Activity() {
                        init { attachBaseContext(isolated) }
                        override fun getApplicationContext(): android.content.Context = isolated
                        override fun getFilesDir() = directory
                        override fun getAssets() = context.assets
                        override fun getResources() = context.resources
                    }
                    createAndroidProfileHost(activity, profileId = "imported", toolCallApprover = ToolCallApprover { true })
                }
                try {
                    host.state.value.failure?.let { throw it }
                    assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
                    val client = assertNotNull(host.profileCommands)
                    val catalogue = client.catalogue()
                    val preview = client.preview(ProfileTarget("imported"))
                    assertTrue(preview.packagesVerified)
                    assertEquals(listOf("dictionary"), preview.entries.map { it.id })
                    val exported = ProfilePortableExporter.decode(client.exportPortable(ProfilePortableExport(
                        ProfileTarget("imported"), catalogue.revision)))
                    assertEquals(listOf(bundle), exported.bundles)
                } finally { host.close() }
            }
        } finally { directory.deleteRecursively() }
    }
}
