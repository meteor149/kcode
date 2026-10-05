package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import kotlinx.serialization.Serializable
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

data class PluginPackageImport(
    val archivePath: String,
    val sha256: String,
    val configuration: StoredPluginConfiguration? = null,
    /** null preserves an installed release's state, or enables a first installation. */
    val enabled: Boolean? = null,
)

data class PluginCompositionChange(
    val upserts: List<DynamicPluginSpec> = emptyList(),
    val removals: Set<String> = emptySet(),
    val enabled: Map<String, Boolean> = emptyMap(),
)

/** Host-owned release lock, committed in the same snapshot as the native descriptor. */
@Serializable
data class PluginPackageInstallation(
    val archivePath: String,
    val archiveSha256: String,
    val variantId: String,
    val runtimeAbi: String,
    val dependencies: Map<String, String> = emptyMap(),
)

interface PluginPackageResolver {
    /** Verify/stage all archives before returning any candidate. Never mutates the plugin tree. */
    suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec>
    /** Recheck archive, selected variant, native payload and SDK identity before activation. */
    suspend fun verify(spec: DynamicPluginSpec)
}

class KcodePluginPackages(ctx: Context, val resolver: PluginPackageResolver) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodePluginPackages>("pluginPackages") }
}
