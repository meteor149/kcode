package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.ApplicationFrame
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.ApplicationServices
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeUiContributions
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.UiContributionSource
import ai.meteor.kcode.plugin.api.UiContributionsSnapshot
import ai.meteor.kcode.plugin.api.UiSlotKey
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

private val FreeformRootUiLabel = UiSlotKey<String>("alternative.console.label")

class FreeformRootUiPlugin : Plugin<Unit> {
    override val name = "freeform-root-ui-fixture"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val available = MutableStateFlow(true)
        effect.collect { available.value = false }
        val contributions = FreeformRootUiContributions(ctx)
        effect.collect { contributions.close() }
        effect.collect(contributions.register(FreeformRootUiLabel, "Independent root without sidebar or application features"))
        KcodeApplicationUi(ctx, FreeformRootUiRenderer(available))
    }
}

class FreeformRootUiRenderer(private val available: MutableStateFlow<Boolean>) : ApplicationRenderer {
    val rendered = AtomicInteger()
    val disposed = AtomicInteger()
    var previousLookup: ApplicationServices? = null

    override suspend fun snapshot(services: ApplicationServices): ApplicationFrame? {
        if (!available.value) return null
        check(services[KcodeSettings.Key] == null)
        check(services[KcodeHistory.Key] == null)
        check(services[KcodeSessions.Key] == null)
        val contributions = requireNotNull(services[KcodeUiContributions.Key]).snapshot()
        check(contributions.ids == setOf(FreeformRootUiLabel.id))
        val label = requireNotNull(contributions[FreeformRootUiLabel])
        previousLookup = services
        return ApplicationFrame {
            if (available.collectAsState().value) {
                DisposableEffect(Unit) { onDispose { disposed.incrementAndGet() } }
                SideEffect { rendered.incrementAndGet() }
                BasicText(label)
            }
        }
    }
}

class FreeformRootUiBrokenPlugin : Plugin<Unit> {
    override val name = "broken-root-ui-fixture"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeApplicationUi(ctx, ApplicationRenderer { error("root snapshot rejected") })
    }
}

internal class FreeformRootUiContributions(ctx: Context) : KcodeUiContributions(ctx) {

    private class Registration(id: String, val source: UiContributionSource<*>) {
        val owner = PluginOperationOwner("UI contribution '$id'")
    }
    private val owner = PluginOperationOwner("UI contributions")
    private val mutex = Mutex()
    private val registrations = mutableMapOf<String, Registration>()

    override suspend fun <T : Any> registerProjection(key: UiSlotKey<T>, source: UiContributionSource<T>): Disposable = owner.run {
        val registration = Registration(key.id, source)
        mutex.withLock {
            require(key.id !in registrations) { "UI contribution '${key.id}' is already registered" }
            registrations[key.id] = registration
        }
        Disposable {
            registration.owner.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock {
                    if (registrations[key.id] === registration) registrations.remove(key.id)
                }
                registration.owner.close()
            }
            Unit
        }
    }

    override suspend fun snapshot(): UiContributionsSnapshot = owner.run {
        val captured = mutex.withLock { registrations.toMap() }
        val values = linkedMapOf<String, Any>()
        captured.forEach { (id, registration) ->
            values[id] = registration.owner.run { registration.source.snapshot() }
        }
        UiContributionsSnapshot(values)
    }

    /**
     * Prepare only the requested contribution, without invoking unrelated providers.
     * Missing keys return null. Withdrawal cancels and joins an admitted projection as with snapshot().
     * Provider code runs outside the registry mutex, so it may read other contributions.
     */
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> snapshot(key: UiSlotKey<T>): T? = owner.run {
        val registration = mutex.withLock { registrations[key.id] } ?: return@run null
        registration.owner.run { registration.source.snapshot() as T }
    }

    override suspend fun close() {
        owner.close()
        withContext(NonCancellable) {
            val retired = mutex.withLock {
                registrations.values.toList().also { registrations.clear() }
            }
            retired.forEach { it.owner.close() }
        }
    }
}
