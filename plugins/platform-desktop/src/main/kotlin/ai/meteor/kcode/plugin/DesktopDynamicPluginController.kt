package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodePluginInventory
import ai.meteor.kcode.plugin.api.PluginDescriptor
import java.io.File
import org.cordis.Context
import org.cordis.loader.EntryOptions
import org.cordis.loader.JvmModuleDescriptor
import org.cordis.loader.JvmModuleLoader
import org.cordis.loader.Loader
import org.cordis.hmr.Hmr
import org.cordis.hmr.HmrConfig

internal class DesktopDynamicPluginController(
    context: Context,
    private val loader: Loader,
    private val inventory: KcodePluginInventory,
    trustedDirectory: File,
) : DynamicPluginController {
    private val modules = JvmModuleLoader(trustedDirectory)
    private val hmr: Hmr
    private val specs = linkedMapOf<String, DynamicPluginSpec>()

    init {
        loader.internal = modules
        hmr = Hmr(context, HmrConfig(base = trustedDirectory.path))
    }

    override suspend fun replace(spec: DynamicPluginSpec) {
        require(spec.id in specs) { "plugin '${spec.id}' is not installed" }
        modules.register(
            JvmModuleDescriptor(
                id = spec.id,
                version = spec.version,
                entryClass = spec.entryClass,
                file = File(spec.artifactPath),
                expectedSha256 = spec.sha256,
                dependencies = spec.dependencies,
                sharedHostPackages = SharedPluginApiPackages,
            ),
        )
        val url = modules.moduleUrl(spec.id)
        hmr.stash(url)
        check(hmr.partialReload()) { "plugin '${spec.id}' replacement failed; previous generation remains active" }
        specs[spec.id] = spec
        inventory.replace(PluginDescriptor(spec.id, spec.version, spec.artifactPath, spec.capabilities))
    }

    override suspend fun install(spec: DynamicPluginSpec) {
        require(spec.id !in specs) { "plugin '${spec.id}' is already installed" }
        require(inventory.snapshot().none { it.id == spec.id }) {
            "plugin id '${spec.id}' is reserved by an existing plugin"
        }
        val descriptor = JvmModuleDescriptor(
            id = spec.id,
            version = spec.version,
            entryClass = spec.entryClass,
            file = File(spec.artifactPath),
            expectedSha256 = spec.sha256,
            dependencies = spec.dependencies,
            sharedHostPackages = SharedPluginApiPackages,
        )
        modules.register(descriptor)
        val url = modules.moduleUrl(spec.id)
        try {
            modules.import(url, null)
            loader.create(EntryOptions(id = entryId(spec.id), name = url, config = spec.config))
            checkNotNull(loader.resolve(entryId(spec.id)).fiber) { "plugin '${spec.id}' did not activate" }
            specs[spec.id] = spec
            inventory.publish(
                PluginDescriptor(spec.id, spec.version, spec.artifactPath, spec.capabilities),
            )
        } catch (error: Throwable) {
            if (loader.store.containsKey(entryId(spec.id))) {
                runCatching { loader.remove(entryId(spec.id)) }
            }
            runCatching { modules.release(spec.id) }
            runCatching { modules.unregister(spec.id) }
            throw error
        }
    }

    override suspend fun uninstall(id: String) {
        require(id in specs) { "plugin '$id' is not installed" }
        loader.remove(entryId(id))
        modules.release(id)
        modules.unregister(id)
        specs.remove(id)
        inventory.remove(id)
    }

    override suspend fun installed(): List<DynamicPluginSpec> = specs.values.toList()

    override suspend fun close() {
        var failure: Throwable? = null
        specs.keys.toList().asReversed().forEach { id ->
            try {
                uninstall(id)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        try {
            hmr.stop()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        try {
            modules.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}

private fun entryId(id: String): String = "external-$id"

private val SharedPluginApiPackages = setOf(
    "ai.meteor.kcode.plugin.api",
    "ai.meteor.kcode.model",
    "ai.meteor.kcode.settings",
    "ai.meteor.kcode.tools.permission",
    "ai.koog",
)
