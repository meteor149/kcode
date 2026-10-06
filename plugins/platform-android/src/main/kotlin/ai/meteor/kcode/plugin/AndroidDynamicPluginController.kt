package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.PluginCodeArtifact
import ai.meteor.kcode.plugin.api.validatePluginApi
import ai.meteor.kcode.plugin.api.KcodePluginInventory
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import android.content.Context as AndroidContext
import java.io.File
import org.cordis.asDynamicPlugin
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
    private val configuredModules = ConfiguredPluginModuleLoader(modules) { url ->
        val descriptors = modules.registered()
        val root = descriptors.firstOrNull { modules.moduleUrl(it.id) == url }
        root?.let { rootDescriptor ->
            pluginCodeOrigin(
                rootId = rootDescriptor.id,
                nodes = descriptors.map { descriptor ->
                    PluginCodeNode(
                        artifact = PluginCodeArtifact(
                            id = descriptor.id,
                            version = descriptor.version,
                            artifactPath = descriptor.file.path,
                            sha256 = descriptor.expectedSha256,
                            entryClass = descriptor.entryClass,
                            packageName = descriptor.packageName,
                        ),
                        dependencies = descriptor.dependencies,
                    )
                },
            )
        }
    }
    private val hmr: Hmr
    private val specs = linkedMapOf<String, DynamicPluginSpec>()
    private val profileSpecs = linkedMapOf<String, DynamicPluginSpec>()
    private var manualReloadOnly = false

    private suspend fun ensureManualReloadOnly() {
        if (!manualReloadOnly) {
            // stash schedules a debounce job even without a filesystem watcher. Package changes
            // belong to the manager transaction; stop the background scope before using explicit HMR.
            hmr.stop()
            manualReloadOnly = true
        }
    }

    init {
        loader.internal = configuredModules
        hmr = Hmr(context, HmrConfig(base = trustedDirectory.path))
    }

    override suspend fun registerProfilePackages(specs: List<DynamicPluginSpec>): Map<String, String> {
        require(this.specs.isEmpty() && profileSpecs.isEmpty()) { "Profile modules must register before any plugin instances" }
        require(specs.map { it.id }.distinct().size == specs.size) { "Duplicate profile package" }
        ensureManualReloadOnly()
        try {
            specs.forEach { spec ->
                spec.validatePluginApi()
                modules.register(AndroidModuleDescriptor(
                    id = spec.id,
                    version = spec.version,
                    entryClass = spec.entryClass,
                    file = File(spec.artifactPath),
                    expectedSha256 = spec.sha256,
                    dependencies = spec.dependencies,
                    sharedHostPackages = SharedPluginApiPackages,
                    packageName = spec.packageName,
                ))
                profileSpecs[spec.id] = spec
            }
            return specs.associate { it.id to modules.moduleUrl(it.id) }
        } catch (error: Throwable) {
            profileSpecs.keys.toList().asReversed().forEach { id ->
                runCatching { modules.release(id) }.exceptionOrNull()?.let(error::addSuppressed)
                runCatching { modules.unregister(id) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            profileSpecs.clear()
            throw error
        }
    }

    override suspend fun prepareProfilePackages(specs: List<DynamicPluginSpec>): ProfileModuleTransaction {
        require(this.specs.isEmpty()) { "Cannot mix Profile and legacy modules" }
        ensureManualReloadOnly()
        return prepareProfileModules(profileSpecs.values.toList(), specs, configuredModules, modules::moduleUrl,
            register = { spec -> modules.register(AndroidModuleDescriptor(spec.id, spec.version, spec.entryClass,
                File(spec.artifactPath), spec.sha256, dependencies = spec.dependencies, sharedHostPackages = SharedPluginApiPackages,
                packageName = spec.packageName)) },
            release = modules::release, unregister = { modules.unregister(it) }, forget = configuredModules::forget,
            stageSpecs = { candidate -> profileSpecs.clear(); profileSpecs.putAll(candidate.associateBy { it.id }) })
    }

    override suspend fun validateCandidate(spec: DynamicPluginSpec) {
        spec.validatePluginApi()
        require(spec.id !in specs) { "Candidate '${spec.id}' is already installed" }
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
        val transaction = modules.beginReload(setOf(modules.moduleUrl(spec.id)))
        try {
            val candidate = transaction.import(modules.moduleUrl(spec.id)).asDynamicPlugin()
                ?: error("plugin '${spec.id}' has no valid entry")
            candidate.config?.validate(spec.config)
        } finally {
            try { transaction.rollback() } finally { modules.unregister(spec.id) }
        }
    }

    override suspend fun replace(spec: DynamicPluginSpec) {
        spec.validatePluginApi()
        ensureManualReloadOnly()
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
        specs[spec.id] = spec.copy(enabled = !entry.disabled)
        inventory.replace(PluginDescriptor(spec.id, spec.version, spec.artifactPath, spec.capabilities, state = if (entry.disabled) PluginState.Disabled else PluginState.Active))
    }

    override suspend fun install(spec: DynamicPluginSpec) {
        spec.validatePluginApi()
        ensureManualReloadOnly()
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
            val imported = modules.import(url, null).asDynamicPlugin()
                ?: error("plugin '${spec.id}' has no valid entry")
            if (!spec.enabled) imported.config?.validate(spec.config)
            loader.create(EntryOptions(id = entryId(spec.id), name = url, config = spec.config, disabled = !spec.enabled))
            if (spec.enabled) checkNotNull(loader.resolve(entryId(spec.id)).fiber) { "plugin '${spec.id}' did not activate" }
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

    override suspend fun uninstall(id: String) = uninstallOwned(id, allowRetiredEntry = false)

    private suspend fun uninstallOwned(id: String, allowRetiredEntry: Boolean) {
        require(id in specs) { "plugin '$id' is not installed" }
        // Runtime closure may already have retired every tracked entry before releasing code.
        if (!allowRetiredEntry || loader.store.containsKey(entryId(id))) loader.remove(entryId(id))
        modules.release(id)
        modules.unregister(id)
        configuredModules.forget(modules.moduleUrl(id))
        specs.remove(id)
        inventory.remove(id)
    }

    override suspend fun installed(): List<DynamicPluginSpec> = specs.values.toList() + profileSpecs.values

    override suspend fun settle() {
        profileSpecs.values.forEach { spec ->
            val entries = loader.entries().filter { it.options.name == modules.moduleUrl(spec.id) || it.options.extra["kcode.packageId"] == spec.id }.toList()
            val state = when {
                entries.isEmpty() || entries.all { it.disabled } -> PluginState.Disabled
                entries.any { it.fiber?.state == FiberState.FAILED } -> PluginState.Failed
                entries.any { it.fiber?.state == FiberState.ACTIVE } -> PluginState.Active
                else -> PluginState.Pending
            }
            inventory.replace(PluginDescriptor("package:${spec.id}", spec.version, spec.artifactPath, spec.capabilities, state = state))
        }
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
        specs[id] = spec.copy(enabled = enabled)
        inventory.replace(
            PluginDescriptor(
                id, spec.version, spec.artifactPath, spec.capabilities,
                state = if (enabled) PluginState.Active else PluginState.Disabled,
            ),
        )
    }

    override suspend fun close() {
        var failure: Throwable? = null
        if (profileSpecs.isNotEmpty()) {
            runCatching { loader.root.stop() }.exceptionOrNull()?.let { failure = it }
            profileSpecs.keys.toList().asReversed().forEach { id ->
                listOf<() -> Unit>({ modules.release(id) }, { modules.unregister(id) }).forEach { release ->
                    runCatching(release).exceptionOrNull()?.let { error ->
                        if (failure == null) failure = error else failure?.addSuppressed(error)
                    }
                }
                configuredModules.forget(modules.moduleUrl(id))
                inventory.remove("package:$id")
            }
            profileSpecs.clear()
        }
        specs.keys.toList().asReversed().forEach { id ->
            try {
                uninstallOwned(id, allowRetiredEntry = true)
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
