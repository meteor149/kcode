package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.profiles.ProfileCommandHandle
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.profileui.resources.Res
import ai.meteor.kcode.plugin.profileui.resources.profile_active
import ai.meteor.kcode.plugin.profileui.resources.profile_apply
import ai.meteor.kcode.plugin.profileui.resources.profile_back
import ai.meteor.kcode.plugin.profileui.resources.profile_cancel
import ai.meteor.kcode.plugin.profileui.resources.profile_cancel_active
import ai.meteor.kcode.plugin.profileui.resources.profile_cancel_command
import ai.meteor.kcode.plugin.profileui.resources.profile_cancelled
import ai.meteor.kcode.plugin.profileui.resources.profile_continue_editing
import ai.meteor.kcode.plugin.profileui.resources.profile_clone
import ai.meteor.kcode.plugin.profileui.resources.profile_closed
import ai.meteor.kcode.plugin.profileui.resources.profile_command_failed
import ai.meteor.kcode.plugin.profileui.resources.profile_confirm
import ai.meteor.kcode.plugin.profileui.resources.profile_delete
import ai.meteor.kcode.plugin.profileui.resources.profile_delete_question
import ai.meteor.kcode.plugin.profileui.resources.profile_disabled
import ai.meteor.kcode.plugin.profileui.resources.profile_discard
import ai.meteor.kcode.plugin.profileui.resources.profile_draft
import ai.meteor.kcode.plugin.profileui.resources.profile_editor
import ai.meteor.kcode.plugin.profileui.resources.profile_failed
import ai.meteor.kcode.plugin.profileui.resources.profile_leave_question
import ai.meteor.kcode.plugin.profileui.resources.profile_discard_leave
import ai.meteor.kcode.plugin.profileui.resources.profile_history
import ai.meteor.kcode.plugin.profileui.resources.profile_history_readonly
import ai.meteor.kcode.plugin.profileui.resources.profile_host_busy
import ai.meteor.kcode.plugin.profileui.resources.profile_host_starting
import ai.meteor.kcode.plugin.profileui.resources.profile_id
import ai.meteor.kcode.plugin.profileui.resources.profile_invalid_document
import ai.meteor.kcode.plugin.profileui.resources.profile_modules
import ai.meteor.kcode.plugin.profileui.resources.profile_name
import ai.meteor.kcode.plugin.profileui.resources.profile_new
import ai.meteor.kcode.plugin.profileui.resources.profile_preview
import ai.meteor.kcode.plugin.profileui.resources.profile_queued
import ai.meteor.kcode.plugin.profileui.resources.profile_recovery
import ai.meteor.kcode.plugin.profileui.resources.profile_refresh
import ai.meteor.kcode.plugin.profileui.resources.profile_rename
import ai.meteor.kcode.plugin.profileui.resources.profile_running
import ai.meteor.kcode.plugin.profileui.resources.profile_save
import ai.meteor.kcode.plugin.profileui.resources.profile_save_leave
import ai.meteor.kcode.plugin.profileui.resources.profile_saved
import ai.meteor.kcode.plugin.profileui.resources.profile_succeeded
import ai.meteor.kcode.plugin.profileui.resources.profile_title
import ai.meteor.kcode.plugin.profileui.resources.profile_tree
import ai.meteor.kcode.plugin.profileui.resources.profile_unsaved
import ai.meteor.kcode.plugin.profileui.resources.profile_verified
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import ai.meteor.kcode.ui.design.KcodeSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProfileSettings(session: ProfileUiSession, client: ProfileManagementClient, onReturn: () -> Unit) {
    val state by session.state.collectAsState()
    val host by client.state.collectAsState()
    val commands by client.commands.collectAsState()
    val command = commands.lastOrNull() ?: state.command
    val scope = rememberCoroutineScope()
    var id by remember(session) { mutableStateOf("") }
    var name by remember(session) { mutableStateOf("") }
    var cancelActive by remember(session) { mutableStateOf(false) }
    var deletion by remember(session) { mutableStateOf<Pair<ProfileTarget, Long>?>(null) }
    var handle by remember(session) { mutableStateOf<ProfileCommandHandle?>(null) }
    var submissionFailed by remember(session) { mutableStateOf(false) }
    val hostReady = host.phase == ProfileManagementPhase.Ready || host.phase == ProfileManagementPhase.RecoveryRequired
    val enabled = hostReady && !state.busy
    LaunchedEffect(session, host.phase) {
        if (hostReady && !state.dirty) session.refresh()
    }
    LazyColumn(Modifier.fillMaxSize().padding(KcodeSpacing.md), verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
        item {
            TextButton(onClick = onReturn) { Text(profileText(Res.string.profile_back)) }
            Text(profileText(Res.string.profile_title), style = MaterialTheme.typography.headlineSmall)
            when (host.phase) {
                ProfileManagementPhase.Starting -> Text(profileText(Res.string.profile_host_starting))
                ProfileManagementPhase.Transitioning -> Text(profileText(Res.string.profile_host_busy))
                ProfileManagementPhase.RecoveryRequired -> Text(profileText(Res.string.profile_recovery))
                ProfileManagementPhase.Closed -> Text(profileText(Res.string.profile_closed))
                ProfileManagementPhase.Ready -> Unit
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.failure != null || submissionFailed) Text(profileText(
                if (state.failure == ProfileUiFailure.InvalidDocument) Res.string.profile_invalid_document else Res.string.profile_failed,
            ), color = MaterialTheme.colorScheme.error)
            TextButton(enabled = enabled, onClick = { scope.launch { session.refresh() } }) {
                Text(profileText(Res.string.profile_refresh))
            }
        }
        items(state.catalogue?.profiles.orEmpty(), key = { it.id }) { profile ->
            Column {
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                Text(profile.id, style = MaterialTheme.typography.bodySmall)
                if (profile.id == state.catalogue?.activeProfileId) Text(profileText(Res.string.profile_active))
                if (profile.generation != null) TextButton(enabled = enabled && !state.dirty,
                    onClick = { scope.launch { session.select(ProfileTarget(profile.id)) } }) {
                    Text(profileText(Res.string.profile_saved))
                }
                if (profile.hasDraft) TextButton(enabled = enabled && !state.dirty,
                    onClick = { scope.launch { session.select(ProfileTarget(profile.id, ProfileSource.Draft)) } }) {
                    Text(profileText(Res.string.profile_draft))
                }
            }
        }
        item {
            OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                label = { Text(profileText(Res.string.profile_id)) })
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                label = { Text(profileText(Res.string.profile_name)) })
            TextButton(enabled = enabled && !state.dirty && id.isNotBlank() && name.isNotBlank(),
                onClick = { scope.launch { session.create(id, name) } }) { Text(profileText(Res.string.profile_new)) }
            TextButton(enabled = enabled && !state.dirty && state.target != null && id.isNotBlank() && name.isNotBlank(),
                onClick = { scope.launch { session.clone(id, name) } }) { Text(profileText(Res.string.profile_clone)) }
            TextButton(enabled = enabled && !state.dirty && state.target != null && state.target!!.source != ProfileSource.History && name.isNotBlank(),
                onClick = {
                    val target = state.target!!
                    val revision = state.documentRevision!!
                    scope.launch { session.rename(target, revision, name) }
                }) { Text(profileText(Res.string.profile_rename)) }
        }
        if (state.target != null) item {
            Text(state.target!!.profileId, style = MaterialTheme.typography.titleLarge)
            if (state.dirty) {
                Text(profileText(Res.string.profile_unsaved))
                TextButton(enabled = enabled, onClick = {
                    scope.launch { session.select(state.target!!, discardEdits = true) }
                }) { Text(profileText(Res.string.profile_discard)) }
            }
            ProfileTreeEditor(state, enabled,
                onOperation = { target, revision, operation -> scope.launch { session.appendOperation(target, revision, operation) } },
                onBundles = { target, revision, bundles -> scope.launch { session.reorderBundles(target, revision, bundles) } })
            if (state.target!!.source == ProfileSource.History) Text(profileText(Res.string.profile_history_readonly))
            OutlinedTextField(state.document, { document ->
                try { session.edit(document) } catch (error: IllegalStateException) { /* Withdrawn or busy callbacks cannot edit. */ }
            }, Modifier.fillMaxWidth().testTag("profile-definition"), enabled = enabled,
                readOnly = state.target!!.source == ProfileSource.History,
                minLines = 10, maxLines = 20, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                label = { Text(profileText(Res.string.profile_editor)) })
            TextButton(enabled = enabled && state.dirty, onClick = { scope.launch { session.save() } }) {
                Text(profileText(Res.string.profile_save))
            }
            TextButton(enabled = enabled && !state.dirty, onClick = { scope.launch { session.preview() } }) {
                Text(profileText(Res.string.profile_preview))
            }
            Text(profileText(Res.string.profile_cancel_active))
            Checkbox(cancelActive, { cancelActive = it }, enabled = enabled)
            Button(enabled = enabled && !state.dirty && state.preview?.packagesVerified == true &&
                state.preview?.diagnostics?.isEmpty() == true && command?.phase !in
                listOf(ProfileCommandPhase.Queued, ProfileCommandPhase.Running), onClick = {
                submissionFailed = false
                try {
                    val accepted = session.activate(cancelActive)
                    handle = accepted
                    scope.launch { session.observe(accepted) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { submissionFailed = true }
            }) { Text(profileText(Res.string.profile_apply)) }
            TextButton(enabled = enabled && !state.dirty && state.target!!.profileId != state.catalogue?.activeProfileId &&
                state.target!!.profileId != state.catalogue?.selectedProfileId,
                onClick = { deletion = state.target!! to state.catalogue!!.revision }) { Text(profileText(Res.string.profile_delete)) }
        }
        command?.let { status -> item {
            Text(profileText(when (status.phase) {
                ProfileCommandPhase.Queued -> Res.string.profile_queued
                ProfileCommandPhase.Running -> Res.string.profile_running
                ProfileCommandPhase.Succeeded -> Res.string.profile_succeeded
                ProfileCommandPhase.Failed -> Res.string.profile_command_failed
                ProfileCommandPhase.Cancelled -> Res.string.profile_cancelled
            }))
            if (handle?.id == status.id && (status.phase == ProfileCommandPhase.Queued || status.phase == ProfileCommandPhase.Running)) {
                TextButton(onClick = { handle?.cancel() }) { Text(profileText(Res.string.profile_cancel_command)) }
            }
        } }
        state.preview?.let { preview ->
            item {
                Text(profileText(Res.string.profile_tree), style = MaterialTheme.typography.titleMedium)
                if (preview.packagesVerified) Text(profileText(Res.string.profile_verified))
                preview.diagnostics.forEach { diagnostic ->
                    Text("${diagnostic.layer}[${diagnostic.operation}] ${diagnostic.target.orEmpty()}: ${diagnostic.message}",
                        color = MaterialTheme.colorScheme.error)
                }
                ProfileTree(preview.entries)
            }
        }
        item { Text(profileText(Res.string.profile_history), style = MaterialTheme.typography.titleMedium) }
        items(state.history, key = { it.generation }) { generation ->
            TextButton(enabled = enabled && !state.dirty, onClick = {
                scope.launch { session.select(ProfileTarget(generation.definition.id, ProfileSource.History, generation.generation)) }
            }) { Text("${generation.definition.displayName} · ${generation.generation}") }
        }
        item { Text(profileText(Res.string.profile_modules), style = MaterialTheme.typography.titleMedium) }
        items(state.modules, key = { it.id }) { module -> Text("${module.id} · ${module.version}", style = MaterialTheme.typography.bodySmall) }
    }
    deletion?.let { pending -> AlertDialog(onDismissRequest = { deletion = null },
        text = { Text(profileText(Res.string.profile_delete_question)) },
        confirmButton = { TextButton(onClick = { deletion = null; scope.launch { session.delete(pending.first, pending.second) } }) {
            Text(profileText(Res.string.profile_confirm))
        } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text(profileText(Res.string.profile_cancel)) } }) }
    if (state.leaveRequested) AlertDialog(
        onDismissRequest = { try { session.cancelLeave() } catch (error: IllegalStateException) { /* Withdrawn editor. */ } },
        text = {
            Column {
                Text(profileText(Res.string.profile_leave_question))
                if (state.failure != null) Text(profileText(
                    if (state.failure == ProfileUiFailure.InvalidDocument) Res.string.profile_invalid_document else Res.string.profile_failed,
                ), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            // One action column gives AlertDialog a single measured child. Separate long
            // confirm/dismiss slots can wrap and clip the final action on narrow screens.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                Button(modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                    onClick = { scope.launch { session.saveAndLeave() } }) {
                    Text(profileText(Res.string.profile_save_leave))
                }
                TextButton(modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = {
                    try { session.discardAndLeave() } catch (error: IllegalStateException) { /* Stale confirmation cannot discard. */ }
                }) { Text(profileText(Res.string.profile_discard_leave)) }
                TextButton(modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = {
                    try { session.cancelLeave() } catch (error: IllegalStateException) { /* Withdrawn editor. */ }
                }) { Text(profileText(Res.string.profile_continue_editing)) }
            }
        },
    )
}

@Composable
private fun ProfileTree(entries: List<ProfileEntry>) {
    entries.forEach { entry ->
        Column(Modifier.padding(start = KcodeSpacing.sm)) {
            Text("${entry.id} · ${entry.packageId}", style = MaterialTheme.typography.bodySmall)
            if (!entry.enabled) Text(profileText(Res.string.profile_disabled))
            entry.children?.let { ProfileTree(it) }
        }
    }
}
