package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.plugin.CurrentPluginApiVersion
import ai.meteor.kcode.plugin.MinimumCompatiblePluginApiVersion
import ai.meteor.kcode.plugin.DynamicPluginSpec
import ai.meteor.kcode.plugin.PluginPackageInstallation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

/** Durable composition metadata, separate from artifacts and application settings. */
interface PluginCompositionStore {
    suspend fun load(): PluginCompositionSnapshot
    /** Atomic publication: on failure the previously committed snapshot must remain readable. */
    suspend fun save(snapshot: PluginCompositionSnapshot)
}

@Serializable
data class PluginCompositionSnapshot(
    val formatVersion: Int = 1,
    val builtinsEnabled: Map<String, Boolean> = emptyMap(),
    val external: List<StoredDynamicPlugin> = emptyList(),
    /** Last offered bundled release, retained after uninstall to remember the user's choice. */
    val bundledPackages: Map<String, String> = emptyMap(),
) {
    fun validate() = validateSnapshot(requireCompatibleApi = true)

    /** Historical descriptors may be replaced by a verified bundled release before activation. */
    fun validateForRestore() = validateSnapshot(requireCompatibleApi = false)

    private fun validateSnapshot(requireCompatibleApi: Boolean) {
        require(formatVersion == 1) { "Unsupported plugin snapshot format: $formatVersion" }
        require(bundledPackages.all { (id, digest) -> id.isNotBlank() && id !in BootstrapIds && digest.matches(Regex("[0-9a-f]{64}")) }) { "Invalid bundled release history" }
        require(builtinsEnabled.keys.all { it.isNotBlank() && it !in BootstrapIds }) { "Invalid builtin plugin state" }
        require(external.map { it.id }.distinct().size == external.size) { "Duplicate external plugin ids" }
        external.forEach {
            val spec = it.toSpec()
            if (requireCompatibleApi) spec.validatePluginApi() else spec.validatePluginDescriptor()
        }
        validatePackageDependencies(external.map { it.toSpec() })
        orderedExternal()
    }

    /** Class-loader dependencies must exist and be restored before their dependants. */
    fun orderedExternal(): List<StoredDynamicPlugin> {
        val remaining = external.toMutableList()
        val ordered = mutableListOf<StoredDynamicPlugin>()
        val ids = external.map { it.id }.toSet()
        require(external.all { spec -> spec.dependencies.all { it in ids } }) { "Missing external plugin dependency" }
        while (remaining.isNotEmpty()) {
            val ready = remaining.firstOrNull { spec ->
                (spec.dependencies + spec.packageInstallation?.dependencies.orEmpty().keys).all { dependency -> ordered.any { it.id == dependency } }
            }
                ?: error("Cyclic external plugin dependencies")
            ordered += ready
            remaining.remove(ready)
        }
        return ordered
    }
}

@Serializable
data class StoredDynamicPlugin(
    val id: String,
    val version: String,
    val entryClass: String,
    val artifactPath: String,
    val sha256: String,
    val dependencies: List<String>,
    val configuration: StoredPluginConfiguration,
    val packageName: String?,
    val capabilities: Set<String>,
    val apiVersion: Int,
    val enabled: Boolean,
    val packageInstallation: PluginPackageInstallation? = null,
) {
    fun toSpec(): DynamicPluginSpec = DynamicPluginSpec(
        id, version, entryClass, artifactPath, sha256, dependencies, configuration.decode(), packageName,
        capabilities, apiVersion, enabled, packageInstallation,
    )

    companion object {
        fun from(spec: DynamicPluginSpec): StoredDynamicPlugin {
            spec.validatePluginApi()
            return StoredDynamicPlugin(
                spec.id, spec.version, spec.entryClass, spec.artifactPath, spec.sha256, spec.dependencies,
                StoredPluginConfiguration.encode(spec.config), spec.packageName, spec.capabilities, spec.apiVersion, spec.enabled, spec.packageInstallation,
            )
        }
    }
}

/** Never serialize host implementation classes or arbitrary Kotlin object graphs. */
@Serializable
data class StoredPluginConfiguration(val kind: String, val value: JsonElement = JsonNull) {
    fun decode(): Any? = when (kind) {
        "unit" -> Unit
        "null" -> null
        "json" -> value
        "string" -> (value as JsonPrimitive).content
        "boolean" -> (value as JsonPrimitive).boolean
        "int" -> (value as JsonPrimitive).int
        "long" -> (value as JsonPrimitive).long
        "float" -> (value as JsonPrimitive).float.also { require(it.isFinite()) }
        "double" -> (value as JsonPrimitive).double.also { require(it.isFinite()) }
        else -> error("Unsupported plugin configuration kind: $kind")
    }

    companion object {
        fun encode(config: Any?): StoredPluginConfiguration = when (config) {
            Unit -> StoredPluginConfiguration("unit")
            null -> StoredPluginConfiguration("null")
            is JsonElement -> StoredPluginConfiguration("json", config)
            is String -> StoredPluginConfiguration("string", JsonPrimitive(config))
            is Boolean -> StoredPluginConfiguration("boolean", JsonPrimitive(config))
            is Int -> StoredPluginConfiguration("int", JsonPrimitive(config))
            is Long -> StoredPluginConfiguration("long", JsonPrimitive(config))
            is Float -> { require(config.isFinite()); StoredPluginConfiguration("float", JsonPrimitive(config)) }
            is Double -> { require(config.isFinite()); StoredPluginConfiguration("double", JsonPrimitive(config)) }
            else -> error("Persistent plugin configuration must be Unit, null, JSON or a supported scalar")
        }
    }
}

fun DynamicPluginSpec.validatePluginApi() {
    validatePluginDescriptor()
    require(apiVersion in MinimumCompatiblePluginApiVersion..CurrentPluginApiVersion) {
        "Plugin '$id' requires API $apiVersion; host supports APIs $MinimumCompatiblePluginApiVersion..$CurrentPluginApiVersion"
    }
}

private fun DynamicPluginSpec.validatePluginDescriptor() {
    require(apiVersion > 0) { "Invalid plugin API version" }
    require(id.isNotBlank() && id !in BootstrapIds) { "Invalid plugin id '$id'" }
    require(version.isNotBlank() && entryClass.isNotBlank() && artifactPath.isNotBlank()) { "Incomplete plugin descriptor '$id'" }
    require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256 for '$id'" }
    require(dependencies.distinct().size == dependencies.size && id !in dependencies) { "Invalid dependencies for '$id'" }
    packageInstallation?.let { release ->
        require(dependencies.isEmpty()) { "Package dependencies must not expose private class loaders" }
        require(release.archivePath.isNotBlank() && release.variantId.isNotBlank()) { "Incomplete package installation" }
        require(release.archiveSha256.matches(Regex("[a-f0-9]{64}")) && release.runtimeAbi.matches(Regex("[a-f0-9]{64}"))) { "Invalid package identity" }
        require(id !in release.dependencies && release.dependencies.all { it.key.isNotBlank() && it.value.isNotBlank() }) { "Invalid package dependency" }
    }
}

fun validatePackageDependencies(specs: List<DynamicPluginSpec>) {
    val byId = specs.associateBy { it.id }
    specs.forEach { spec ->
        spec.packageInstallation?.dependencies?.forEach { (id, version) ->
            val dependency = byId[id]
            require(dependency?.packageInstallation != null && dependency.version == version) { "Missing/conflicting package dependency '$id@$version'" }
            require(!spec.enabled || dependency.enabled) { "Enabled package '${spec.id}' requires enabled '$id'" }
        }
    }
    val pending = specs.filter { it.packageInstallation != null }.toMutableList()
    val ordered = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
        val ready = pending.firstOrNull { it.packageInstallation!!.dependencies.keys.all { dependency -> dependency in ordered } }
            ?: error("Cyclic package dependencies")
        ordered += ready.id
        pending.remove(ready)
    }
}

/** Optional persistent provider; removing it explicitly returns management to process-local state. */
class KcodePluginInstallations(ctx: Context, val store: PluginCompositionStore?) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodePluginInstallations>("pluginInstallations") }
}

private val BootstrapIds = setOf("core.plugin-inventory", "core.loader")
