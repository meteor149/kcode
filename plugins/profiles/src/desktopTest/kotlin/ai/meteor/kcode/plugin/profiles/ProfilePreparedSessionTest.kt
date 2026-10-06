package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.DynamicPluginSpec
import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfilePreparedSessionTest {
    @Test
    fun repeatedPreparationAdvancesOneGenerationAndRetainsThePreviousTargetHistory(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-prepared-session").toFile()
        val repository = FileProfileRepository(root)
        val definition = ProfileDefinition(id = "target")
        try {
            val original = CommittedProfileGeneration(generation = 1, definition = definition,
                lock = ProfileLock(), composition = PluginCompositionSnapshot())
            repository.commit(original, null)
            val before = repository.state()
            val session = ProfileCompositionSession.prepareSwitch(repository, definition, before.revision)
            session.save(PluginCompositionSnapshot(builtinsEnabled = mapOf("first" to true)))
            val finalSnapshot = PluginCompositionSnapshot(builtinsEnabled = mapOf("second" to false))
            session.save(finalSnapshot)
            assertEquals(original, repository.loadCommitted("target"))
            assertEquals(before, repository.state())
            val committed = session.publishPreparedSwitch()
            assertEquals(2L, committed.generation)
            assertEquals(finalSnapshot, committed.composition)
            assertEquals(original, repository.loadGeneration("target", 1))
            assertEquals(listOf(1L, 2L), repository.generations("target"))
            session.save(finalSnapshot)
            assertEquals(3L, repository.state().selected?.generation)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun nativeStagedPreparationDoesNotSaveAMigrationDraftOrChangeSelection(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-native-staged-bootstrap").toFile()
        val repository = FileProfileRepository(root)
        val template = ProfileDefinition(id = "native")
        val resolver = object : PluginPackageResolver {
            override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> = error("No packages")
            override suspend fun verify(spec: DynamicPluginSpec) = error("No packages")
        }
        try {
            val before = repository.state()
            val activation = prepareNativeProfileActivation(repository, template, emptyList(), resolver, emptyMap(), emptySet(),
                requestedId = "native", stageSwitch = true)
            activation.session.commitDefinition(activation.resolved.definition, PluginCompositionSnapshot())
            assertEquals(before, repository.state())
            assertNull(repository.loadDraft("native"))
            assertNull(repository.loadCommitted("native"))
            activation.session.publishPreparedSwitch()
            assertEquals("native", repository.selected())
            assertEquals(1L, repository.state().selected?.generation)
        } finally { root.deleteRecursively() }
    }
}
