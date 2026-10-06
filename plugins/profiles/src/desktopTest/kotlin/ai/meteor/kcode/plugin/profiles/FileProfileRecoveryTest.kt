package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileProfileRecoveryTest {
    private fun generation(id: String) = CommittedProfileGeneration(
        generation = 1, definition = ProfileDefinition(id = id), lock = ProfileLock(), composition = PluginCompositionSnapshot(),
    )

    @Test
    fun healthyRepositoryDoesNotOfferOrApplyRepair(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-healthy-recovery")
        try {
            val repository = FileProfileRepository(root.toFile())
            val before = repository.state()
            assertNull(repository.inspectRecovery())
            assertFailsWith<IllegalStateException> {
                repository.repair(ProfileRepositoryRepairRequest("0".repeat(64), ProfileRepositoryRepairMode.StartEmpty))
            }
            assertEquals(before, repository.state())
        } finally { root.toFile().deleteRecursively() }
    }

    private suspend fun committed(root: Path): FileProfileRepository = FileProfileRepository(root.toFile()).also {
        it.saveDraft(ProfileDefinition(id = "coding", displayName = "Original"))
        it.commit(generation("coding"), null)
        it.select("coding")
        it.saveDraft(ProfileDefinition(id = "coding", displayName = "Later edit"))
    }

    @Test
    fun checkpointRestorationPreservesDamageAndNeverAdoptsOrphans(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-checkpoint-recovery")
        try {
            val repository = committed(root)
            val lostRevision = repository.state().revision
            val orphan = root.resolve("coding/generations/${UUID.randomUUID()}.json")
            val orphanBytes = Json.encodeToString(generation("coding").copy(generation = 2)).encodeToByteArray()
            Files.write(orphan, orphanBytes)
            val business = root.resolve("durable-data.txt")
            Files.writeString(business, "business data")
            val primary = root.resolve(".profile-state.json")
            Files.writeString(primary, "{damaged authority")
            val review = assertNotNull(repository.inspectRecovery())
            assertEquals("coding", assertNotNull(review.checkpoint).selectedProfileId)
            assertEquals(1L, review.checkpoint!!.profiles.single().generation)
            assertEquals("{damaged authority", Files.readString(primary))
            val result = repository.repair(ProfileRepositoryRepairRequest(review.fingerprint, ProfileRepositoryRepairMode.RestoreCheckpoint))
            assertTrue(result.catalogue.revision != lostRevision)
            assertEquals("coding", result.catalogue.selectedProfileId)
            assertEquals("Original", repository.loadDraft("coding")!!.displayName)
            assertEquals(listOf(1L), repository.generations("coding"))
            assertEquals("{damaged authority", Files.readString(root.resolve(".profile-recovery/${result.evidenceId}/.profile-state.json")))
            assertContentEquals(orphanBytes, Files.readAllBytes(orphan))
            assertEquals("business data", Files.readString(business))
            assertFailsWith<IllegalArgumentException> {
                repository.writeDraft(ProfileDraftDocument(ProfileDefinition(id = "stale")), lostRevision, createOnly = true)
            }
            assertEquals(result.catalogue, FileProfileRepository(root.toFile()).catalogue())
            assertNull(repository.inspectRecovery())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun resetWithoutCheckpointPreservesInvalidUtf8AndLegacyFiles(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-empty-recovery")
        try {
            val repository = FileProfileRepository(root.toFile())
            repository.state()
            val primary = root.resolve(".profile-state.json")
            val damaged = byteArrayOf(0xff.toByte(), 0, 1, 2)
            Files.write(primary, damaged)
            val folder = Files.createDirectories(root.resolve("retained/generations"))
            val orphan = folder.resolve("${UUID.randomUUID()}.json")
            Files.writeString(orphan, Json.encodeToString(generation("retained")))
            val legacy = root.resolve("retained/committed.json")
            Files.writeString(legacy, Json.encodeToString(generation("retained")))
            val review = assertNotNull(repository.inspectRecovery())
            assertNull(review.checkpoint)
            assertFailsWith<IllegalStateException> {
                repository.repair(ProfileRepositoryRepairRequest(review.fingerprint, ProfileRepositoryRepairMode.RestoreCheckpoint))
            }
            assertContentEquals(damaged, Files.readAllBytes(primary))
            val result = repository.repair(ProfileRepositoryRepairRequest(review.fingerprint, ProfileRepositoryRepairMode.StartEmpty))
            assertTrue(result.catalogue.profiles.isEmpty())
            assertNull(result.catalogue.selectedProfileId)
            assertContentEquals(damaged, Files.readAllBytes(root.resolve(".profile-recovery/${result.evidenceId}/.profile-state.json")))
            assertTrue(Files.exists(legacy) && Files.exists(orphan))
            val reopened = FileProfileRepository(root.toFile())
            assertTrue(reopened.list().isEmpty())
            assertNull(reopened.loadCommitted("retained"))
            val saved = ProfileDraftDocument(ProfileDefinition(id = "repair"))
            reopened.writeDraft(saved, result.catalogue.revision, createOnly = true)
            assertEquals(saved.definition, reopened.loadDraft("repair"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun changedAuthorityOrCheckpointDocumentsRejectTheReviewedRequest(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-stale-recovery")
        try {
            val repository = committed(root)
            val primary = root.resolve(".profile-state.json")
            Files.writeString(primary, "{broken")
            val review = assertNotNull(repository.inspectRecovery())
            Files.writeString(primary, "{changed")
            assertFailsWith<IllegalArgumentException> {
                repository.repair(ProfileRepositoryRepairRequest(review.fingerprint, ProfileRepositoryRepairMode.StartEmpty))
            }
            val fresh = assertNotNull(repository.inspectRecovery())
            val checkpoint = Json.parseToJsonElement(Files.readString(root.resolve(".profile-checkpoint.json")))
                .toString()
            val generation = Files.newDirectoryStream(root.resolve("coding/generations")).use { it.single() }
            Files.writeString(generation, "{changed generation")
            assertFailsWith<IllegalArgumentException> {
                repository.repair(ProfileRepositoryRepairRequest(fresh.fingerprint, ProfileRepositoryRepairMode.RestoreCheckpoint))
            }
            assertNull(assertNotNull(repository.inspectRecovery()).checkpoint)
            assertEquals("{changed", Files.readString(primary))
            assertEquals(checkpoint, Json.parseToJsonElement(Files.readString(root.resolve(".profile-checkpoint.json"))).toString())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun missingAuthorityRequiresExplicitRepairInsteadOfLegacyMigration(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-missing-authority")
        try {
            val repository = committed(root)
            Files.writeString(root.resolve("coding/committed.json"), Json.encodeToString(generation("coding")))
            Files.delete(root.resolve(".profile-state.json"))
            assertFailsWith<IllegalArgumentException> { FileProfileRepository(root.toFile()).state() }
            assertTrue(!Files.exists(root.resolve(".profile-state.json")))
            val review = assertNotNull(repository.inspectRecovery())
            val restored = repository.repair(ProfileRepositoryRepairRequest(review.fingerprint, ProfileRepositoryRepairMode.RestoreCheckpoint))
            assertEquals("coding", restored.catalogue.selectedProfileId)
            assertEquals("Original", repository.loadDraft("coding")!!.displayName)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun failedEvidencePreparationLeavesAuthorityUntouched(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-evidence-failure")
        try {
            val repository = committed(root)
            val primary = root.resolve(".profile-state.json")
            Files.writeString(primary, "{broken")
            val review = assertNotNull(repository.inspectRecovery())
            Files.writeString(root.resolve(".profile-recovery"), "obstruction")
            assertFailsWith<FileAlreadyExistsException> {
                repository.repair(ProfileRepositoryRepairRequest(review.fingerprint, ProfileRepositoryRepairMode.RestoreCheckpoint))
            }
            assertEquals("{broken", Files.readString(primary))
            assertEquals("obstruction", Files.readString(root.resolve(".profile-recovery")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun checkpointPublicationFailureDoesNotCommitTheStagedDraft(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-checkpoint-failure")
        try {
            val repository = committed(root)
            val before = repository.catalogue()
            val original = repository.loadDraft("coding")
            Files.delete(root.resolve(".profile-checkpoint.json"))
            Files.createDirectory(root.resolve(".profile-checkpoint.json"))
            assertFailsWith<IllegalArgumentException> {
                repository.writeDraft(ProfileDraftDocument(ProfileDefinition(id = "unpublished")), before.revision, createOnly = true)
            }
            assertEquals(before, repository.catalogue())
            assertEquals(original, repository.loadDraft("coding"))
            assertNull(repository.loadDraft("unpublished"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun competingRepairsPublishExactlyOneNewAuthority(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-repair-race")
        try {
            val first = committed(root)
            val second = FileProfileRepository(root.toFile())
            Files.writeString(root.resolve(".profile-state.json"), "{broken")
            val request = ProfileRepositoryRepairRequest(assertNotNull(first.inspectRecovery()).fingerprint,
                ProfileRepositoryRepairMode.RestoreCheckpoint)
            val outcomes = listOf(first, second).map { repository -> async { runCatching { repository.repair(request) }.isSuccess } }
                .map { it.await() }
            assertEquals(1, outcomes.count { it })
            assertNull(first.inspectRecovery())
        } finally { root.toFile().deleteRecursively() }
    }
}
