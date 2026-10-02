package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodePluginInventory
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import android.content.Context as AndroidContext
import java.io.File
import org.cordis.Context
import org.cordis.FiberState
import org.cordis.hmr.Hmr
import org.cordis.hmr.HmrConfig
import org.cordis.loader.AndroidModuleDescriptor
import org.cordis.loader.AndroidModuleLoader
import org.cordis.loader.changeTo
import org.cordis.loader.EntryOptions
import org.cordis.loader.EntryPatch
import org.cordis.loader.Loader

internal class AndroidDynamicPluginController(
    context: Context,
    androidContext: AndroidContext,
    private val loader: Loader,
    private val inventory: KcodePluginInventory,
    trustedDirectory: File,
) : DynamicPluginController {
    private val modules = AndroidModuleLoader(androidContext, trustedRoot = trustedDirectory)
    private val configuredModules = ConfiguredPluginModuleLoader(modules)
    private val hmr: Hmr
    private val specs = linkedMapOf<String, DynamicPluginSpec>()

    init {
        loader.internal = configuredModules
        hmr = Hmr(context, HmrConfig(base = trustedDirectory.path))
    }

    override suspend fun replace(spec: DynamicPluginSpec) {
        require(spec.id in specs) { "plugin '${spec.id}' is not installed" }
        modules.register(
            AndroidModuleDescriptor(
                id = spec.id,
                version = spec.version,
                entryClass = spec.entryClass,
                file = File(spec.artifactPath),
                expectedSha256 = spec.sha256,
                dependencies = spec.dependencies,
                sharedHostPackages = SharedPluginApiPackages,
                packageName = spec.packageName,
            ),
        )
        val url = modules.moduleUrl(spec.id)
        configuredModules.configure(url, spec.config)
        hmr.stash(url)
        val entry = loader.resolve(entryId(spec.id))
        val previousConfig = entry.options.config
        entry.options.config = spec.config
        try {
            check(hmr.partialReload()) { "plugin '${spec.id}' replacement failed; previous generation remains active" }
        } catch (error: Throwable) {
            entry.options.config = previousConfig
            configuredModules.configure(url, previousConfig)
            val previous = specs.getValue(spec.id)
            modules.register(
                AndroidModuleDescriptor(
                    id = previous.id,
                    version = previous.version,
                    entryClass = previous.entryClass,
                    file = File(previous.artifactPath),
                    expectedSha256 = previous.sha256,
                    dependencies = previous.dependencies,
                    sharedHostPackages = SharedPluginApiPackages,
                    packageName = previous.packageName,
                ),
            )
            throw error
        }
        specs[spec.id] = spec
        inventory.replace(PluginDescriptor(spec.id, spec.version, spec.artifactPath, spec.capabilities))
    }

    override suspend fun install(spec: DynamicPluginSpec) {
        require(spec.id !in specs) { "plugin '${spec.id}' is already installed" }
        require(inventory.snapshot().none { it.id == spec.id }) {
            "plugin id '${spec.id}' is reserved by an existing plugin"
        }
        val descriptor = AndroidModuleDescriptor(
            id = spec.id,
            version = spec.version,
            entryClass = spec.entryClass,
            file = File(spec.artifactPath),
            expectedSha256 = spec.sha256,
            dependencies = spec.dependencies,
            sharedHostPackages = SharedPluginApiPackages,
            packageName = spec.packageName,
        )
        modules.register(descriptor)
        val url = modules.moduleUrl(spec.id)
        configuredModules.configure(url, spec.config)
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
            configuredModules.forget(url)
            throw error
        }
    }

    override suspend fun uninstall(id: String) {
        require(id in specs) { "plugin '$id' is not installed" }
        loader.remove(entryId(id))
        modules.release(id)
        modules.unregister(id)
        configuredModules.forget(modules.moduleUrl(id))
        specs.remove(id)
        inventory.remove(id)
    }

    override suspend fun installed(): List<DynamicPluginSpec> = specs.values.toList()

    override suspend fun settle() {
        specs.values.forEach { spec ->
            val entry = loader.resolve(entryId(spec.id))
            entry.fiber?.await()
            val state = when {
                entry.disabled -> PluginState.Disabled
                entry.fiber?.state == FiberState.ACTIVE -> PluginState.Active
                entry.fiber?.state == FiberState.FAILED -> PluginState.Failed
                else -> PluginState.Pending
            }
            inventory.replace(PluginDescriptor(spec.id, spec.version, spec.artifactPath, spec.capabilities, state = state))
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        val spec = specs[id] ?: error("plugin '$id' is not installed")
        loader.resolve(entryId(id)).update(EntryPatch(disabled = changeTo(!enabled)))
        inventory.replace(
            PluginDescriptor(
                id, spec.version, spec.artifactPath, spec.capabilities,
                state = if (enabled) PluginState.Active else PluginState.Disabled,
            ),
        )
    }

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
        failure?.let { throw it }
    }
}

private fun entryId(id: String): String = "external-$id"
