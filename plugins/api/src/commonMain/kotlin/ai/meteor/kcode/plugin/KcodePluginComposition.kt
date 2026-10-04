package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.PluginDescriptor
import org.cordis.Context
import org.cordis.Fiber
import org.cordis.Plugin

interface KcodePluginMount {
    val descriptor: PluginDescriptor
    suspend fun mount(context: Context): Fiber<*>
}

fun <C> kcodePlugin(descriptor: PluginDescriptor, plugin: Plugin<C>, config: C): KcodePluginMount {
    val validated = plugin.config?.validate(config) ?: config
    return object : KcodePluginMount {
        override val descriptor = descriptor
        override suspend fun mount(context: Context): Fiber<*> = context.plugin(plugin, validated)
    }
}

/** A declarative patch over the default bundle; ids are checked before mounting. */
data class KcodePluginProfile(
    val includeDefaults: Boolean = true,
    val disabled: Set<String> = emptySet(),
    val overrides: List<KcodePluginMount> = emptyList(),
)
