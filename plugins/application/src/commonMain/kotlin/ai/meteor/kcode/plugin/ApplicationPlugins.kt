package ai.meteor.kcode.plugin

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.application.ui.KcodeApp
import ai.meteor.kcode.plugin.application.ui.defaultApplicationServices
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.ApplicationFrame
import ai.meteor.kcode.plugin.ui.api.DefaultUiRenderer
import ai.meteor.kcode.plugin.ui.api.ApplicationViewServices
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeModelSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import org.cordis.dependencies
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import org.cordis.Disposable
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.ConfigValidator


/** Default application UI is a provider; profiles may replace it with a different renderer. */
object ApplicationUiPlugin : Plugin<DefaultUiRenderer> {
    override val name = "kcode-application-ui"
    override suspend fun apply(ctx: Context, config: DefaultUiRenderer, effect: EffectScope) {
        val owner = PluginOperationOwner("Application settings")
        val available = MutableStateFlow(true)
        effect.collect(Disposable { available.value = false; owner.close() })
        KcodeApplicationUi(ctx, ApplicationRenderer { servicesLookup ->
            if (!available.value) return@ApplicationRenderer null
            val services = defaultApplicationServices(servicesLookup) ?: return@ApplicationRenderer null
            ApplicationFrame { options ->
                key(config, services.settingsStore) {
                    if (available.collectAsState().value) {
                        if (config === DefaultApplicationRenderer) DefaultApplicationRenderer.Render(services, options, owner)
                        else config.Render(services, options)
                    }
                }
            }
        })
    }
}

object DefaultApplicationUiPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-default-application-ui"
    override val inject = dependencies(KcodeSettings.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        ApplicationUiPlugin.apply(ctx, DefaultApplicationRenderer, effect)
    }
}

object DefaultApplicationRenderer : DefaultUiRenderer {
    @Composable
    override fun Render(services: ApplicationViewServices, options: ApplicationHostOptions) {
        val owner = remember { PluginOperationOwner("Application settings") }
        val scope = rememberCoroutineScope()
        DisposableEffect(owner) { onDispose { scope.launch(NonCancellable) { owner.close() } } }
        Render(services, options, owner)
    }

    @Composable
    internal fun Render(services: ApplicationViewServices, options: ApplicationHostOptions, owner: PluginOperationOwner) {
        KcodeApp(services, options, owner)
    }
}
