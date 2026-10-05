package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileProfileRepositoryTest {
    private fun generation(id: String, revision: Long) = CommittedProfileGeneration(
        generation = revision,
        definition = ProfileDefinition(id = id),
        lock = ProfileLock(),
        composition = PluginCompositionSnapshot(),
    )

    @Test
    fun draftsAndLastGoodGenerationsSurviveReopeningIndependently() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-test")
        val repository = FileProfileRepository(root.toFile())
        repository.saveDraft(ProfileDefinition(id = "coding"))
        repository.commit(generation("coding", 1), null)
        repository.select("coding")
        val edited = ProfileDefinition(id = "coding", patches = listOf(ProfileOperation.Disable("missing")))
        repository.saveDraft(edited)
        val reopened = FileProfileRepository(root.toFile())
        assertEquals(edited, reopened.loadDraft("coding"))
        assertEquals(generation("coding", 1), reopened.loadCommitted("coding"))
        assertEquals("coding", reopened.selected())
        assertEquals(listOf("coding"), reopened.list())
    }

    @Test
    fun publicationRejectsStaleRevisionAndInvalidLockWithoutReplacingPreviousCommit() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-test")
        val repository = FileProfileRepository(root.toFile())
        repository.commit(generation("coding", 1), null)
        assertFailsWith<IllegalArgumentException> { repository.commit(generation("coding", 2), null) }
        val corrupt = generation("coding", 2).copy(lock = ProfileLock(packages = listOf(
            LockedProfilePackage("extra", "1", "0".repeat(64), "desktop", "1".repeat(64)),
        )))
        assertFailsWith<IllegalArgumentException> { repository.commit(corrupt, 1) }
        assertEquals(generation("coding", 1), repository.loadCommitted("coding"))
    }

    @Test
    fun competingRepositoriesPublishExactlyOneNextGeneration() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-test")
        val first = FileProfileRepository(root.toFile())
        val second = FileProfileRepository(root.toFile())
        first.commit(generation("coding", 1), null)
        val outcomes = listOf(first, second).map { repository ->
            async { runCatching { repository.commit(generation("coding", 2), 1) }.isSuccess }
        }.map { it.await() }
        assertEquals(1, outcomes.count { it })
        assertEquals(2L, first.loadCommitted("coding")?.generation)
    }

    @Test
    fun profilesHaveSeparateStateAndSelectedProfilesCannotBeDeleted() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-test")
        val repository = FileProfileRepository(root.toFile())
        repository.commit(generation("first", 1), null)
        repository.commit(generation("second", 1), null)
        repository.select("first")
        assertFailsWith<IllegalArgumentException> { repository.remove("first") }
        repository.remove("second")
        assertNull(repository.loadCommitted("second"))
        assertEquals(generation("first", 1), repository.loadCommitted("first"))
        repository.select(null)
        repository.remove("first")
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun corruptCommitIsReportedAndNeverRewrittenDuringRead() = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-test")
        val repository = FileProfileRepository(root.toFile())
        repository.commit(generation("coding", 1), null)
        val file = root.resolve("coding/committed.json")
        Files.writeString(file, "{invalid")
        assertFailsWith<IllegalArgumentException> { repository.loadCommitted("coding") }
        assertEquals("{invalid", Files.readString(file))
        assertFailsWith<IllegalArgumentException> { repository.loadDraft("../outside") }
        assertFailsWith<IllegalArgumentException> { repository.select("missing") }
        Unit
    }
}
