package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import org.cordis.ConfigValidator
import org.cordis.asDynamicPlugin
import org.cordis.plugin
import org.cordis.Context
import org.cordis.Fiber
import org.cordis.Plugin

interface KcodePluginMount {
    val descriptor: PluginDescriptor
    suspend fun mount(context: Context): Fiber<*>
    /** Exposes a lazy module to a declarative tree; resources still allocate during apply. */
    fun export(configuration: StoredPluginConfiguration? = null): Plugin<Any?> {
        require(configuration == null) { "This host mount does not support portable configuration" }
        return plugin<Any?> { context, _ ->
            val fiber = mount(context)
            collect { fiber.dispose() }
            fiber.await()
        }
    }
}

fun <C> kcodePlugin(descriptor: PluginDescriptor, plugin: Plugin<C>, config: C): KcodePluginMount {
    val validated = plugin.config?.validate(config) ?: config
    return object : KcodePluginMount {
        override val descriptor = descriptor
        override suspend fun mount(context: Context): Fiber<*> = context.plugin(plugin, validated)
        override fun export(configuration: StoredPluginConfiguration?): Plugin<Any?> {
            val dynamic = checkNotNull(plugin.asDynamicPlugin())
            val selected = if (configuration != null) configuration.decode() else validated
            return object : Plugin<Any?> by dynamic {
                override val config = ConfigValidator<Any?> { dynamic.config?.validate(selected) ?: selected }
            }
        }
    }
}

/** A declarative patch over the default bundle; ids are checked before mounting. */
data class KcodePluginProfile(
    val includeDefaults: Boolean = true,
    val disabled: Set<String> = emptySet(),
    val overrides: List<KcodePluginMount> = emptyList(),
)
