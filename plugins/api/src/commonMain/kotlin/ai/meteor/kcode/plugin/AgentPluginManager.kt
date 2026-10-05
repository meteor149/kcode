package ai.meteor.kcode.plugin

const val CurrentPluginApiVersion = 60

interface AgentPluginManager {
    /** Resolve platform packages and commit the complete dependency set as one composition. */
    suspend fun importPackages(packages: List<PluginPackageImport>): Unit =
        error("This manager does not support plugin package archives")

    /** All upserts, removals and enable changes publish together or restore the committed state. */
    suspend fun applyChanges(changes: PluginCompositionChange): Unit =
        error("This manager does not support composition transactions")

    suspend fun install(spec: DynamicPluginSpec)
    suspend fun replace(spec: DynamicPluginSpec)
    suspend fun uninstall(id: String)
    suspend fun installed(): List<DynamicPluginSpec>

    /** Enables or disables a configured plugin, including built-in providers and consumers. */
    suspend fun setEnabled(id: String, enabled: Boolean)
}

data class DynamicPluginSpec(
    val id: String,
    val version: String,
    val entryClass: String,
    val artifactPath: String,
    val sha256: String,
    val dependencies: List<String> = emptyList(),
    val config: Any? = Unit,
    val packageName: String? = null,
    val capabilities: Set<String> = emptySet(),
    val apiVersion: Int = CurrentPluginApiVersion,
    val enabled: Boolean = true,
    val packageInstallation: PluginPackageInstallation? = null,
)
