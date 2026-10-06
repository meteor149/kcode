package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveImport
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProfileArchiveImportTest {
    @Test
    fun preflightPublicationConflictAndCancellationDoNotPublishDrafts(): Unit = runBlocking {
        val directory = Files.createTempDirectory("profile-archive-command").toFile()
        try {
            val repository = FileProfileRepository(directory)
            var calls = 0
            var cancel = false
            val transport = object : ProfileArchiveTransport {
                override suspend fun prepareArchive(input: ProfileArchiveReference, id: String, displayName: String): PortableProfileDocument {
                    calls++
                    if (cancel) throw CancellationException("cancelled")
                    repository.writeDraft(ProfileDraftDocument(ProfileDefinition(id = "other")), repository.state().revision)
                    return PortableProfileDocument(definition = ProfileDefinition(id = id), bundles = emptyList(), lock = ProfileLock())
                }
                override suspend fun exportArchive(management: ProfileManagement, request: ProfilePortableExport, consume: suspend (ProfileArchiveReference) -> Unit): Unit = error("No export")
            }
            val management = ProfileManagement(repository, { emptyList() }, ProfileExportReviewFactory { ProfileExportReview { null } },
                { _, _, _ -> error("No Bundle import") }, transport) { error("Import must not activate") }
            val request = ProfileArchiveImport(ProfileArchiveReference("source", "a".repeat(64)), "imported", 0)
            assertFailsWith<IllegalArgumentException> { management.importArchive(request.copy(expectedRevision = 1)) }
            assertEquals(0, calls)
            assertFailsWith<IllegalArgumentException> { management.importArchive(request) }
            assertNull(repository.loadDraft("imported"))
            val before = repository.state()
            cancel = true
            assertFailsWith<CancellationException> { management.importArchive(request.copy(expectedRevision = before.revision)) }
            assertEquals(before, repository.state())
            assertNull(repository.loadDraft("imported"))
        } finally { directory.deleteRecursively() }
    }
}
