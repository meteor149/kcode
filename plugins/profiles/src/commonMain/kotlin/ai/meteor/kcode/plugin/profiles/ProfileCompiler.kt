package ai.meteor.kcode.plugin.profiles

import org.cordis.include.CompositionLayer
import org.cordis.include.CompositionResult
import org.cordis.include.PatchOptions
import org.cordis.include.composeEntries
import org.cordis.loader.EntryOptions
import org.cordis.loader.changeTo

/** Compiles user intent only. Package verification and resource allocation happen during activation. */
class ProfileCompiler {
    fun compile(
        profile: ProfileDefinition,
        bundles: List<ProfileBundle>,
        machineOverrides: List<ProfileOperation> = emptyList(),
        launchOverrides: List<ProfileOperation> = emptyList(),
    ): CompositionResult {
        profile.validate()
        require(bundles.map { it.id }.distinct().size == bundles.size) { "Duplicate resolved bundle" }
        val available = bundles.associateBy { it.id }
        val layers = profile.bundles.map { reference ->
            val bundle = requireNotNull(available[reference.id]) { "Missing profile bundle '${reference.id}'" }
            require(bundle.formatVersion == 1 && bundle.version == reference.version) { "Incompatible profile bundle '${reference.id}'" }
            CompositionLayer("bundle:${bundle.id}@${bundle.version}", bundle.patches.map(::patch))
        } + listOf(
            CompositionLayer("profile:${profile.id}", profile.patches.map(::patch)),
            CompositionLayer("machine", machineOverrides.map(::patch)),
            CompositionLayer("launch", launchOverrides.map(::patch)),
        )
        return composeEntries(emptyList(), layers)
    }

    private fun entry(value: ProfileEntry): EntryOptions {
        require(value.id.isNotBlank() && value.packageId.isNotBlank()) { "Incomplete profile entry" }
        return EntryOptions(
            id = value.id,
            name = value.packageId,
            config = value.children?.map(::entry) ?: value.config,
            group = value.children?.let { true },
            disabled = !value.enabled,
        )
    }

    private fun patch(operation: ProfileOperation): PatchOptions = when (operation) {
        is ProfileOperation.Insert -> PatchOptions(id = operation.parent, insert = operation.entries.map(::entry))
        is ProfileOperation.Configure -> PatchOptions(id = operation.target, config = changeTo(operation.config))
        is ProfileOperation.Enable -> PatchOptions(id = operation.target, disabled = changeTo(false))
        is ProfileOperation.Disable -> PatchOptions(id = operation.target, disabled = changeTo(true))
        is ProfileOperation.Replace -> PatchOptions(
            id = operation.target,
            name = operation.expectedPackageId,
            replacement = operation.packageId,
        )
        is ProfileOperation.Remove -> PatchOptions(id = operation.target, remove = true)
    }
}
