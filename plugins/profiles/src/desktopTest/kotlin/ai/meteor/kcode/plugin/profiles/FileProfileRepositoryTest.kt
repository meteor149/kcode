package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class FileProfileRepositoryTest {
    @Test
    fun omittedLegacyVersionUpgradesToAnExplicitGenerationWithFrozenBundles(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-format-upgrade")
        val definition = ProfileDefinition(id = "legacy", bundles = listOf(ProfileBundleReference("base", "1")))
        val bundle = ProfileBundle(id = "base", version = "1", patches = emptyList())
        try {
            val folder = Files.createDirectories(root.resolve(definition.id))
            val old = CommittedProfileGeneration(formatVersion = 1, generation = 1, definition = definition,
                lock = ProfileLock(), composition = PluginCompositionSnapshot())
            val document = Json.parseToJsonElement(Json.encodeToString(old)).jsonObject
            Files.writeString(folder.resolve("committed.json"), JsonObject(document - "formatVersion").toString())
            val repository = FileProfileRepository(root.toFile())
            assertEquals(1, repository.loadCommitted(definition.id)?.formatVersion)
            val session = ProfileCompositionSession.open(repository, definition, initialBundles = listOf(bundle))
            session.save(session.load())
            val upgraded = repository.loadCommitted(definition.id)!!
            assertEquals(2, upgraded.formatVersion)
            assertEquals(listOf(bundle), upgraded.bundles)
            assertEquals(1, repository.loadGeneration(definition.id, 1)?.formatVersion)
            assertTrue("formatVersion" in Json.parseToJsonElement(Files.readString(generationFile(root, definition.id, 2))).jsonObject)
            assertEquals(JsonObject(document - "formatVersion").toString(), Files.readString(folder.resolve("committed.json")))
            assertFailsWith<IllegalArgumentException> { repository.commit(upgraded.copy(generation = 3, bundles = emptyList()), 2) }
            assertEquals(upgraded, repository.loadCommitted(definition.id))
        } finally { root.toFile().deleteRecursively() }
    }

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
        val file = generationFile(root, "coding", 1)
        Files.writeString(file, "{invalid")
        assertFailsWith<IllegalArgumentException> { repository.loadCommitted("coding") }
        assertEquals("{invalid", Files.readString(file))
        assertFailsWith<IllegalArgumentException> { repository.loadDraft("../outside") }
        assertFailsWith<IllegalArgumentException> { repository.select("missing") }
        Unit
    }

    private fun generationFile(root: Path, id: String, revision: Long): Path =
        Files.newDirectoryStream(root.resolve("$id/generations")).use { files ->
            files.single { file ->
                Json.parseToJsonElement(Files.readString(file)).jsonObject["generation"].toString() == revision.toString()
            }
        }

    @Test
    fun historiesStayImmutableAndSelectedGenerationAdvancesWithTheAuthority(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-history")
        try {
            val repository = FileProfileRepository(root.toFile())
            repository.commit(generation("coding", 1), null)
            repository.select("coding")
            val firstFile = generationFile(root, "coding", 1)
            val firstBytes = Files.readString(firstFile)
            val second = generation("coding", 2).copy(definition = ProfileDefinition(id = "coding", displayName = "Edited"))
            repository.commit(second, 1)
            val reopened = FileProfileRepository(root.toFile())
            assertEquals(listOf(1L, 2L), reopened.generations("coding"))
            assertEquals(generation("coding", 1), reopened.loadGeneration("coding", 1))
            assertEquals(second, reopened.state().selected)
            assertEquals(firstBytes, Files.readString(firstFile))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun publishAndSelectUsesOneCompareAndSetIncludingChangesToOtherProfiles(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-selection")
        try {
            val repository = FileProfileRepository(root.toFile())
            repository.commit(generation("old", 1), null)
            repository.select("old")
            val before = repository.state()
            repository.commit(generation("other", 1), null)
            assertFailsWith<IllegalArgumentException> {
                repository.commitAndSelect(generation("target", 1), null, before.revision)
            }
            assertEquals(generation("old", 1), repository.state().selected)
            assertNull(repository.loadCommitted("target"))
            val current = repository.state()
            repository.commitAndSelect(generation("target", 1), null, current.revision)
            val reopened = FileProfileRepository(root.toFile()).state()
            assertEquals(current.revision + 1, reopened.revision)
            assertEquals(generation("target", 1), reopened.selected)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun competingSwitchPublicationsSelectExactlyOneCompleteGeneration(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-switch-race")
        try {
            val repositories = listOf(FileProfileRepository(root.toFile()), FileProfileRepository(root.toFile()))
            val before = repositories.first().state()
            val outcomes = repositories.mapIndexed { index, repository ->
                async { runCatching { repository.commitAndSelect(generation("profile-$index", 1), null, before.revision) }.isSuccess }
            }.map { it.await() }
            assertEquals(1, outcomes.count { it })
            val winner = "profile-${outcomes.indexOf(true)}"
            val repository = FileProfileRepository(root.toFile())
            assertEquals(listOf(winner), repository.list())
            assertEquals(generation(winner, 1), repository.state().selected)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun unpublishedGenerationFilesNeverBecomeRecoveryCandidates(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-orphans")
        try {
            val repository = FileProfileRepository(root.toFile())
            repository.commit(generation("coding", 1), null)
            repository.select("coding")
            val before = repository.state()
            val orphan = root.resolve("coding/generations/${UUID.randomUUID()}.json")
            Files.writeString(orphan, Json.encodeToString(generation("coding", 2)))
            val orphanBytes = Files.readString(orphan)
            val reopened = FileProfileRepository(root.toFile())
            assertEquals(before, reopened.state())
            assertEquals(listOf(1L), reopened.generations("coding"))
            assertNull(reopened.loadGeneration("coding", 2))
            val candidate = generation("coding", 2).copy(definition = ProfileDefinition(id = "coding", displayName = "Retry"))
            reopened.commit(candidate, 1)
            assertEquals(candidate, reopened.loadGeneration("coding", 2))
            assertEquals(orphanBytes, Files.readString(orphan))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun corruptedAuthorityDoesNotFallBackToLegacySelectionOrRewriteItself(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-corrupt-authority")
        try {
            val repository = FileProfileRepository(root.toFile())
            repository.commit(generation("coding", 1), null)
            Files.writeString(root.resolve("coding/committed.json"), Json.encodeToString(generation("coding", 1)))
            Files.writeString(root.resolve("selection.json"), "\"coding\"")
            val stateFile = root.resolve(".profile-state.json")
            Files.writeString(stateFile, "{invalid")
            val reopened = FileProfileRepository(root.toFile())
            assertFailsWith<IllegalArgumentException> { reopened.state() }
            assertFailsWith<IllegalArgumentException> { reopened.loadCommitted("coding") }
            assertEquals("{invalid", Files.readString(stateFile))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun deletionAndRecreationDoNotResurrectRetainedMetadata(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-removal")
        try {
            val repository = FileProfileRepository(root.toFile())
            repository.saveDraft(ProfileDefinition(id = "coding", displayName = "Old"))
            repository.commit(generation("coding", 1), null)
            val businessData = root.resolve("coding/borrowed-data.txt")
            Files.writeString(businessData, "durable data")
            repository.remove("coding")
            val reopened = FileProfileRepository(root.toFile())
            assertTrue(reopened.list().isEmpty())
            assertNull(reopened.loadDraft("coding"))
            assertNull(reopened.loadCommitted("coding"))
            assertTrue(reopened.generations("coding").isEmpty())
            reopened.saveDraft(ProfileDefinition(id = "coding", displayName = "New"))
            assertNull(reopened.loadCommitted("coding"))
            val fresh = generation("coding", 1).copy(definition = ProfileDefinition(id = "coding", displayName = "New"))
            reopened.commit(fresh, null)
            assertEquals(fresh, reopened.loadGeneration("coding", 1))
            assertEquals("durable data", Files.readString(businessData))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun legacySelectionMigratesOnceAndThenStopsControllingTheAuthority(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-legacy-selection")
        try {
            val folder = Files.createDirectories(root.resolve("legacy"))
            val old = generation("legacy", 4)
            Files.writeString(folder.resolve("committed.json"), Json.encodeToString(old))
            Files.writeString(root.resolve("selection.json"), "\"legacy\"")
            val repository = FileProfileRepository(root.toFile())
            assertEquals(old, repository.state().selected)
            assertEquals(listOf(4L), repository.generations("legacy"))
            repository.select(null)
            assertNull(FileProfileRepository(root.toFile()).state().selected)
            assertEquals("\"legacy\"", Files.readString(root.resolve("selection.json")))
        } finally { root.toFile().deleteRecursively() }
    }
}
