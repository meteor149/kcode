package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot

/** Pure legacy migration. Publication happens only after package/runtime activation succeeds. */
fun migrateLegacyProfile(
    template: ProfileDefinition,
    bundles: List<ProfileBundle>,
    legacy: PluginCompositionSnapshot,
    aliases: Map<String, Set<String>> = emptyMap(),
): ProfileDefinition {
    legacy.validateForRestore()
    val base = ProfileCompiler().compile(template, bundles).requireValid()
    val entries = linkedMapOf<String, org.cordis.loader.EntryOptions>()
    fun visit(items: List<org.cordis.loader.EntryOptions>) {
        items.forEach { entry ->
            entries[entry.id] = entry
            if (entry.group == true) visit((entry.config as List<*>).map { it as org.cordis.loader.EntryOptions })
        }
    }
    visit(base.entries)
    val operations = mutableListOf<ProfileOperation>()
    val states = linkedMapOf<String, Boolean>()
    legacy.builtinsEnabled.forEach { (id, enabled) ->
        val targets = if (id in entries) setOf(id) else aliases[id] ?: error("Legacy builtin '$id' has no profile migration target")
        targets.forEach { target ->
            require(target in entries) { "Unknown legacy alias target '$target'" }
            states[target] = (states[target] ?: true) && enabled
        }
    }
    // An offered-but-absent release is the user's explicit uninstall decision.
    val installed = legacy.external.associateBy { it.id }
    legacy.bundledPackages.keys.filter { it !in installed }.forEach { id ->
        if (id in entries) operations += ProfileOperation.Remove(id)
        else aliases[id].orEmpty().filter { it in entries }.forEach { operations += ProfileOperation.Disable(it) }
    }
    legacy.external.forEach { spec ->
        val config = spec.configuration
        if (spec.id !in entries) operations += ProfileOperation.Insert(listOf(ProfileEntry(
            id = spec.id,
            packageId = spec.id,
            config = config.value,
            enabled = spec.enabled,
            configurationKind = config.kind,
        ))) else {
            operations += ProfileOperation.Replace(spec.id, spec.id)
            operations += ProfileOperation.Configure(spec.id, config.value, config.kind)
            states[spec.id] = spec.enabled
        }
    }
    states.forEach { (id, enabled) ->
        if (operations.none { it is ProfileOperation.Remove && it.target == id }) {
            operations += if (enabled) ProfileOperation.Enable(id) else ProfileOperation.Disable(id)
        }
    }
    return template.copy(patches = template.patches + operations).also {
        ProfileCompiler().compile(it, bundles).requireValid()
    }
}
