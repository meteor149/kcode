package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfilePluginImport
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.desktopPackageHost
import ai.meteor.kcode.plugin.packages.stageBundledPackageCatalog
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfilePluginImportTest {
    @Test
    fun independentManagerImportsIntoAnUncommittedDraftAndBootsFromCacheAfterRestart(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-manager-plugin-import").toFile()
        try {
            val catalog = stageBundledPackageCatalog(File(home, "publisher"), host = desktopPackageHost()) { name ->
                requireNotNull(NativePluginPackagesPlugin::class.java.classLoader.getResourceAsStream(name))
            }
            val release = catalog.single { it.id == "feature.localization" }.release
            val source = File(home, "dictionary.kplugin")
            File(release.archivePath).copyTo(source)
            val repository = FileProfileRepository(File(home, "profiles"))
            repository.saveDraft(ProfileDefinition(id = "editable"))
            val profile = KcodePluginProfile(includeDefaults = false)
            val manager = createDesktopProfileHost(homeDirectory = home.toPath(), profileId = "editable", profile = profile,
                managementOnly = true)
            try {
                val client = assertNotNull(manager.profileCommands)
                client.importPlugin(ProfilePluginImport("editable", ProfileArchiveReference(source.absolutePath, release.sha256),
                    client.catalogue().revision))
                assertEquals(ProfileHostPhase.RecoveryRequired, manager.state.value.phase)
                assertTrue(client.preview(ProfileTarget("editable", ProfileSource.Draft)).packagesVerified)
            } finally { manager.close() }
            assertTrue(source.delete())
            val started = createDesktopProfileHost(homeDirectory = home.toPath(), profileId = "editable", profile = profile)
            try {
                started.state.value.failure?.let { throw it }
                assertEquals(ProfileHostPhase.Ready, started.state.value.phase)
                assertEquals("feature.localization", assertNotNull(started.profileCommands).preview(ProfileTarget("editable"))
                    .entries.single().packageId)
            } finally { started.close() }
        } finally { home.deleteRecursively() }
    }

    @Test
    fun importingOneRealJarKeepsTheActiveGenerationAndSurvivesDraftEditingAndRestart(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-plugin-import").toFile()
        try {
            val catalog = stageBundledPackageCatalog(File(home, "publisher"), host = desktopPackageHost()) { name ->
                requireNotNull(NativePluginPackagesPlugin::class.java.classLoader.getResourceAsStream(name))
            }
            val release = catalog.single { it.id == "feature.localization" }.release
            val source = File(home, "dictionary.kplugin")
            File(release.archivePath).copyTo(source)
            val repository = FileProfileRepository(File(home, "profiles"))
            repository.saveDraft(ProfileDefinition(id = "editable"))
            val profile = KcodePluginProfile(includeDefaults = false)
            val host = createDesktopProfileHost(homeDirectory = home.toPath(), profileId = "editable", profile = profile)
            try {
                host.state.value.failure?.let { throw it }
                assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
                val client = assertNotNull(host.profileCommands)
                val before = assertNotNull(repository.loadCommitted("editable"))
                val revision = client.catalogue().revision
                val request = ProfilePluginImport("editable", ProfileArchiveReference(source.absolutePath, release.sha256), revision)
                assertFailsWith<IllegalArgumentException> { client.importPlugin(request.copy(expectedRevision = revision - 1)) }
                assertFailsWith<IllegalArgumentException> {
                    client.importPlugin(request.copy(archive = request.archive.copy(sha256 = "0".repeat(64))))
                }
                assertEquals(revision, client.catalogue().revision)
                val imported = client.importPlugin(request)
                assertEquals("editable", imported.activeProfileId)
                assertEquals(before, repository.loadCommitted("editable"))
                val draft = assertNotNull(repository.loadDraftDocument("editable"))
                assertEquals(release.sha256, draft.packageImports.packages.single().archiveSha256)
                assertTrue(source.delete())
                val target = ProfileTarget("editable", ProfileSource.Draft)
                val preview = client.preview(target)
                assertTrue(preview.packagesVerified, preview.diagnostics.toString())
                val entry = preview.entries.single()
                assertEquals("feature.localization", entry.packageId)
                val config = Json.parseToJsonElement("""{"defaultLanguage":"zh"}""")
                val edited = client.writeDraft(ProfileDraftWrite(preview.definition.copy(
                    patches = preview.definition.patches + ProfileOperation.Configure(entry.id, config),
                ), imported.revision))
                assertEquals(draft.packageImports, repository.loadDraftDocument("editable")?.packageImports)
                val result = client.submit(ProfileCommand.Activate(ProfileActivationRequest(target, edited.revision))).await()
                assertEquals(ProfileCommandPhase.Succeeded, result.phase, result.failure)
                assertEquals(config, client.preview(ProfileTarget("editable")).entries.single().config)
            } finally { host.close() }
            val restarted = createDesktopProfileHost(homeDirectory = home.toPath(), profileId = "editable", profile = profile)
            try {
                restarted.state.value.failure?.let { throw it }
                assertEquals(ProfileHostPhase.Ready, restarted.state.value.phase)
                val preview = assertNotNull(restarted.profileCommands).preview(ProfileTarget("editable"))
                assertTrue(preview.packagesVerified, preview.diagnostics.toString())
                assertEquals("feature.localization", preview.entries.single().packageId)
            } finally { restarted.close() }
        } finally { home.deleteRecursively() }
    }
}
