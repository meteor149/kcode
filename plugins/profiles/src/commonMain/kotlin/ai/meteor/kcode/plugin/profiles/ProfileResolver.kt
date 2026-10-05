package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.DynamicPluginSpec
import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.StoredDynamicPlugin
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import org.cordis.include.CompositionResult
import org.cordis.loader.EntryOptions
import kotlinx.serialization.json.JsonElement

data class ResolvedProfile(
    val definition: ProfileDefinition,
    val composition: CompositionResult,
    val packages: List<DynamicPluginSpec>,
    val lock: ProfileLock,
    val bundles: List<ProfileBundle> = emptyList(),
    val machineOverrides: List<ProfileOperation> = emptyList(),
    val launchOverrides: List<ProfileOperation> = emptyList(),
)

/** Dependency hints locate archives; the native resolver still verifies the real manifest graph. */
data class ProfilePackageOffer(val release: PluginPackageImport, val dependencies: Set<String> = emptySet())

/** Resolves verified releases without mounting anything. Entries retain their own identities and scopes. */
class ProfileResolver(private val packages: PluginPackageResolver) {
    suspend fun resolve(
        definition: ProfileDefinition,
        bundles: List<ProfileBundle>,
        offers: Map<String, ProfilePackageOffer>,
        builtinModules: Set<String>,
        previous: List<DynamicPluginSpec> = emptyList(),
        machineOverrides: List<ProfileOperation> = emptyList(),
        launchOverrides: List<ProfileOperation> = emptyList(),
    ): ResolvedProfile {
        val composition = ProfileCompiler().compile(definition, bundles, machineOverrides, launchOverrides).requireValid()
        val referenced = mutableSetOf<String>()
        fun visit(entries: List<EntryOptions>) {
            entries.forEach { entry ->
                profileConfiguration(entry)
                if (entry.group == true) {
                    require(entry.name == "core.group" || entry.name == "cordis:group") { "Profile groups use core.group" }
                    val children = entry.config as List<*>
                    visit(children.map { it as EntryOptions })
                } else if (entry.name !in builtinModules) referenced += entry.name
            }
        }
        visit(composition.entries)
        // Releases are available code; only entries determine which instances run.
        val existing = previous.associate { it.id to it.copy(enabled = true) }
        val requested = linkedSetOf<String>()
        fun collect(id: String) {
            if (!requested.add(id)) return
            val dependencies = offers[id]?.dependencies ?: existing[id]?.packageInstallation?.dependencies?.keys
                ?: error("No package release available for '$id'")
            dependencies.forEach(::collect)
        }
        referenced.forEach(::collect)
        val requests = requested.mapNotNull { id ->
            offers[id]?.release?.copy(enabled = true)?.also { require(it.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid profile package digest" } }
                ?: run {
                    require(id in existing) { "No package release available for '$id'" }
                    null
                }
        }
        val candidates = if (requests.isEmpty()) emptyList() else packages.resolve(requests, existing.values.filter { it.id in requested })
        require(candidates.map { it.id }.distinct().size == candidates.size) { "Duplicate resolved package identity" }
        require(candidates.all { it.id in requested && offers[it.id]?.release?.sha256 == it.packageInstallation?.archiveSha256 }) {
            "Resolved package identity does not match profile offers"
        }
        val available = existing + candidates.associateBy { it.id }
        val required = linkedSetOf<String>()
        fun retain(id: String) {
            if (!required.add(id)) return
            val spec = requireNotNull(available[id]) { "Package resolution omitted '$id'" }
            val installation = requireNotNull(spec.packageInstallation) { "Portable profiles require verified package archives for '$id'" }
            installation.dependencies.keys.forEach(::retain)
        }
        referenced.forEach(::retain)
        val resolved = required.map { available.getValue(it) }
        val snapshot = PluginCompositionSnapshot(external = resolved.map(StoredDynamicPlugin::from)).also { it.validate() }
        val ordered = snapshot.orderedExternal().map { available.getValue(it.id) }
        ordered.forEach { packages.verify(it) }
        return ResolvedProfile(definition, composition, ordered, profileLock(snapshot),
            bundles.filter { bundle -> definition.bundles.any { it.id == bundle.id } }, machineOverrides, launchOverrides)
    }
}

/** Null means use the module's declared default; explicit JSON null remains an override. */
fun profileConfiguration(entry: EntryOptions): StoredPluginConfiguration? {
    if (entry.group == true || entry.config == null) return null
    val value = entry.config as? JsonElement ?: error("Profile configuration must be portable JSON")
    return StoredPluginConfiguration(entry.extra["kcode.configurationKind"] as? String ?: "json", value).also { it.decode() }
}

fun profileLock(snapshot: PluginCompositionSnapshot): ProfileLock = ProfileLock(packages = snapshot.external.mapNotNull { spec ->
    spec.packageInstallation?.let { installation ->
        LockedProfilePackage(spec.id, spec.version, installation.archiveSha256, installation.variantId,
            installation.runtimeAbi, installation.dependencies)
    }
}).also { it.validate() }
