package ai.meteor.kcode.plugin.notifications

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.ui.api.ApplicationEffect
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import android.Manifest
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Requests notification consent only after the mounted application effect reaches committed UI. */
class AndroidNotificationPermissionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "android-notification-permission"
    override val inject = dependencies(KcodeUiSlots.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
            "Android notification permission requires native host inputs"
        }
        val permissions = requireNotNull(inputs.permissions()) { "Android permission requests are unavailable" }
        val owner = PluginOperationOwner("Android notification permission")
        val available = MutableStateFlow(true)
        val attempted = AtomicBoolean(false)
        effect.collect {
            owner.requireCanClose()
            available.value = false
            owner.close()
        }
        effect.collect(ctx.require(KcodeUiSlots.Key).registerEffect(ApplicationEffect(
            id = "android.notifications.permission", order = -100,
            renderer = UiRenderer {
                if (available.collectAsState().value) {
                    LaunchedEffect(owner) {
                        if (!attempted.compareAndSet(false, true)) return@LaunchedEffect
                        owner.run {
                            if (!permissions.isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                                permissions.request(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
                }
            },
        )))
    }
}
