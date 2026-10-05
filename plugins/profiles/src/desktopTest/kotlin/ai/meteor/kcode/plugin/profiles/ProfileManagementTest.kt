package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.DynamicPluginSpec
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileManagementTest {
    private val resolver = object : PluginPackageResolver {
        override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> = error("No package imports expected")
        override suspend fun verify(spec: DynamicPluginSpec) = error("No package verification expected")
    }

    @Test
    fun clonedDraftKeepsFrozenBundleIntentAndPreviewDoesNotPublish(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-management")
        try {
            val repository = FileProfileRepository(root.toFile())
            val frozen = ProfileBundle(id = "base", version = "1", patches = listOf(ProfileOperation.Insert(
                listOf(ProfileEntry("provider", "example.provider", JsonPrimitive("frozen"))))))
            val definition = ProfileDefinition(id = "source", bundles = listOf(ProfileBundleReference("base", "1")))
            repository.commit(CommittedProfileGeneration(generation = 1, definition = definition, lock = ProfileLock(),
                composition = PluginCompositionSnapshot(), bundles = listOf(frozen)), null)
            val changedOffer = frozen.copy(patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("provider", "example.provider", JsonPrimitive("changed offer"))))))
            val management = ProfileManagement(repository, { listOf(changedOffer) }) { request ->
                prepare(repository, request, listOf(changedOffer))
            }
            val catalogue = management.catalogue()
            management.clone(ProfileCloneRequest(ProfileTarget("source"), "copy", catalogue.revision))
            val copied = assertNotNull(repository.loadDraftDocument("copy"))
            assertEquals(listOf(frozen), copied.base!!.bundles)
            assertEquals("profile", copied.definition.dataScope.workspace)
            assertNull(repository.loadCommitted("copy"))
            val before = repository.catalogue()
            val preview = management.preview(ProfileTarget("copy", ProfileSource.Draft))
            assertTrue(preview.packagesVerified)
            assertEquals(JsonPrimitive("frozen"), preview.entries.single().config)
            assertEquals(before, repository.catalogue())
            assertEquals(listOf(1L), repository.generations("source"))
            val staged = prepare(repository, ProfileActivationRequest(ProfileTarget("copy", ProfileSource.Draft), before.revision), listOf(changedOffer))
            staged.session.save(PluginCompositionSnapshot())
            staged.session.publishPreparedSwitch()
            assertEquals(listOf(frozen), repository.loadCommitted("copy")!!.bundles)
            assertEquals("copy", repository.selected())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun historyActivationAdvancesTheCurrentHeadInsteadOfRewindingIt(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-history-activation")
        try {
            val repository = FileProfileRepository(root.toFile())
            val original = ProfileDefinition(id = "coding", displayName = "Original")
            val snapshot = PluginCompositionSnapshot()
            repository.commit(CommittedProfileGeneration(generation = 1, definition = original, lock = ProfileLock(), composition = snapshot), null)
            repository.commit(CommittedProfileGeneration(generation = 2, definition = original.copy(displayName = "Edited"), lock = ProfileLock(), composition = snapshot), 1)
            val revision = repository.state().revision
            val restored = prepare(repository, ProfileActivationRequest(ProfileTarget("coding", ProfileSource.History, 1), revision))
            assertEquals(original, restored.resolved.definition)
            restored.session.save(snapshot)
            assertEquals(2L, repository.loadCommitted("coding")!!.generation)
            val published = restored.session.publishPreparedSwitch()
            assertEquals(3L, published.generation)
            assertEquals(original, published.definition)
            assertEquals(listOf(1L, 2L, 3L), repository.generations("coding"))
            assertEquals("Edited", repository.loadGeneration("coding", 2)!!.definition.displayName)
            assertFailsWith<IllegalArgumentException> {
                prepare(repository, ProfileActivationRequest(ProfileTarget("coding", ProfileSource.History, 1), revision))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun draftCasRejectsConcurrentEditsAndAnUnpublishedFileCannotReplaceTheVisibleDraft(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-draft-cas")
        try {
            val first = FileProfileRepository(root.toFile())
            val second = FileProfileRepository(root.toFile())
            val initial = ProfileDefinition(id = "coding", displayName = "Initial")
            first.writeDraft(ProfileDraftDocument(initial), first.state().revision, createOnly = true)
            val before = first.catalogue()
            first.writeDraft(ProfileDraftDocument(initial.copy(displayName = "First")), before.revision)
            assertFailsWith<IllegalArgumentException> {
                second.writeDraft(ProfileDraftDocument(initial.copy(displayName = "Stale")), before.revision)
            }
            assertEquals("First", second.loadDraft("coding")!!.displayName)
            val state = root.resolve(".profile-state.json")
            val fields = Json.parseToJsonElement(Files.readString(state)).jsonObject.toMutableMap()
            fields["revision"] = JsonPrimitive(Long.MAX_VALUE)
            Files.writeString(state, kotlinx.serialization.json.JsonObject(fields).toString())
            assertFailsWith<IllegalArgumentException> {
                second.writeDraft(ProfileDraftDocument(initial.copy(displayName = "Unpublished")), Long.MAX_VALUE)
            }
            assertEquals("First", FileProfileRepository(root.toFile()).loadDraft("coding")!!.displayName)
            assertEquals(3L, Files.list(root.resolve("coding/drafts")).use { it.count() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun legacyDraftRemainsReadOnlyAndInvalidPreviewReturnsDiagnostics(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-legacy-draft")
        try {
            Files.createDirectories(root.resolve("coding"))
            val legacy = root.resolve("coding/profile.json")
            val definition = ProfileDefinition(id = "coding")
            val text = Json.encodeToString(ProfileDefinition.serializer(), definition)
            Files.writeString(legacy, text)
            Files.writeString(root.resolve(".profile-state.json"), """{"formatVersion":1,"revision":0,"profiles":{"coding":{"draft":true,"generations":[]}}}""")
            val repository = FileProfileRepository(root.toFile())
            val management = ProfileManagement(repository, { emptyList() }) { request -> prepare(repository, request) }
            val before = management.catalogue()
            val invalid = definition.copy(patches = listOf(ProfileOperation.Disable("missing")))
            management.write(ProfileDraftWrite(invalid, before.revision))
            assertEquals(JsonPrimitive(2), Json.parseToJsonElement(Files.readString(root.resolve(".profile-state.json"))).jsonObject["formatVersion"])
            assertEquals(text, Files.readString(legacy))
            assertEquals(invalid, repository.loadDraft("coding"))
            val revision = repository.state().revision
            val preview = management.preview(ProfileTarget("coding", ProfileSource.Draft))
            assertFalse(preview.packagesVerified)
            assertEquals("missing", preview.diagnostics.single().target)
            assertEquals(revision, repository.state().revision)
            assertFailsWith<IllegalArgumentException> { management.remove("coding", before.revision) }
            management.remove("coding", revision)
            assertTrue(FileProfileRepository(root.toFile()).list().isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    private suspend fun prepare(repository: ProfileGenerationRepository, request: ProfileActivationRequest,
        bundles: List<ProfileBundle> = emptyList()): ProfileActivation = prepareNativeProfileActivation(
        repository, ProfileDefinition(id = "native"), bundles, resolver, emptyMap(), setOf("example.provider"),
        requestedId = request.target.profileId, stageSwitch = true, activationRequest = request,
    )
}
