package ai.meteor.kcode.plugin

import org.cordis.asDynamicPlugin
import org.cordis.ConfigValidator
import org.cordis.loader.ModuleLoader
import org.cordis.loader.ReloadTransaction
import org.cordis.Plugin

/**
 * Cordis HMR replays fiber configs. Bind each artifact generation to its installation config so
 * code and config change in the same transaction, with the original validator still enforced.
 */
class ConfiguredPluginModuleLoader(private val delegate: ModuleLoader) : ModuleLoader by delegate {
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
        if (url !in configs) return value
        val configured = configs[url]
        val wrapped = object : Plugin<Any?> by plugin {
            override val config = ConfigValidator<Any?> { plugin.config?.validate(configured) ?: configured }
        }
        cache[url] = Export(value, wrapped)
        return wrapped
    }
}
