package ai.meteor.kcode.plugin.api

import org.cordis.Context
import org.cordis.Disposable
import org.cordis.Service
import org.cordis.ServiceKey

/**
 * Plugins define their own presentation vocabulary; the kernel does not enumerate destinations.
 * The ID is the lookup identity. Its owning contract must use the same value type at every call site.
 */
class UiSlotKey<T : Any>(val id: String) {
    init { require(id.isNotBlank() && id == id.trim()) { "UI contribution id must be nonblank" } }
}

fun interface UiContributionSource<T : Any> {
    suspend fun snapshot(): T
}

/** A detached lookup of opaque values. Only the owning UI contract interprets these values. */
class UiContributionsSnapshot(values: Map<String, Any> = emptyMap()) {
    private val values = values.toMap()
    val ids: Set<String> get() = values.keys.toSet()

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: UiSlotKey<T>): T? = values[key.id] as T?
}

/** Shared service identity; the provider owns registration storage and operation lifetimes. */
abstract class KcodeUiContributions(ctx: Context) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeUiContributions>("uiContributions") }

    suspend fun <T : Any> register(key: UiSlotKey<T>, value: T): Disposable =
        registerProjection(key, UiContributionSource { value })

    abstract suspend fun <T : Any> registerProjection(key: UiSlotKey<T>, source: UiContributionSource<T>): Disposable
    abstract suspend fun snapshot(): UiContributionsSnapshot
    abstract suspend fun <T : Any> snapshot(key: UiSlotKey<T>): T?
    abstract suspend fun close()
}
