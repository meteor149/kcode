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
import ai.meteor.kcode.plugin.profileui.resources.profile_profiles
import ai.meteor.kcode.plugin.profileui.resources.profile_queued
import ai.meteor.kcode.plugin.profileui.resources.profile_recovery
import ai.meteor.kcode.plugin.profileui.resources.profile_refresh
import ai.meteor.kcode.plugin.profileui.resources.profile_rename
import ai.meteor.kcode.plugin.profileui.resources.profile_running
import ai.meteor.kcode.plugin.profileui.resources.profile_save
import ai.meteor.kcode.plugin.profileui.resources.profile_save_leave
import ai.meteor.kcode.plugin.profileui.resources.profile_saved
import ai.meteor.kcode.plugin.profileui.resources.profile_succeeded
import ai.meteor.kcode.plugin.profileui.resources.profile_tree
import ai.meteor.kcode.plugin.profileui.resources.profile_unsaved
import ai.meteor.kcode.plugin.profileui.resources.profile_verified
import ai.meteor.kcode.plugin.profileui.resources.profile_import_file
import ai.meteor.kcode.plugin.profileui.resources.profile_import_bundles
import ai.meteor.kcode.plugin.profileui.resources.profile_import_archive
import ai.meteor.kcode.plugin.profileui.resources.profile_export_archive
import ai.meteor.kcode.plugin.profileui.resources.profile_export_file
import ai.meteor.kcode.plugin.profileui.resources.profile_imported
import ai.meteor.kcode.plugin.profileui.resources.profile_exported
import ai.meteor.kcode.plugin.profileui.resources.profile_manage
import ai.meteor.kcode.plugin.profileui.resources.profile_transfer
import ai.meteor.kcode.plugin.profileui.resources.profile_imports
import ai.meteor.kcode.plugin.profileui.resources.profile_exports
import ai.meteor.kcode.plugin.profileui.resources.profile_show_json
import ai.meteor.kcode.plugin.profileui.resources.profile_hide_json
import ai.meteor.kcode.plugin.profileui.resources.profile_no_profiles
import ai.meteor.kcode.plugin.profileui.resources.profile_no_history
import ai.meteor.kcode.plugin.profileui.resources.profile_no_modules
import ai.meteor.kcode.plugin.profileui.resources.profile_loading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import ai.meteor.kcode.plugin.profileui.resources.profile_bundle_order
import ai.meteor.kcode.plugin.profileui.resources.profile_bundle_order_hint
import ai.meteor.kcode.plugin.profileui.resources.profile_bundle_import_confirm
import ai.meteor.kcode.plugin.profileui.resources.profile_move_up
import ai.meteor.kcode.plugin.profileui.resources.profile_move_down
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.SoftInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProfileManagementPage(session: ProfileUiSession, client: ProfileManagementClient) {
    val state by session.state.collectAsState()
    val host by client.state.collectAsState()
    val commands by client.commands.collectAsState()
    val command = commands.lastOrNull() ?: state.command
    val scope = rememberCoroutineScope()
    val documents = rememberProfileDocumentFiles()
    val importTitle = profileText(Res.string.profile_import_file)
    val bundleImportTitle = profileText(Res.string.profile_import_bundles)
    val exportTitle = profileText(Res.string.profile_export_file)
    val archiveImportTitle = profileText(Res.string.profile_import_archive)
    val archiveExportTitle = profileText(Res.string.profile_export_archive)
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
    var showRawDocument by remember(session) { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(KcodeSpacing.md),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    TextButton(enabled = enabled, onClick = { scope.launch { session.refresh() } }) {
                        Text(profileText(Res.string.profile_refresh))
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.failure != null || submissionFailed) {
                    Text(
                        profileText(
                            if (state.failure == ProfileUiFailure.InvalidDocument) Res.string.profile_invalid_document
                            else Res.string.profile_failed,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (!hostReady || state.catalogue == null) {
            item {
                val status = when (host.phase) {
                    ProfileManagementPhase.Starting -> profileText(Res.string.profile_host_starting)
                    ProfileManagementPhase.Transitioning -> profileText(Res.string.profile_host_busy)
                    ProfileManagementPhase.Closed -> profileText(Res.string.profile_closed)
                    ProfileManagementPhase.RecoveryRequired -> profileText(Res.string.profile_recovery)
                    ProfileManagementPhase.Ready -> profileText(Res.string.profile_loading)
                }
                ProfileSettingsSection(status) {
                    state.failure?.let { Text(profileText(Res.string.profile_failed), color = MaterialTheme.colorScheme.error) }
                    if (state.busy || host.phase == ProfileManagementPhase.Starting ||
                        host.phase == ProfileManagementPhase.Transitioning
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
        item {
            ProfileSettingsSection(profileText(Res.string.profile_profiles)) {
                val profiles = state.catalogue?.profiles.orEmpty()
                if (profiles.isEmpty()) {
                    Text(profileText(Res.string.profile_no_profiles), color = SoftInk,
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    profiles.forEachIndexed { index, profile ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(profile.displayName, color = Ink, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(profile.id, color = SoftInk, style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                if (profile.id == state.catalogue?.activeProfileId) {
                                    ProfileStatusPill(profileText(Res.string.profile_active), highlighted = true)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                                    if (profile.generation != null) TextButton(
                                        enabled = enabled && !state.dirty,
                                        modifier = Modifier.testTag("profile-committed-${profile.id}"),
                                        onClick = { scope.launch { session.select(ProfileTarget(profile.id)) } },
                                    ) {
                                        Text(profileText(Res.string.profile_saved), maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    if (profile.hasDraft) TextButton(
                                        enabled = enabled && !state.dirty,
                                        modifier = Modifier.testTag("profile-draft-${profile.id}"),
                                        onClick = { scope.launch { session.select(ProfileTarget(profile.id, ProfileSource.Draft)) } },
                                    ) {
                                        Text(profileText(Res.string.profile_draft), maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        if (index < profiles.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(color = Hairline)
                        }
                    }
                }
            }
        }

        item {
            ProfileSettingsSection(profileText(Res.string.profile_manage)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    modifier = Modifier.fillMaxWidth().testTag("profile-new-id"),
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(profileText(Res.string.profile_id)) },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(profileText(Res.string.profile_name)) },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled && !state.dirty && id.isNotBlank() && name.isNotBlank(),
                    onClick = { scope.launch { session.create(id, name) } },
                ) { Text(profileText(Res.string.profile_new)) }
                Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        enabled = enabled && !state.dirty && state.target != null && id.isNotBlank() && name.isNotBlank(),
                        onClick = { scope.launch { session.clone(id, name) } },
                    ) { Text(profileText(Res.string.profile_clone)) }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        enabled = enabled && !state.dirty && state.target != null &&
                            state.target?.source != ProfileSource.History && name.isNotBlank(),
                        onClick = {
                            val target = state.target ?: return@TextButton
                            val revision = state.documentRevision ?: return@TextButton
                            scope.launch { session.rename(target, revision, name) }
                        },
                    ) { Text(profileText(Res.string.profile_rename)) }
                }
            }
        }

        item {
            ProfileSettingsSection(profileText(Res.string.profile_transfer)) {
                Text(profileText(Res.string.profile_imports), color = SoftInk,
                    style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                    TextButton(enabled = enabled && !state.dirty && id.isNotBlank(),
                        modifier = Modifier.weight(1f).testTag("profile-import-file"),
                        onClick = { scope.launch { session.importFile(documents, id, name.ifBlank { id }, importTitle) } }) {
                        Text(importTitle, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    TextButton(enabled = enabled && !state.dirty && id.isNotBlank(),
                        modifier = Modifier.weight(1f).testTag("profile-import-bundles"),
                        onClick = { scope.launch { session.importBundles(documents, id, name.ifBlank { id }, bundleImportTitle) } }) {
                        Text(bundleImportTitle, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                TextButton(enabled = enabled && !state.dirty && id.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("profile-import-archive"),
                    onClick = { scope.launch { session.importArchive(documents, id, name.ifBlank { id }, archiveImportTitle) } }) {
                    Text(archiveImportTitle)
                }
                Text(profileText(Res.string.profile_exports), color = SoftInk,
                    style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                    TextButton(enabled = enabled && !state.dirty && state.target != null && state.target?.source != ProfileSource.Draft,
                        modifier = Modifier.weight(1f).testTag("profile-export-file"),
                        onClick = { scope.launch { session.exportFile(documents, exportTitle) } }) {
                        Text(exportTitle, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    TextButton(enabled = enabled && !state.dirty && state.target != null && state.target?.source != ProfileSource.Draft,
                        modifier = Modifier.weight(1f).testTag("profile-export-archive"),
                        onClick = { scope.launch { session.exportArchive(documents, archiveExportTitle) } }) {
                        Text(archiveExportTitle, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                when (state.exchange) {
                    ProfileExchangeResult.Imported -> Text(profileText(Res.string.profile_imported), color = MaterialTheme.colorScheme.primary)
                    ProfileExchangeResult.Exported -> Text(profileText(Res.string.profile_exported), color = MaterialTheme.colorScheme.primary)
                    null -> Unit
                }
            }
        }

        if (state.target != null) item {
            val target = state.target!!
            ProfileSettingsSection(target.profileId) {
                if (state.dirty) {
                    Text(profileText(Res.string.profile_unsaved), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = enabled, onClick = {
                        scope.launch { session.select(target, discardEdits = true) }
                    }) { Text(profileText(Res.string.profile_discard)) }
                }
                ProfileTreeEditor(
                    state,
                    enabled,
                    onOperation = { selected, revision, operation ->
                        scope.launch { session.appendOperation(selected, revision, operation) }
                    },
                    onBundles = { selected, revision, bundles ->
                        scope.launch { session.reorderBundles(selected, revision, bundles) }
                    },
                )
                if (target.source == ProfileSource.History) {
                    Text(profileText(Res.string.profile_history_readonly), color = SoftInk,
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showRawDocument = !showRawDocument }) {
                    Text(profileText(if (showRawDocument) Res.string.profile_hide_json else Res.string.profile_show_json))
                }
                if (showRawDocument) {
                    OutlinedTextField(
                        value = state.document,
                        onValueChange = { document ->
                            try { session.edit(document) } catch (error: IllegalStateException) { /* Withdrawn or busy callbacks cannot edit. */ }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("profile-definition"),
                        enabled = enabled,
                        readOnly = target.source == ProfileSource.History,
                        minLines = 8,
                        maxLines = 14,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        label = { Text(profileText(Res.string.profile_editor)) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                    TextButton(enabled = enabled && state.dirty, onClick = { scope.launch { session.save() } }) {
                        Text(profileText(Res.string.profile_save))
                    }
                    TextButton(enabled = enabled && !state.dirty, onClick = { scope.launch { session.preview() } }) {
                        Text(profileText(Res.string.profile_preview))
                    }
                    TextButton(
                        enabled = enabled && !state.dirty && target.profileId != state.catalogue?.activeProfileId &&
                            target.profileId != state.catalogue?.selectedProfileId,
                        onClick = { deletion = target to (state.catalogue?.revision ?: return@TextButton) },
                    ) { Text(profileText(Res.string.profile_delete), color = MaterialTheme.colorScheme.error) }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(cancelActive, { cancelActive = it }, enabled = enabled)
                    Text(profileText(Res.string.profile_cancel_active), color = SoftInk,
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled && !state.dirty && state.preview?.packagesVerified == true &&
                        state.preview?.diagnostics?.isEmpty() == true && command?.phase !in
                        listOf(ProfileCommandPhase.Queued, ProfileCommandPhase.Running),
                    onClick = {
                        submissionFailed = false
                        try {
                            val accepted = session.activate(cancelActive)
                            handle = accepted
                            scope.launch { session.observe(accepted) }
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { submissionFailed = true }
                    },
                ) { Text(profileText(Res.string.profile_apply)) }
            }
        }

        command?.let { status -> item {
            ProfileSettingsSection(profileText(when (status.phase) {
                ProfileCommandPhase.Queued -> Res.string.profile_queued
                ProfileCommandPhase.Running -> Res.string.profile_running
                ProfileCommandPhase.Succeeded -> Res.string.profile_succeeded
                ProfileCommandPhase.Failed -> Res.string.profile_command_failed
                ProfileCommandPhase.Cancelled -> Res.string.profile_cancelled
            })) {
                if (handle?.id == status.id && (status.phase == ProfileCommandPhase.Queued || status.phase == ProfileCommandPhase.Running)) {
                    TextButton(onClick = { handle?.cancel() }) { Text(profileText(Res.string.profile_cancel_command)) }
                }
            }
        } }

        state.preview?.let { preview ->
            item {
                ProfileSettingsSection(profileText(Res.string.profile_tree)) {
                    if (preview.packagesVerified) Text(profileText(Res.string.profile_verified), color = MaterialTheme.colorScheme.primary)
                    preview.diagnostics.forEach { diagnostic ->
                        Text("${diagnostic.layer}[${diagnostic.operation}] ${diagnostic.target.orEmpty()}: ${diagnostic.message}",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    ProfileTree(preview.entries)
                }
            }
        }
        item {
            ProfileSettingsSection(profileText(Res.string.profile_history)) {
                if (state.history.isEmpty()) Text(profileText(Res.string.profile_no_history), color = SoftInk,
                    style = MaterialTheme.typography.bodySmall)
                state.history.forEach { generation ->
                    TextButton(enabled = enabled && !state.dirty, onClick = {
                        scope.launch { session.select(ProfileTarget(generation.definition.id, ProfileSource.History, generation.generation)) }
                    }) { Text("${generation.definition.displayName} · ${generation.generation}") }
                }
            }
        }
        item {
            ProfileSettingsSection(profileText(Res.string.profile_modules)) {
                if (state.modules.isEmpty()) Text(profileText(Res.string.profile_no_modules), color = SoftInk,
                    style = MaterialTheme.typography.bodySmall)
                state.modules.forEach { module ->
                    Text("${module.id} · ${module.version}", color = SoftInk,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        }
    }
    state.bundleSelection?.let { selection -> AlertDialog(
        onDismissRequest = { session.finishBundleSelection(false) },
        title = { Text(profileText(Res.string.profile_bundle_order)) },
        text = {
            LazyColumn {
                item { Text(profileText(Res.string.profile_bundle_order_hint)) }
                itemsIndexed(selection, key = { _, item -> item.token }) { index, item ->
                    Column(Modifier.testTag("profile-bundle-selected-${item.token}")) {
                        Text(item.name)
                        TextButton(enabled = index > 0, modifier = Modifier.testTag("profile-bundle-up-${item.token}"),
                            onClick = { session.moveBundle(item.token, -1) }) { Text(profileText(Res.string.profile_move_up)) }
                        TextButton(enabled = index < selection.lastIndex, modifier = Modifier.testTag("profile-bundle-down-${item.token}"),
                            onClick = { session.moveBundle(item.token, 1) }) { Text(profileText(Res.string.profile_move_down)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.testTag("profile-bundle-import-confirm"),
            onClick = { session.finishBundleSelection(true) }) { Text(profileText(Res.string.profile_bundle_import_confirm)) } },
        dismissButton = { TextButton(onClick = { session.finishBundleSelection(false) }) { Text(profileText(Res.string.profile_cancel)) } },
    ) }
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
private fun ProfileStatusPill(label: String, highlighted: Boolean) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = KcodeSpacing.xs, vertical = 2.dp),
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else SoftInk,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProfileSettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
        Text(title, color = Ink, style = MaterialTheme.typography.titleSmall)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Panel,
            shape = RoundedCornerShape(KcodeRadius.card),
            border = BorderStroke(1.dp, Hairline.copy(alpha = 0.65f)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(KcodeSpacing.md),
                verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm),
                content = content,
            )
        }
    }
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
