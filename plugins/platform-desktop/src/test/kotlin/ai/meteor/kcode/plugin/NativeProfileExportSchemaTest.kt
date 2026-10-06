package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfilePortableExporter
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class NativeProfileExportSchemaTest {
    @Test
    fun nativeHostReviewsPrivateDictionaryConfigurationFromItsLockedPackage(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-export-schema")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        val configuration = Json.parseToJsonElement("""{"defaultLanguage":"en","translations":{"en":{"profile_title":"Custom Profiles"}}}""")
        val definition = ProfileDefinition(id = "dictionary", patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("dictionary", "feature.localization", configuration),
        ))))
        repository.saveDraft(definition)
        val host = createDesktopProfileHost(homeDirectory = home, profileId = definition.id)
        try {
            host.state.value.failure?.let { throw it }
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            val client = assertNotNull(host.profileCommands)
            val catalogue = client.catalogue()
            val target = ProfileTarget(definition.id)
            val document = ProfilePortableExporter.decode(client.exportPortable(ProfilePortableExport(target, catalogue.revision)))
            assertEquals(configuration, (document.definition.patches.single() as ProfileOperation.Insert).entries.single().config)
            assertEquals(catalogue, client.catalogue())
            val committed = assertNotNull(repository.loadCommitted(definition.id))
            val release = committed.composition.external.single { it.id == "feature.localization" }.packageInstallation!!
            // The selected deployment is immutable in production. Tampering must invalidate export metadata too.
            val archive = java.io.File(release.archivePath)
            check(archive.setWritable(true))
            archive.appendBytes(byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> {
                client.exportPortable(ProfilePortableExport(target, catalogue.revision))
            }
            assertEquals(catalogue, client.catalogue())
        } finally {
            try { host.close() } finally { home.toFile().deleteRecursively() }
        }
    }
}
