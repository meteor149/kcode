package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfilePortableExporter
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class NativeProfileExportSchemaTest {
    @Test
    fun defaultProfileExplicitFeatureConfigurationExportsButHiddenInvalidFieldsDoNot(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-default-export")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        val host = createDesktopProfileHost(homeDirectory = home)
        try {
            host.state.value.failure?.let { throw it }
            val client = assertNotNull(host.profileCommands)
            val source = assertNotNull(repository.loadCommitted("native"))
            val temperature = Json.parseToJsonElement("""{"minimumTemperature":0.25,"maximumTemperature":1.75}""")
            val concurrency = Json.parseToJsonElement("""{"maxConcurrency":3}""")
            val theme = Json.parseToJsonElement("""{
                "colors":{"primary":"#123abc","surface":"#Ff223344"},
                "extendedColors":{"panel":"#334455"},"spacing":{"md":22},
                "radius":{"control":19},"size":{"touchTarget":64},
                "glass":{"blurRadius":6,"tintOpacity":0.5,"noiseFactor":0.1},
                "overlay":{"floatingSize":68,"bubbleMinWidth":200,"bubbleMaxWidth":400},
                "fontScale":1.25
            }""")
            val explicit = source.definition.copy(patches = source.definition.patches + source.composition.external.map {
                ProfileOperation.Configure(it.id, JsonNull, "unit")
            } + listOf(ProfileOperation.Configure("provider.model-settings.catalog", temperature),
                ProfileOperation.Configure("feature.subagents", concurrency),
                ProfileOperation.Configure("provider.ui.theme", theme)))
            val written = client.writeDraft(ProfileDraftWrite(explicit, client.catalogue().revision))
            host.pluginManager.activateProfile(ProfileActivationRequest(ProfileTarget("native", ProfileSource.Draft), written.revision))
            val before = repository.state()
            val request = ProfilePortableExport(ProfileTarget("native"), before.revision)
            val exported = ProfilePortableExporter.decode(client.exportPortable(request))
            assertEquals(explicit.patches, exported.definition.patches)
            assertEquals(source.bundles, exported.bundles)
            client.exportArchive(request) { reference ->
                val document = ZipFile(File(reference.archivePath)).use { zip ->
                    ProfilePortableExporter.decode(zip.getInputStream(zip.getEntry("profile.json")).bufferedReader().use { it.readText() })
                }
                assertEquals(exported, document)
            }
            assertEquals(before, repository.state())
            for ((id, invalid, valid) in listOf(
                Triple("provider.model-settings.catalog", """{"minimumTemperature":0.25,"apiKey":"sentinel"}""", temperature),
                Triple("provider.ui.theme", """{"colors":{"primary":"secret"}}""", theme),
                Triple("provider.ui.theme", """{"spacing":{"md":513}}""", theme),
                Triple("provider.ui.theme", """{"colors":{"apiKey":"#123456"}}""", theme),
            )) {
                val hidden = explicit.copy(patches = explicit.patches + listOf(
                    ProfileOperation.Configure(id, Json.parseToJsonElement(invalid)),
                    ProfileOperation.Configure(id, valid),
                ))
                val hiddenDraft = client.writeDraft(ProfileDraftWrite(hidden, client.catalogue().revision))
                host.pluginManager.activateProfile(ProfileActivationRequest(ProfileTarget("native", ProfileSource.Draft), hiddenDraft.revision))
                val denied = repository.state()
                assertFailsWith<IllegalArgumentException> {
                    client.exportPortable(ProfilePortableExport(ProfileTarget("native"), denied.revision))
                }
                assertEquals(denied, repository.state())
            }
        } finally { try { host.close() } finally { home.toFile().deleteRecursively() } }
    }

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
