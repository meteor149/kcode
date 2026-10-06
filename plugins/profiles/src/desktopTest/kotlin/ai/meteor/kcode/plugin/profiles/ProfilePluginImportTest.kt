package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfilePluginImport
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ProfilePluginImportTest {
    @Test
    fun cancelledInvalidAndStaleImportsNeverPublishAndDraftEditsAndClonesKeepVerifiedCode(): Unit = runBlocking {
        val directory = Files.createTempDirectory("profile-plugin-command").toFile()
        try {
            val repository = FileProfileRepository(directory)
            repository.saveDraft(ProfileDefinition(id = "editable"))
            var mode = "failure"
            var preparations = 0
            val lock = ProfileLock(packages = listOf(
                LockedProfilePackage("example.code", "1.0.0", "a".repeat(64), "desktop", "b".repeat(64)),
            ))
            val transport = object : ProfileArchiveTransport {
                override suspend fun prepareArchive(input: ProfileArchiveReference, id: String, displayName: String): PortableProfileDocument =
                    error("Plugin imports do not use Profile archives")
                override suspend fun exportArchive(management: ProfileManagement, request: ProfilePortableExport,
                    consume: suspend (ProfileArchiveReference) -> Unit) = error("Plugin imports do not export")
            }
            val management = ProfileManagement(repository, { emptyList() }, ProfileExportReviewFactory { ProfileExportReview { null } },
                { _, _, _ -> error("Plugin imports do not use Bundles") }, transport,
                ProfilePluginImportPreparation { _, existing ->
                    preparations++
                    assertEquals(ProfileLock(), existing)
                    when (mode) {
                        "failure" -> error("Invalid package")
                        "cancel" -> throw CancellationException("Cancelled")
                        "conflict" -> repository.saveDraft(ProfileDefinition(id = "other"))
                    }
                    PreparedProfilePluginImport("example.code", lock)
                }, { error("Import must not activate") })
            val initial = repository.state().revision
            val request = ProfilePluginImport("editable", ProfileArchiveReference("input.kplugin", "a".repeat(64)), initial)
            assertFailsWith<IllegalArgumentException> { management.importPlugin(request.copy(expectedRevision = initial - 1)) }
            assertEquals(0, preparations)
            assertFailsWith<IllegalStateException> { management.importPlugin(request) }
            assertEquals(initial, repository.state().revision)
            mode = "cancel"
            assertFailsWith<CancellationException> { management.importPlugin(request) }
            assertEquals(initial, repository.state().revision)
            mode = "conflict"
            assertFailsWith<IllegalArgumentException> { management.importPlugin(request) }
            assertEquals(ProfileLock(), repository.loadDraftDocument("editable")?.packageImports)
            mode = "success"
            val result = management.importPlugin(request.copy(expectedRevision = repository.state().revision))
            val saved = assertNotNull(repository.loadDraftDocument("editable"))
            assertEquals(lock, saved.packageImports)
            management.write(ProfileDraftWrite(saved.definition.copy(displayName = "Renamed"), result.revision))
            repository.saveDraft(saved.definition.copy(displayName = "Saved"))
            assertEquals(lock, FileProfileRepository(directory).loadDraftDocument("editable")?.packageImports)
            management.clone(ProfileCloneRequest(ProfileTarget("editable", ProfileSource.Draft), "clone", repository.state().revision))
            assertEquals(lock, repository.loadDraftDocument("clone")?.packageImports)
        } finally { directory.deleteRecursively() }
    }
}
