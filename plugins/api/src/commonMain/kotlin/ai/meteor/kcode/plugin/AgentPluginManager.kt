package ai.meteor.kcode.plugin

const val CurrentPluginApiVersion = 34

interface AgentPluginManager {
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
)
