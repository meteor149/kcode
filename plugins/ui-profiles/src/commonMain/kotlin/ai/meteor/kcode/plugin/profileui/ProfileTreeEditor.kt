package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.profileui.resources.Res
import ai.meteor.kcode.plugin.profileui.resources.profile_add_bundle
import ai.meteor.kcode.plugin.profileui.resources.profile_bundle_id
import ai.meteor.kcode.plugin.profileui.resources.profile_bundles
import ai.meteor.kcode.plugin.profileui.resources.profile_clone_history
import ai.meteor.kcode.plugin.profileui.resources.profile_config_kind
import ai.meteor.kcode.plugin.profileui.resources.profile_config_value
import ai.meteor.kcode.plugin.profileui.resources.profile_configure_node
import ai.meteor.kcode.plugin.profileui.resources.profile_context_node
import ai.meteor.kcode.plugin.profileui.resources.profile_disable_node
import ai.meteor.kcode.plugin.profileui.resources.profile_enable_node
import ai.meteor.kcode.plugin.profileui.resources.profile_entry_id
import ai.meteor.kcode.plugin.profileui.resources.profile_group_node
import ai.meteor.kcode.plugin.profileui.resources.profile_inject
import ai.meteor.kcode.plugin.profileui.resources.profile_insert_node
import ai.meteor.kcode.plugin.profileui.resources.profile_intercept
import ai.meteor.kcode.plugin.profileui.resources.profile_invalid_form
import ai.meteor.kcode.plugin.profileui.resources.profile_isolate
import ai.meteor.kcode.plugin.profileui.resources.profile_module
import ai.meteor.kcode.plugin.profileui.resources.profile_move_down
import ai.meteor.kcode.plugin.profileui.resources.profile_move_node
import ai.meteor.kcode.plugin.profileui.resources.profile_move_up
import ai.meteor.kcode.plugin.profileui.resources.profile_node
import ai.meteor.kcode.plugin.profileui.resources.profile_operation
import ai.meteor.kcode.plugin.profileui.resources.profile_parent
import ai.meteor.kcode.plugin.profileui.resources.profile_position
import ai.meteor.kcode.plugin.profileui.resources.profile_remove_bundle
import ai.meteor.kcode.plugin.profileui.resources.profile_remove_node
import ai.meteor.kcode.plugin.profileui.resources.profile_replace_node
import ai.meteor.kcode.plugin.profileui.resources.profile_root
import ai.meteor.kcode.plugin.profileui.resources.profile_save_operation
import ai.meteor.kcode.plugin.profileui.resources.profile_sources
import ai.meteor.kcode.plugin.profileui.resources.profile_tree_editor
import ai.meteor.kcode.plugin.profileui.resources.profile_version
import ai.meteor.kcode.ui.design.KcodeSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource

@Composable
internal fun ProfileTreeEditor(
    state: ProfileUiState,
    enabled: Boolean,
    onOperation: (ProfileTarget, Long, ProfileOperation) -> Unit,
    onBundles: (ProfileTarget, Long, List<ProfileBundleReference>) -> Unit,
) {
    val target = state.target ?: return
    val revision = state.documentRevision ?: return
    val entries = state.preview?.entries.orEmpty()
    val flat = remember(entries) { flattenProfileEntries(entries) }
    val editable = enabled && !state.dirty && target.source != ProfileSource.History
    var action by remember(target.profileId) { mutableStateOf(ProfileTreeAction.Insert) }
    var selectedId by remember(target.profileId) { mutableStateOf("") }
    val selected = flat.firstOrNull { it.id == selectedId }
    var id by remember(target.profileId, revision) { mutableStateOf("") }
    var module by remember(target.profileId, revision, selectedId) { mutableStateOf(selected?.packageId.orEmpty()) }
    var parent by remember(target.profileId, revision, selectedId) { mutableStateOf<String?>(null) }
    var position by remember(target.profileId, revision, selectedId) { mutableStateOf("") }
    var config by remember(target.profileId, revision, selectedId) { mutableStateOf(selected?.config?.toString().orEmpty()) }
    var kind by remember(target.profileId, revision, selectedId) {
        mutableStateOf(selected?.configurationKind?.takeIf { it in ProfileConfigurationKinds } ?: "json")
    }
    var inject by remember(target.profileId, revision, selectedId) { mutableStateOf(JsonObject(selected?.inject.orEmpty()).toString()) }
    var intercept by remember(target.profileId, revision, selectedId) { mutableStateOf(JsonObject(selected?.intercept.orEmpty()).toString()) }
    var isolate by remember(target.profileId, revision, selectedId) {
        mutableStateOf(JsonObject(selected?.isolate.orEmpty().mapValues { (_, realm) -> realm?.let(::JsonPrimitive) ?: JsonNull }).toString())
    }
    var invalid by remember(target, revision, action, selectedId) { mutableStateOf(false) }
    var sources by remember(target.profileId, selectedId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
        Text(profileText(Res.string.profile_tree_editor), style = MaterialTheme.typography.titleMedium)
        if (target.source == ProfileSource.History) Text(profileText(Res.string.profile_clone_history))
        ProfileChoice(profileText(Res.string.profile_operation), profileText(action.label()), editable,
            ProfileTreeAction.entries.map { it to profileText(it.label()) }) { action = it }
        if (action != ProfileTreeAction.Insert && action != ProfileTreeAction.Group) {
            ProfileChoice(profileText(Res.string.profile_node), selectedId, editable,
                flat.map { it.id to "${it.id} · ${it.packageId}" }) { selectedId = it }
        }
        if (action == ProfileTreeAction.Insert || action == ProfileTreeAction.Group) {
            ProfileField(profileText(Res.string.profile_entry_id), id, editable) { id = it }
        }
        if (action == ProfileTreeAction.Insert || action == ProfileTreeAction.Replace) {
            ProfileChoice(profileText(Res.string.profile_module), module, editable,
                state.modules.map { it.id to it.id }) { module = it }
        }
        if (action in listOf(ProfileTreeAction.Insert, ProfileTreeAction.Group, ProfileTreeAction.Move)) {
            ProfileChoice(profileText(Res.string.profile_parent), parent ?: profileText(Res.string.profile_root), editable,
                listOf<String?>(null).map { it to profileText(Res.string.profile_root) } +
                    flat.filter { it.children != null }.map { it.id as String? to it.id }) { parent = it }
            ProfileField(profileText(Res.string.profile_position), position, editable) { position = it }
        }
        if (action == ProfileTreeAction.Configure || action == ProfileTreeAction.Insert) {
            ProfileChoice(profileText(Res.string.profile_config_kind), kind, editable,
                ProfileConfigurationKinds.map { it to it }) { kind = it }
            ProfileField(profileText(Res.string.profile_config_value), config, editable, multiline = true) { config = it }
        }
        if (action == ProfileTreeAction.Context) {
            ProfileField(profileText(Res.string.profile_inject), inject, editable, multiline = true) { inject = it }
            ProfileField(profileText(Res.string.profile_intercept), intercept, editable, multiline = true) { intercept = it }
            ProfileField(profileText(Res.string.profile_isolate), isolate, editable, multiline = true) { isolate = it }
        }
        if (invalid) Text(profileText(Res.string.profile_invalid_form), color = MaterialTheme.colorScheme.error)
        Button(enabled = editable && state.preview != null && state.preview.diagnostics.isEmpty(), onClick = {
            try {
                val form = ProfileTreeForm(action, selectedId, id, module, parent, position, config, kind,
                    inject, intercept, isolate)
                val operation = form.operation(entries, state.modules.map { it.id }.toSet())
                invalid = false
                onOperation(target, revision, operation)
            } catch (error: IllegalArgumentException) { invalid = true }
            catch (error: IllegalStateException) { invalid = true }
        }) { Text(profileText(Res.string.profile_save_operation)) }
        if (selected != null) {
            TextButton(onClick = { sources = !sources }) { Text(profileText(Res.string.profile_sources)) }
            if (sources) state.preview?.origins?.get(selected.id)?.forEach { (field, origin) ->
                Text("$field · ${origin.layer}[${origin.operation}]", style = MaterialTheme.typography.bodySmall)
            }
        }
        BundleEditor(state, editable, onBundles)
    }
}

@Composable
private fun BundleEditor(
    state: ProfileUiState,
    enabled: Boolean,
    onBundles: (ProfileTarget, Long, List<ProfileBundleReference>) -> Unit,
) {
    val target = state.target ?: return
    val revision = state.documentRevision ?: return
    val definition = remember(state.document) {
        try { Json.decodeFromString(ProfileDefinition.serializer(), state.document) }
        catch (error: IllegalArgumentException) { null }
    } ?: return
    var id by remember(target.profileId) { mutableStateOf("") }
    var version by remember(target.profileId) { mutableStateOf("") }
    Text(profileText(Res.string.profile_bundles), style = MaterialTheme.typography.titleMedium)
    definition.bundles.forEachIndexed { index, bundle ->
        Text("${bundle.id} · ${bundle.version}")
        Row {
            TextButton(enabled = enabled && index > 0, onClick = {
                val next = definition.bundles.toMutableList()
                next.add(index - 1, next.removeAt(index))
                onBundles(target, revision, next)
            }) { Text(profileText(Res.string.profile_move_up)) }
            TextButton(enabled = enabled && index < definition.bundles.lastIndex, onClick = {
                val next = definition.bundles.toMutableList()
                next.add(index + 1, next.removeAt(index))
                onBundles(target, revision, next)
            }) { Text(profileText(Res.string.profile_move_down)) }
            TextButton(enabled = enabled, onClick = {
                onBundles(target, revision, definition.bundles.filterIndexed { position, _ -> position != index })
            }) { Text(profileText(Res.string.profile_remove_bundle)) }
        }
    }
    ProfileField(profileText(Res.string.profile_bundle_id), id, enabled) { id = it }
    ProfileField(profileText(Res.string.profile_version), version, enabled) { version = it }
    TextButton(enabled = enabled && id.isNotBlank() && version.isNotBlank(), onClick = {
        onBundles(target, revision, definition.bundles + ProfileBundleReference(id, version))
    }) { Text(profileText(Res.string.profile_add_bundle)) }
}

@Composable
private fun ProfileField(label: String, value: String, enabled: Boolean, multiline: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 6 else 1,
        label = { Text(label) })
}

@Composable
private fun <T> ProfileChoice(label: String, value: String, enabled: Boolean, choices: List<Pair<T, String>>, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(enabled = enabled, onClick = { expanded = true }) { Text("$label: $value") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChange(key) })
            }
        }
    }
}

private fun ProfileTreeAction.label(): StringResource = when (this) {
    ProfileTreeAction.Insert -> Res.string.profile_insert_node
    ProfileTreeAction.Group -> Res.string.profile_group_node
    ProfileTreeAction.Configure -> Res.string.profile_configure_node
    ProfileTreeAction.Move -> Res.string.profile_move_node
    ProfileTreeAction.Replace -> Res.string.profile_replace_node
    ProfileTreeAction.Enable -> Res.string.profile_enable_node
    ProfileTreeAction.Disable -> Res.string.profile_disable_node
    ProfileTreeAction.Remove -> Res.string.profile_remove_node
    ProfileTreeAction.Context -> Res.string.profile_context_node
}
