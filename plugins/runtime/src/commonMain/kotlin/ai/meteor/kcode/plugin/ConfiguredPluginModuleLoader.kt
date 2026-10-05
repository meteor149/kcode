package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.PluginCodeOrigin
import org.cordis.asDynamicPlugin
import org.cordis.ConfigValidator
import org.cordis.loader.ModuleLoader
import org.cordis.loader.ReloadTransaction
import org.cordis.Plugin
import org.cordis.Context
import org.cordis.EffectScope

/**
 * Cordis HMR replays fiber configs. Bind each artifact generation to its installation config so
 * code, config and origin change in the same transaction, with the original validator enforced.
 */
class ConfiguredPluginModuleLoader(
    private val delegate: ModuleLoader,
    private val codeOrigin: (String) -> PluginCodeOrigin? = { null },
) : ModuleLoader by delegate {
    private data class Export(val original: Any?, val wrapped: Any?)
    private val configs = mutableMapOf<String, Any?>()
    private val active = mutableMapOf<String, Export>()

    fun configure(url: String, config: Any?) {
        configs[url] = config
    }

    fun forget(url: String) {
        configs.remove(url)
        active.remove(url)
    }

    override suspend fun import(specifier: String, parentUrl: String?): Any? {
        val url = delegate.resolve(specifier, parentUrl).url
        return export(url, delegate.import(specifier, parentUrl), active)
    }

    override fun peek(url: String): Any? = export(url, delegate.peek(url), active)

    override fun beginReload(urls: Set<String>): ReloadTransaction {
        val transaction = delegate.beginReload(urls)
        val staged = active.toMutableMap()
        return object : ReloadTransaction {
            override suspend fun import(url: String): Any? = export(url, transaction.import(url), staged)
            override fun commit() {
                transaction.commit()
                active.clear()
                active.putAll(staged)
            }
            override fun rollback() = transaction.rollback()
        }
    }

    private fun export(url: String, value: Any?, cache: MutableMap<String, Export>): Any? {
        cache[url]?.takeIf { it.original === value }?.let { return it.wrapped }
        val plugin = value.asDynamicPlugin() ?: return value
        val origin = codeOrigin(url)
        if (url !in configs && origin == null) return value
        // HMR imports the candidate graph before retiring any active fibers. Reject invalid
        // deployment data here, including disabled entries, rather than during replacement apply.
        val fixed = url in configs
        val configured = configs[url]
        val validated = if (fixed) plugin.config?.validate(configured) ?: configured else null
        val wrapped = object : Plugin<Any?> by plugin {
            override val config = ConfigValidator<Any?> { candidate ->
                if (fixed) validated else plugin.config?.validate(candidate) ?: candidate
            }
            override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
                plugin.apply(origin?.bind(ctx) ?: ctx, config, effect)
            }
        }
        cache[url] = Export(value, wrapped)
        return wrapped
    }
}
