package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.uitexts.executionsettings.BuiltinUiTexts
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.api.KcodeShellMode
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.executionsettings.ui.ShellExecutionSettings
import ai.meteor.kcode.plugin.executionsettings.ui.shellModeTitle
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import ai.meteor.kcode.plugin.ui.api.SettingsSectionRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.plugin.api.ShellModeSettingsPolicy
import ai.meteor.kcode.ui.component.KcodeIconAsset
import androidx.compose.runtime.Composable
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.Disposable
import org.cordis.dependencies
import org.cordis.plugin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class ShellSettingsRenderer(private val policy: ShellModeSettingsPolicy) : UiRenderer<SettingsSectionRequest> {
    @Composable
    override fun Render(request: SettingsSectionRequest) {
        ShellExecutionSettings(
            selected = policy.resolve(request.page.appSettings),
            onSelected = {
                request.page.onSettingsChange(policy.update(request.page.appSettings, it))
            },
        )
    }
}

object DefaultShellSettingsSectionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "settings-shell"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeShellMode.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val settingsPolicy = ctx.require(KcodeShellMode.Key).policy.settings ?: return
        val slots = ctx.require(KcodeUiSlots.Key)
        val mutex = Mutex()
        val backends = mutableSetOf<Any>()
        var registration: Disposable? = null
        suspend fun acquire(): Disposable {
            val token = Any()
            mutex.withLock {
                if (backends.isEmpty()) {
                    registration = slots.registerSettings(
                        SettingsSection(
                            texts = BuiltinUiTexts,
                            id = "shell", order = 40, icon = KcodeIconAsset.Terminal,
                            title = { text(UiText.ShellExecution) },
                            description = { shellModeTitle(settingsPolicy.resolve(it.appSettings)) },
                            renderer = ShellSettingsRenderer(settingsPolicy),
                        ),
                    )
                }
                backends += token
            }
            return Disposable {
                mutex.withLock {
                    if (backends.remove(token) && backends.isEmpty()) {
                        registration?.dispose()
                        registration = null
                    }
                }
            }
        }
        // Both native backends share execution identity policy. Either can keep the one
        // settings contribution alive; withdrawing the last capability revokes its callbacks.
        val system = ctx.plugin(plugin<Unit>(name = "settings-shell-system", inject = dependencies(KcodeShell.Key)) { _, _ ->
            collect(acquire())
        }, Unit)
        effect.collect { system.dispose() }
        val ubuntu = ctx.plugin(plugin<Unit>(name = "settings-shell-ubuntu", inject = dependencies(KcodeUbuntuShell.Key)) { _, _ ->
            collect(acquire())
        }, Unit)
        effect.collect { ubuntu.dispose() }
    }
}
