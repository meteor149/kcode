package ai.meteor.kcode.plugin.recovery

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.KcodeProfileHost
import ai.meteor.kcode.plugin.ProfileHostPhase
import ai.meteor.kcode.plugin.profiles.ProfileRepositoryRepairMode
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.managementui.ProfilePluginManagerScreen
import ai.meteor.kcode.plugin.profileui.ProfileManagementPage
import ai.meteor.kcode.plugin.profileui.ProfileUiSession
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.design.KcodeDefaultDesignTokens
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.KcodeTheme
import ai.meteor.kcode.ui.defaulttheme.KcodeDefaultColorScheme
import ai.meteor.kcode.ui.defaulttheme.KcodeDefaultExtendedColors
import ai.meteor.kcode.ui.defaulttheme.KcodeDefaultTypography
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.KcodeRadius
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Native fallback is outside the replaceable product tree. Ready roots retain their own theme. */
@Composable
fun ProfileHostContent(
    host: KcodeProfileHost,
    options: ApplicationHostOptions,
    languageCode: String,
    managementMode: Boolean = false,
    managementClient: ProfileManagementClient? = null,
) {
    val hostState by host.state.collectAsState()
    val client = managementClient ?: host.profileCommands
    val sessionClient = host.profileCommands
    val session = remember(host, sessionClient) { sessionClient?.let {
        RecoverySession(it, host::inspectRepositoryRecovery, host::repairRepository, host::prepareRecoveryMetadata)
    } }
    val scope = rememberCoroutineScope()
    var showProfiles by remember(host) { mutableStateOf(false) }
    val profileSession = remember(managementMode, client) {
        if (managementMode) client?.let(::ProfileUiSession) else null
    }
    DisposableEffect(profileSession) {
        onDispose {
            profileSession?.let { activeSession -> scope.launch { activeSession.close() } }
        }
    }
    var showDetails by remember(hostState.failure) { mutableStateOf(false) }
    var showRecoveryTools by remember(host) { mutableStateOf(false) }
    var newProfileId by remember(host) { mutableStateOf("") }
    var repairMode by remember(host) { mutableStateOf<ProfileRepositoryRepairMode?>(null) }
    val texts = remember(languageCode) {
        { key: String -> RecoveryResourceStrings[if (languageCode.startsWith("zh")) "${key}_zh" else key]
            ?: RecoveryResourceStrings[key] ?: key }
    }
    if (hostState.phase == ProfileHostPhase.Ready && !managementMode) {
        host.Render(options)
        return
    }
    if (hostState.phase == ProfileHostPhase.Closed) return

    if (managementMode && client == null) {
        KcodeTheme(KcodeDefaultColorScheme, Shapes(), KcodeDefaultExtendedColors,
            KcodeDefaultTypography, KcodeDefaultDesignTokens) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(KcodeSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                    Text(texts("title"), style = MaterialTheme.typography.headlineSmall)
                    Text(texts("unavailable"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        return
    }

    if (managementMode && client != null && (hostState.phase == ProfileHostPhase.Ready || !showRecoveryTools)) {
        KcodeTheme(KcodeDefaultColorScheme, Shapes(), KcodeDefaultExtendedColors,
            KcodeDefaultTypography, KcodeDefaultDesignTokens) {
            val activeProfileSession = profileSession
            if (showProfiles && activeProfileSession != null) {
                val profileState by activeProfileSession.state.collectAsState()
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.fromCode(languageCode)) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                enabled = !profileState.busy,
                                onClick = {
                                    activeProfileSession.requestLeave { showProfiles = false }
                                },
                            ) { Text(texts("plugin_manager")) }
                            Spacer(Modifier.weight(1f))
                            if (hostState.phase == ProfileHostPhase.RecoveryRequired && managementClient == null) {
                                TextButton(
                                    enabled = !profileState.busy,
                                    onClick = {
                                        activeProfileSession.requestLeave { showRecoveryTools = true }
                                    },
                                ) { Text(texts("recovery_tools")) }
                            }
                        }
                        Box(Modifier.weight(1f)) { ProfileManagementPage(activeProfileSession, client) }
                    }
                }
            } else {
                ProfilePluginManagerScreen(
                    client = client,
                    templates = host.profileTemplates,
                    languageCode = languageCode,
                    hostFailure = hostState.failure?.let { generateSequence(it) { cause -> cause.cause }.last().toString() },
                    onReturn = if (hostState.phase == ProfileHostPhase.RecoveryRequired && managementClient == null) {
                        { showRecoveryTools = true }
                    } else {
                        null
                    },
                    onProfileManagement = { showProfiles = true },
                )
            }
        }
        return
    }
    if (hostState.phase == ProfileHostPhase.Ready) {
        host.Render(options)
        return
    }

    // Recovery stays in the host process and uses only Kcode's bundled design system.
    KcodeTheme(KcodeDefaultColorScheme, Shapes(), KcodeDefaultExtendedColors,
        KcodeDefaultTypography, KcodeDefaultDesignTokens) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(KcodeSpacing.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                            Text(texts("title"), color = Ink, style = MaterialTheme.typography.titleLarge)
                            Text(texts("explanation"), color = SoftInk, style = MaterialTheme.typography.bodySmall)
                        }
                        if (managementMode) {
                            TextButton(onClick = { showRecoveryTools = false }) { Text(texts("plugin_manager")) }
                        }
                    }

                    if (hostState.phase != ProfileHostPhase.RecoveryRequired) {
                        RecoveryPanel {
                            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                            Text(texts("transition"), color = SoftInk)
                        }
                    } else {
                        hostState.failure?.let { failure ->
                            RecoveryPanel {
                                Text(texts("activation_failed"), color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.titleSmall)
                                Text(generateSequence(failure) { it.cause }.last().toString(),
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { showDetails = !showDetails }) {
                                    Text(texts("details"))
                                }
                                if (showDetails) {
                                    Text(failure.stackTraceToString(), color = SoftInk,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        if (session == null) {
                            RecoveryPanel { Text(texts("unavailable"), color = MaterialTheme.colorScheme.error) }
                        } else {
                            val state by session.state.collectAsState()
                            var historyMenu by remember(session) { mutableStateOf(false) }
                            var checkpointDetails by remember(session) { mutableStateOf(false) }
                            var templateMenu by remember(session) { mutableStateOf(false) }
                            var selectedTemplate by remember(host.profileTemplates) {
                                mutableStateOf(host.profileTemplates.firstOrNull())
                            }
                            LaunchedEffect(session, hostState) { session.refresh() }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.busy) {
                                    LinearProgressIndicator(Modifier.weight(1f))
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                TextButton(onClick = { scope.launch { session.refresh() } }, enabled = !state.busy) {
                                    Text(texts("refresh"))
                                }
                            }

                            state.repositoryRecovery?.let { review ->
                                RecoveryPanel {
                                    Text(texts("storage_title"), color = Ink, style = MaterialTheme.typography.titleSmall)
                                    Text(texts("storage_explanation"), color = SoftInk, style = MaterialTheme.typography.bodySmall)
                                    review.checkpoint?.let { checkpoint ->
                                        Text(
                                            "${texts("checkpoint_revision")}: ${checkpoint.revision} · ${checkpoint.profiles.size} ${texts("profiles_available")}",
                                            color = Ink,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            "${texts("checkpoint_selection")}: ${checkpoint.selectedProfileId ?: texts("none")}",
                                            color = SoftInk,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        TextButton(onClick = { checkpointDetails = !checkpointDetails }) {
                                            Text(texts("details"))
                                        }
                                        if (checkpointDetails) {
                                            checkpoint.profiles.forEach { profile ->
                                                Text("${profile.displayName} (${profile.id})", color = SoftInk,
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    review.checkpointFailure?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            onClick = { repairMode = ProfileRepositoryRepairMode.RestoreCheckpoint },
                                            enabled = !state.busy && !state.dirty && review.checkpoint != null,
                                        ) { Text(texts("restore_checkpoint"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        TextButton(
                                            modifier = Modifier.weight(1f),
                                            onClick = { repairMode = ProfileRepositoryRepairMode.StartEmpty },
                                            enabled = !state.busy && !state.dirty,
                                        ) { Text(texts("start_empty"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                                repairMode?.let { mode ->
                                    AlertDialog(
                                        onDismissRequest = { repairMode = null },
                                        title = {
                                            Text(texts(if (mode == ProfileRepositoryRepairMode.RestoreCheckpoint) {
                                                "restore_checkpoint"
                                            } else {
                                                "start_empty"
                                            }))
                                        },
                                        text = {
                                            Text(texts(if (mode == ProfileRepositoryRepairMode.RestoreCheckpoint) {
                                                "restore_confirm"
                                            } else {
                                                "empty_confirm"
                                            }))
                                        },
                                        confirmButton = {
                                            TextButton(
                                                onClick = { repairMode = null; scope.launch { session.repair(mode) } },
                                                enabled = !state.busy,
                                            ) { Text(texts("confirm_repair")) }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { repairMode = null }) { Text(texts("cancel")) }
                                        },
                                    )
                                }
                            }

                            state.evidenceId?.let { evidenceId ->
                                Text("${texts("evidence")}: $evidenceId", color = SoftInk,
                                    style = MaterialTheme.typography.bodySmall)
                            }

                            Text(texts("profiles"), color = Ink, style = MaterialTheme.typography.titleSmall)
                            RecoveryPanel {
                                if (state.catalogue?.profiles.isNullOrEmpty()) {
                                    Text(texts("no_profile_available"), color = SoftInk,
                                        style = MaterialTheme.typography.bodySmall)
                                } else {
                                    state.catalogue?.profiles.orEmpty().forEachIndexed { index, profile ->
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(profile.displayName, color = Ink,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(profile.id, color = SoftInk,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            if (profile.generation != null) {
                                                val target = ProfileTarget(profile.id)
                                                TextButton(
                                                    onClick = { scope.launch { session.select(target) } },
                                                    enabled = !state.busy && !state.dirty,
                                                ) {
                                                    Text(if (state.target == target) texts("selected") else texts("committed"),
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                            if (profile.hasDraft) {
                                                val target = ProfileTarget(profile.id, ProfileSource.Draft)
                                                TextButton(
                                                    onClick = { scope.launch { session.select(target) } },
                                                    enabled = !state.busy && !state.dirty,
                                                ) {
                                                    Text(if (state.target == target) texts("selected") else texts("draft"),
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                        if (index < state.catalogue!!.profiles.lastIndex) HorizontalDivider(color = Hairline)
                                    }
                                }
                            }

                            state.target?.let { target ->
                                RecoveryPanel {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(texts("document"), color = Ink,
                                                style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "${target.profileId} · ${when (target.source) {
                                                    ProfileSource.Committed -> texts("committed")
                                                    ProfileSource.Draft -> texts("draft")
                                                    ProfileSource.History -> "${texts("history")} ${target.generation ?: ""}"
                                                }}",
                                                color = SoftInk,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        Box {
                                            TextButton(
                                                enabled = !state.busy && !state.dirty && state.history.isNotEmpty(),
                                                onClick = { historyMenu = true },
                                            ) { Text("${texts("history")} · ${state.history.size}") }
                                            DropdownMenu(expanded = historyMenu, onDismissRequest = { historyMenu = false }) {
                                                state.history.sortedByDescending { it.generation }.forEach { generation ->
                                                    DropdownMenuItem(
                                                        text = { Text("${generation.definition.displayName} · ${generation.generation}") },
                                                        onClick = {
                                                            historyMenu = false
                                                            val historical = ProfileTarget(
                                                                generation.definition.id,
                                                                ProfileSource.History,
                                                                generation.generation,
                                                            )
                                                            scope.launch { session.select(historical) }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    state.historyFailure?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (target.source == ProfileSource.History) {
                                        Text(texts("history_readonly"), color = SoftInk,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedTextField(
                                        value = state.document,
                                        onValueChange = session::edit,
                                        modifier = Modifier.fillMaxWidth().testTag("recovery-definition"),
                                        enabled = !state.busy,
                                        readOnly = target.source == ProfileSource.History,
                                        label = { Text(texts("document")) },
                                        minLines = 8,
                                        maxLines = 14,
                                    )
                                    if (state.dirty) Text(texts("unsaved"), color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                    if (state.dirty) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                                            Button(
                                                modifier = Modifier.weight(1f),
                                                onClick = { scope.launch { session.activate(saveFirst = true) } },
                                                enabled = !state.busy && state.catalogue != null,
                                            ) { Text(texts("save_activate"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                            TextButton(
                                                modifier = Modifier.weight(1f),
                                                onClick = { scope.launch { session.save() } },
                                                enabled = !state.busy && state.catalogue != null && target.source != ProfileSource.History,
                                            ) { Text(texts("save"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        }
                                        TextButton(
                                            onClick = session::discard,
                                            enabled = !state.busy,
                                            modifier = Modifier.align(Alignment.End),
                                        ) { Text(texts("discard")) }
                                    } else {
                                        Button(
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = { scope.launch { session.activate() } },
                                            enabled = !state.busy && state.catalogue != null,
                                        ) { Text(texts("activate")) }
                                    }
                                }
                            }

                            RecoveryPanel {
                                Text(texts("copies"), color = Ink, style = MaterialTheme.typography.titleSmall)
                                Text(texts("copy_explanation"), color = SoftInk, style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = newProfileId,
                                    onValueChange = { newProfileId = it },
                                    modifier = Modifier.fillMaxWidth().testTag("recovery-copy-id"),
                                    enabled = !state.busy,
                                    singleLine = true,
                                    label = { Text(texts("new_id")) },
                                )
                                if (host.profileTemplates.isNotEmpty()) {
                                    Box {
                                        OutlinedButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !state.busy,
                                            onClick = { templateMenu = true },
                                        ) {
                                            Text(selectedTemplate?.displayName ?: texts("select_template"),
                                                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            KcodeIcon(KcodeIconAsset.ChevronDown, SoftInk, Modifier.size(18.dp))
                                        }
                                        DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                                            host.profileTemplates.forEach { template ->
                                                DropdownMenuItem(text = { Text(template.displayName) }, onClick = {
                                                    selectedTemplate = template
                                                    templateMenu = false
                                                })
                                            }
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                                    if (state.target != null) {
                                        OutlinedButton(
                                            modifier = Modifier.weight(1f),
                                            onClick = { scope.launch { session.copySelected(newProfileId) } },
                                            enabled = !state.busy && !state.dirty && newProfileId.isNotBlank(),
                                        ) { Text(texts("copy_selected"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                    if (selectedTemplate != null) {
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                val template = selectedTemplate ?: return@Button
                                                scope.launch { session.createFromTemplate(template, newProfileId) }
                                            },
                                            enabled = !state.busy && !state.dirty && state.catalogue != null && newProfileId.isNotBlank(),
                                        ) { Text(texts("create_template"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                                host.profileTemplateFailure?.let {
                                    Text(it.toString(), color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                state.failure?.let {
                                    Text(texts(it), color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(KcodeRadius.card),
        border = BorderStroke(1.dp, Hairline.copy(alpha = 0.6f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(KcodeSpacing.md),
            verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs),
            content = content,
        )
    }
}
