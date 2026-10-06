package ai.meteor.kcode.plugin.recovery

import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandHandle
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandStatus
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionState
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementState
import ai.meteor.kcode.plugin.api.profiles.ProfileModuleSummary
import ai.meteor.kcode.plugin.api.profiles.ProfilePreview
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileSummary
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecoverySessionTest {
    @Test
    fun brokenModuleDraftCanBeEditedWithoutResolvingProductServices() = runTest {
        val client = Client()
        val session = RecoverySession(client)
        session.refresh()
        assertEquals(ProfileTarget("broken", ProfileSource.Draft), session.state.value.target)
        session.edit(encode(client.definition.copy(displayName = "Repaired")))
        session.activate(saveFirst = true)
        assertEquals("Repaired", client.definition.displayName)
        assertFalse(session.state.value.dirty)
        assertEquals(1, client.submissions)
        assertEquals(2L, (client.command as ProfileCommand.Activate).request.expectedRevision)
    }

    @Test
    fun refreshRetainsDirtyRevisionAndConflictDoesNotDiscardEdits() = runTest {
        val client = Client()
        val session = RecoverySession(client)
        session.refresh()
        val edited = encode(client.definition.copy(displayName = "Local edit"))
        session.edit(edited)
        client.revision++
        session.refresh()
        assertEquals(1L, session.state.value.revision)
        session.save()
        assertEquals(edited, session.state.value.document)
        assertTrue(session.state.value.dirty)
        assertNotNull(session.state.value.failure)
        assertEquals(0, client.writes)
    }

    @Test
    fun dirtySelectionAndActivationRequireExplicitSaveOrDiscard() = runTest {
        val client = Client()
        val session = RecoverySession(client)
        session.refresh()
        val saved = session.state.value.document
        session.edit("unfinished")
        session.select(ProfileTarget("other", ProfileSource.Draft))
        session.activate()
        assertEquals("unfinished", session.state.value.document)
        assertEquals("broken", session.state.value.target?.profileId)
        assertEquals(0, client.submissions)
        session.discard()
        assertEquals(saved, session.state.value.document)
        session.select(ProfileTarget("other", ProfileSource.Draft))
        assertEquals("other", session.state.value.target?.profileId)
    }

    @Test
    fun invalidJsonAndChangedIdentityNeverWriteOrActivate() = runTest {
        val client = Client()
        val session = RecoverySession(client)
        session.refresh()
        for (document in listOf("invalid", encode(ProfileDefinition(id = "other")))) {
            session.edit(document)
            session.activate(saveFirst = true)
            assertEquals(document, session.state.value.document)
            assertNotNull(session.state.value.failure)
        }
        assertEquals(0, client.writes)
        assertEquals(0, client.submissions)
    }

    @Test
    fun failedActivationKeepsSavedRepairAvailableForRetry() = runTest {
        val client = Client().apply { result = ProfileCommandPhase.Failed }
        val session = RecoverySession(client)
        session.refresh()
        session.edit(encode(client.definition.copy(displayName = "Repaired")))
        session.activate(saveFirst = true)
        assertFalse(session.state.value.dirty)
        assertTrue(session.state.value.document.contains("Repaired"))
        assertEquals("cleanup failed", session.state.value.failure)
        client.result = ProfileCommandPhase.Succeeded
        session.activate()
        assertEquals(1, client.writes)
        assertEquals(2, client.submissions)
    }

    @Test
    fun duplicateClickAndObserverCancellationDoNotCancelAcceptedCommand() = runTest {
        val client = Client().apply { gate = CompletableDeferred() }
        val session = RecoverySession(client)
        session.refresh()
        val activation = async { session.activate() }
        runCurrent()
        assertTrue(session.state.value.busy)
        session.activate()
        assertEquals(1, client.submissions)
        activation.cancel()
        activation.join()
        assertFalse(session.state.value.busy)
        assertEquals(0, client.cancellations)
        client.gate!!.complete(ProfileCommandStatus(1, ProfileCommandPhase.Succeeded))
    }

    @Test
    fun initialBindingWaitsAndClosedHostRejectsMetadata() = runTest {
        val client = Client().apply { state.value = ProfileManagementState(ProfileManagementPhase.Starting) }
        val session = RecoverySession(client)
        val query = async { session.refresh() }
        runCurrent()
        assertEquals(null, session.state.value.catalogue)
        client.state.value = ProfileManagementState(ProfileManagementPhase.RecoveryRequired)
        query.await()
        assertNotNull(session.state.value.target)
        client.state.value = ProfileManagementState(ProfileManagementPhase.Closed)
        session.activate()
        assertEquals("closed", session.state.value.failure)
        assertEquals(0, client.submissions)
    }

    @Test
    fun committedSelectionLoadsExactlyTheCataloguedGeneration() = runTest {
        val client = Client().apply { generation = 3 }
        val session = RecoverySession(client)
        session.refresh()
        assertEquals(ProfileSource.Committed, session.state.value.target?.source)
        assertEquals("Committed", Json.decodeFromString(ProfileDefinition.serializer(), session.state.value.document).displayName)
    }

    private fun encode(definition: ProfileDefinition) = Json.encodeToString(ProfileDefinition.serializer(), definition)

    private class Client : ProfileManagementClient {
        override val state = MutableStateFlow(ProfileManagementState(ProfileManagementPhase.RecoveryRequired))
        override val commands = MutableStateFlow(emptyList<ProfileCommandStatus>())
        var revision = 1L
        var generation: Long? = null
        var definition = ProfileDefinition(id = "broken")
        var writes = 0
        var submissions = 0
        var cancellations = 0
        var command: ProfileCommand? = null
        var result = ProfileCommandPhase.Succeeded
        var gate: CompletableDeferred<ProfileCommandStatus>? = null
        override suspend fun catalogue() = ProfileCatalogue(revision, "broken", listOf(
            ProfileSummary("broken", "Broken", true, generation), ProfileSummary("other", "Other", true, null),
        ))
        override suspend fun draft(id: String) = if (id == "broken") definition else ProfileDefinition(id = id)
        override suspend fun writeDraft(write: ProfileDraftWrite): ProfileCatalogue {
            check(write.expectedRevision == revision) { "conflict" }
            writes++
            definition = write.definition
            revision++
            return catalogue()
        }
        override suspend fun history(id: String) = listOf(ProfileCompositionState(ProfileDefinition(id = id, displayName = "Committed"), 3))
        override suspend fun modules(): List<ProfileModuleSummary> = error("Recovery cannot depend on runtime modules")
        override suspend fun preview(target: ProfileTarget): ProfilePreview = error("Recovery cannot resolve broken modules")
        override suspend fun clone(request: ProfileCloneRequest): ProfileCatalogue = error("unused")
        override suspend fun delete(id: String, expectedRevision: Long): ProfileCatalogue = error("unused")
        override fun submit(command: ProfileCommand): ProfileCommandHandle {
            submissions++
            this.command = command
            return object : ProfileCommandHandle {
                override val id = submissions.toLong()
                override val state = MutableStateFlow(ProfileCommandStatus(id, ProfileCommandPhase.Running))
                override suspend fun await() = gate?.await() ?: ProfileCommandStatus(id, result,
                    failure = if (result == ProfileCommandPhase.Failed) "cleanup failed" else null)
                override fun cancel() { cancellations++ }
            }
        }
    }
}
