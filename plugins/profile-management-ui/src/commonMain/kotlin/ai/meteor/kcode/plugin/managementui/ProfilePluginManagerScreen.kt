package ai.meteor.kcode.plugin.managementui

import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileModuleSource
import ai.meteor.kcode.plugin.api.profiles.ProfileModuleSummary
import ai.meteor.kcode.plugin.api.profiles.ProfilePreview
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.ui.component.KcodeBrandMark
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.design.Error
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.SoftInk
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

/** Host-neutral editor shared by the product settings contribution and native manager mode. */
@Composable
fun ProfilePluginManagerScreen(
    client: ProfileManagementClient,
    templates: List<ai.meteor.kcode.plugin.api.profiles.ProfileDefinition> = emptyList(),
    languageCode: String,
    hostFailure: String? = null,
    onReturn: (() -> Unit)? = null,
    embeddedInSettings: Boolean = false,
    onProfileManagement: (() -> Unit)? = null,
) {
    val session = remember(client, embeddedInSettings) {
        ProfilePluginManagerSession(client, activeProfileOnly = embeddedInSettings)
    }
    val state by session.state.collectAsState()
    val management by client.state.collectAsState()
    val scope = rememberCoroutineScope()
    val files = rememberProfileManagerBundleFiles()
    var profileMenu by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }
    var moduleMenu by remember { mutableStateOf(false) }
    var template by remember(templates) { mutableStateOf(templates.firstOrNull()) }
    var profileId by remember { mutableStateOf("") }
    var importId by remember { mutableStateOf("") }
    var importName by remember { mutableStateOf("") }
    var entryId by remember { mutableStateOf("") }
    var moduleId by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var listFilter by remember { mutableStateOf(PluginListFilter.All) }
    var configureEntry by remember { mutableStateOf<ProfileEntry?>(null) }
    var configureValue by remember { mutableStateOf("") }
    var removeEntry by remember { mutableStateOf<ProfileEntry?>(null) }
    var showCreateProfile by remember { mutableStateOf(false) }
    var showInstallBundles by remember { mutableStateOf(false) }
    var showAddPlugin by remember { mutableStateOf(false) }
    var cancelActive by remember { mutableStateOf(false) }
    var invalidConfiguration by remember { mutableStateOf(false) }
    val text: (String) -> String = { profilePluginManagerText(it, languageCode) }

    LaunchedEffect(session) { session.refresh() }
    DisposableEffect(session) {
        onDispose { scope.launch { session.close() } }
    }

    val catalogue = state.catalogue
    val preview = state.preview
    val target = state.target
    val profile = catalogue?.profiles?.firstOrNull { it.id == target?.profileId }
    val entries = preview?.let { flattenPluginEntries(it.entries) }.orEmpty()
    val inferredModules = entries.map { entry ->
        ProfileModuleSummary(entry.packageId, "", ProfileModuleSource.Package, listOf(entry.id))
    }
    val availableModules = (state.modules + inferredModules).distinctBy { it.id }
    val selectedModule = availableModules.firstOrNull { it.id == moduleId } ?: availableModules.firstOrNull()
    val acceptingCommands = management.phase == ProfileManagementPhase.Ready ||
        management.phase == ProfileManagementPhase.RecoveryRequired
    val canManage = acceptingCommands && !state.busy
    val canEdit = canManage && target != null && target.source != ProfileSource.History
    val canActivate = canManage && preview != null && preview.packagesVerified &&
        preview.diagnostics.isEmpty() && target != null && catalogue?.revision == preview.revision
    val showActivationBar = target != null && (!embeddedInSettings ||
        target.source == ProfileSource.Draft && catalogue?.activeProfileId == target.profileId)
    val filteredEntries = entries.filter { entry ->
        val matchesQuery = searchQuery.isBlank() || entry.id.contains(searchQuery, ignoreCase = true) ||
            entry.packageId.contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (listFilter) {
            PluginListFilter.All -> true
            PluginListFilter.Enabled -> entry.enabled
            PluginListFilter.Disabled -> !entry.enabled
        }
        matchesQuery && matchesFilter
    }

    Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
        Column(
            Modifier.weight(1f)
                .widthIn(max = 780.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(KcodeSpacing.md),
        ) {
            if (embeddedInSettings) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !state.busy, onClick = { scope.launch { session.refresh() } }) {
                        Text(text("refresh"))
                    }
                }
            } else {
                ManagerHeader(
                    title = text("title"),
                    description = text("description"),
                    refreshLabel = text("refresh"),
                    recoveryLabel = text("recovery_tools"),
                    showRecovery = onReturn != null,
                    profileManagementLabel = text("manage_profiles"),
                    onProfileManagement = onProfileManagement,
                    onRefresh = { scope.launch { session.refresh() } },
                    onReturn = onReturn,
                    refreshEnabled = !state.busy,
                )
            }

            if (hostFailure != null || management.failure != null) {
                ManagerNotice(
                    title = text("host_failure"),
                    message = hostFailure ?: management.failure.orEmpty(),
                    isError = true,
                )
            }
            state.modulesFailure?.let { ManagerNotice(text("module"), it, isError = true) }
            state.failure?.let { ManagerNotice(text("failure"), it, isError = true) }
            when (management.phase) {
                ProfileManagementPhase.Starting -> ManagerNotice(text("loading"), null, isError = false)
                ProfileManagementPhase.Transitioning -> ManagerNotice(text("busy"), null, isError = false)
                ProfileManagementPhase.Closed -> ManagerNotice(text("closed"), null, isError = true)
                else -> Unit
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (embeddedInSettings) {
                when {
                    catalogue == null && state.busy -> ManagerNotice(text("loading"), null, isError = false)
                    target == null -> ManagerNotice(text("no_active_profile"), null, isError = false)
                    else -> {
                        profile?.let { activeProfile ->
                            ManagerPanel {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = KcodeSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.sm),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(text("active_profile"), color = SoftInk, style = MaterialTheme.typography.labelSmall)
                                        Text(
                                            activeProfile.displayName,
                                            color = Ink,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    ManagerPill(text("active"), highlighted = true)
                                }
                            }
                            if (target?.source == ProfileSource.Draft) {
                                ManagerNotice(text("pending_plugin_changes"), null, isError = false)
                            }
                        }
                    }
                }
            } else {
                ManagerSection(text("profiles")) {
                    if (catalogue == null && state.busy) {
                        ManagerNotice(text("loading"), null, isError = false)
                    } else if (catalogue?.profiles.orEmpty().isEmpty()) {
                        ManagerNotice(text("no_profile"), null, isError = false)
                        Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                            if (templates.isNotEmpty()) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = canManage,
                                    onClick = { showCreateProfile = true },
                                ) { Text(text("create_from_template"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = canManage && catalogue != null,
                                onClick = { showInstallBundles = true },
                            ) { Text(text("install_bundle_short"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    } else {
                        ManagerPanel {
                            Box {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(KcodeRadius.control))
                                        .clickable(enabled = !state.busy) { profileMenu = true }
                                        .padding(vertical = KcodeSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(text("profile"), color = SoftInk, style = MaterialTheme.typography.labelSmall)
                                        Text(
                                            profile?.displayName?.takeIf(String::isNotBlank) ?: text("select_profile"),
                                            color = Ink,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        profile?.let { Text(it.id, color = SoftInk, style = MaterialTheme.typography.bodySmall) }
                                    }
                                    KcodeIcon(KcodeIconAsset.ChevronDown, SoftInk, Modifier.size(20.dp))
                                }
                                DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                                    catalogue?.profiles.orEmpty().forEach { item ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(item.id, color = SoftInk, style = MaterialTheme.typography.bodySmall)
                                                }
                                            },
                                            onClick = {
                                                profileMenu = false
                                                scope.launch { session.selectProfile(item.id) }
                                            },
                                        )
                                    }
                                    if (templates.isNotEmpty()) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(text("create_from_template")) },
                                            onClick = { profileMenu = false; showCreateProfile = true },
                                        )
                                    }
                                }
                            }
                            if (profile != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                                    val isActive = catalogue.activeProfileId == profile.id
                                    ManagerPill(
                                        label = if (isActive) text("active") else if (target?.source == ProfileSource.Draft) {
                                            text("draft")
                                        } else {
                                            text("committed")
                                        },
                                        highlighted = isActive,
                                    )
                                    if (target?.source == ProfileSource.History) {
                                        ManagerPill("${text("history_readonly")} ${target.generation ?: ""}")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (target != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                    Button(
                        modifier = Modifier.weight(1f).testTag("plugin-manager-install-plugin"),
                        enabled = canEdit,
                        onClick = {
                            val revision = catalogue?.revision ?: return@Button
                            val selectedProfileId = target.profileId
                            scope.launch {
                                try {
                                    files.withBundles(text("install_plugin"), singlePlugin = true) { selected ->
                                        val file = selected.single().reference
                                        session.importPlugin(ProfileArchiveReference(file.archivePath, file.sha256), revision, selectedProfileId)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    session.reportFailure(failure.message ?: text("plugin_import_failed"))
                                }
                            }
                        },
                    ) {
                        KcodeIcon(KcodeIconAsset.Add, MaterialTheme.colorScheme.onPrimary, Modifier.size(18.dp))
                        Spacer(Modifier.size(KcodeSpacing.xs))
                        Text(text("install_plugin"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        enabled = canEdit && availableModules.isNotEmpty(),
                        onClick = { showAddPlugin = true },
                    ) { Text(text("add_instance"), maxLines = 1) }
                }
                Text(text("install_plugin_hint"), color = SoftInk, style = MaterialTheme.typography.bodySmall)
                if (!embeddedInSettings) {
                    TextButton(
                        enabled = canManage && catalogue != null,
                        onClick = { showInstallBundles = true },
                    ) { Text(text("install_bundle_short")) }
                }

                ManagerSection("${text("plugins")} · ${entries.size}") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        singleLine = true,
                        placeholder = { Text(text("search_plugins")) },
                        leadingIcon = { KcodeIcon(KcodeIconAsset.Search, SoftInk, Modifier.size(20.dp)) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                        FilterChip(
                            selected = listFilter == PluginListFilter.All,
                            onClick = { listFilter = PluginListFilter.All },
                            label = { Text(text("all")) },
                        )
                        FilterChip(
                            selected = listFilter == PluginListFilter.Enabled,
                            onClick = { listFilter = PluginListFilter.Enabled },
                            label = { Text(text("enabled")) },
                        )
                        FilterChip(
                            selected = listFilter == PluginListFilter.Disabled,
                            onClick = { listFilter = PluginListFilter.Disabled },
                            label = { Text(text("disabled")) },
                        )
                    }
                    if (entries.isEmpty()) {
                        ManagerNotice(text("no_plugins"), null, isError = false)
                    } else if (filteredEntries.isEmpty()) {
                        ManagerNotice(text("no_plugin_matches"), null, isError = false)
                    } else {
                        ManagerPanel {
                            filteredEntries.forEachIndexed { index, entry ->
                                val module = availableModules.firstOrNull { it.id == entry.packageId }
                                ManagedPluginRow(
                                    entry = entry,
                                    module = module,
                                    canEdit = canEdit,
                                    text = text,
                                    onEnabledChange = { enabled -> scope.launch { session.setEnabled(entry.id, enabled) } },
                                    onConfigure = {
                                        configureEntry = entry
                                        configureValue = entry.config?.toString() ?: "null"
                                        invalidConfiguration = false
                                    },
                                    onRemove = { removeEntry = entry },
                                )
                                if (index < filteredEntries.lastIndex) {
                                    HorizontalDivider(color = Hairline.copy(alpha = 0.75f))
                                }
                            }
                        }
                    }
                }
            } else if (!embeddedInSettings && catalogue?.profiles?.isNotEmpty() == true && availableModules.isEmpty()) {
                ManagerNotice(text("no_modules"), null, isError = false)
            }

            preview?.let { renderPreviewStatus(it, text, showSuccess = !embeddedInSettings) }
            Spacer(Modifier.size(KcodeSpacing.xs))
        }

        if (showActivationBar) {
            Surface(
                modifier = Modifier.widthIn(max = 780.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                color = Panel,
                shape = RoundedCornerShape(topStart = KcodeRadius.panel, topEnd = KcodeRadius.panel),
                border = BorderStroke(1.dp, Hairline.copy(alpha = 0.7f)),
                shadowElevation = 4.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cancelActive, onCheckedChange = { cancelActive = it }, enabled = canActivate)
                        Column(Modifier.weight(1f)) {
                            Text(text("cancel_active"), color = Ink, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text(if (embeddedInSettings) "activate_plugin_changes_hint" else "activate_hint"),
                                color = SoftInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    preview?.let {
                        val ready = it.packagesVerified && it.diagnostics.isEmpty() && catalogue?.revision == it.revision
                        val previewStatus = if (ready) {
                            "${text("ready_to_activate")} · ${entries.size} ${text("plugins_short")}"
                        } else {
                            text("fix_preview")
                        }
                        Text(
                            previewStatus,
                            color = if (ready) SoftInk else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        enabled = canActivate,
                        modifier = Modifier.fillMaxWidth().testTag("plugin-manager-activate"),
                        onClick = { scope.launch { session.activate(cancelActive) } },
                    ) {
                        Text(text(when {
                            state.busy -> "busy"
                            embeddedInSettings -> "activate_plugin_changes"
                            else -> "activate"
                        }))
                    }
                }
            }
        }
    }

    if (!embeddedInSettings && showCreateProfile) {
        AlertDialog(
            onDismissRequest = { showCreateProfile = false },
            title = { Text(text("create_from_template")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                    Box {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canManage,
                            onClick = { templateMenu = true },
                        ) {
                            Text(template?.displayName ?: text("template"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            KcodeIcon(KcodeIconAsset.ChevronDown, SoftInk, Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                            templates.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice.displayName) },
                                    onClick = { template = choice; templateMenu = false },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = profileId,
                        onValueChange = { profileId = it },
                        modifier = Modifier.fillMaxWidth().testTag("plugin-manager-new-profile-id"),
                        singleLine = true,
                        label = { Text(text("new_profile_id")) },
                        enabled = canManage,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = canManage && template != null && profileId.isNotBlank(), onClick = {
                    val selected = template ?: return@TextButton
                    val id = profileId.trim()
                    showCreateProfile = false
                    profileId = ""
                    scope.launch { session.createFromTemplate(selected, id) }
                }) { Text(text("create_from_template")) }
            },
            dismissButton = { TextButton(onClick = { showCreateProfile = false }) { Text(text("cancel")) } },
        )
    }

    if (showAddPlugin) {
        AlertDialog(
            onDismissRequest = { showAddPlugin = false },
            title = { Text(text("add_instance")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                    Box {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canEdit && availableModules.isNotEmpty(),
                            onClick = { moduleMenu = true },
                        ) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(text("module"), color = SoftInk, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    selectedModule?.let { moduleLabel(it, text) } ?: text("select_module"),
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            KcodeIcon(KcodeIconAsset.ChevronDown, SoftInk, Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = moduleMenu, onDismissRequest = { moduleMenu = false }) {
                            availableModules.forEach { module ->
                                DropdownMenuItem(
                                    text = { Text(moduleLabel(module, text), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { moduleId = module.id; moduleMenu = false },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = entryId,
                        onValueChange = { entryId = it },
                        modifier = Modifier.fillMaxWidth().testTag("plugin-manager-new-entry-id"),
                        singleLine = true,
                        label = { Text(text("entry")) },
                        enabled = canEdit,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = canEdit && selectedModule != null && entryId.isNotBlank(), onClick = {
                    val module = selectedModule ?: return@TextButton
                    val id = entryId.trim()
                    scope.launch {
                        session.add(module.id, id)
                        if (session.state.value.failure == null) {
                            showAddPlugin = false
                            entryId = ""
                        }
                    }
                }) { Text(text("add_instance")) }
            },
            dismissButton = { TextButton(onClick = { showAddPlugin = false }) { Text(text("cancel")) } },
        )
    }

    if (!embeddedInSettings && showInstallBundles) {
        AlertDialog(
            onDismissRequest = { showInstallBundles = false },
            title = { Text(text("install_bundle")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                    Text(text("install_description"), color = SoftInk, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = importId,
                        onValueChange = { importId = it },
                        modifier = Modifier.fillMaxWidth().testTag("plugin-manager-import-id"),
                        singleLine = true,
                        label = { Text(text("new_profile_id")) },
                        enabled = canManage,
                    )
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(text("display_name")) },
                        enabled = canManage,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = canManage && importId.isNotBlank() && catalogue != null, onClick = {
                    val id = importId.trim()
                    val name = importName.ifBlank { id }
                    showInstallBundles = false
                    scope.launch {
                        files.withBundles(text("install_bundle")) { selected ->
                            session.importBundles(id, name, selected.map { it.reference })
                        }
                        importId = ""
                        importName = ""
                    }
                }) { Text(text("choose_bundle_files")) }
            },
            dismissButton = { TextButton(onClick = { showInstallBundles = false }) { Text(text("cancel")) } },
        )
    }

    configureEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { configureEntry = null },
            title = { Text("${text("configure")}: ${entry.id}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                    OutlinedTextField(
                        value = configureValue,
                        onValueChange = { configureValue = it; invalidConfiguration = false },
                        label = { Text(text("configuration")) },
                        modifier = Modifier.fillMaxWidth().testTag("plugin-manager-config"),
                        minLines = 5,
                        enabled = canEdit,
                    )
                    if (invalidConfiguration) Text(text("config_invalid"), color = Error)
                }
            },
            confirmButton = {
                TextButton(enabled = canEdit, onClick = {
                    val value = runCatching {
                        configureValue.takeIf(String::isNotBlank)?.let { Json.parseToJsonElement(it) } ?: JsonNull
                    }.getOrElse {
                        invalidConfiguration = true
                        return@TextButton
                    }
                    configureEntry = null
                    scope.launch { session.configure(entry.id, value, entry.configurationKind) }
                }) { Text(text("save")) }
            },
            dismissButton = { TextButton(onClick = { configureEntry = null }) { Text(text("cancel")) } },
        )
    }
    removeEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { removeEntry = null },
            title = { Text(text("remove")) },
            text = { Text(text("remove_question")) },
            confirmButton = {
                TextButton(
                    enabled = canEdit,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        removeEntry = null
                        scope.launch { session.remove(entry.id) }
                    },
                ) { Text(text("confirm")) }
            },
            dismissButton = { TextButton(onClick = { removeEntry = null }) { Text(text("cancel")) } },
        )
    }
}

private enum class PluginListFilter { All, Enabled, Disabled }

@Composable
private fun ManagedPluginRow(
    entry: ProfileEntry,
    module: ProfileModuleSummary?,
    canEdit: Boolean,
    text: (String) -> String,
    onEnabledChange: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember(entry.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag("managed-plugin-${entry.id}").padding(vertical = KcodeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.id,
                color = Ink,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val moduleDetails = buildList {
                add(entry.packageId)
                module?.version?.takeIf(String::isNotBlank)?.let { add("v$it") }
                add(when (module?.source) {
                    ProfileModuleSource.Host -> text("host_module")
                    ProfileModuleSource.Package -> text("package_module")
                    null -> text("unknown_module")
                })
            }.joinToString(" · ")
            Text(moduleDetails, color = SoftInk, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text(if (entry.enabled) "enabled" else "disabled"),
            color = SoftInk,
            style = MaterialTheme.typography.labelSmall,
        )
        Switch(checked = entry.enabled, enabled = canEdit, onCheckedChange = onEnabledChange)
        Box {
            IconButton(enabled = canEdit, onClick = { menuExpanded = true }) {
                KcodeIcon(KcodeIconAsset.More, SoftInk, Modifier.size(20.dp), contentDescription = text("more_options"))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text(text("configure")) }, onClick = { menuExpanded = false; onConfigure() })
                DropdownMenuItem(text = { Text(text("remove")) }, onClick = { menuExpanded = false; onRemove() })
            }
        }
    }
}

@Composable
private fun ManagerHeader(
    title: String,
    description: String,
    refreshLabel: String,
    recoveryLabel: String,
    showRecovery: Boolean,
    profileManagementLabel: String,
    onProfileManagement: (() -> Unit)?,
    onRefresh: () -> Unit,
    onReturn: (() -> Unit)?,
    refreshEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.md)) {
            Surface(
                modifier = Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) { KcodeBrandMark(Modifier.size(24.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Ink, style = MaterialTheme.typography.titleLarge)
                Text(description, color = SoftInk, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(enabled = refreshEnabled, onClick = onRefresh) { Text(refreshLabel) }
        }
        if ((showRecovery && onReturn != null) || onProfileManagement != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
                onProfileManagement?.let { onClick ->
                    TextButton(onClick = onClick) { Text(profileManagementLabel) }
                }
                if (showRecovery && onReturn != null) {
                    TextButton(onClick = onReturn) { Text(recoveryLabel) }
                }
            }
        }
    }
}

@Composable
private fun ManagerSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
        Column(Modifier.padding(start = KcodeSpacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, color = SoftInk, style = MaterialTheme.typography.bodySmall) }
        }
        content()
    }
}

@Composable
private fun ManagerPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(KcodeRadius.card),
        border = BorderStroke(1.dp, Hairline.copy(alpha = 0.6f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(KcodeSpacing.md),
            verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm),
            content = content,
        )
    }
}

@Composable
private fun ManagerNotice(
    title: String,
    message: String?,
    isError: Boolean,
    isSuccess: Boolean = false,
) {
    val container = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isSuccess -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isSuccess -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        shape = RoundedCornerShape(KcodeRadius.card),
    ) {
        Column(Modifier.padding(KcodeSpacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = content, style = MaterialTheme.typography.titleSmall)
            message?.let { Text(it, color = content, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ManagerPill(label: String, highlighted: Boolean = false) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = CircleShape,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else SoftInk,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun moduleLabel(module: ProfileModuleSummary, text: (String) -> String): String {
    val version = module.version.takeIf { it.isNotBlank() } ?: text("unknown_version")
    return "${module.id} · $version"
}

@Composable
private fun renderPreviewStatus(
    preview: ProfilePreview,
    text: (String) -> String,
    showSuccess: Boolean = true,
) {
    if (showSuccess && preview.packagesVerified && preview.diagnostics.isEmpty()) {
        ManagerNotice(text("preview_ok"), null, isError = false, isSuccess = true)
    }
    if (preview.diagnostics.isNotEmpty()) {
        ManagerSection(text("diagnostics")) {
            ManagerPanel {
                preview.diagnostics.forEach { diagnostic ->
                    Text(
                        "${diagnostic.layer} · ${diagnostic.target.orEmpty()} · ${diagnostic.message}",
                        color = Error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

fun profilePluginManagerText(key: String, languageCode: String): String {
    val language = if (languageCode.startsWith("zh")) "zh" else "en"
    val localizedKey = if (language == "zh") "${key}_zh" else key
    return ProfileManagerResourceStrings[localizedKey] ?: ProfileManagerResourceStrings[key] ?: key
}
