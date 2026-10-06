package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
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
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfilePreview
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableImport
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleImport
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveImport
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileSummary
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileUiSessionTest {
    @Test
    fun archiveExchangeConsumesExportLeaseAndImportsDraftWithCapturedRevision(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val files = Files()
            files.onArchiveWrite = { assertTrue(client.archiveLeaseLive) }
            session.exportArchive(files, "Export")
            assertEquals("original.kprofile", files.name)
            assertEquals(client.archive, files.writtenArchive)
            assertFalse(client.archiveLeaseLive)
            assertEquals(ProfileExchangeResult.Exported, session.state.value.exchange)
            val previews = client.previewCalls
            session.importArchive(files, "copy", "Copy", "Import")
            assertEquals(ProfileArchiveImport(files.archive!!, "copy", 1, "Copy"), client.archiveImported)
            assertEquals(ProfileTarget("copy", ProfileSource.Draft), session.state.value.target)
            assertEquals(previews, client.previewCalls)
            assertNull(client.submitted)
            files.onRead = { client.revision++ }
            val document = session.state.value.document
            session.importArchive(files, "conflict", "Conflict", "Import")
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
            assertEquals(document, session.state.value.document)
            assertEquals("copy", client.archiveImported!!.id)
        } finally { session.close() }
    }

    @Test
    fun archivePickerCancellationAndWithdrawalPublishNoImport(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        session.refresh()
        val files = Files().apply { archive = null }
        val before = session.state.value
        session.importArchive(files, "copy", "Copy", "Import")
        assertEquals(before, session.state.value)
        files.archive = client.archive
        files.gate = CompletableDeferred()
        val pending = async { session.importArchive(files, "copy", "Copy", "Import") }
        runCurrent()
        session.close()
        assertFailsWith<CancellationException> { pending.await() }
        assertTrue(files.finished)
        assertNull(client.archiveImported)
    }

    @Test
    fun bundleImportPreservesOrderAndRevisionAndDoesNotActivate(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val previews = client.previewCalls
            val files = Files()
            val importing = async { session.importBundles(files, "copy", "Copy", "Bundles") }
            runCurrent()
            assertNull(client.bundleImported)
            assertFalse(files.finished)
            session.moveBundle(1, -1)
            assertEquals(listOf("second", "first"), session.state.value.bundleSelection!!.map { it.name })
            session.finishBundleSelection(true)
            importing.await()
            assertTrue(files.finished)
            assertEquals(ProfileBundleImport(files.bundles.reversed(), "copy", 1, "Copy"), client.bundleImported)
            assertEquals(ProfileTarget("copy", ProfileSource.Draft), session.state.value.target)
            assertNull(session.state.value.preview)
            assertEquals(previews, client.previewCalls)
            assertNull(client.submitted)
            files.onRead = { client.revision++ }
            val document = session.state.value.document
            val conflicting = async { session.importBundles(files, "conflict", "Conflict", "Bundles") }
            runCurrent()
            session.finishBundleSelection(true)
            conflicting.await()
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
            assertEquals(document, session.state.value.document)
            assertEquals("copy", client.bundleImported!!.id)
        } finally { session.close() }
    }

    @Test
    fun bundleOrderCancellationAndWithdrawalReleaseInputsWithoutPublishing(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        session.refresh()
        val before = session.state.value
        val cancelledFiles = Files()
        val cancelled = async { session.importBundles(cancelledFiles, "copy", "Copy", "Bundles") }
        runCurrent()
        assertEquals(listOf(0, 1), session.state.value.bundleSelection!!.map { it.token })
        assertFailsWith<IllegalArgumentException> { session.moveBundle(0, -1) }
        session.finishBundleSelection(false)
        cancelled.await()
        assertTrue(cancelledFiles.finished)
        assertEquals(before, session.state.value)
        assertNull(client.bundleImported)
        val withdrawnFiles = Files()
        val withdrawn = async { session.importBundles(withdrawnFiles, "copy", "Copy", "Bundles") }
        runCurrent()
        assertFalse(withdrawnFiles.finished)
        session.close()
        assertFailsWith<CancellationException> { withdrawn.await() }
        assertTrue(withdrawnFiles.finished)
        assertNull(session.state.value.bundleSelection)
        assertFalse(session.state.value.busy)
        assertNull(client.bundleImported)
    }

    @Test
    fun bundlePickerCancellationAndWithdrawalLeaveNoCommand(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        session.refresh()
        val before = session.state.value
        val files = Files().apply { bundles = emptyList() }
        session.importBundles(files, "copy", "Copy", "Bundles")
        assertEquals(before, session.state.value)
        files.bundles = listOf(ProfileBundleArchiveReference("a", "a".repeat(64)))
        files.gate = CompletableDeferred()
        val import = async { session.importBundles(files, "copy", "Copy", "Bundles") }
        runCurrent()
        session.close()
        assertFailsWith<CancellationException> { import.await() }
        assertTrue(files.finished)
        assertNull(client.bundleImported)
    }
    @Test
    fun fileImportCreatesAnUnpreparedDraftAndCapturesRevisionBeforeSelection(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val previews = client.previewCalls
            val files = Files()
            session.importFile(files, "copy", "Copy", "Import")
            assertEquals(ProfilePortableImport("portable", "copy", 1, "Copy"), client.imported)
            assertEquals(ProfileTarget("copy", ProfileSource.Draft), session.state.value.target)
            assertEquals(2L, session.state.value.documentRevision)
            assertEquals(ProfileExchangeResult.Imported, session.state.value.exchange)
            assertNull(session.state.value.preview)
            assertEquals(previews, client.previewCalls)
            assertEquals(0, client.writes)
            assertNull(client.submitted)
        } finally { session.close() }
    }

    @Test
    fun cancelledAndConflictingImportPreserveTheEditor(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val before = session.state.value
            val files = Files().apply { document = null }
            session.importFile(files, "copy", "Copy", "Import")
            assertEquals(before, session.state.value)
            assertNull(client.imported)
            files.document = "portable"
            files.onRead = { client.revision++ }
            session.importFile(files, "copy", "Copy", "Import")
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
            assertEquals(before.document, session.state.value.document)
            assertEquals(before.target, session.state.value.target)
            assertEquals(before.documentRevision, session.state.value.documentRevision)
            assertNull(client.imported)
            session.edit("unfinished")
            val reads = files.reads
            session.importFile(files, "copy", "Copy", "Import")
            assertEquals(reads, files.reads)
            assertEquals("unfinished", session.state.value.document)
        } finally { session.close() }
    }

    @Test
    fun historicalExportIsReviewedBeforeChoosingAFileAndCancellationIsNotSuccess(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val target = ProfileTarget("original", ProfileSource.History, 5)
            session.select(target)
            val files = Files()
            client.failExport = true
            session.exportFile(files, "Export")
            assertEquals(0, files.writes)
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
            client.failExport = false
            files.saved = false
            session.exportFile(files, "Export")
            assertEquals(ProfilePortableExport(target, 1), client.exported)
            assertEquals("original.kcode-profile.json", files.name)
            assertEquals("reviewed", files.written)
            assertNull(session.state.value.exchange)
            files.saved = true
            session.exportFile(files, "Export")
            assertEquals(ProfileExchangeResult.Exported, session.state.value.exchange)
            assertEquals(0, client.writes)
            assertNull(client.submitted)
        } finally { session.close() }
    }

    @Test
    fun withdrawalJoinsThePickerAndRepeatedImportDoesNotOpenAnother(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        session.refresh()
        val files = Files().apply { gate = CompletableDeferred() }
        val importing = async { session.importFile(files, "copy", "Copy", "Import") }
        runCurrent()
        assertEquals(1, files.reads)
        assertTrue(session.state.value.busy)
        session.importFile(files, "other", "Other", "Import")
        assertEquals(1, files.reads)
        session.close()
        assertTrue(files.finished)
        assertFailsWith<CancellationException> { importing.await() }
        assertNull(client.imported)
        assertFalse(session.state.value.busy)
    }

    private class Files : ProfileDocumentFiles {
        var archive: ProfileArchiveReference? = ProfileArchiveReference("source", "c".repeat(64))
        var writtenArchive: ProfileArchiveReference? = null
        var onArchiveWrite: () -> Unit = {}
        override suspend fun readArchive(title: String, consume: suspend (ProfileArchiveReference) -> Unit): Boolean {
            try {
                gate?.await()
                onRead()
                val input = archive ?: return false
                consume(input)
                return true
            } finally { finished = true }
        }
        override suspend fun writeArchive(title: String, name: String, archive: ProfileArchiveReference): Boolean {
            onArchiveWrite()
            this.name = name
            writtenArchive = archive
            return saved
        }
        var bundles = listOf(ProfileBundleArchiveReference("first", "a".repeat(64)),
            ProfileBundleArchiveReference("second", "b".repeat(64)))
        override suspend fun readBundles(title: String, consume: suspend (List<ProfileBundleFile>) -> Unit): Boolean {
            try {
                gate?.await()
                onRead()
                if (bundles.isEmpty()) return false
                consume(bundles.map { ProfileBundleFile(it, it.archivePath) })
                return true
            } finally { finished = true }
        }
        var document: String? = "portable"
        var reads = 0
        var writes = 0
        var saved = true
        var written: String? = null
        var name: String? = null
        var onRead: () -> Unit = {}
        var gate: CompletableDeferred<Unit>? = null
        var finished = false
        override suspend fun read(title: String): String? {
            reads++
            try { gate?.await(); onRead(); return document } finally { finished = true }
        }
        override suspend fun write(title: String, name: String, document: String): Boolean {
            writes++
            this.name = name
            written = document
            return saved
        }
    }

    @Test
    fun rawHistoricalEditorRequiresACloneAndActivationKeepsHistoricalTarget(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val target = ProfileTarget("original", ProfileSource.History, 5)
            session.select(target)
            val document = session.state.value.document
            assertFailsWith<IllegalStateException> { session.edit("historical overwrite") }
            session.save()
            assertEquals(0, client.writes)
            assertEquals(document, session.state.value.document)
            session.activate()
            assertEquals(target, client.submitted?.request?.target)
            session.clone("copy", "Copy")
            assertEquals(target, client.cloned?.source)
            assertEquals(ProfileSource.Draft, session.state.value.target?.source)
        } finally { session.close() }
    }

    @Test
    fun busyLeaveSaveCannotBeDismissedBeforeItsDurableResult(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            session.edit(Json.encodeToString(ProfileDefinition.serializer(), client.definition.copy(displayName = "Saved")))
            var navigations = 0
            session.requestLeave { navigations++ }
            val gate = CompletableDeferred<Unit>()
            client.queryGate = gate
            client.queryStarted = CompletableDeferred()
            val save = async { session.saveAndLeave() }
            client.queryStarted!!.await()
            assertTrue(session.state.value.busy)
            assertFailsWith<IllegalStateException> { session.cancelLeave() }
            assertTrue(session.state.value.leaveRequested)
            assertEquals(0, navigations)
            gate.complete(Unit)
            save.await()
            assertEquals(1, navigations)
            assertEquals("Saved", client.definition.displayName)
        } finally { session.close() }
    }

    @Test
    fun navigationPreservesEditsUntilExplicitDiscardAndKeepsFirstDestination(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            var destination = ""
            session.requestLeave { destination = "clean" }
            assertEquals("clean", destination)
            val baseline = session.state.value.document
            session.edit("unfinished")
            session.requestLeave { destination = "cancelled" }
            assertTrue(session.state.value.leaveRequested)
            session.cancelLeave()
            assertEquals("unfinished", session.state.value.document)
            assertTrue(session.state.value.dirty)
            session.requestLeave { destination = "first" }
            session.requestLeave { destination = "second" }
            session.discardAndLeave()
            assertEquals("first", destination)
            assertEquals(baseline, session.state.value.document)
            assertFalse(session.state.value.dirty)
            assertFalse(session.state.value.leaveRequested)
            assertEquals(0, client.writes)
        } finally { session.close() }
    }

    @Test
    fun saveAndLeavePublishesDraftBeforeNavigationWithoutActivating(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            session.edit(Json.encodeToString(ProfileDefinition.serializer(), client.definition.copy(displayName = "Saved")))
            var navigated = false
            session.requestLeave {
                assertEquals("Saved", client.definition.displayName)
                assertEquals(1, client.writes)
                assertFalse(session.state.value.busy)
                assertFalse(session.state.value.dirty)
                assertEquals(session.state.value.document, session.state.value.savedDocument)
                navigated = true
            }
            session.saveAndLeave()
            assertTrue(navigated)
            assertFalse(session.state.value.leaveRequested)
            assertNull(client.submitted)
            assertNull(session.state.value.preview)
        } finally { session.close() }
    }

    @Test
    fun failedLeaveSaveRetainsDocumentAndConfirmation(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            var navigated = false
            session.edit("invalid")
            session.requestLeave { navigated = true }
            session.saveAndLeave()
            assertEquals(ProfileUiFailure.InvalidDocument, session.state.value.failure)
            assertTrue(session.state.value.leaveRequested)
            assertEquals(0, client.writes)
            session.cancelLeave()
            val document = Json.encodeToString(ProfileDefinition.serializer(), client.definition.copy(displayName = "Conflict"))
            session.edit(document)
            session.requestLeave { navigated = true }
            client.revision++
            session.saveAndLeave()
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
            assertEquals(document, session.state.value.document)
            assertTrue(session.state.value.dirty)
            assertTrue(session.state.value.leaveRequested)
            assertFalse(navigated)
            assertEquals(0, client.writes)
        } finally { session.close() }
    }

    @Test
    fun staleConfirmationCannotDiscardNewEditsAndWithdrawalNeverNavigates(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        session.refresh()
        var navigated = false
        session.edit("first")
        session.requestLeave { navigated = true }
        session.edit("newer")
        assertFailsWith<IllegalStateException> { session.discardAndLeave() }
        session.saveAndLeave()
        assertEquals("newer", session.state.value.document)
        assertTrue(session.state.value.dirty)
        assertEquals(0, client.writes)
        session.close()
        assertFalse(navigated)
        assertFailsWith<IllegalStateException> { session.requestLeave { navigated = true } }
        session.saveAndLeave()
        assertFalse(navigated)
    }

    @Test
    fun refreshPreservesUnsavedDocumentAndRefusesInterveningPublication(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val edited = Json.encodeToString(ProfileDefinition.serializer(), client.definition.copy(displayName = "Changed"))
            session.edit(edited)
            client.revision++
            session.refresh()
            assertEquals(edited, session.state.value.document)
            assertEquals(1L, session.state.value.documentRevision)
            assertEquals(2L, session.state.value.catalogue!!.revision)
            session.save()
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
            assertTrue(session.state.value.dirty)
            assertEquals("Original", client.definition.displayName)
            assertNull(session.state.value.preview)
        } finally { session.close() }
    }

    @Test
    fun invalidDocumentsDoNotWriteAndSaveRequiresANewPreview(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            session.edit("not json")
            session.save()
            assertEquals(ProfileUiFailure.InvalidDocument, session.state.value.failure)
            assertEquals(0, client.writes)
            session.edit(Json.encodeToString(ProfileDefinition.serializer(), client.definition.copy(id = "different")))
            session.save()
            assertEquals(0, client.writes)
            session.edit(Json.encodeToString(ProfileDefinition.serializer(), client.definition.copy(displayName = "Renamed")))
            session.save()
            assertFalse(session.state.value.dirty)
            assertEquals(ProfileSource.Draft, session.state.value.target!!.source)
            assertNull(session.state.value.preview)
            assertFailsWith<IllegalArgumentException> { session.activate() }
            session.preview()
            session.activate()
            assertEquals(ProfileTarget("original", ProfileSource.Draft), client.submitted!!.request.target)
            assertEquals(client.revision, client.submitted!!.request.expectedRevision)
        } finally { session.close() }
    }

    @Test
    fun selectingAnotherSourceRequiresExplicitDiscard(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val target = ProfileTarget("original", ProfileSource.History, 5)
            session.edit("unfinished")
            session.select(target)
            assertTrue(session.state.value.dirty)
            assertEquals(ProfileSource.Committed, session.state.value.target!!.source)
            session.select(target, discardEdits = true)
            assertFalse(session.state.value.dirty)
            session.activate(cancelActive = true)
            assertEquals(target, client.submitted!!.request.target)
            assertTrue(client.submitted!!.cancelActive)
        } finally { session.close() }
    }

    @Test
    fun creationAndCloningUseRevisionCheckedIsolatedDrafts(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            session.clone("copy", "Copy")
            assertEquals(ProfileTarget("original"), client.cloned!!.source)
            assertEquals("profile", client.cloned!!.dataScope.workspace)
            assertEquals("copy", session.state.value.target!!.profileId)
            session.create("empty", "Empty")
            assertTrue(client.created!!.createOnly)
            assertTrue(client.created!!.definition.bundles.isEmpty())
            assertEquals("profile", client.created!!.definition.dataScope.settings)
            assertEquals("profile", client.created!!.definition.dataScope.history)
            assertEquals("profile", client.created!!.definition.dataScope.workspace)
        } finally { session.close() }
    }

    @Test
    fun structuredEditsSaveDraftsAndRejectStaleOrDirtyForms(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val target = session.state.value.target!!
            val revision = session.state.value.documentRevision!!
            val operation = ProfileOperation.Disable("entry")
            session.appendOperation(target, revision, operation)
            assertEquals(listOf(operation), client.definition.patches)
            assertEquals(ProfileSource.Draft, session.state.value.target!!.source)
            assertFalse(session.state.value.dirty)
            assertTrue(session.state.value.preview!!.packagesVerified)
            assertNull(client.submitted)
            session.appendOperation(target, revision, ProfileOperation.Enable("entry"))
            assertEquals(1, client.writes)
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
            val current = session.state.value
            session.edit("unfinished")
            session.appendOperation(current.target!!, current.documentRevision!!, operation)
            assertEquals(1, client.writes)
            assertEquals("unfinished", session.state.value.document)
        } finally { session.close() }
    }

    @Test
    fun savedStructuredIntentRemainsVisibleWhenItsPreviewQueryFails(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            client.failPreview = true
            session.rename(session.state.value.target!!, session.state.value.documentRevision!!, "Renamed")
            assertEquals("Renamed", client.definition.displayName)
            assertEquals(2L, session.state.value.documentRevision)
            assertEquals(ProfileSource.Draft, session.state.value.target!!.source)
            assertTrue(session.state.value.document.contains("Renamed"))
            assertFalse(session.state.value.dirty)
            assertNull(session.state.value.preview)
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
            client.failPreview = false
            session.preview()
            assertEquals("Renamed", session.state.value.preview!!.definition.displayName)
        } finally { session.close() }
    }

    @Test
    fun bundleOrderIsExplicitAndHistoricalFormsRequireCloning(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val bundles = listOf(ProfileBundleReference("second", "2"), ProfileBundleReference("first", "1"))
            session.reorderBundles(session.state.value.target!!, session.state.value.documentRevision!!, bundles)
            assertEquals(bundles, client.definition.bundles)
            session.reorderBundles(session.state.value.target!!, session.state.value.documentRevision!!, bundles + bundles.first())
            assertEquals(1, client.writes)
            session.select(ProfileTarget("original", ProfileSource.History, 5))
            session.rename(session.state.value.target!!, session.state.value.documentRevision!!, "Historical overwrite")
            assertEquals(1, client.writes)
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
        } finally { session.close() }
    }

    @Test
    fun deletionConfirmationCannotFollowAChangedSelection(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        try {
            session.refresh()
            val target = session.state.value.target!!
            val revision = session.state.value.catalogue!!.revision
            session.select(ProfileTarget("original", ProfileSource.History, 5))
            session.delete(target, revision)
            assertEquals(ProfileUiFailure.OperationFailed, session.state.value.failure)
            assertNull(client.deleted)
            session.select(target)
            session.delete(target, revision)
            assertEquals("original", client.deleted)
            assertNull(session.state.value.target)
            assertTrue(session.state.value.catalogue!!.profiles.isEmpty())
        } finally { session.close() }
    }

    @Test
    fun observerWithdrawalDoesNotCancelAcceptedHostCommand(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        session.refresh()
        val handle = session.activate()
        val observer = async { session.observe(handle) }
        runCurrent()
        client.handle.state.value = ProfileCommandStatus(1, ProfileCommandPhase.Running)
        runCurrent()
        assertEquals(ProfileCommandPhase.Running, session.state.value.command!!.phase)
        session.close()
        assertFailsWith<CancellationException> { observer.await() }
        assertFalse(client.handle.cancelled)
        client.handle.state.value = ProfileCommandStatus(1, ProfileCommandPhase.Succeeded)
        assertEquals(ProfileCommandPhase.Succeeded, handle.await().phase)
        assertFailsWith<IllegalStateException> { session.activate() }
        assertFailsWith<IllegalStateException> { session.edit("{}") }
        session.refresh()
        assertEquals(ProfileCommandPhase.Running, session.state.value.command!!.phase)
    }

    @Test
    fun closeCancelsAndJoinsPendingQueryAndUnverifiedPreviewCannotActivate(): Unit = runTest {
        val client = Client()
        val session = ProfileUiSession(client)
        val started = CompletableDeferred<Unit>()
        client.queryGate = CompletableDeferred()
        client.queryStarted = started
        val query = async { session.refresh() }
        started.await()
        session.close()
        assertTrue(client.queryFinished)
        assertFailsWith<CancellationException> { query.await() }
        val second = ProfileUiSession(client)
        try {
            client.queryGate = null
            client.verified = false
            second.refresh()
            assertNotNull(second.state.value.preview)
            assertFailsWith<IllegalStateException> { second.activate() }
            assertNull(client.submitted)
        } finally { second.close() }
    }

    private class Handle : ProfileCommandHandle {
        override val id = 1L
        override val state = MutableStateFlow(ProfileCommandStatus(id, ProfileCommandPhase.Queued))
        var cancelled = false
        override suspend fun await() = state.first { it.phase !in listOf(ProfileCommandPhase.Queued, ProfileCommandPhase.Running) }
        override fun cancel() { cancelled = true }
    }

    private class Client : ProfileManagementClient {
        override val state = MutableStateFlow(ProfileManagementState(ProfileManagementPhase.Ready, "original"))
        override val commands = MutableStateFlow(emptyList<ProfileCommandStatus>())
        var definition = ProfileDefinition(id = "original", displayName = "Original")
        var revision = 1L
        var writes = 0
        var verified = true
        var failPreview = false
        var previewCalls = 0
        var failExport = false
        var imported: ProfilePortableImport? = null
        var bundleImported: ProfileBundleImport? = null
        var archiveImported: ProfileArchiveImport? = null
        val archive = ProfileArchiveReference("export", "d".repeat(64))
        var archiveLeaseLive = false
        var exported: ProfilePortableExport? = null
        var queryGate: CompletableDeferred<Unit>? = null
        var queryStarted: CompletableDeferred<Unit>? = null
        var queryFinished = false
        var submitted: ProfileCommand.Activate? = null
        var cloned: ProfileCloneRequest? = null
        var created: ProfileDraftWrite? = null
        var deleted: String? = null
        val handle = Handle()
        override suspend fun catalogue(): ProfileCatalogue {
            queryStarted?.complete(Unit)
            try { queryGate?.await() } finally { queryFinished = true }
            return ProfileCatalogue(revision, definition.id,
                listOf(ProfileSummary(definition.id, definition.displayName, true, 1)), definition.id)
        }
        override suspend fun draft(id: String) = definition
        override suspend fun writeDraft(write: ProfileDraftWrite): ProfileCatalogue {
            check(write.expectedRevision == revision)
            writes++
            if (write.createOnly) created = write
            definition = write.definition
            revision++
            return catalogue()
        }
        override suspend fun clone(request: ProfileCloneRequest): ProfileCatalogue {
            check(request.expectedRevision == revision)
            cloned = request
            definition = definition.copy(id = request.id, displayName = request.displayName, dataScope = request.dataScope)
            revision++
            return catalogue()
        }
        override suspend fun delete(id: String, expectedRevision: Long): ProfileCatalogue {
            check(expectedRevision == revision)
            deleted = id
            revision++
            return ProfileCatalogue(revision, null, emptyList())
        }
        override suspend fun preview(target: ProfileTarget): ProfilePreview {
            previewCalls++
            check(!failPreview) { "Preview query failed" }
            return ProfilePreview(revision, definition, emptyList(), emptyList(), emptyMap(), verified)
        }
        override suspend fun history(id: String) = listOf(ProfileCompositionState(definition, 5))
        override suspend fun modules() = emptyList<ProfileModuleSummary>()
        override suspend fun importPortable(request: ProfilePortableImport): ProfileCatalogue {
            check(request.expectedRevision == revision)
            imported = request
            definition = ProfileDefinition(id = request.id, displayName = request.displayName)
            revision++
            return catalogue()
        }
        override suspend fun importBundles(request: ProfileBundleImport): ProfileCatalogue {
            check(request.expectedRevision == revision)
            bundleImported = request
            definition = ProfileDefinition(id = request.id, displayName = request.displayName)
            revision++
            return catalogue()
        }
        override suspend fun exportPortable(request: ProfilePortableExport): String {
            check(request.expectedRevision == revision && !failExport)
            exported = request
            return "reviewed"
        }
        override suspend fun importArchive(request: ProfileArchiveImport): ProfileCatalogue {
            check(request.expectedRevision == revision)
            archiveImported = request
            definition = ProfileDefinition(id = request.id, displayName = request.displayName)
            revision++
            return catalogue()
        }
        override suspend fun exportArchive(request: ProfilePortableExport, consume: suspend (ProfileArchiveReference) -> Unit) {
            check(request.expectedRevision == revision && !failExport)
            archiveLeaseLive = true
            try { consume(archive) } finally { archiveLeaseLive = false }
        }
        override fun submit(command: ProfileCommand): ProfileCommandHandle {
            submitted = command as ProfileCommand.Activate
            return handle
        }
    }
}
