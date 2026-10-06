package ai.meteor.kcode.plugin

import ai.meteor.kcode.createAndroidProfileHost
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.packages.NativePluginPackageResolver
import ai.meteor.kcode.plugin.packages.androidPackageHost
import ai.meteor.kcode.plugin.packages.androidPackageVerifier
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileArchiveExchange
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveInput
import ai.meteor.kcode.plugin.profiles.ProfileManagement
import ai.meteor.kcode.plugin.profiles.ProfilePortableExporter
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requires the actual Desktop-exported fixture; ordinary instrumentation runs explicitly skip it. */
class AndroidProfileArchiveExchangeTest {
    @Test(timeout = 180_000)
    fun desktopArchiveRebuildsAndroidLockAndStartsFromItsImportedApk(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("profileArchive")
        val digest = arguments.getString("profileArchiveSha256")
        assumeTrue("Pass the Desktop fixture and its digest", path != null && digest != null)
        require(path == "/data/local/tmp/kcode-profile-archive-fixture.kprofile")
        require(requireNotNull(digest).matches(Regex("[0-9a-f]{64}")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "profile-archive-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
        }
        try {
            val archive = File(directory, "desktop.kprofile")
            // Shell owns the transfer file. Stream through its descriptor rather than granting app storage access.
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $path")).use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
            val original = ZipFile(archive).use { zip ->
                ProfilePortableExporter.decode(zip.getInputStream(zip.getEntry("profile.json")).bufferedReader().use { it.readText() })
            }
            val repository = FileProfileRepository(File(directory, "cordis_profiles"))
            val packages = File(directory, "cordis_plugins")
            val platform = androidPackageHost()
            val before = repository.state()
            val prepared = ProfileArchiveExchange(packages, platform, NativePluginPackageResolver(packages, platform,
                artifactVerifier = androidPackageVerifier(isolated)))
                .prepare(ProfileBundleArchiveInput(archive, digest), "copy", "Copy")
            assertEquals(before, repository.state())
            assertNull(repository.loadDraft("copy"))
            assertEquals(2, prepared.bundles.size)
            assertEquals(original.bundles, prepared.bundles)
            assertEquals(original.definition.patches, prepared.definition.patches)
            val desktopLock = original.lock.packages.single()
            val androidLock = prepared.lock.packages.single()
            assertEquals("feature.localization", androidLock.id)
            assertEquals(desktopLock.id, androidLock.id)
            assertEquals(desktopLock.version, androidLock.version)
            assertEquals(desktopLock.archiveSha256, androidLock.archiveSha256)
            assertEquals(desktopLock.dependencies, androidLock.dependencies)
            assertNotEquals(desktopLock.variantId, androidLock.variantId)
            ProfileManagement(repository, { emptyList() }) { error("Import must not activate") }
                .importPortable(Json.encodeToString(prepared), "copy", "Copy", before.revision)
            assertNull(repository.loadCommitted("copy"))
            assertTrue(archive.delete())
            assertTrue(File(packages, "profile-archives").deleteRecursively())
            val host = withContext(Dispatchers.Main.immediate) {
                val activity = object : Activity() {
                    init { attachBaseContext(isolated) }
                    override fun getApplicationContext(): android.content.Context = isolated
                    override fun getFilesDir() = directory
                    override fun getAssets() = context.assets
                    override fun getResources() = context.resources
                }
                createAndroidProfileHost(activity, profileId = "copy", toolCallApprover = ToolCallApprover { true })
            }
            try {
                host.state.value.failure?.let { throw it }
                assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
                val client = assertNotNull(host.profileCommands)
                val preview = client.preview(ProfileTarget("copy"))
                assertTrue(preview.packagesVerified)
                assertEquals(Json.parseToJsonElement("""{"defaultLanguage":"zh"}"""), preview.entries.single().config)
                val exported = ProfilePortableExporter.decode(client.exportPortable(
                    ProfilePortableExport(ProfileTarget("copy"), client.catalogue().revision)))
                assertEquals(prepared.bundles, exported.bundles)
                assertEquals(prepared.lock, exported.lock)
            } finally { host.close() }
        } finally { directory.deleteRecursively() }
    }
}
