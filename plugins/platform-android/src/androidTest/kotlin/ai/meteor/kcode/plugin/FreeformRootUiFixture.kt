package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.ApplicationFrame
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.ApplicationServices
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.KcodeUiContributions
import ai.meteor.kcode.plugin.api.UiSlotKey
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

private val FreeformRootUiLabel = UiSlotKey<String>("alternative.console.label")

class FreeformRootUiPlugin : Plugin<Unit> {
    override val name = "freeform-root-ui-fixture"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val available = MutableStateFlow(true)
        effect.collect { available.value = false }
        val contributions = KcodeUiContributions(ctx)
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
        check(services[KcodeArtifacts.Key] == null)
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
