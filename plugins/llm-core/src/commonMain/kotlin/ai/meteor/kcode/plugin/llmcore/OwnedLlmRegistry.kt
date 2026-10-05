package ai.meteor.kcode.plugin.llmcore

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.api.PluginCleanupException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Disposable

internal class OwnedLlmRegistry(ctx: Context) : KcodeLlm(ctx) {
    private val mutex = Mutex()
    private val adapters = linkedMapOf<String, ModelAdapter>()
    private val lifetimes = linkedSetOf<ModelAdapterLifetime>()
    private val closing = MutableStateFlow(false)
    private val closed = CompletableDeferred<Unit>()

    private fun requireOpen() {
        check(!closing.value) { "LLM registry is disposed" }
    }

    override suspend fun register(adapter: ModelAdapter): Disposable {
        requireOpen()
        require(adapter.id.isNotBlank()) { "model adapter id must not be blank" }
        val catalog = adapter.catalog?.let { specification ->
            require(specification.displayName?.isNotBlank() != false) {
                "model provider display name must not be blank"
            }
            require(specification.models.isNotEmpty()) { "model catalog must not be empty" }
            require(specification.models.all {
                it.provider == specification.provider && it.id.isNotBlank() &&
                    it.defaultTemperature.isFinite() && it.defaultTemperature in 0.0..1.0
            }) { "model catalog contains invalid model metadata" }
            require(specification.models.map { it.id }.distinct().size == specification.models.size) {
                "model catalog contains duplicate model ids"
            }
            require(specification.iconId?.isNotBlank() != false) { "model icon identity must not be blank" }
            require(specification.regionMigrationKey?.isNotBlank() != false) { "region migration key must not be blank" }
            require(specification.regionChoices.all { it.value.isNotBlank() } &&
                specification.regionChoices.map { it.value }.distinct().size == specification.regionChoices.size) {
                "model region choices must have distinct nonblank values"
            }
            require(specification.regionChoices.isEmpty() ||
                specification.regionChoices.any { it.value == specification.defaults.region }) {
                "model default region must be a declared choice"
            }
            specification.copy(
                models = specification.models.map { it.copy(displayNames = it.displayNames.toMap(), descriptions = it.descriptions.toMap()) },
                displayNames = specification.displayNames.toMap(),
                descriptions = specification.descriptions.toMap(),
                regionChoices = specification.regionChoices.map { it.copy(displayNames = it.displayNames.toMap()) },
            )
        }
        val lifetime = ModelAdapterLifetime(adapter.id)
        val registered = adapter.copy(
            supports = { !closing.value && lifetime.isOpen && adapter.supports(it) },
            create = { configuration, factory ->
                lifetime.acquire { adapter.create(configuration, factory) }
            },
            catalog = catalog,
        )
        try {
            mutex.withLock {
                requireOpen()
                require(catalog == null || adapters.values.none { it.catalog?.provider == catalog.provider }) {
                    "model provider '${catalog?.provider}' is already registered"
                }
                require(adapter.id !in adapters) { "model adapter '${adapter.id}' is already registered" }
                adapters[adapter.id] = registered
                lifetimes += lifetime
            }
        } catch (error: Throwable) {
            lifetime.close()
            throw error
        }
        return Disposable {
            lifetime.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock {
                    if (adapters[adapter.id] === registered) adapters.remove(adapter.id)
                }
                try {
                    lifetime.close()
                } finally {
                    mutex.withLock { lifetimes.remove(lifetime) }
                }
            }
        }
    }

    override suspend fun resolve(configuration: ModelConfiguration): ModelAdapter {
        val candidates = mutex.withLock {
            requireOpen()
            adapters.values.toList()
        }
        // Supports callbacks may inspect the registry; invoke them without holding its lock.
        val selected = candidates.filter { it.supports(configuration) }
            .maxWithOrNull(compareBy<ModelAdapter> { it.priority }.thenBy { it.id })
            ?: error("no model adapter supports ${configuration.provider.name}")
        requireOpen()
        return selected
    }

    override suspend fun catalog(): ModelCatalogSnapshot = mutex.withLock {
        requireOpen()
        ModelCatalogSnapshot(adapters.values.mapNotNull { it.catalog }
            .sortedWith(compareBy<ModelProviderSpec> { it.order }.thenBy { it.provider.id }))
    }

    override suspend fun adapterIds(): List<String> = mutex.withLock {
        requireOpen()
        adapters.keys.toList()
    }

    override suspend fun close() {
        mutex.withLock { lifetimes.toList() }.forEach { it.requireCanClose() }
        withContext(NonCancellable) {
            val retired = mutex.withLock {
                if (closing.value) null else {
                    closing.value = true
                    adapters.clear()
                    lifetimes.toList()
                }
            }
            if (retired == null) {
                closed.await()
                return@withContext
            }
            try {
                val failures = mutableListOf<Throwable>()
                retired.forEach { lifetime ->
                    try {
                        lifetime.close()
                    } catch (error: Throwable) {
                        failures += error
                    }
                }
                mutex.withLock { lifetimes.removeAll(retired.toSet()) }
                if (failures.isNotEmpty()) throw PluginCleanupException("LLM registry", failures)
                closed.complete(Unit)
            } catch (error: Throwable) {
                closed.completeExceptionally(error)
                throw error
            }
        }
    }
}
