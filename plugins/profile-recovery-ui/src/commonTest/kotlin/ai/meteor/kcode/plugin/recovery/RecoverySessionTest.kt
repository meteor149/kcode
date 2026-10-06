package ai.meteor.kcode.plugin.recovery

import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
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

    @Test
    fun unreadableSelectedDefinitionRetainsCatalogueForTemplateRecovery() = runTest {
        val client = Client().apply { draftFailure = "draft damaged" }
        val session = RecoverySession(client)
        session.refresh()
        assertEquals(null, session.state.value.target)
        assertNotNull(session.state.value.catalogue)
        assertEquals("draft damaged", session.state.value.failure)
        session.createFromTemplate(ProfileDefinition(id = "native"), "repair")
        assertEquals(ProfileTarget("repair", ProfileSource.Draft), session.state.value.target)
        assertEquals(1, client.writes)
        assertEquals("broken", client.definition.id)
    }

    @Test
    fun historicalActivationKeepsItsSourceAndNeverWritesAnImplicitDraft() = runTest {
        val client = Client().apply { generation = 3 }
        val session = RecoverySession(client)
        session.refresh()
        val target = ProfileTarget("broken", ProfileSource.History, 3)
        session.select(target)
        val document = session.state.value.document
        session.edit("overwrite historical intent")
        session.save()
        assertEquals("history_readonly", session.state.value.failure)
        assertEquals(document, session.state.value.document)
        session.activate(saveFirst = true)
        assertEquals(target, (client.command as ProfileCommand.Activate).request.target)
        assertEquals(0, client.writes)
        session.copySelected("copy")
        assertEquals(target, client.cloned?.source)
        assertEquals(ProfileSource.Draft, session.state.value.target?.source)
        session.edit(encode(client.created.getValue("copy").copy(displayName = "Edited copy")))
        session.save()
        assertEquals("Edited copy", client.created.getValue("copy").displayName)
    }

    @Test
    fun templateCopyPreservesTheOriginalAndUsesSeparateDataScopes() = runTest {
        val client = Client()
        val session = RecoverySession(client)
        session.refresh()
        val original = client.definition
        val template = ProfileDefinition(id = "native", bundles = listOf(ProfileBundleReference("native.base", "1")),
            dataScope = ProfileDataScope(settings = "legacy", history = "legacy"))
        session.createFromTemplate(template, "repair")
        val created = client.created.getValue("repair")
        assertEquals(template.bundles, created.bundles)
        assertEquals(ProfileDataScope(workspace = "profile"), created.dataScope)
        assertEquals(original, client.definition)
        assertTrue(client.lastWrite!!.createOnly)
        assertEquals(ProfileTarget("repair", ProfileSource.Draft), session.state.value.target)
        session.createFromTemplate(template, "repair")
        assertNotNull(session.state.value.failure)
        assertEquals(1, client.writes)
        assertEquals(0, client.submissions)
    }

    @Test
    fun dirtyEditorAndConflictingRevisionCannotCreateACopy() = runTest {
        val client = Client()
        val session = RecoverySession(client)
        session.refresh()
        session.edit("unfinished")
        session.createFromTemplate(ProfileDefinition(id = "native"), "repair")
        session.copySelected("copy")
        assertEquals(0, client.writes)
        assertEquals(null, client.cloned)
        session.discard()
        client.revision++
        session.createFromTemplate(ProfileDefinition(id = "native"), "repair")
        assertEquals(0, client.writes)
        assertEquals("broken", session.state.value.target?.profileId)
    }

    @Test
    fun unreadableHistoryDoesNotPreventRepairingAnAvailableDraft() = runTest {
        val client = Client().apply { historyFailure = "history damaged" }
        val session = RecoverySession(client)
        session.refresh()
        assertEquals(ProfileTarget("broken", ProfileSource.Draft), session.state.value.target)
        assertEquals("history damaged", session.state.value.historyFailure)
        session.edit(encode(client.definition.copy(displayName = "Repair")))
        session.save()
        assertEquals("Repair", client.definition.displayName)
    }

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
        val created = linkedMapOf<String, ProfileDefinition>()
        var cloned: ProfileCloneRequest? = null
        var lastWrite: ProfileDraftWrite? = null
        var historyFailure: String? = null
        var draftFailure: String? = null
        override suspend fun catalogue() = ProfileCatalogue(revision, "broken", listOf(
            ProfileSummary("broken", "Broken", true, generation), ProfileSummary("other", "Other", true, null),
        ) + created.values.map { ProfileSummary(it.id, it.displayName, true, null) })
        override suspend fun draft(id: String): ProfileDefinition {
            if (id == "broken") draftFailure?.let { error(it) }
            return created[id] ?: if (id == "broken") definition else ProfileDefinition(id = id)
        }
        override suspend fun writeDraft(write: ProfileDraftWrite): ProfileCatalogue {
            check(write.expectedRevision == revision) { "conflict" }
            check(!write.createOnly || write.definition.id !in setOf("broken", "other") + created.keys) { "exists" }
            writes++
            lastWrite = write
            if (write.definition.id == "broken") definition = write.definition else created[write.definition.id] = write.definition
            revision++
            return catalogue()
        }
        override suspend fun history(id: String): List<ProfileCompositionState> {
            historyFailure?.let { error(it) }
            return if (generation == null || id != "broken") emptyList()
            else listOf(ProfileCompositionState(ProfileDefinition(id = id, displayName = "Committed"), 3))
        }
        override suspend fun modules(): List<ProfileModuleSummary> = error("Recovery cannot depend on runtime modules")
        override suspend fun preview(target: ProfileTarget): ProfilePreview = error("Recovery cannot resolve broken modules")
        override suspend fun clone(request: ProfileCloneRequest): ProfileCatalogue {
            check(request.expectedRevision == revision) { "conflict" }
            check(request.id !in setOf("broken", "other") + created.keys) { "exists" }
            val source = if (request.source.source == ProfileSource.History)
                history(request.source.profileId).single { it.generation == request.source.generation }.definition
            else requireNotNull(draft(request.source.profileId))
            created[request.id] = source.copy(id = request.id, displayName = request.displayName, dataScope = request.dataScope)
            cloned = request
            revision++
            return catalogue()
        }
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
