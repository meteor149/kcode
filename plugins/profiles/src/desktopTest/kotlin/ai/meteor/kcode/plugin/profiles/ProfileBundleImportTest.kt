package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleImport
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProfileBundleImportTest {
    @Test
    fun preflightAndPublicationConflictsAndCancellationNeverPublishTheImportedDraft(): Unit = runBlocking {
        val directory = Files.createTempDirectory("profile-bundle-command").toFile()
        try {
            val repository = FileProfileRepository(directory)
            var prepared = 0
            var mode = "conflict"
            val management = ProfileManagement(repository, { emptyList() }, ProfileExportReviewFactory { ProfileExportReview { null } },
                { _, id, _ ->
                    prepared++
                    if (mode == "cancel") throw CancellationException("cancelled")
                    repository.writeDraft(ProfileDraftDocument(ProfileDefinition(id = "other")), repository.state().revision)
                    PortableProfileDocument(definition = ProfileDefinition(id = id), bundles = emptyList(), lock = ProfileLock())
                }, { error("Import must not activate") })
            val request = ProfileBundleImport(listOf(ProfileBundleArchiveReference("source", "a".repeat(64))), "imported", 0)
            assertFailsWith<IllegalArgumentException> { management.importBundles(request.copy(expectedRevision = 1)) }
            assertEquals(0, prepared)
            assertFailsWith<IllegalArgumentException> { management.importBundles(request) }
            assertNull(repository.loadDraft("imported"))
            assertEquals(1, prepared)
            val before = repository.state()
            mode = "cancel"
            assertFailsWith<CancellationException> { management.importBundles(request.copy(expectedRevision = before.revision)) }
            assertEquals(before, repository.state())
            assertNull(repository.loadDraft("imported"))
        } finally { directory.deleteRecursively() }
    }
}
