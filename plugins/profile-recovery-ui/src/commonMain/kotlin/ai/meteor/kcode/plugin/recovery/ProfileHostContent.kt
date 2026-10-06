package ai.meteor.kcode.plugin.recovery

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.KcodeProfileHost
import ai.meteor.kcode.plugin.ProfileHostPhase
import ai.meteor.kcode.plugin.profiles.ProfileRepositoryRepairMode
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.ui.design.KcodeDefaultDesignTokens
import ai.meteor.kcode.ui.design.KcodeExtendedColors
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.KcodeTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

/** Native fallback is outside the replaceable product tree. Ready roots retain their own theme. */
@Composable
fun ProfileHostContent(host: KcodeProfileHost, options: ApplicationHostOptions, languageCode: String) {
    val hostState by host.state.collectAsState()
    val client = host.profileCommands
    val session = remember(host, client) { client?.let {
        RecoverySession(it, host::inspectRepositoryRecovery, host::repairRepository, host::prepareRecoveryMetadata)
    } }
    val scope = rememberCoroutineScope()
    var showDetails by remember(hostState.failure) { mutableStateOf(false) }
    var newProfileId by remember(host) { mutableStateOf("") }
    var repairMode by remember(host) { mutableStateOf<ProfileRepositoryRepairMode?>(null) }
    val texts = remember(languageCode) {
        { key: String -> RecoveryResourceStrings[if (languageCode.startsWith("zh")) "${key}_zh" else key]
            ?: RecoveryResourceStrings[key] ?: key }
    }
    if (hostState.phase == ProfileHostPhase.Ready) {
        host.Render(options)
        return
    }
    if (hostState.phase == ProfileHostPhase.Closed) return

    // This host surface uses standard Material roles; no default product theme is loaded.
    val colors = lightColorScheme()
    KcodeTheme(colors, Shapes(), KcodeExtendedColors(colors.surfaceVariant, colors.surface,
        colors.surfaceContainer, colors.secondaryContainer), Typography(), KcodeDefaultDesignTokens) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(KcodeSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                Text(texts("title"), style = MaterialTheme.typography.headlineSmall)
                if (hostState.phase != ProfileHostPhase.RecoveryRequired) {
                    CircularProgressIndicator()
                    Text(texts("transition"))
                } else {
                    Text(texts("explanation"))
                    hostState.failure?.let { failure ->
                        Text(generateSequence(failure) { it.cause }.last().toString(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { showDetails = !showDetails }) { Text(texts("details")) }
                        if (showDetails) Text(failure.stackTraceToString())
                    }
                    if (session == null) Text(texts("unavailable")) else {
                        val state by session.state.collectAsState()
                        LaunchedEffect(session, hostState) { session.refresh() }
                        Button(onClick = { scope.launch { session.refresh() } }, enabled = !state.busy) { Text(texts("refresh")) }
                        state.repositoryRecovery?.let { review ->
                            Text(texts("storage_title"), style = MaterialTheme.typography.titleMedium)
                            Text(texts("storage_explanation"))
                            review.checkpoint?.let { checkpoint ->
                                Text("${texts("checkpoint_revision")}: ${checkpoint.revision}")
                                checkpoint.profiles.forEach { profile -> Text("${profile.displayName} (${profile.id})") }
                                Text("${texts("checkpoint_selection")}: ${checkpoint.selectedProfileId ?: texts("none")}")
                            }
                            review.checkpointFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            OutlinedButton(onClick = { repairMode = ProfileRepositoryRepairMode.RestoreCheckpoint },
                                enabled = !state.busy && !state.dirty && review.checkpoint != null) { Text(texts("restore_checkpoint")) }
                            OutlinedButton(onClick = { repairMode = ProfileRepositoryRepairMode.StartEmpty },
                                enabled = !state.busy && !state.dirty) { Text(texts("start_empty")) }
                            repairMode?.let { mode ->
                                AlertDialog(onDismissRequest = { repairMode = null },
                                    title = { Text(texts(if (mode == ProfileRepositoryRepairMode.RestoreCheckpoint) "restore_checkpoint" else "start_empty")) },
                                    text = { Text(texts(if (mode == ProfileRepositoryRepairMode.RestoreCheckpoint) "restore_confirm" else "empty_confirm")) },
                                    confirmButton = {
                                        TextButton(onClick = { repairMode = null; scope.launch { session.repair(mode) } },
                                            enabled = !state.busy) { Text(texts("confirm_repair")) }
                                    },
                                    dismissButton = { TextButton(onClick = { repairMode = null }) { Text(texts("cancel")) } },
                                )
                            }
                        }
                        state.evidenceId?.let { Text("${texts("evidence")}: $it") }
                        state.catalogue?.profiles?.forEach { profile ->
                            Text("${profile.displayName} (${profile.id})", style = MaterialTheme.typography.titleMedium)
                            if (profile.generation != null) {
                                OutlinedButton(onClick = { scope.launch { session.select(ProfileTarget(profile.id)) } },
                                    enabled = !state.busy && !state.dirty) {
                                    Text(texts("committed") + if (state.target == ProfileTarget(profile.id)) " (${texts("selected")})" else "")
                                }
                            }
                            if (profile.hasDraft) {
                                val target = ProfileTarget(profile.id, ProfileSource.Draft)
                                OutlinedButton(onClick = { scope.launch { session.select(target) } }, enabled = !state.busy && !state.dirty) {
                                    Text(texts("draft") + if (state.target == target) " (${texts("selected")})" else "")
                                }
                            }
                        }
                        state.target?.let {
                            state.history.sortedByDescending { it.generation }.forEach { generation ->
                                val target = ProfileTarget(generation.definition.id, ProfileSource.History, generation.generation)
                                OutlinedButton(onClick = { scope.launch { session.select(target) } }, enabled = !state.busy && !state.dirty) {
                                    Text("${texts("history")} ${generation.generation}" +
                                        if (state.target == target) " (${texts("selected")})" else "")
                                }
                            }
                            state.historyFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (state.target?.source == ProfileSource.History) Text(texts("history_readonly"))
                            OutlinedTextField(state.document, session::edit, modifier = Modifier.fillMaxWidth().testTag("recovery-definition"),
                                enabled = !state.busy, readOnly = state.target?.source == ProfileSource.History,
                                label = { Text(texts("document")) }, minLines = 8, maxLines = 16)
                            if (state.dirty) Text(texts("unsaved"))
                            Button(onClick = { scope.launch { session.save() } }, enabled = !state.busy && state.catalogue != null && state.dirty && state.target?.source != ProfileSource.History) { Text(texts("save")) }
                            Button(onClick = { scope.launch { session.activate() } }, enabled = !state.busy && state.catalogue != null && !state.dirty) { Text(texts("activate")) }
                            Button(onClick = { scope.launch { session.activate(saveFirst = true) } }, enabled = !state.busy && state.catalogue != null) { Text(texts("save_activate")) }
                            OutlinedButton(onClick = session::discard, enabled = !state.busy && state.dirty) { Text(texts("discard")) }
                        }
                        Text(texts("copies"), style = MaterialTheme.typography.titleMedium)
                        Text(texts("copy_explanation"))
                        OutlinedTextField(newProfileId, { newProfileId = it }, modifier = Modifier.fillMaxWidth().testTag("recovery-copy-id"),
                            enabled = !state.busy, singleLine = true, label = { Text(texts("new_id")) })
                        if (state.target != null) {
                            OutlinedButton(onClick = { scope.launch { session.copySelected(newProfileId) } },
                                enabled = !state.busy && !state.dirty && newProfileId.isNotBlank()) { Text(texts("copy_selected")) }
                        }
                        host.profileTemplates.forEach { template ->
                            OutlinedButton(onClick = { scope.launch { session.createFromTemplate(template, newProfileId) } },
                                enabled = !state.busy && !state.dirty && state.catalogue != null && newProfileId.isNotBlank()) {
                                Text("${texts("create_template")}: ${template.displayName}")
                            }
                        }
                        host.profileTemplateFailure?.let { Text(it.toString(), color = MaterialTheme.colorScheme.error) }
                        if (state.busy) CircularProgressIndicator()
                        state.failure?.let { Text(texts(it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
