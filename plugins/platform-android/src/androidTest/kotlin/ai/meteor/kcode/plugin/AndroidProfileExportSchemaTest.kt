package ai.meteor.kcode.plugin

import ai.meteor.kcode.createAndroidProfileHost
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfilePortableExporter
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class AndroidProfileExportSchemaTest {
    @Test(timeout = 180_000)
    fun lockedApkSchemaExportsDictionaryJsonAndRejectsArchiveTampering(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "profile-export-schema-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
        }
        val repository = FileProfileRepository(File(directory, "cordis_profiles"))
        val config = Json.parseToJsonElement("""{"defaultLanguage":"en","translations":{"en":{"profile_title":"Custom Profiles"}}}""")
        val definition = ProfileDefinition(id = "dictionary", patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("dictionary", "feature.localization", config),
        ))))
        repository.saveDraft(definition)
        val host = withContext(Dispatchers.Main.immediate) {
            val activity = object : Activity() {
                init { attachBaseContext(isolated) }
                override fun getApplicationContext(): android.content.Context = isolated
                override fun getFilesDir() = directory
                override fun getAssets() = context.assets
                override fun getResources() = context.resources
            }
            createAndroidProfileHost(activity, profileId = definition.id, toolCallApprover = ToolCallApprover { true })
        }
        try {
            host.state.value.failure?.let { throw it }
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            val client = assertNotNull(host.profileCommands)
            val catalogue = client.catalogue()
            val request = ProfilePortableExport(ProfileTarget(definition.id), catalogue.revision)
            val document = ProfilePortableExporter.decode(client.exportPortable(request))
            assertEquals(config, (document.definition.patches.single() as ProfileOperation.Insert).entries.single().config)
            val committed = assertNotNull(repository.loadCommitted(definition.id))
            val installation = committed.composition.external.single { it.id == "feature.localization" }.packageInstallation!!
            val archive = File(installation.archivePath)
            check(archive.setWritable(true))
            archive.appendBytes(byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> { client.exportPortable(request) }
            assertEquals(catalogue, client.catalogue())
        } finally {
            try { host.close() } finally { directory.deleteRecursively() }
        }
    }
}
